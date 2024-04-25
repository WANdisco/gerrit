package com.google.gerrit.server.replication.coordinators;

import com.google.common.base.Verify;
import com.google.gerrit.index.project.ProjectIndexer;
import com.google.gerrit.server.index.group.GroupIndexer;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.replication.configuration.ReplicatedConfiguration;
import com.google.gerrit.server.replication.ReplicatedScheduling;
import com.google.gerrit.server.replication.feeds.ReplicatedOutgoingAccountBaseIndexEventsFeed;
import com.google.gerrit.server.replication.feeds.ReplicatedOutgoingCacheEventsFeed;
import com.google.gerrit.server.replication.feeds.ReplicatedOutgoingIndexEventsFeed;
import com.google.gerrit.server.replication.feeds.ReplicatedOutgoingProjectEventsFeed;
import com.google.gerrit.server.replication.feeds.ReplicatedOutgoingProjectIndexEventsFeed;
import com.google.gerrit.server.replication.feeds.ReplicatedOutgoingServerEventsFeed;
import com.google.gerrit.server.replication.processors.ReplicatedEventProcessor;
import com.google.gerrit.server.replication.processors.ReplicatedIncomingAccountGroupIndexEventProcessor;
import com.google.gerrit.server.replication.processors.ReplicatedIncomingAccountUserIndexEventProcessor;
import com.google.gerrit.server.replication.processors.ReplicatedIncomingCacheEventProcessor;
import com.google.gerrit.server.replication.processors.ReplicatedIncomingIndexEventProcessor;
import com.google.gerrit.server.replication.processors.ReplicatedIncomingProjectEventProcessor;
import com.google.gerrit.server.replication.processors.ReplicatedIncomingProjectIndexEventProcessor;
import com.google.gerrit.server.replication.processors.ReplicatedIncomingServerEventProcessor;
import com.google.gerrit.server.replication.workers.ReplicatedIncomingEventWorker;
import com.google.gerrit.server.replication.workers.ReplicatedOutgoingEventWorker;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.index.account.AccountIndexer;
import com.google.gerrit.server.index.change.ChangeIndexer;
import com.google.gson.Gson;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;

import java.util.Map;

@Singleton
public class NonReplicatedEventsCoordinatorImpl implements ReplicatedEventsCoordinator {

  private final ReplicatedConfiguration replicatedConfiguration;

  private final Provider<SchemaFactory<ReviewDb>> schemaFactory;
  private final Provider<ChangeNotes.Factory> notesFactory;

  private final Provider<ChangeIndexer> changeIndexer;
  private final Provider<AccountIndexer> accountIndexer;
  private final Provider<GroupIndexer> groupIndexer;
  private final Provider<ProjectIndexer> projectIndexer;

  private final GitRepositoryManager gitRepositoryManager;



  @Inject
  public NonReplicatedEventsCoordinatorImpl(ReplicatedConfiguration configuration,
                                         Provider<SchemaFactory<ReviewDb>> schemaFactory,
                                         Provider<ChangeNotes.Factory> notesFactory,
                                         Provider<ChangeIndexer> changeIndexer,
                                         Provider<AccountIndexer> accountIndexer,
                                         Provider<GroupIndexer> groupIndexer,
                                         Provider<ProjectIndexer> projectIndexer,
                                         GitRepositoryManager gitRepositoryManager) throws Exception {
    Verify.verifyNotNull(configuration);
    this.replicatedConfiguration = configuration;
    this.schemaFactory = schemaFactory;
    this.notesFactory = notesFactory;
    this.changeIndexer = changeIndexer;
    this.accountIndexer = accountIndexer;
    this.groupIndexer = groupIndexer;
    this.projectIndexer = projectIndexer;
    this.gitRepositoryManager = gitRepositoryManager;
  }

  @Override
  public Injector getSysInjector() {
    return null;
  }

  @Override
  public void setSysInjector(Injector sysInjector) { }

  @Override
  public void start() {

  }

  @Override
  public void stop() {

  }

  @Override
  public boolean isReplicationEnabled() {
    return false;
  }

  @Override
  public ChangeIndexer getChangeIndexer() {
    return changeIndexer.get();
  }

  @Override
  public AccountIndexer getAccountIndexer() {
    return accountIndexer.get();
  }

  @Override
  public GroupIndexer getGroupIndexer() {
    return groupIndexer.get();
  }

  @Override
  public ProjectIndexer getProjectIndexer() {
    return projectIndexer.get();
  }

  @Override
  public ReplicatedConfiguration getReplicatedConfiguration() {
    return replicatedConfiguration;
  }

  @Override
  public GitRepositoryManager getGitRepositoryManager() {
    return gitRepositoryManager;
  }

  @Override
  public Gson getGson() {
    throw new UnsupportedOperationException("getGson: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public SchemaFactory<ReviewDb> getSchemaFactory() {
    return schemaFactory.get();
  }


  @Override
  public ChangeNotes.Factory getChangeNotesFactory(){
    return notesFactory.get();
  }

  @Override
  public Map<EventWrapper.Originator, ReplicatedEventProcessor> getReplicatedProcessors() {
    throw new UnsupportedOperationException("getReplicatedProcessors: Unable to get access to replicated objects when not using replicated entry points.");
  }


  @Override
  public void subscribeEvent(EventWrapper.Originator eventType, ReplicatedEventProcessor toCall) {
    throw new UnsupportedOperationException("subscribeEvent: Unable to get access to replicated objects when not using replicated entry points.");

  }

  @Override
  public void unsubscribeEvent(EventWrapper.Originator eventType, ReplicatedEventProcessor toCall) {
    throw new UnsupportedOperationException("unsubscribeEvent: Unable to get access to replicated objects when not using replicated entry points.");

  }

  @Override
  public boolean isCacheToBeEvicted(String cacheName) {
    return false;
  }

  @Override
  public ReplicatedIncomingIndexEventProcessor getReplicatedIncomingIndexEventProcessor() {
    throw new UnsupportedOperationException("getReplicatedIncomingIndexEventProcessor: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public ReplicatedIncomingAccountUserIndexEventProcessor getReplicatedIncomingAccountUserIndexEventProcessor() {
    throw new UnsupportedOperationException("getReplicatedIncomingAccountUserIndexEventProcessor: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public ReplicatedIncomingAccountGroupIndexEventProcessor getReplicatedIncomingAccountGroupIndexEventProcessor() {
    throw new UnsupportedOperationException("getReplicatedIncomingAccountGroupIndexEventProcessor: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public ReplicatedIncomingServerEventProcessor getReplicatedIncomingServerEventProcessor() {
    throw new UnsupportedOperationException("getReplicatedIncomingServerEventProcessor: Unable to get access to replicated objects when not using replicated entry points.");

  }

  @Override
  public ReplicatedIncomingCacheEventProcessor getReplicatedIncomingCacheEventProcessor() {
    throw new UnsupportedOperationException("getReplicatedIncomingCacheEventProcessor: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public ReplicatedIncomingProjectEventProcessor getReplicatedIncomingProjectEventProcessor() {
    throw new UnsupportedOperationException("getReplicatedIncomingProjectEventProcessor: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public ReplicatedIncomingProjectIndexEventProcessor getReplicatedIncomingProjectIndexEventProcessor() {
    throw new UnsupportedOperationException("getReplicatedIncomingProjectIndexEventProcessor: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public ReplicatedOutgoingIndexEventsFeed getReplicatedOutgoingIndexEventsFeed() {
    throw new UnsupportedOperationException("getReplicatedOutgoingIndexEventsFeed: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public ReplicatedOutgoingCacheEventsFeed getReplicatedOutgoingCacheEventsFeed() {
    throw new UnsupportedOperationException("getReplicatedOutgoingCacheEventsFeed: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public ReplicatedOutgoingProjectEventsFeed getReplicatedOutgoingProjectEventsFeed() {
    throw new UnsupportedOperationException("getReplicatedOutgoingProjectEventsFeed: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public ReplicatedOutgoingAccountBaseIndexEventsFeed getReplicatedOutgoingAccountBaseIndexEventsFeed() {
    throw new UnsupportedOperationException("getReplicatedOutgoingAccountBaseIndexEventsFeed: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public ReplicatedOutgoingProjectIndexEventsFeed getReplicatedOutgoingProjectIndexEventsFeed() {
    throw new UnsupportedOperationException("getReplicatedOutgoingProjectIndexEventsFeed: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public ReplicatedOutgoingServerEventsFeed getReplicatedOutgoingServerEventsFeed() {
    throw new UnsupportedOperationException("getReplicatedOutgoingServerEventsFeed: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public ReplicatedIncomingEventWorker getReplicatedIncomingEventWorker() {
    throw new UnsupportedOperationException("getReplicatedIncomingEventWorker: Unable to get access to replicated objects when not using replicated entry points.");
  }


  @Override
  public ReplicatedOutgoingEventWorker getReplicatedOutgoingEventWorker() {
    throw new UnsupportedOperationException("getReplicatedOutgoingEventWorker: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public ReplicatedScheduling getReplicatedScheduling() {
    throw new UnsupportedOperationException("getReplicatedScheduling: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public void queueEventForReplication(EventWrapper event) {
    throw new UnsupportedOperationException("queueEventForReplication: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public String getThisNodeIdentity() {
    return getReplicatedConfiguration().getThisNodeIdentity();
  }

}
