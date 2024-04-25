package com.google.gerrit.server.replication.feeds;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.replication.GerritEventFactory;
import com.google.gerrit.server.replication.SingletonEnforcement;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.server.replication.customevents.ProjectIndexEvent;
import com.google.inject.Singleton;
import com.wandisco.gerrit.gitms.shared.ReplicationConstants;

import java.io.IOException;

@Singleton //Not guice bound but makes it clear that it's a singleton
public class ReplicatedOutgoingProjectIndexEventsFeed extends ReplicatedOutgoingEventsFeedCommon {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * We only create this class from the replicatedEventsCoordinator.
   * This is a singleton, and it's enforced by our SingletonEnforcement below that if anyone else tries to create
   * this class it will fail.
   * Sorry by adding a getInstance, make this class look much more public than it is,
   * and people expect they can just call getInstance - when in fact they should always request it via the
   * ReplicatedEventsCoordinator.getReplicatedXWorker() methods.
   * @param eventsCoordinator
   */
  public ReplicatedOutgoingProjectIndexEventsFeed(ReplicatedEventsCoordinator eventsCoordinator) {
    super(eventsCoordinator);
    SingletonEnforcement.registerClass(ReplicatedOutgoingProjectIndexEventsFeed.class);
  }

  @Override
  public void stop() {
    SingletonEnforcement.unregisterClass(ReplicatedOutgoingProjectIndexEventsFeed.class);
  }

  /**
   * Queues a ProjectsIndexEvent
   * The ProjectsIndexEvent can be constructed with a boolean flag to state
   * whether the index event is to delete the project from the index.
   * @param nameKey: The name of the project to replicate the reindex for.
   */
  public void replicateReindex(Project.NameKey nameKey, boolean deleteFromIndex) throws IOException {

    ProjectIndexEvent projectIndexEvent = new ProjectIndexEvent(nameKey,
        replicatedEventsCoordinator.getReplicatedConfiguration().getThisNodeIdentity(), deleteFromIndex);

    logger.atFine().log("Queuing new ProjectIndexEvent, deleteFromIndex : %s ", deleteFromIndex);

    replicatedEventsCoordinator.queueEventForReplication(
        GerritEventFactory.createReplicatedProjectsIndexEvent(replicatedEventsCoordinator.getReplicatedConfiguration().getAllProjectsName(), projectIndexEvent));
  }
}
