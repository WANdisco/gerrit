package com.google.gerrit.server.replication;

import com.google.gerrit.server.config.AllProjectsNameProvider;
import com.google.gerrit.server.config.AllUsersNameProvider;
import com.google.gerrit.server.replication.configuration.ReplicatedConfiguration;
import org.eclipse.jgit.lib.Config;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.Properties;

import static com.google.gerrit.server.replication.configuration.ReplicationConstants.DEFAULT_MAX_LOGGING_PERIOD_VALUE_SECS;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_EVENTS_BACKOFF_CEILING_PERIOD;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_EVENTS_BACKOFF_INITIAL_PERIOD;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_EVENT_TYPES_TO_BE_SKIPPED;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_MAX_MS_TO_WAIT_BEFORE_PROPOSING_EVENTS;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_MAX_NUM_EVENTS_RETRIES;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_REPLICATED_EVENT_WORKER_CORE_PROJECTS;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_REPLICATED_EVENT_WORKER_POOL_MIN_SIZE;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_REPLICATED_EVENT_WORKER_POOL_SIZE;

public class ReplicatedConfigurationTest extends AbstractReplicationSetup {

  public ReplicatedScheduling scheduling;

  @Before
  public void setupTest() throws Exception {
    // make sure we clear out and have a new coordinator for each test - sorry, but otherwise we would need to be
    // clearing out lists which would change depend on ordering!
    Properties extraProperties = new Properties();

    // SET our pool to 2 items, plus the 2 core projects.
    extraProperties.put("dummy_property_with_long_value", "20000000L");

    // Make sure to pass the ignoreDefaultTestingProperties flag.
    AbstractReplicationSetup.setupReplicatedEventsCoordinatorProps(true, extraProperties, true);
    Assert.assertNotNull(dummyTestCoordinator);
  }

  @Test
  public void testDefaultConfiguration() {
    Assert.assertTrue(dummyTestCoordinator.getReplicatedConfiguration()
        .getOutgoingReplEventsDirectory().toString().contains("replicated_events/outgoing"));
    Assert.assertTrue(dummyTestCoordinator.getReplicatedConfiguration()
        .getIncomingReplEventsDirectory().toString().contains("replicated_events/incoming"));
    Assert.assertEquals(dummyTestCoordinator.getReplicatedConfiguration().getMaxSecsToWaitBeforeProposingEvents(), 5000);
    Assert.assertEquals(dummyTestCoordinator.getReplicatedConfiguration().getMaxNumberOfEventsBeforeProposing(), 30);
    Assert.assertEquals(dummyTestCoordinator.getReplicatedConfiguration().getEventWorkerDelayPeriodMs(), 1000);
    // 2 core projects ( all-users, all-projects )
    Assert.assertEquals(dummyTestCoordinator.getReplicatedConfiguration().getCoreProjects().size(), 2);
    // test default min worker pool size = 10 + 2 (core)
    Assert.assertEquals(dummyTestCoordinator.getReplicatedConfiguration().getMinNumberOfEventWorkerThreads(), 12);
    // test default max worker pool size = 10 + 2 (core)
    Assert.assertEquals(dummyTestCoordinator.getReplicatedConfiguration().getMaxNumberOfEventWorkerThreads(), 12);

  }


  @Test
  public void testSetNewConfiguration() throws Exception {

    Properties testingProperties = new Properties();

    // SET our pool to 2 items, plus the 2 core projects.
    testingProperties.put(GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING, "29");
    testingProperties.put(GERRIT_MAX_MS_TO_WAIT_BEFORE_PROPOSING_EVENTS, "15");
    testingProperties.put(GERRIT_MAX_NUM_EVENTS_RETRIES, "8");
    testingProperties.put(GERRIT_EVENTS_BACKOFF_INITIAL_PERIOD, "0.5");
    testingProperties.put(GERRIT_EVENTS_BACKOFF_CEILING_PERIOD, "5");

    testingProperties.put(GERRIT_REPLICATED_EVENT_WORKER_POOL_SIZE, "5");

    dummyTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);

    Assert.assertEquals(dummyTestCoordinator.getReplicatedConfiguration()
        .getMaxNumberOfEventsBeforeProposing(), 29);
    Assert.assertEquals(dummyTestCoordinator.getReplicatedConfiguration()
        .getMaxSecsToWaitBeforeProposingEvents(), 15000);
    Assert.assertEquals(2, dummyTestCoordinator.getReplicatedConfiguration().getCoreProjects().size());
    // default max=min using the override max non core of 5+2(core)
    Assert.assertEquals(dummyTestCoordinator.getReplicatedConfiguration()
        .getMinNumberOfEventWorkerThreads(), 7);
    Assert.assertEquals(dummyTestCoordinator.getReplicatedConfiguration()
        .getMaxNumberOfEventWorkerThreads(), 7);
    Assert.assertEquals(dummyTestCoordinator.getReplicatedConfiguration()
        .getIndexBackoffCeilingPeriodMs(), 5000);
    Assert.assertEquals(dummyTestCoordinator.getReplicatedConfiguration()
        .getIndexBackoffInitialPeriodMs(), 500);
  }


  @Test(expected = NumberFormatException.class)
  public void testLongTooLarge(){
    //Invalid long value
    ReplicatedConfiguration.sanitizeLongValueAndConvertToMilliseconds("9223372036854775808L");
  }

  @Test
  public void testCleanLforLongAndConvertToMilliseconds() {
    Assert.assertEquals(ReplicatedConfiguration.sanitizeLongValueAndConvertToMilliseconds("200L"), "200000");
    Assert.assertEquals(ReplicatedConfiguration.sanitizeLongValueAndConvertToMilliseconds("2000L"), "2000000");
    Assert.assertEquals(ReplicatedConfiguration.sanitizeLongValueAndConvertToMilliseconds("20000L"), "20000000");
    Assert.assertEquals(ReplicatedConfiguration.sanitizeLongValueAndConvertToMilliseconds("200000L"), "200000000");
    Assert.assertEquals(ReplicatedConfiguration.sanitizeLongValueAndConvertToMilliseconds("2000000L"), "2000000000");
    Assert.assertEquals(ReplicatedConfiguration.sanitizeLongValueAndConvertToMilliseconds("20000000L"), "20000000000");
    Assert.assertEquals(ReplicatedConfiguration.sanitizeLongValueAndConvertToMilliseconds("20.L"), "20000");
    Assert.assertEquals(ReplicatedConfiguration.sanitizeLongValueAndConvertToMilliseconds("20."), "20000");
    Assert.assertEquals(ReplicatedConfiguration.sanitizeLongValueAndConvertToMilliseconds("0.2"), "200");
    Assert.assertEquals(ReplicatedConfiguration.sanitizeLongValueAndConvertToMilliseconds("0.2L"), "200");
    Assert.assertEquals(ReplicatedConfiguration.sanitizeLongValueAndConvertToMilliseconds("2.5L"), "2500");
    Assert.assertEquals(ReplicatedConfiguration.sanitizeLongValueAndConvertToMilliseconds("2.5"), "2500");
    Assert.assertEquals(ReplicatedConfiguration.sanitizeLongValueAndConvertToMilliseconds("2000000.5000"), "2000000500");
    //300000ms is 5mins need to verify that the 300secs is converted correctly to 300000
    Assert.assertEquals(ReplicatedConfiguration.sanitizeLongValueAndConvertToMilliseconds(DEFAULT_MAX_LOGGING_PERIOD_VALUE_SECS), "300000");
  }


  //   The test is NOT using an override as we are passing an empty string for the GERRIT_EVENT_TYPES_TO_BE_SKIPPED property
//   only the default events to skip ( RefReplicatedEvent and RefReplicationDoneEvent ) should be skipped.
  @Test
  public void testDefaultEventsToSkip() throws Exception {

    Properties testingProperties = new Properties();

    testingProperties.put(GERRIT_EVENT_TYPES_TO_BE_SKIPPED, "");
    dummyTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);
    Assert.assertEquals(2, dummyTestCoordinator.getReplicatedConfiguration()
        .getEventSkipList().size());

  }

  // The test is using an override so we do not want the defaults to be skipped anymore
  // The only event that should be skipped is the CommentAddedEvent
  @Test
  public void testOverrideSkipEvents() throws Exception {

    Properties testingProperties = new Properties();

    testingProperties.put(GERRIT_EVENT_TYPES_TO_BE_SKIPPED, "CommentAddedEvent");
    dummyTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);
    Assert.assertEquals(1, dummyTestCoordinator.getReplicatedConfiguration()
        .getEventSkipList().size());

  }

  // The test is using an override so we do not want the defaults to be skipped anymore by default
  // We should skip all the events specified such as CommentAddedEvent, RefReplicatedEvent, RefReplicationDoneEvent
  @Test
  public void testOverrideSkipEventsAll() throws Exception {

    Properties testingProperties = new Properties();

    testingProperties.put(GERRIT_EVENT_TYPES_TO_BE_SKIPPED, "CommentAddedEvent, RefReplicatedEvent, RefReplicationDoneEvent");
    dummyTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);
    Assert.assertEquals(3, dummyTestCoordinator.getReplicatedConfiguration()
        .getEventSkipList().size());

  }

}
