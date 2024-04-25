package com.google.gerrit.server.replication.processors;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.index.project.ProjectIndexer;
import com.google.gerrit.server.replication.SingletonEnforcement;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.server.replication.customevents.ProjectIndexEvent;
import com.google.gerrit.server.replication.exceptions.ReplicatedEventsTransientException;
import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;

import java.io.IOException;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.PROJECTS_INDEX_EVENT;


public class ReplicatedIncomingProjectIndexEventProcessor extends AbstractReplicatedEventProcessor {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private ProjectIndexer indexer;

  /**
   * We only create this class from the replicatedEventsCoordinator.
   * This is a singleton and it's enforced by our SingletonEnforcement below that if anyone else tries to create
   * this class it will fail.
   * Sorry by adding a getInstance, make this class look much more public than it is,
   * and people expect they can just call getInstance - when in fact they should always request it via the
   * ReplicatedEventsCoordinator.getReplicatedXWorker() methods.
   *
   * @param eventsCoordinator
   */
  public ReplicatedIncomingProjectIndexEventProcessor(ReplicatedEventsCoordinator eventsCoordinator) {
    super(PROJECTS_INDEX_EVENT, eventsCoordinator);
    logger.atInfo().log("Creating main processor for event type: %s", eventType);
    subscribeEvent(this);
    SingletonEnforcement.registerClass(ReplicatedIncomingProjectIndexEventProcessor.class);
  }


  @Override
  public void stop() {
    SingletonEnforcement.unregisterClass(ReplicatedIncomingProjectIndexEventProcessor.class);
    unsubscribeEvent(this);
  }

  public ProjectIndexer getIndexer() {
    if (indexer == null) {
        indexer = replicatedEventsCoordinator.getProjectIndexer();
    }
    return indexer;
  }

  /**
   * Processes incoming ReplicatedEvent which is cast to a ProjectIndexEvent
   * This method then calls reindexProject which will produce a non replicated local reindex
   * of the Projects Index.
   * Processes originator type PROJECTS_INDEX_EVENT
   * @param replicatedEvent Base event type for all replicated events
   * @return true if the local reindex of the Projects Index has been completed successfully.
   * @throws IOException if there is an issue when deleting or adding to the local index.
   */
  @Override
  public void processIncomingReplicatedEvent(final ReplicatedEvent replicatedEvent) {
    reindexProject((ProjectIndexEvent) replicatedEvent);
  }

  /**
   * Local reindex of the Projects event.
   * This can be either a deletion from an index or an update to
   * an index.
   * @return : Returns true if the indexing operation has completed.
   */
  private boolean reindexProject(ProjectIndexEvent projectIndexEvent) {
    // If the index is to be deleted, indicated by the boolean flag in the ProjectIndexEvent
    // then we will delete the index. Otherwise, it is a normal reindex.
    try {
      if (projectIndexEvent.isDeleteIndex()) {
        logger.atFine().log("Calling deleteIndexNoRepl for %s", projectIndexEvent.getIdentifier());
        getIndexer().deleteIndexNoRepl(projectIndexEvent.getIdentifier());
      } else {
        logger.atFine().log("Calling indexNoRepl for %s", projectIndexEvent.getIdentifier());
        getIndexer().indexNoRepl(projectIndexEvent.getIdentifier());
      }
    }catch (IOException e){
      final String err = String.format("RC Project reindex issue " +
          "hit while carrying out reindex of %s, isDeleteIndex :[ %s ]", projectIndexEvent, projectIndexEvent.isDeleteIndex());
      logger.atSevere().withCause(e).log(err);
      throw new ReplicatedEventsTransientException(err, e);
    }
    return true;
  }
}
