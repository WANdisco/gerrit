package com.google.gerrit.server.replication.processors;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.server.index.group.GroupIndexer;
import com.google.gerrit.server.replication.SingletonEnforcement;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.server.replication.customevents.AccountGroupIndexEvent;
import com.google.gerrit.server.replication.exceptions.ReplicatedEventsTransientException;
import com.google.inject.Singleton;
import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;

import java.io.IOException;

import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.ACCOUNT_GROUP_INDEX_EVENT;


@Singleton //Not guice bound but makes it clear that it's a singleton
public class ReplicatedIncomingAccountGroupIndexEventProcessor extends AbstractReplicatedEventProcessor {
    private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private GroupIndexer indexer;

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
  public ReplicatedIncomingAccountGroupIndexEventProcessor(ReplicatedEventsCoordinator eventsCoordinator) {
    super(ACCOUNT_GROUP_INDEX_EVENT, eventsCoordinator);
    logger.atInfo().log("Creating main processor for event type: %s", eventType);
    subscribeEvent(this);
    SingletonEnforcement.registerClass(ReplicatedIncomingAccountGroupIndexEventProcessor.class);
  }


  @Override
  public void stop() {
    SingletonEnforcement.unregisterClass(ReplicatedIncomingAccountGroupIndexEventProcessor.class);
    unsubscribeEvent(this);
  }

  public GroupIndexer getIndexer() {
    if (indexer == null) {
      indexer = replicatedEventsCoordinator.getGroupIndexer();
    }
    return indexer;
  }


  @Override
  public void processIncomingReplicatedEvent(final ReplicatedEvent replicatedEvent) {
    reindexAccount((AccountGroupIndexEvent) replicatedEvent);
  }

  private void reindexAccount(AccountGroupIndexEvent accountGroupIndexEvent) {
    try {
      //Perform a local reindex.
      getIndexer().indexNoRepl(new AccountGroup.UUID(accountGroupIndexEvent.uuid.get()));
    } catch (IOException ie) {
      final String err = String.format("RC AccountUser reindex issue hit while carrying out reindex of %s", accountGroupIndexEvent);
      logger.atSevere().withCause(ie).log(err);
      throw new ReplicatedEventsTransientException(err, ie);
    }
  }
}
