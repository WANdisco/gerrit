package com.google.gerrit.server.replication.processors;

import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;


public interface ReplicatedEventProcessor extends ReplicatedStoppable {
  void processIncomingReplicatedEvent(final ReplicatedEvent replicatedEvent);


  /**
   * Stop is used to call unsubscribe at appropriate time.. But as it passes in the this pointer I needed
   * to keep it abstract...
   */
  void subscribeEvent(ReplicatedEventProcessor toCall);
  void unsubscribeEvent(ReplicatedEventProcessor toCall);
}
