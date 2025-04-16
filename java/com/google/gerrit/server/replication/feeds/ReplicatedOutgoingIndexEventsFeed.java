package com.google.gerrit.server.replication.feeds;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.replication.GerritEventFactory;
import com.google.gerrit.server.replication.customevents.IndexToReplicate;
import com.google.gerrit.server.replication.SingletonEnforcement;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.inject.Singleton;

import java.io.IOException;
import java.time.Instant;

@Singleton //Not guice bound but makes it clear that it's a singleton
public class ReplicatedOutgoingIndexEventsFeed extends ReplicatedOutgoingEventsFeedCommon {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * Constructor is public but only a replicatedEventsCoordinator should create instances and all other references
   * should be obtained by calling ReplicatedEventsCoordinator.getReplicatedX(). Subsequent attempts to construct are
   * blocked by SingletonEnforcement.
   */
  public ReplicatedOutgoingIndexEventsFeed(ReplicatedEventsCoordinator eventsCoordinator) {
    super(eventsCoordinator);
    SingletonEnforcement.registerClass(ReplicatedOutgoingIndexEventsFeed.class);
  }

  @Override
  public void stop() {
    SingletonEnforcement.unregisterClass(ReplicatedOutgoingIndexEventsFeed.class);
  }

  /**
   * Queue a notification to be made to replica nodes regarding the deletion of an index. This must be done
   * independently of the delete() call in ChangeIndexer, as in that context, the Change is no longer
   * accessible, preventing lookup of the project name and the subsequent attempt to tie the change to
   * a specific DSM in the replicator.
   *
   * @param indexNumber index id to queue for deletion.
   * @param projectName project to affect.
   */
  public void queueReplicationIndexDeletionEvent(int indexNumber, String projectName) throws IOException {
    queueReplicationIndexEvent(indexNumber, projectName, Instant.now(), true, false);
  }


  /**
   * Main method used by the gerrit ChangeIndexer to communicate that a new index event has happened
   * and must be replicated across the nodes.
   * <p>
   * This will enqueue the event for async replication
   *
   * @param indexNumber index id to queue up.
   * @param projectName project to affect.
   * @param lastUpdatedOn Instant in time the index was last updated.
   * @param safeToIgnoreMissingChange Whether we can continue if the id is not currently present.
   */
  public void queueReplicationIndexEvent(int indexNumber, String projectName, Instant lastUpdatedOn, boolean safeToIgnoreMissingChange) throws IOException {
    queueReplicationIndexEvent(indexNumber, projectName, lastUpdatedOn, false, safeToIgnoreMissingChange);
  }

  /**
   * Used by the gerrit ChangeIndexer to communicate that a new index event has happened
   * and must be replicated across the nodes with an additional boolean flag to indicate if the index
   * to be updated is being deleted.
   * <p>
   * This will enqueue the event for async replication
   * See {@link IndexTask} for further details of the parameters.
   *
   * @param indexNumber index id to queue up.
   * @param projectName project to affect.
   * @param lastUpdatedOn Instant in time the index was last updated.
   * @param deleteIndex Flag whether to delete the index.
   * @param safeToIgnoreMissingChange Whether we can continue if the id is not currently present.
   */
  private void queueReplicationIndexEvent(int indexNumber, String projectName, Instant lastUpdatedOn, boolean deleteIndex, boolean safeToIgnoreMissingChange) throws IOException {
    if (replicatedEventsCoordinator.isReplicationEnabled()) { // we only take the event if it's normal Gerrit functioning. If it's indexing we ignore them
      IndexToReplicate indexToReplicate =
          new IndexToReplicate(indexNumber, projectName, lastUpdatedOn, deleteIndex,
              replicatedEventsCoordinator.getReplicatedConfiguration().getThisNodeIdentity(), safeToIgnoreMissingChange);
      replicatedEventsCoordinator.queueEventForReplication(GerritEventFactory.createReplicatedIndexEvent(indexToReplicate));
      logger.atFine().log("RC Just added %s to cache queue", indexToReplicate);
    }
  }
}
