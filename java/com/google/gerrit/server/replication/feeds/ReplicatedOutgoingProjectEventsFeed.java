package com.google.gerrit.server.replication.feeds;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.replication.customevents.DeleteProjectChangeEvent;
import com.google.gerrit.server.replication.GerritEventFactory;
import com.google.gerrit.server.replication.customevents.ProjectInfoWrapper;
import com.google.gerrit.server.replication.SingletonEnforcement;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.inject.Singleton;
import com.wandisco.gerrit.gitms.shared.events.DeleteProjectMessageEvent;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static com.wandisco.gerrit.gitms.shared.events.DeleteProjectMessageEvent.DeleteMessage.DELETE_PROJECT_FROM_DISK;
import static com.wandisco.gerrit.gitms.shared.events.DeleteProjectMessageEvent.DeleteMessage.DO_NOT_DELETE_PROJECT_FROM_DISK;

@Singleton //Not guice bound but makes it clear that it's a singleton
public class ReplicatedOutgoingProjectEventsFeed extends ReplicatedOutgoingEventsFeedCommon {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * We only create this class from the replicatedEventsCoordinator.
   * This is a singleton and it's enforced by our SingletonEnforcement below that if anyone else tries to create
   * this class it will fail.
   * Sorry by adding a getInstance, make this class look much more public than it is,
   * and people expect they can just call getInstance - when in fact they should always request it via the
   * ReplicatedEventsCoordinator.getReplicatedXWorker() methods.
   * @param eventsCoordinator
   */
  public ReplicatedOutgoingProjectEventsFeed(ReplicatedEventsCoordinator eventsCoordinator) {
    super(eventsCoordinator);
    SingletonEnforcement.registerClass(ReplicatedOutgoingProjectEventsFeed.class);
  }

  @Override
  public void stop() {
    SingletonEnforcement.unregisterClass(ReplicatedOutgoingProjectEventsFeed.class);
  }

  public void replicateProjectDeletion(String projectName, boolean preserve, String taskUuid) throws IOException {
    ProjectInfoWrapper projectInfoWrapper = new ProjectInfoWrapper(projectName, preserve, taskUuid, replicatedEventsCoordinator.getThisNodeIdentity());
    logger.atInfo().log("PROJECT About to call replicated project deletion event: %s, %s, %s",
        projectName, preserve, taskUuid);
    replicatedEventsCoordinator.queueEventForReplication(GerritEventFactory.createReplicatedDeleteProjectEvent(projectInfoWrapper));
  }

  public void replicateProjectChangeDeletion(Project project, boolean preserve, List<Change.Id> changesToBeDeleted, String taskUuid) throws IOException {
    DeleteProjectChangeEvent deleteProjectChangeEvent =
        new DeleteProjectChangeEvent(project, preserve, changesToBeDeleted, taskUuid, replicatedEventsCoordinator.getThisNodeIdentity());
    logger.atInfo().log("PROJECT About to call replicated project change deletion event: %s, %s, %s, %s",
        project.getName(), preserve, changesToBeDeleted, taskUuid);
    replicatedEventsCoordinator.queueEventForReplication(GerritEventFactory.createReplicatedDeleteProjectChangeEvent(deleteProjectChangeEvent));
  }

  /**
   * We call this method if we are dealing with a single node replication
   * group. For a single node membership we need to create a DeleteProjectMessageEvent
   * instead of using a wrapped ProjectInfoWrapper.
   * @param project : Gerrit project to be deleted.
   * @param preserve : When preserve is true, the project is not removed from disk
   * @param taskUuid : unique UUID which allows for tracking the delete request.
   */
  public void deleteProjectSingleNodeGroup(Project project, boolean preserve,
                                                  final String taskUuid) throws IOException {

    //Delete the project from the jgit cache on a single node
    boolean jgitCacheChangesRemoved = replicatedEventsCoordinator.getReplicatedIncomingProjectEventProcessor()
        .applyActionsForDeletingProject(new ProjectInfoWrapper(project.getName(), preserve, taskUuid,
            Objects.requireNonNull(replicatedEventsCoordinator.getThisNodeIdentity())));

    // Send a DELETE_PROJECT_MESSAGE_EVENT which will be picked up by the
    // GerritEventStreamProposer in the outgoing and which will be used to
    // send the GerritDeleteProjectProposal to remove the node from the GerritDeleteRepositoryTask.

    createDeleteProjectMessageEvent(taskUuid,
        jgitCacheChangesRemoved && !preserve, project.getName());
  }


  /**
   * Creates new instance of DeleteProjectMessageEvent
   * if the value of deleteFromDisk is true then we construct the
   * instance with the enum DELETE_PROJECT_FROM_DISK otherwise we construct
   * it with DO_NOT_DELETE_PROJECT_FROM_DISK
   * @param taskUuid : The taskId
   * @param deleteFromDisk : boolean whether to delete project from disk or not
   * @param name : name of the repository to either delete from disk or to delete changes for.
   */
  public void createDeleteProjectMessageEvent(String taskUuid, boolean deleteFromDisk, String name){
    DeleteProjectMessageEvent deleteProjectMessageEvent;
    if (deleteFromDisk) {
      // If the request was to remove the repository from the disk, then we do that only after all the nodes have replied
      // So first phase is to clear the data about the repo, 2nd phase is to remove it
      deleteProjectMessageEvent = new DeleteProjectMessageEvent(name, DELETE_PROJECT_FROM_DISK,
          taskUuid, Objects.requireNonNull(replicatedEventsCoordinator.getThisNodeIdentity()));
    } else {
      // If the result is false then we have failed the first part of the removal. If we are Preserving the repo then we do not
      // want to remove the repo so we send a failed response so we know not to remove it.
      deleteProjectMessageEvent = new DeleteProjectMessageEvent(name, DO_NOT_DELETE_PROJECT_FROM_DISK,
          taskUuid, Objects.requireNonNull(replicatedEventsCoordinator.getThisNodeIdentity()));
    }

    try {
      replicatedEventsCoordinator
          .queueEventForReplication(GerritEventFactory.createReplicatedDeleteProjectMessageEvent(deleteProjectMessageEvent));
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Unable to create event wrapper and queue this DeleteProjectMessageEvent.");
    }
  }
}
