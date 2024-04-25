package com.google.gerrit.server.replication;

import com.google.gerrit.index.project.ProjectIndexer;
import com.google.gerrit.server.index.group.GroupIndexer;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.replication.configuration.ReplicatedConfiguration;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.server.replication.feeds.ReplicatedOutgoingAccountBaseIndexEventsFeed;
import com.google.gerrit.server.replication.feeds.ReplicatedOutgoingCacheEventsFeed;
import com.google.gerrit.server.replication.feeds.ReplicatedOutgoingIndexEventsFeed;
import com.google.gerrit.server.replication.feeds.ReplicatedOutgoingProjectEventsFeed;
import com.google.gerrit.server.replication.feeds.ReplicatedOutgoingProjectIndexEventsFeed;
import com.google.gerrit.server.replication.feeds.ReplicatedOutgoingServerEventsFeed;
import com.google.gerrit.server.replication.modules.ReplicationModule;
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
import com.google.inject.Injector;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import org.eclipse.jgit.errors.ConfigInvalidException;

import java.io.File;
import java.util.Map;
import java.util.Properties;

import static com.google.gerrit.server.replication.configuration.ReplicationConstants.REPLICATION_DISABLED;


public class TestingReplicatedEventsCoordinator implements ReplicatedEventsCoordinator {

  ReplicatedConfiguration replicatedConfiguration;
  ReplicatedIncomingCacheEventProcessor replicatedIncomingCacheEventProcessor;
  ReplicatedIncomingEventWorker replicatedIncomingEventWorker;
  ReplicatedOutgoingEventWorker replicatedOutgoingEventWorker;

  public TestingReplicatedEventsCoordinator() throws ConfigInvalidException {
    Properties testingProperties = new Properties();
    testingProperties.put(REPLICATION_DISABLED, true);
    replicatedConfiguration = new ReplicatedConfiguration(testingProperties);
    replicatedIncomingCacheEventProcessor = new ReplicatedIncomingCacheEventProcessor(this);
    replicatedIncomingEventWorker = new ReplicatedIncomingEventWorker(this);
    replicatedOutgoingEventWorker = new ReplicatedOutgoingEventWorker(this);
  }

  // allow supply of config.
  public TestingReplicatedEventsCoordinator(Properties testingProperties) throws Exception {
    replicatedConfiguration = new ReplicatedConfiguration(testingProperties);
    ensureEventsDirectoriesExistForTests();
    replicatedIncomingCacheEventProcessor = new ReplicatedIncomingCacheEventProcessor(this);
    replicatedIncomingEventWorker = new ReplicatedIncomingEventWorker(this);
    replicatedOutgoingEventWorker = new ReplicatedOutgoingEventWorker(this);
  }

  @Override
  public boolean isReplicationEnabled() {
    return false;
  }

  @Override
  public ChangeIndexer getChangeIndexer() {
    return null;
  }

  @Override
  public AccountIndexer getAccountIndexer() {
    return null;
  }

  @Override
  public GroupIndexer getGroupIndexer() {
    return null;
  }

  @Override
  public ProjectIndexer getProjectIndexer() {
    return null;
  }

  @Override
  public String getThisNodeIdentity() {
    return "TestNodeId";
  }

  @Override
  public ReplicatedConfiguration getReplicatedConfiguration() {
    return replicatedConfiguration;
  }

  @Override
  public Gson getGson() {
    return new ReplicationModule().provideGson();
  }

  @Override
  public GitRepositoryManager getGitRepositoryManager() {
    return null;
  }

  @Override
  public Map<EventWrapper.Originator, ReplicatedEventProcessor> getReplicatedProcessors() {
    return null;
  }

  @Override
  public SchemaFactory<ReviewDb> getSchemaFactory() {
    return null;
  }

  @Override
  public ChangeNotes.Factory getChangeNotesFactory() {
    return null;
  }

  @Override
  public void subscribeEvent(EventWrapper.Originator eventType, ReplicatedEventProcessor toCall) {

  }

  @Override
  public void unsubscribeEvent(EventWrapper.Originator eventType, ReplicatedEventProcessor toCall) {

  }

  @Override
  public boolean isCacheToBeEvicted(String cacheName) {
    return false;
  }

  @Override
  public void queueEventForReplication(EventWrapper event) {

  }

  @Override
  public ReplicatedIncomingIndexEventProcessor getReplicatedIncomingIndexEventProcessor() {
    return null;
  }

  @Override
  public ReplicatedIncomingAccountUserIndexEventProcessor getReplicatedIncomingAccountUserIndexEventProcessor() {
    return null;
  }

  @Override
  public ReplicatedIncomingAccountGroupIndexEventProcessor getReplicatedIncomingAccountGroupIndexEventProcessor() {
    return null;
  }

  @Override
  public ReplicatedIncomingServerEventProcessor getReplicatedIncomingServerEventProcessor() {
    return null;
  }

  @Override
  public ReplicatedIncomingCacheEventProcessor getReplicatedIncomingCacheEventProcessor() {
    return replicatedIncomingCacheEventProcessor;
  }

  @Override
  public ReplicatedIncomingProjectEventProcessor getReplicatedIncomingProjectEventProcessor() {
    return null;
  }

  @Override
  public ReplicatedIncomingProjectIndexEventProcessor getReplicatedIncomingProjectIndexEventProcessor() {
    return null;
  }

  @Override
  public ReplicatedOutgoingIndexEventsFeed getReplicatedOutgoingIndexEventsFeed() {
    return null;
  }

  @Override
  public ReplicatedOutgoingCacheEventsFeed getReplicatedOutgoingCacheEventsFeed() {
    return null;
  }

  @Override
  public ReplicatedOutgoingProjectEventsFeed getReplicatedOutgoingProjectEventsFeed() {
    return null;
  }

  @Override
  public ReplicatedOutgoingAccountBaseIndexEventsFeed getReplicatedOutgoingAccountBaseIndexEventsFeed() {
    return null;
  }

  @Override
  public ReplicatedOutgoingProjectIndexEventsFeed getReplicatedOutgoingProjectIndexEventsFeed() {
    return null;
  }

  @Override
  public ReplicatedOutgoingServerEventsFeed getReplicatedOutgoingServerEventsFeed() {
    return null;
  }

  @Override
  public ReplicatedIncomingEventWorker getReplicatedIncomingEventWorker() {
    return replicatedIncomingEventWorker;
  }

  @Override
  public ReplicatedOutgoingEventWorker getReplicatedOutgoingEventWorker() {
    return replicatedOutgoingEventWorker;
  }

  @Override
  public ReplicatedScheduling getReplicatedScheduling() {
    return null;
  }

  @Override
  public void start() {

  }

  @Override
  public void stop() {
  }

  @Override
  public Injector getSysInjector() {
    return null;
  }

  @Override
  public void setSysInjector(Injector sysInjector) {
  }

  private void ensureEventsDirectoriesExistForTests() throws Exception {
    checkForEventsDirectoryExists(replicatedConfiguration.getIncomingReplEventsDirectory());
    checkForEventsDirectoryExists(replicatedConfiguration.getIncomingTemporaryReplEventsDirectory());
    checkForEventsDirectoryExists(replicatedConfiguration.getIncomingFailedReplEventsDirectory());

    checkForEventsDirectoryExists(replicatedConfiguration.getOutgoingReplEventsDirectory());
    checkForEventsDirectoryExists(replicatedConfiguration.getOutgoingTemporaryReplEventsDirectory());
  }

  private void checkForEventsDirectoryExists(final File eventsDirectoryToCheck) throws Exception {
    if (!eventsDirectoryToCheck.exists()) {
      if (!eventsDirectoryToCheck.mkdirs()) {
        throw new Exception("RE {} path cannot be created! Replicated events will not work!" +
            eventsDirectoryToCheck.getAbsolutePath());
      }

    }
  }

}
