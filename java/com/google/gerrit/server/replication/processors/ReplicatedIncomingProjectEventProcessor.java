package com.google.gerrit.server.replication.processors;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.replication.customevents.DeleteProjectChangeEvent;
import com.google.gerrit.server.replication.customevents.ProjectInfoWrapper;
import com.google.gerrit.server.replication.SingletonEnforcement;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.replication.exceptions.ReplicatedEventsUnknownTypeException;
import com.google.inject.Singleton;
import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;

import java.io.IOException;
import java.util.Objects;

import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.DELETE_PROJECT_EVENT;

@Singleton
public class ReplicatedIncomingProjectEventProcessor extends AbstractReplicatedEventProcessor {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private GitRepositoryManager repoManager;

  /**
   * We only create this class from the replicatedEventsCoordinator.
   * This is a singleton and it's enforced by our SingletonEnforcement below that if anyone else tries to create
   * this class it will fail.
   * Sorry by adding a getInstance, make this class look much more public than it is,
   * and people expect they can just call getInstance - when in fact they should always request it via the
   * ReplicatedEventsCoordinator.getReplicatedXWorker() methods.
   *
   * @param replicatedEventsCoordinator
   */
  public ReplicatedIncomingProjectEventProcessor(ReplicatedEventsCoordinator replicatedEventsCoordinator) {
    super(DELETE_PROJECT_EVENT, replicatedEventsCoordinator);
    logger.atInfo().log("Creating main processor for event type: %s", eventType);
    subscribeEvent(this);
    this.repoManager = replicatedEventsCoordinator.getGitRepositoryManager();
    SingletonEnforcement.registerClass(ReplicatedIncomingProjectEventProcessor.class);
  }

  @Override
  public void stop() {
    SingletonEnforcement.unregisterClass(ReplicatedIncomingProjectEventProcessor.class);
    unsubscribeEvent(this);
  }


  @Override
  public void processIncomingReplicatedEvent(final ReplicatedEvent replicatedEvent) {
    if (replicatedEvent instanceof DeleteProjectChangeEvent) {

      DeleteProjectChangeEvent deleteProjectChangeEvent = (DeleteProjectChangeEvent) replicatedEvent;
      deleteProjectChanges(deleteProjectChangeEvent);

    } else if (replicatedEvent instanceof ProjectInfoWrapper) {
      ProjectInfoWrapper projectInfoWrapper = (ProjectInfoWrapper) replicatedEvent;
      deleteProject(projectInfoWrapper);
    } else{
      final String err = String.format("Encountered unknown ReplicatedEvent type %s", replicatedEvent.toString());
      logger.atSevere().log(err);
      throw new ReplicatedEventsUnknownTypeException(err);
    }
  }


  /**
   * Perform actions to actually delete the project on all nodes and send round a message
   * that the node has successfully deleted the project
   * @param projectInfoWrapper Wraps data required for a DeleteProjectMessageEvent
   * @return true if we succeed in deleting the project from the jgit cache and we are able to send
   * a DeleteProjectMessageEvent with the data from the ProjectInfoWrapper.
   */
  private void deleteProject(ProjectInfoWrapper projectInfoWrapper) {
    boolean deleteFromJgitCacheResult;

    if (projectInfoWrapper == null) {
      logger.atWarning().log("Received null ProjectInfoWrapper");
      return;
    }

    logger.atInfo().log("RE Original event: %s", projectInfoWrapper.toString());
    projectInfoWrapper.replicated = true; // not needed, but makes it clear
    projectInfoWrapper.setNodeIdentity(Objects.requireNonNull(replicatedEventsCoordinator.getThisNodeIdentity()));
    deleteFromJgitCacheResult = applyActionsForDeletingProject(projectInfoWrapper);

    // If the result from deleting from the Jgit cache was successful, we now need to decide if we should
    // delete the project from disk also. We make this decision based on the value of preserve. If preserve is
    // false then we can proceed with deleting the project on disk.
    boolean deleteProjectOnDisk = deleteFromJgitCacheResult && !projectInfoWrapper.preserve;

      replicatedEventsCoordinator.getReplicatedOutgoingProjectEventsFeed()
          .createDeleteProjectMessageEvent(projectInfoWrapper.taskUuid, deleteProjectOnDisk, projectInfoWrapper.projectName);
  }


  /**
   * Perform actions to delete all the changes associated with the project on all nodes.
   * @param deleteProjectChangeEvent : Event type used for the purpose of deleting open changes for a given project
   * @return true if project changes have been successfully deleted.
   */
  private void deleteProjectChanges(DeleteProjectChangeEvent deleteProjectChangeEvent) {

    if (deleteProjectChangeEvent == null) {
      logger.atWarning().log("Received null DeleteProjectChangeEvent");
      return;
    }

    logger.atInfo().log("Original event: %s", deleteProjectChangeEvent.toString());
    deleteProjectChangeEvent.replicated = true; // not needed, but makes it clear
    deleteProjectChangeEvent.setNodeIdentity(Objects.requireNonNull(replicatedEventsCoordinator.getThisNodeIdentity()));
    applyActionsForDeletingProjectChanges(deleteProjectChangeEvent);
  }


  /**
   * Remove the project from the jgit cache on all nodes
   *
   * @param originalEvent
   * @return
   */
  public boolean applyActionsForDeletingProject(ProjectInfoWrapper originalEvent) {
    logger.atInfo().log("PROJECT event is about to remove the project from the jgit cache. Original event was %s!", originalEvent);
    Project.NameKey nameKey = new Project.NameKey(originalEvent.projectName);
    Repository repository;
    try {
      repository = repoManager.openRepository(nameKey);
      // The cleanCache() method in FileSystemDeleteHandler performs the following 2 calls
      repository.close();
      RepositoryCache.close(repository);
      return true;
    } catch (RepositoryNotFoundException e) {
      logger.atSevere().withCause(e).log("Could not locate Repository %s", nameKey);
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Could not open Repository %s", nameKey);
    }

    return false;
  }

  /**
   * Remove the changes associated with the project on all nodes
   *
   * @param originalEvent
   * @return
   */
  private void applyActionsForDeletingProjectChanges(DeleteProjectChangeEvent originalEvent) {
    logger.atInfo().log("PROJECT event is about to remove the changes related to project %s. Original event was %s!",
            originalEvent.project.getName(), originalEvent);
    try {
      replicatedEventsCoordinator.getReplicatedIncomingIndexEventProcessor().deleteChanges(originalEvent.changes);
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Error while deleting changes ");
    }
  }
}
