package com.google.gerrit.server.replication.feeds;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.server.replication.GerritEventFactory;
import com.google.gerrit.server.replication.SingletonEnforcement;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.server.replication.customevents.AccountGroupIndexEvent;
import com.google.gerrit.server.replication.customevents.AccountIndexEventBase;
import com.google.gerrit.server.replication.customevents.AccountUserIndexEvent;
import com.google.inject.Singleton;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;

import java.io.IOException;
import java.io.Serializable;

@Singleton //Not guice bound but makes it clear that it's a singleton
public class ReplicatedOutgoingAccountBaseIndexEventsFeed extends ReplicatedOutgoingEventsFeedCommon {
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
  public ReplicatedOutgoingAccountBaseIndexEventsFeed(ReplicatedEventsCoordinator eventsCoordinator) {
    super(eventsCoordinator);
    SingletonEnforcement.registerClass(ReplicatedOutgoingAccountBaseIndexEventsFeed.class);
  }


  @Override
  public void stop() {
    SingletonEnforcement.unregisterClass(ReplicatedOutgoingAccountBaseIndexEventsFeed.class);
  }

  /**
   * Queues either a AccountGroupIndexEvent or AccountUserIndexEvent event based on the identifier passed
   * to the method. The identifier is either an Account.Id or an AccountGroup.UUID.
   * The call to this method is either made from AccountIndexerImpl or GroupIndexerImpl
   * indexImplementation.
   *
   * @param identifier
   */
  public void replicateReindex(Serializable identifier) throws IOException {

    AccountIndexEventBase accountIndexEventBase = null;
    EventWrapper.Originator originator = null;

    if (identifier instanceof Account.Id) {
      accountIndexEventBase = new AccountUserIndexEvent((Account.Id) identifier, replicatedEventsCoordinator.getThisNodeIdentity());
      originator = EventWrapper.Originator.ACCOUNT_USER_INDEX_EVENT;
      logger.atFine().log("RC Account User reindex being replicated for Id: %s ", identifier);
    } else if (identifier instanceof AccountGroup.UUID) {
      accountIndexEventBase = new AccountGroupIndexEvent((AccountGroup.UUID) identifier, replicatedEventsCoordinator.getThisNodeIdentity());
      originator = EventWrapper.Originator.ACCOUNT_GROUP_INDEX_EVENT;
      logger.atFine().log("RC Account Group reindex being replicated for UUID: %s ", identifier);
    }

    replicatedEventsCoordinator.queueEventForReplication(GerritEventFactory.createReplicatedAccountIndexEvent(
        replicatedEventsCoordinator.getReplicatedConfiguration().getAllUsersName(), accountIndexEventBase, originator));
  }
}
