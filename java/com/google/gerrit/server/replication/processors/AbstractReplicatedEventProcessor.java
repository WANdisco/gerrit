package com.google.gerrit.server.replication.processors;

import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gson.Gson;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;

public abstract class AbstractReplicatedEventProcessor implements ReplicatedEventProcessor {

  protected final EventWrapper.Originator eventType;
  protected final ReplicatedEventsCoordinator replicatedEventsCoordinator;
  protected final Gson gson;

  // indicate what type of event we are interested in here!
  protected AbstractReplicatedEventProcessor(EventWrapper.Originator eventType, ReplicatedEventsCoordinator replicatedEventsCoordinator){
    this.eventType = eventType;
    this.replicatedEventsCoordinator = replicatedEventsCoordinator;
    this.gson = replicatedEventsCoordinator.getGson();
  }

  @Override
  public void subscribeEvent(ReplicatedEventProcessor toCall){
    replicatedEventsCoordinator.subscribeEvent(eventType, toCall);
  }

  @Override
  public void unsubscribeEvent(ReplicatedEventProcessor toCall) {
    // lets unsubscribe our listener now we are stopping.
    replicatedEventsCoordinator.unsubscribeEvent(eventType, toCall);
  }

}
