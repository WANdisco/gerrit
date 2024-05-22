package com.google.gerrit.server.replication.feeds;

import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.server.replication.processors.ReplicatedStoppable;

/**
 * I don't want to confuse this with a real feed class, but this is going to be the base class that allows
 * all feeds to use the same type of base information from the events coordinator.
 */
public class ReplicatedOutgoingEventsFeedCommon implements ReplicatedStoppable {
  protected final ReplicatedEventsCoordinator replicatedEventsCoordinator;

  protected ReplicatedOutgoingEventsFeedCommon(ReplicatedEventsCoordinator eventsCoordinator){
    this.replicatedEventsCoordinator = eventsCoordinator;
  }

  @Override
  public void stop() {
  }
}
