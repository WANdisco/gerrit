package com.google.gerrit.server.replication;

import com.google.gson.Gson;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import com.wandisco.gerrit.gitms.shared.events.exceptions.InvalidEventJsonException;
import com.wandisco.gerrit.gitms.shared.util.StringUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Arrays;
import java.util.Properties;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING;

public class FailedEventUtilTest extends AbstractReplicationSetup {

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  public ReplicatedScheduling scheduling;

  @BeforeClass
  public static void setupTest() throws Exception {
    // make sure we clear out and have a new coordinator for each test - sorry, but otherwise we would need to be
    // clearing out lists which would change depend on ordering!
    Properties testingProperties = new Properties();
    testingProperties.put(GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING, "2");

    AbstractReplicationSetup.setupReplicatedEventsCoordinatorProps(true, testingProperties);
  }

  @AfterClass
  public static void tearDown() {

    // Attempt to tidy up / delete after ourselves and no leave a lot of random behind because its gerrit, the workspace
    // cache areas can get very bloated as they aren't usually cleaned up - let get left behind.
    File outgoingPath = dummyTestCoordinator.getReplicatedConfiguration().getOutgoingReplEventsDirectory();
    File incomingPath = dummyTestCoordinator.getReplicatedConfiguration().getIncomingReplEventsDirectory();

    File[] oEntries = outgoingPath.listFiles();
    File[] iEntries = incomingPath.listFiles();

    assert oEntries != null;
    int oLen = oEntries.length;
    assert iEntries != null;
    int iLen = iEntries.length;

    File[] result = new File[oLen + iLen];

    System.arraycopy(oEntries, 0, result, 0, oLen);
    System.arraycopy(iEntries, 0, result, oLen, iLen);


    for(File currentFile: result){
      currentFile.delete();
    }
  }




  @Test
  public void testRemoveEachEventInTurn_CheckPersistenceOfRemainingEventsAndOrdering() throws Exception {

    final String projectName = StringUtils.createUniqueString("ProjectA");

    // This test setup now replaces 5 identical tests from this point on, as the only difference was the actual wrapper being removed / worked on
    // So setup 5 events, and we shall change how we use them later on.
    final String uniqueFileName = StringUtils.createUniqueString("events-test");
    EventWrapper dummyWrapper1 = createAccountIndexEventWrapper(projectName);
    EventWrapper dummyWrapper2 = createAccountIndexEventWrapper(projectName);
    EventWrapper dummyWrapper3 = createAccountIndexEventWrapper(projectName);
    EventWrapper dummyWrapper4 = createAccountIndexEventWrapper(projectName);
    EventWrapper dummyWrapper5 = createAccountIndexEventWrapper(projectName);
    final List<EventWrapper> originalEventsList = Arrays.asList(dummyWrapper1, dummyWrapper2, dummyWrapper3, dummyWrapper4, dummyWrapper5);

    // So we will have a unique file under incoming being processed by this call, note changes to the file happen under the /tmp sub directory.
    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, uniqueFileName, projectName);

    for ( EventWrapper event : originalEventsList ){
      persistedEventInformation.writeEventsToFile(getEventBytes(event));
    }
    persistedEventInformation.atomicRenameTmpFilename();

    Assert.assertFalse(persistedEventInformation.getEventFile().exists());
    Assert.assertTrue(persistedEventInformation.getFinalEventFile().exists());

    final File originalFinishedEventFile = persistedEventInformation.getFinalEventFile();
    long origSize = originalFinishedEventFile.length();

    // need a copy so we can alter it - as the original list can't be changed, and we dont want it to be
    List<EventWrapper> reaminingEventsToBeProcessed = new ArrayList<>(originalEventsList.size());

    // so lets go through all 5 events, removing one from the list at a time, and writing out all remaining so we have 4 different persisted
    // events each time.
    for( int index=0; index<originalEventsList.size(); index++) {
      // for this test we want to indicate we processed wrapper(pos=index), so add all items, then remove the required one,
      // so we always start with the same list.
      reaminingEventsToBeProcessed.clear();
      reaminingEventsToBeProcessed.addAll(originalEventsList);
      reaminingEventsToBeProcessed.remove(index);

      performAndCompareRemainingEventPersistence(projectName, persistedEventInformation, originalFinishedEventFile, origSize, reaminingEventsToBeProcessed);
    }
  }

  @Test
  public void testRemoveUpTo_multipleFailedEvents() throws Exception {

    final String projectName = StringUtils.createUniqueString("ProjectA");

    // This test setup now replaces 5 identical tests from this point on, as the only difference was the actual wrapper being removed / worked on
    // So setup 5 events, and we shall change how we use them later on.
    final String uniqueFileName = StringUtils.createUniqueString("events-test");
    EventWrapper dummyWrapper1 = createAccountIndexEventWrapper(projectName);
    EventWrapper dummyWrapper2 = createAccountIndexEventWrapper(projectName);
    EventWrapper dummyWrapper3 = createAccountIndexEventWrapper(projectName);
    EventWrapper dummyWrapper4 = createAccountIndexEventWrapper(projectName);
    EventWrapper dummyWrapper5 = createAccountIndexEventWrapper(projectName);
    final List<EventWrapper> originalEventsList = Arrays.asList(dummyWrapper1, dummyWrapper2, dummyWrapper3, dummyWrapper4, dummyWrapper5);

    // So we will have a unique file under incoming being processed by this call, note changes to the file happen under the /tmp sub directory.
    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, uniqueFileName, projectName);

    for ( EventWrapper event : originalEventsList ){
      persistedEventInformation.writeEventsToFile(getEventBytes(event));
    }
    persistedEventInformation.atomicRenameTmpFilename();

    Assert.assertFalse(persistedEventInformation.getEventFile().exists());
    Assert.assertTrue(persistedEventInformation.getFinalEventFile().exists());

    final File originalFinishedEventFile = persistedEventInformation.getFinalEventFile();
    long origSize = originalFinishedEventFile.length();

    // need a copy so we can alter it - as the original list can't be changed, and we dont want it to be
    List<EventWrapper> reaminingEventsToBeProcessed = new ArrayList<>(originalEventsList.size());
    reaminingEventsToBeProcessed.addAll(originalEventsList);

    // Lets remove all events from the list except the one before the end ( dummyWrapper4 ), so only it will be persisted.
    reaminingEventsToBeProcessed.removeAll(Arrays.asList(dummyWrapper1, dummyWrapper2, dummyWrapper3, dummyWrapper5));
    performAndCompareRemainingEventPersistence(projectName, persistedEventInformation, originalFinishedEventFile, origSize, reaminingEventsToBeProcessed);
  }

  private void performAndCompareRemainingEventPersistence(final String projectName,
                                                          final PersistedEventInformation persistedEventInformation,
                                                          final File originalFinishedEventFile,
                                                          long origSize,
                                                          final List<EventWrapper> reaminingEventsToBeProcessed ) throws IOException, InvalidEventJsonException {
    // Process the final file location (not the tmp file getEventFile) with only these remaining events.
    {
      ReplicatedEventTask replicatedEventTask = new ReplicatedEventTask(projectName,
          originalFinishedEventFile, dummyTestCoordinator);

      FailedEventUtil.persistRemainingEvents(dummyTestCoordinator, replicatedEventTask, reaminingEventsToBeProcessed);
    }

    // make sure the file we started with ( that was processed as a tmp file, was atomically replaced.
    // So a) tmp file isn't there and b) original file is there with diff size
    File finalProcessedFile = persistedEventInformation.getFinalEventFile();
    Assert.assertTrue("Final file must be back in original location", finalProcessedFile.exists());
    Assert.assertEquals("Final file must be back in original location and name", originalFinishedEventFile.getAbsolutePath(), finalProcessedFile.getAbsolutePath());

    Assert.assertNotEquals(finalProcessedFile.getAbsolutePath(), persistedEventInformation.getEventFile().getAbsolutePath(),
        "Must be reducing the actual file in the incoming directory, not some temp file. ");

    long newSize = finalProcessedFile.length();

    Assert.assertNotEquals("Failed and persisted file should have reduced size", origSize, newSize);
    Assert.assertTrue( "New file with less events must be smaller in size than original file. ", newSize < origSize);

    /** Validate the final event file content is missing dummyWrapper2 **/
    ReplicatedEventTask finalReducedFileTask = new ReplicatedEventTask(projectName, finalProcessedFile, dummyTestCoordinator );

    List<EventWrapper> eventListFromFile = finalReducedFileTask.getEventsDirectlyFromFile();

    compareEventsListsMatchExactly(reaminingEventsToBeProcessed, eventListFromFile);
  }

  private void compareEventsListsMatchExactly(List<EventWrapper> reaminingEventsToBeProcessed, List<EventWrapper> eventListFromFile) {
    Assert.assertEquals("Events persisted to disk must be reduced to only this remaining events list size.", reaminingEventsToBeProcessed.size(), eventListFromFile.size());

    // now compare each member in the list.
    for (int index = 0; index < reaminingEventsToBeProcessed.size(); index++){
      compareEventWrappers(reaminingEventsToBeProcessed.get(index), eventListFromFile.get(index));
    }
  }


  private void compareEventWrappers(final EventWrapper eventToBeChecked, final EventWrapper originalEvent) {

    // So long term this is the best of all worlds - let each class item be compared exactly.
    // GER-1942 prevents this from working due to int/shorts being reserialized as longs, leading to incorrect compare
    // results -> for now compare each member correctly except the properties list - and use toString for now
    // until fixed.
//    Assert.assertEquals( originalEvent, eventToBeChecked );
    // Check off {
//    this.event = event;
//    this.className = className;
//    this.projectName = projectName;
//    this.eventOrigin = eventOrigin;
//    this.eventData = ObjectUtils.createObjectFromJson(event, GerritEventMetadata.class);


    Assert.assertEquals("Event String info must match",originalEvent.getEvent(), eventToBeChecked.getEvent());
    Assert.assertEquals("Event classname must match",originalEvent.getClassName(), eventToBeChecked.getClassName());
    Assert.assertEquals("Event projectname must match",originalEvent.getProjectName(), eventToBeChecked.getProjectName());
    Assert.assertEquals("Event origin must match",originalEvent.getEventOrigin(), eventToBeChecked.getEventOrigin());
    // TODO: GER-1942: To be fixed when we remove the properties items from being duplicated in GerritEventMetadata
    Assert.assertEquals("Event GerritEventMetadata ( adding timestamp / properties ) must match", originalEvent.getEventData(), eventToBeChecked.getEventData());
  }

}
