package com.google.gerrit.server.replication;

import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.server.replication.workers.ReplicatedIncomingEventWorker;
import com.wandisco.gerrit.gitms.shared.config.ReplicationConfiguration;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import com.wandisco.gerrit.gitms.shared.util.ObjectUtils;
import com.wandisco.gerrit.gitms.shared.events.EventFilename;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

import static com.google.gerrit.server.replication.PersistedEventInformation.TMP_EVENTS_BATCH;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_MAX_MS_TO_WAIT_BEFORE_PROPOSING_EVENTS;
import static com.wandisco.gerrit.gitms.shared.util.StringUtils.createUniqueString;
import static com.wandisco.gerrit.gitms.shared.util.StringUtils.getProjectNameSha1;
import static com.wandisco.gerrit.gitms.shared.events.EventFilename.InvalidEventFilenameException;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

public class PersistedEventInformationTest extends AbstractReplicationSetup {

  public static File outgoingDir;

  @BeforeClass
  public static void setupTest() throws Exception {
    // make sure we clear out and have a new coordinator for each test - sorry, but otherwise we would need to be
    // clearing out lists which would change depend on ordering!
    AbstractReplicationSetup.setupReplicatedEventsCoordinatorProps(true, null);
    outgoingDir = dummyTestCoordinator.getReplicatedConfiguration().getOutgoingReplEventsDirectory();
  }

  @After
  public void tearDown() {

    File outgoingPath = dummyTestCoordinator.getReplicatedConfiguration().getOutgoingReplEventsDirectory();
    String[]entries = outgoingPath.list();
    for(String s: entries){
      File currentFile = new File(outgoingPath.getPath(),s);
      currentFile.delete();
    }
  }

  @Test
  public void testOutgoingEventInformationConstructor() throws IOException {

    EventWrapper dummyWrapper = createAccountIndexEventWrapper("ProjectA");
    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, dummyWrapper);

    Assert.assertNotNull(persistedEventInformation.getFinalEventFile());
    Assert.assertNotNull(persistedEventInformation.getEventFile());
    Assert.assertNotNull(persistedEventInformation.getFileOutputStream());

    {
      final String tmpFileName = persistedEventInformation.getEventFile().getName();
      Assert.assertTrue(tmpFileName.contains(TMP_EVENTS_BATCH) && tmpFileName.contains(".tmp"));
    }

    {
      final String actualFileName = persistedEventInformation.getFinalEventFile().getName();
      Assert.assertTrue(actualFileName.contains("events") && actualFileName.contains(".json"));
    }

    Assert.assertTrue( "Temporary event file for batching should exist - it must be a temp file by default construction.", persistedEventInformation.getEventFile().exists());
    Assert.assertTrue( "Final File cannot exist yet - it must be a temp file by default construction.", !persistedEventInformation.getFinalEventFile().exists());

    Assert.assertEquals(persistedEventInformation.getNumEventsWritten().get(), 0);
    Assert.assertEquals(persistedEventInformation.getProjectName(), "ProjectA");
  }

  /**
   * Test ways of setting the final event name, either directly specified, from the event information, or null negative tests
   *
   **/
  @Test
  public void testGetFinalEventFileName() throws IOException {
    final String uniquefilename = createUniqueString("some-uniquefile");
    final String uniqueProjectname = createUniqueString("some-project");

    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, uniquefilename, uniqueProjectname );

    Assert.assertEquals(uniquefilename, persistedEventInformation.getFinalEventFile().getName());

  }

  @Test
  public void testGetFinalEventFileNameFromEventData() throws IOException {
    EventWrapper dummyWrapper = createAccountIndexEventWrapper("ProjectA");
    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, dummyWrapper);

    String eventTimestamp = dummyWrapper.getEventData().getEventTimestamp();
    String eventNanoTime = ObjectUtils.getHexStringOfLongObjectHash(
        Long.parseLong(dummyWrapper.getEventData().getEventNanoTime()));

    String nodeId = dummyWrapper.getEventData().getNodeIdentity();
    String objectHash = ObjectUtils.getHexStringOfIntObjectHash(dummyWrapper.hashCode());
    String projectSha = getProjectNameSha1(dummyWrapper.getProjectName());

    Assert.assertEquals(persistedEventInformation.getFinalEventFile().getName(),
        String.format(
            EventFilename.EVENTS_FILENAME_FORMAT,
            eventTimestamp,
            eventNanoTime,
            nodeId,
            projectSha,
            objectHash));
  }

  @Test
  public void testEventFilenameMinimalValidationPath() throws IOException {
    EventWrapper dummyWrapper = createAccountIndexEventWrapper("ProjectA");
    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, dummyWrapper);

    // Get event filename data using the minimal validation parsing path
    EventFilename eventFilename =
        new EventFilename(persistedEventInformation.getFinalEventFile(),false);

    // Validate the parsed data, comparing the directly accessed values with
    // their validated counterparts.
    Assert.assertEquals(
        eventFilename.getTimestamp(),
        eventFilename.getValidatedTimestamp());
    Assert.assertEquals(
        eventFilename.getNanoTime(),
        eventFilename.getValidatedNanoTime());
    Assert.assertEquals(
        eventFilename.getNodeId(),
        eventFilename.getValidatedNodeId());
    Assert.assertEquals(
        eventFilename.getProjectSha(),
        eventFilename.getValidatedProjectSha());
    Assert.assertEquals(
        eventFilename.getHashCode(),
        eventFilename.getValidatedHashCode());

    // Ensure the generated filename matches the input filename. This
    // also validates the format of the filename using the filename validator
    // in the EventFilename class.
    Assert.assertEquals(persistedEventInformation.getFinalEventFile().getName(),
        eventFilename.getValidatedFilename());
  }

  // The maximum validation parser isn't used in the real-world due to
  // performance requirements optimised for in the minimal validation parser,
  // but it is useful as a test tool to validate our expectations of the input
  // data used for constructing an event filename
  @Test
  public void testEventFilenameMaximumValidationPath() throws IOException {
    EventWrapper dummyWrapper = createAccountIndexEventWrapper("ProjectA");
    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, dummyWrapper);

    // Get event filename data using the maximum validation parsing path.
    // If validation fails we'll get an exception which will fail the test.
    new EventFilename(persistedEventInformation.getFinalEventFile(),true);
  }

  @Test(expected = NullPointerException.class)
  public void test_atomicRenameAndResetNullFinalName() throws IOException {

    new PersistedEventInformation(dummyTestCoordinator, null, "any old project");

    // should never get here
    Assert.fail();
  }


  @Test
  public void test_atomicRenameAndReset() throws IOException {
    EventWrapper dummyWrapper = createAccountIndexEventWrapper("ProjectA");
    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, dummyWrapper);

    persistedEventInformation.atomicRenameTmpFilename();

    // Make sure we persisted into the outgoing directory by using the correct constructor
    Assert.assertEquals(outgoingDir, persistedEventInformation.getEventsFinalDirectory());
    Assert.assertTrue(outgoingDir.exists());
    Assert.assertFalse(persistedEventInformation.getEventFile().exists());
    Assert.assertTrue(persistedEventInformation.getFinalEventFile().exists());
  }


  @Test
  public void testTimeToWaitBeforeProposingExpired() throws IOException, InterruptedException {
    EventWrapper dummyWrapper = createAccountIndexEventWrapper("ProjectA");
    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, dummyWrapper);

    Assert.assertEquals(dummyTestCoordinator.getReplicatedConfiguration()
        .getMaxSecsToWaitBeforeProposingEvents(), 5000);

    Thread.sleep(5000);

    Assert.assertTrue(persistedEventInformation.timeToWaitBeforeProposingExpired());
  }



  @Test
  public void testTimeToWaitBeforeProposingExpired_NotExpired() throws Exception {
    // dont use any statics - here as we are changing enviromnent / property context.
    Properties testingProperties = new Properties();

    testingProperties.put(GERRIT_MAX_MS_TO_WAIT_BEFORE_PROPOSING_EVENTS, "20L");

    ReplicatedEventsCoordinator testingReplicatedEventsCoordinator =
        new TestingReplicatedEventsCoordinator(testingProperties);

    EventWrapper dummyWrapper = createAccountIndexEventWrapper("ProjectA");
    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(testingReplicatedEventsCoordinator, dummyWrapper);

    Assert.assertEquals(testingReplicatedEventsCoordinator.getReplicatedConfiguration()
        .getMaxSecsToWaitBeforeProposingEvents(), 20000);

    Assert.assertFalse(persistedEventInformation.timeToWaitBeforeProposingExpired());
  }


  @Test
  public void testTimeToWaitBeforeProposingExpired_NegativeValue() throws Exception {
    // dont use any statics - here as we are changing enviromnent / property context.
    Properties testingProperties = new Properties();

    //Will always be expired if negative value
    testingProperties.put(GERRIT_MAX_MS_TO_WAIT_BEFORE_PROPOSING_EVENTS, "-1");

    ReplicatedEventsCoordinator testingReplicatedEventsCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);


    EventWrapper dummyWrapper = createAccountIndexEventWrapper("ProjectA");
    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(testingReplicatedEventsCoordinator, dummyWrapper);

    Assert.assertEquals(testingReplicatedEventsCoordinator.getReplicatedConfiguration()
        .getMaxSecsToWaitBeforeProposingEvents(), -1000);
    Assert.assertTrue(persistedEventInformation.timeToWaitBeforeProposingExpired());
  }


  @Test
  public void testExceedsMaxEventsBeforeProposing() throws Exception {
    // dont use any statics - here as we are changing enviromnent / property context.
    Properties testingProperties = new Properties();

    //Will always be expired if negative value
    testingProperties.put(GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING, "2");

    ReplicatedEventsCoordinator testingReplicatedEventsCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);

    EventWrapper dummyWrapper1 = createAccountIndexEventWrapper("ProjectA");
    EventWrapper dummyWrapper2 = createAccountIndexEventWrapper("ProjectA");
    EventWrapper dummyWrapper3 = createAccountIndexEventWrapper("ProjectA");


    byte[] bytes1 = getEventBytes(dummyWrapper1);
    byte[] bytes2 = getEventBytes(dummyWrapper2);
    byte[] bytes3 = getEventBytes(dummyWrapper3);

    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(testingReplicatedEventsCoordinator, dummyWrapper1);

    persistedEventInformation.writeEventsToFile(bytes1);
    persistedEventInformation.writeEventsToFile(bytes2);
    persistedEventInformation.writeEventsToFile(bytes3);

    Assert.assertEquals(persistedEventInformation.getNumEventsWritten().get(), 3);

    Assert.assertTrue(persistedEventInformation.exceedsMaxEventsBeforeProposing());
  }




  @Test
  public void testSetFileReady_noEventsWritten() throws Exception {
    EventWrapper dummyWrapper = createAccountIndexEventWrapper("ProjectA");
    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, dummyWrapper);

    Assert.assertEquals(persistedEventInformation.getNumEventsWritten().get(), 0);

    persistedEventInformation.setFileReady();
    Assert.assertFalse(persistedEventInformation.isFileOutputStreamClosed());
  }


  @Test
  public void testSetFileReady() throws Exception {
    EventWrapper dummyWrapper = createAccountIndexEventWrapper("ProjectA");
    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, dummyWrapper);


    byte[] bytes = getEventBytes(dummyWrapper);
    persistedEventInformation.writeEventsToFile(bytes);

    Assert.assertEquals(persistedEventInformation.getNumEventsWritten().get(), 1);

    persistedEventInformation.setFileReady();
    Assert.assertTrue(persistedEventInformation.isFileOutputStreamClosed());
  }


  @AfterClass
  public static void shutdown(){
    dummyTestCoordinator.stop();

  }

}
