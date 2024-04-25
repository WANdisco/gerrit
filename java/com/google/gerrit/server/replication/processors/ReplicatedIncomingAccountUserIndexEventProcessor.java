package com.google.gerrit.server.replication.processors;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.server.replication.SingletonEnforcement;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.server.replication.customevents.AccountUserIndexEvent;
import com.google.gerrit.server.replication.exceptions.ReplicatedEventsTransientException;
import com.google.gerrit.server.index.account.AccountIndexer;
import com.google.inject.Singleton;
import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;

import java.io.IOException;

import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.ACCOUNT_USER_INDEX_EVENT;


@Singleton //Not guice bound but makes it clear that it's a singleton
public class ReplicatedIncomingAccountUserIndexEventProcessor extends AbstractReplicatedEventProcessor {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private AccountIndexer indexer;

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
  public ReplicatedIncomingAccountUserIndexEventProcessor(ReplicatedEventsCoordinator eventsCoordinator) {
    super(ACCOUNT_USER_INDEX_EVENT, eventsCoordinator);
    logger.atInfo().log("Creating main processor for event type: %s", eventType);
    subscribeEvent(this);
    SingletonEnforcement.registerClass(ReplicatedIncomingAccountUserIndexEventProcessor.class);
  }

  @Override
  public void stop() {
    SingletonEnforcement.unregisterClass(ReplicatedIncomingAccountUserIndexEventProcessor.class);
    unsubscribeEvent(this);
  }

  public AccountIndexer getIndexer() {
    if (indexer == null) {
      indexer = replicatedEventsCoordinator.getAccountIndexer();
    }
    return indexer;
  }

  @Override
  public void processIncomingReplicatedEvent(final ReplicatedEvent replicatedEvent) {
    reindexAccount((AccountUserIndexEvent) replicatedEvent);
  }

  private void reindexAccount(AccountUserIndexEvent accountUserIndexEvent) {
    try {
      //Perform a local reindex.
      getIndexer().indexNoRepl(new Account.Id(accountUserIndexEvent.id.get()));
    } catch (IOException ie) {
      final String err = String.format("RC AccountUser reindex issue hit while carrying out reindex of %s", accountUserIndexEvent);
      logger.atSevere().withCause(ie).log(err);
      throw new ReplicatedEventsTransientException(err, ie);
    }
  }
}
