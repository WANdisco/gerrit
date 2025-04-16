package com.google.gerrit.server.replication;

import com.google.gerrit.server.replication.workers.ReplicatedIncomingEventWorker;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import com.wandisco.gerrit.gitms.shared.events.exceptions.InvalidEventJsonException;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.eclipse.jgit.util.FS;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_REPLICATED_EVENT_WORKER_CORE_PROJECTS;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_REPLICATED_EVENT_WORKER_POOL_MIN_SIZE;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_REPLICATED_EVENT_WORKER_POOL_SIZE;
import static com.google.gerrit.server.replication.processors.ReplicatedIncomingIndexEventProcessor.buildListOfMissingIds;
import static com.wandisco.gerrit.gitms.shared.util.StringUtils.createUniqueString;
import static com.wandisco.gerrit.gitms.shared.util.StringUtils.getProjectNameSha1;

public class ReplicatedSchedulingTest extends AbstractReplicationSetup {

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  public ReplicatedScheduling scheduling;

  @Before
  public void setupTest() throws Exception {
    // make sure we clear out and have a new coordinator for each test - sorry, but otherwise we would need to be
    // clearing out lists which would change depend on ordering!
    AbstractReplicationSetup.setupReplicatedEventsCoordinatorProps();
    scheduling = new ReplicatedScheduling(dummyTestCoordinator);
    HashMap<String, ReplicatedEventTask> eventFilesInProgress = scheduling.getCopyEventsFilesInProgress();

    for (Map.Entry eventTask : eventFilesInProgress.entrySet()) {
      scheduling.clearEventsFileInProgress((ReplicatedEventTask) eventTask.getValue(), false);
    }

    scheduling.getSkippedProjectsEventFiles().clear();

    Assert.assertNotNull(dummyTestCoordinator);
    Assert.assertNotNull(scheduling);
  }

  @After
  public void tearDown() {

    File incomingPath = dummyTestCoordinator.getReplicatedConfiguration().getIncomingReplEventsDirectory();
    String[] entries = incomingPath.list();
    for (String s : entries) {
      File currentFile = new File(incomingPath.getPath(), s);
      currentFile.delete();
    }
  }


  @Test
  public void testOverrideReplicatedSchedulingConfiguration() throws Exception {
    // setup a dummy coordinator with non default values, so we can check the configuration setup works.
    // make sure we clear out and have a new coordinator for each test - sorry, but otherwise we would need to be
    // clearing out lists which would change depend on ordering!
    Properties testingProperties = new Properties();
    testingProperties.put(GERRIT_REPLICATED_EVENT_WORKER_POOL_MIN_SIZE, "15");
    testingProperties.put(GERRIT_REPLICATED_EVENT_WORKER_POOL_SIZE, "16");
    testingProperties.put(GERRIT_REPLICATED_EVENT_WORKER_CORE_PROJECTS, "bob,builder"); // this adds 2 more onto the all-users/all-projects
    AbstractReplicationSetup.setupReplicatedEventsCoordinatorProps(true, testingProperties, false);

    scheduling = new ReplicatedScheduling(dummyTestCoordinator);

    Assert.assertNotNull(dummyTestCoordinator);
    Assert.assertNotNull(scheduling);
    Assert.assertEquals(4, dummyTestCoordinator.getReplicatedConfiguration().getCoreProjects().size());
    Assert.assertEquals(20, dummyTestCoordinator.getReplicatedConfiguration().getMaxNumberOfEventWorkerThreads());
    Assert.assertEquals(19, dummyTestCoordinator.getReplicatedConfiguration().getMinNumberOfEventWorkerThreads());
  }

  @Test
  public void testCopyOfWIPMaintainsEntries() throws IOException {
    // this would start failing earlier if someone changes the coreProjects, so just checking otherwise change this test!!
    Assert.assertNotNull(scheduling);
    Assert.assertEquals(2, dummyTestCoordinator.getReplicatedConfiguration().getCoreProjects().size());
    Assert.assertEquals(4, dummyTestCoordinator.getReplicatedConfiguration().getMaxNumberOfEventWorkerThreads());
    Assert.assertEquals(4, dummyTestCoordinator.getReplicatedConfiguration().getMinNumberOfEventWorkerThreads());
    Assert.assertEquals(0, scheduling.getNumEventFilesInProgress());
    Assert.assertEquals(0, scheduling.getNumberOfCoreProjectsInProgress());

    EventWrapper dummyWrapper = createAccountIndexEventWrapper("SkipMe1");

    // Note we use random UUIDs, so we need to ensure correct ordering for below to work its not just a FIFO now,
    // its a prioritised queue to ensure we always keep the correct first event at HEAD of the queue.
    // So take 5 randomly named event files, and order correctly as if by timestamp
    File[] events = (File[]) Arrays.asList(createDummyEventFile(), createDummyEventFile(), createDummyEventFile(), createDummyEventFile()).toArray(new File[4]);
    Arrays.sort(events);

    // Now they are sorted, lets take them and assign correctly to our named items, so we can play arround with
    // ordering correctly
    final File eventInfoIndex0 = events[0];
    final File eventInfoIndex1 = events[1];

    // Lets dummy creation of replicated tasts - we could just attempt schedule - easy route.
    // It should also add to WIP!!!
    final ReplicatedEventTask scheduledTaskWIP = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper.getProjectName(), eventInfoIndex0);

    // check is in progress first!!!
    Assert.assertTrue(scheduling.hasProjectEventInProgress(dummyWrapper.getProjectName()));

    HashMap<String, ReplicatedEventTask> copyWIP = scheduling.getCopyEventsFilesInProgress();
    // we build a copy of the WIP as a collection for FILE based quick lookups - check this works as runtime expects it.
    Collection<File> dirtyCopyOfWIPFiles = ReplicatedIncomingEventWorker.buildDirtyWIPFiles(copyWIP);

    // ensure our copy can be found directly
    Assert.assertTrue(dirtyCopyOfWIPFiles.contains(eventInfoIndex0));
    Assert.assertTrue(scheduling.hasProjectEventInProgress(dummyWrapper.getProjectName()));

    // make sure it doesn't find something thats not there.
    Assert.assertFalse(dirtyCopyOfWIPFiles.contains(eventInfoIndex1));

    // Now we need to make sure deletion from the core WIP doesn't actually delete it from our copy.
    scheduling.clearEventsFileInProgress(scheduledTaskWIP, false);
    // it should be gone locally in the wip real hashmap, but not the copy.
    Assert.assertFalse(scheduling.hasProjectEventInProgress(dummyWrapper.getProjectName()));
    Assert.assertTrue(dirtyCopyOfWIPFiles.contains(eventInfoIndex0));
  }


  @Test
  public void testSkippedEventFilesCannotHoldDuplicates() throws IOException {
    // this would start failing earlier if someone changes the coreProjects, so just checking otherwise change this test!!
    Assert.assertNotNull(scheduling);
    Assert.assertEquals(2, dummyTestCoordinator.getReplicatedConfiguration().getCoreProjects().size());
    Assert.assertEquals(4, dummyTestCoordinator.getReplicatedConfiguration().getMaxNumberOfEventWorkerThreads());
    Assert.assertEquals(0, scheduling.getNumEventFilesInProgress());
    Assert.assertEquals(0, scheduling.getNumberOfCoreProjectsInProgress());

    EventWrapper dummyWrapper = createAccountIndexEventWrapper("SkipMe1");

    // Note we use random UUIDs, so we need to ensure correct ordering for below to work its not just a FIFO now,
    // its a prioritised queue to ensure we always keep the correct first event at HEAD of the queue.
    // So take 5 randomly named event files, and order correctly as if by timestamp
    File[] events = Arrays.asList(createDummyEventFile(), createDummyEventFile(), createDummyEventFile(), createDummyEventFile()).toArray(new File[4]);
    Arrays.sort(events);

    // Now they are sorted, lets take them and assign correctly to our named items, so we can play arround with
    // ordering correctly
    final File eventInfoIndex0 = events[0];
    final File eventInfoIndex1 = events[1];

    // Lets dummy creation of replicated tests - we could just attempt schedule - easy route.
    // It should also add to WIP!!!
    scheduling.tryScheduleReplicatedEventsTask(dummyWrapper.getProjectName(), eventInfoIndex0);

    // check is in progress first!!!
    Assert.assertTrue(scheduling.hasProjectEventInProgress(dummyWrapper.getProjectName()));

    HashMap<String, ReplicatedEventTask> copyWIP = scheduling.getCopyEventsFilesInProgress();
    // we build a copy of the WIP as a collection for FILE based quick lookups - check this works as runtime expects it.
    Collection<File> dirtyCopyOfWIPFiles = ReplicatedIncomingEventWorker.buildDirtyWIPFiles(copyWIP);

    // ensure our copy can be found directly
    Assert.assertTrue(dirtyCopyOfWIPFiles.contains(eventInfoIndex0));
    Assert.assertTrue(scheduling.hasProjectEventInProgress(dummyWrapper.getProjectName()));
    Assert.assertFalse(scheduling.containsSkippedEventFilesForProject(dummyWrapper.getProjectName()));

    // Now if we try to schedule the same file again it should go into the skipped list.

    final ReplicatedEventTask nothingTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper.getProjectName(), eventInfoIndex0);

    // make sure it doesn't find something thats not there.
    Assert.assertNull(nothingTask);

    // now we should have the index0 not in the skipped list as it is already in progress!
    Assert.assertFalse(scheduling.containsSkippedEventFilesForProject(dummyWrapper.getProjectName()));
    // lets try prepend and append and check we can't duplicate entries
    scheduling.addSkippedProjectEventFile(eventInfoIndex1, dummyWrapper.getProjectName());
    Assert.assertEquals(1, scheduling.getSkippedEventFilesQueueForProject(dummyWrapper.getProjectName()).size());
    // this shouldn't add twice.
    scheduling.addSkippedProjectEventFile(eventInfoIndex1, dummyWrapper.getProjectName());
    Assert.assertEquals(1, scheduling.getSkippedEventFilesQueueForProject(dummyWrapper.getProjectName()).size());

    // ok lets prepend the earlier file I know its in progress but I am bypassing WIP check to test skipped handling!!
    scheduling.prependSkippedProjectEventFile(eventInfoIndex0, dummyWrapper.getProjectName());
    Assert.assertEquals(2, scheduling.getSkippedEventFilesQueueForProject(dummyWrapper.getProjectName()).size());
    assertFileNamesMatch(eventInfoIndex0, scheduling.getSkippedEventFilesQueueForProject(dummyWrapper.getProjectName()).getFirst());
    assertFileNamesMatch(eventInfoIndex1, scheduling.getSkippedEventFilesQueueForProject(dummyWrapper.getProjectName()).getLast());
  }

  @Test
  public void testSchedulingOfSkippedEventsMaintainsOrderingOfEvents() throws IOException {
    // this would start failing earlier if someone changes the coreProjects, so just checking otherwise change this test!!
    Assert.assertNotNull(scheduling);
    Assert.assertEquals(2, dummyTestCoordinator.getReplicatedConfiguration().getCoreProjects().size());
    Assert.assertEquals(4, dummyTestCoordinator.getReplicatedConfiguration().getMaxNumberOfEventWorkerThreads());
    Assert.assertEquals(0, scheduling.getNumEventFilesInProgress());
    Assert.assertEquals(0, scheduling.getNumberOfCoreProjectsInProgress());

    EventWrapper dummyWrapper = createAccountIndexEventWrapper("SkipMe1");

    // Note we use random UUIDs, so we need to ensure correct ordering for below to work its not just a FIFO now,
    // its a prioritised queue to ensure we always keep the correct first event at HEAD of the queue.
    // So take 5 randomly named event files, and order correctly as if by timestamp
    File[] events = (File[]) Arrays.asList(createDummyEventFile(), createDummyEventFile(), createDummyEventFile(), createDummyEventFile(), createDummyEventFile()).toArray(new File[5]);
    Arrays.sort(events);

    // Now they are sorted, lets take them and assign correctly to our named items, so we can play arround with
    // ordering correctly
    final File eventInfoIndex0 = events[0];
    final File eventInfoIndex1 = events[1];
    final File eventInfoIndex2 = events[2];
    final File eventInfoIndex3 = events[3];
    final File eventInfoIndex4 = events[4];

    scheduling.addSkippedProjectEventFile(eventInfoIndex0, dummyWrapper.getProjectName());
    scheduling.addSkipThisProjectsEventsForNow(dummyWrapper.getProjectName());

    Assert.assertTrue(scheduling.containsSkippedEventFilesForProject(dummyWrapper.getProjectName()));
    Deque<File> skippedInfo1 = scheduling.getSkippedEventFilesQueueForProject(dummyWrapper.getProjectName());
    Assert.assertEquals(1, skippedInfo1.size());
    assertFileNamesMatch(eventInfoIndex0, skippedInfo1.getFirst());
    Assert.assertTrue(scheduling.containsSkipThisProjectForNow(dummyWrapper.getProjectName()));

    // add 2 more skipped events to skipme1
    scheduling.addSkippedProjectEventFile(eventInfoIndex2, dummyWrapper.getProjectName());
    scheduling.addSkippedProjectEventFile(eventInfoIndex4, dummyWrapper.getProjectName());

    // Lets make another event file and skip it also for a different project to ensure they dont
    // overlap
    EventWrapper dummyWrapper2 = createAccountIndexEventWrapper("SkipMe2");

    // add 2 to skipme2
    scheduling.addSkippedProjectEventFile(eventInfoIndex1, dummyWrapper2.getProjectName());
    scheduling.addSkippedProjectEventFile(eventInfoIndex3, dummyWrapper2.getProjectName());


    scheduling.addSkipThisProjectsEventsForNow(dummyWrapper2.getProjectName());
    Deque<File> skippedInfo2 = scheduling.getSkippedEventFilesQueueForProject(dummyWrapper2.getProjectName());


    Assert.assertTrue(scheduling.containsSkipThisProjectForNow(dummyWrapper.getProjectName()));
    skippedInfo1 = scheduling.getSkippedEventFilesQueueForProject(dummyWrapper.getProjectName());

    Assert.assertEquals(3, skippedInfo1.size());
    Assert.assertEquals(eventInfoIndex0, skippedInfo1.toArray()[0]);
    Assert.assertEquals(eventInfoIndex2, skippedInfo1.toArray()[1]);
    Assert.assertEquals(eventInfoIndex4, skippedInfo1.toArray()[2]);

    Assert.assertEquals(2, skippedInfo2.size());
    Assert.assertEquals(eventInfoIndex1, skippedInfo2.toArray()[0]);
    Assert.assertEquals(eventInfoIndex3, skippedInfo2.toArray()[1]);

  }


  @Test
  public void testSchedulingOfProjectWhenFull() throws IOException {
    // this would start failing earlier if someone changes the coreProjects, so just checking otherwise change this test!!
    Assert.assertNotNull(scheduling);
    Assert.assertEquals(2, dummyTestCoordinator.getReplicatedConfiguration().getCoreProjects().size());
    Assert.assertEquals(4, dummyTestCoordinator.getReplicatedConfiguration().getMinNumberOfEventWorkerThreads());
    Assert.assertEquals(4, dummyTestCoordinator.getReplicatedConfiguration().getMaxNumberOfEventWorkerThreads());
    Assert.assertEquals(2, dummyTestCoordinator.getReplicatedConfiguration().getNumberOfNonCoreWorkerThreads());
    Assert.assertEquals(0, scheduling.getNumEventFilesInProgress());
    Assert.assertEquals(0, scheduling.getNumberOfCoreProjectsInProgress());

    {
      EventWrapper dummyWrapper = createAccountIndexEventWrapper("All-Users");
      ReplicatedEventTask replicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper.getProjectName(), createDummyEventFile());
      Assert.assertNotNull(replicatedEventTask);
      Assert.assertEquals(dummyWrapper.getProjectName(), replicatedEventTask.getProjectname());
      Assert.assertEquals(1, scheduling.getNumberOfCoreProjectsInProgress());
    }

    {
      EventWrapper dummyWrapper = createAccountIndexEventWrapper("All-Projects");
      ReplicatedEventTask replicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper.getProjectName(), createDummyEventFile());
      Assert.assertNotNull(replicatedEventTask);
      Assert.assertEquals(dummyWrapper.getProjectName(), replicatedEventTask.getProjectname());
      Assert.assertEquals(2, scheduling.getNumberOfCoreProjectsInProgress());
    }


    {
      EventWrapper dummyWrapper = createAccountIndexEventWrapper("ProjectA");
      ReplicatedEventTask replicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper.getProjectName(), createDummyEventFile());
      Assert.assertNotNull(replicatedEventTask);
      Assert.assertEquals(dummyWrapper.getProjectName(), replicatedEventTask.getProjectname());
    }


    {
      EventWrapper dummyWrapper = createAccountIndexEventWrapper("ProjectB");
      ReplicatedEventTask replicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper.getProjectName(), createDummyEventFile());
      Assert.assertNotNull(replicatedEventTask);
      Assert.assertEquals(dummyWrapper.getProjectName(), replicatedEventTask.getProjectname());
    }

    {
      EventWrapper dummyWrapper = createAccountIndexEventWrapper("ThisShouldFail!!");
      ReplicatedEventTask replicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper.getProjectName(), createDummyEventFile());
      Assert.assertNull(replicatedEventTask);
    }

  }

  @Test
  public void testSchedulingOfProjectWhenFullButReservedThreadsAvailable() throws IOException {
    Assert.assertNotNull(scheduling);
    Assert.assertEquals(2, dummyTestCoordinator.getReplicatedConfiguration().getCoreProjects().size());
    Assert.assertEquals(4, dummyTestCoordinator.getReplicatedConfiguration().getMaxNumberOfEventWorkerThreads());

    ReplicatedEventTask replicatedEventTask;
    {
      EventWrapper dummyWrapper = createAccountIndexEventWrapper("All-Users");
      replicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper.getProjectName(), createDummyEventFile());
      Assert.assertEquals(1, scheduling.getNumberOfCoreProjectsInProgress());
      Assert.assertEquals(1, scheduling.getNumEventFilesInProgress());
      Assert.assertNotNull(replicatedEventTask);
      Assert.assertEquals(dummyWrapper.getProjectName(), replicatedEventTask.getProjectname());
    }

    {
      EventWrapper dummyWrapper = createAccountIndexEventWrapper("ProjectX");
      replicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper.getProjectName(), createDummyEventFile());
      Assert.assertNotNull(replicatedEventTask);
      Assert.assertEquals(1, scheduling.getNumberOfCoreProjectsInProgress());
      Assert.assertEquals(2, scheduling.getNumEventFilesInProgress());
      Assert.assertEquals(dummyWrapper.getProjectName(), replicatedEventTask.getProjectname());
    }

    {
      EventWrapper dummyWrapper = createAccountIndexEventWrapper("ProjectY");
      replicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper.getProjectName(), createDummyEventFile());
      Assert.assertNotNull(replicatedEventTask);
      Assert.assertEquals(1, scheduling.getNumberOfCoreProjectsInProgress());
      Assert.assertEquals(3, scheduling.getNumEventFilesInProgress());
      Assert.assertEquals(dummyWrapper.getProjectName(), replicatedEventTask.getProjectname());
    }

    // lastly when full - so no more general threads - it should return null event though
    // there is another thread for all projects - its reserved so can't be used.
    {
      EventWrapper dummyWrapper = createAccountIndexEventWrapper("ProjectZ");
      replicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper.getProjectName(), createDummyEventFile());
      Assert.assertNull(replicatedEventTask);
    }
  }

  @Test
  public void testSimpleBuildMissingIdsList() {

    Collection<Integer> deletedIdsList = Arrays.asList(1, 3); // make sure we skip say no2 so make sure there is no ordering assumptions.
    Collection<Integer> requestedIdsList = Arrays.asList(1, 2, 3, 5, 6); // using a set so we have each id only once.

    // build the list of missing items or items yet to be processed
    Collection<Integer> listOfMissingIds = buildListOfMissingIds(requestedIdsList, deletedIdsList);

    for (int thisId : requestedIdsList) {
      Assert.assertTrue(deletedIdsList.contains(thisId) ? !listOfMissingIds.contains(thisId) : listOfMissingIds.contains(thisId));
    }
  }

  @Test
  public void testSchedulingOfProjectWhenSkippedShouldNotSchedule() throws IOException {

    EventWrapper dummyWrapper = createAccountIndexEventWrapper("All-Users");
    File dummyEventFile = createDummyEventFile();

    ReplicatedEventTask replicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper.getProjectName(), dummyEventFile);
    Assert.assertNotNull(replicatedEventTask);
    Assert.assertEquals(dummyWrapper.getProjectName(), replicatedEventTask.getProjectname());

    // Mark to skip project A for all future work - make sure it cant be scheduled.

    dummyWrapper = createAccountIndexEventWrapper("ProjectA");
    scheduling.addSkipThisProjectsEventsForNow(dummyWrapper.getProjectName());

    Assert.assertEquals("ProjectA", dummyWrapper.getProjectName());
    Assert.assertTrue(scheduling.containsSkipThisProjectForNow(dummyWrapper.getProjectName()));
    Assert.assertTrue("Failed should skip found this backoffInfo: " + scheduling.getSkipThisProjectForNowBackoffInfo(dummyWrapper.getProjectName()),
        scheduling.shouldStillSkipThisProjectForNow(dummyWrapper.getProjectName()));

    ReplicatedEventTask nonScheduledTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper.getProjectName(), createDummyEventFile());
    // shouldn't of scheduled - check null.
    Assert.assertNull(nonScheduledTask);
  }

  private File createDummyEventFile() throws IOException {
    // name these events correctly, maybe even stick some content in them later??
    return createDummyEventFile("dummyEventFile");
  }

  private File createDummyEventFile(final String someNamePrefix) throws IOException {
    // name these events correctly, maybe even stick some content in them later??
    return File.createTempFile(someNamePrefix, null, dummyTestCoordinator.getReplicatedConfiguration().getIncomingReplEventsDirectory());
  }

  @Test
  public void testSchedulingOfProjectWhenWorkSkippedAlreadySwapsOutNextEventToProcess() throws IOException {

    // numbering the file for ordering correctness.
    File eventFile0 = createDummyEventFile("eventtest-0");
    File eventFile1 = createDummyEventFile("eventtest-1");
    File eventFile2 = createDummyEventFile("eventtest-2");

    // Note we use random UUIDs, so we need to ensure correct ordering for below to work its not just a FIFO now,
    // its a prioritised queue to ensure we always keep the correct first event at HEAD of the queue.
    // So take x randomly named event files, and order correctly as if by timestamp  I am putting them in the list
    // in incorrect order, just to make sure the sort is correctly sorting to 0, 1, 2.
    File[] events = (File[]) Arrays.asList( eventFile1, eventFile0, eventFile2).toArray(new File[3]);
    Arrays.sort(events);

    // check sort is correct
    assertFileNamesMatch(eventFile0, events[0]);
    assertFileNamesMatch(eventFile1, events[1]);
    assertFileNamesMatch(eventFile2, events[2]);

    // Check that the files being ordered have appeared correctly as file0, then file1.
    // Note later we will ask for file 2, and it should swap out for file0
    final String projectName = "ProjectA";

    // Now add the first 2 event files to the skipped list, so when we try to schedule file2 it will
    // be able to schedule but should swap out for an already skipped over event with a higher ordering.
    scheduling.addSkippedProjectEventFile(eventFile0, projectName);
    scheduling.addSkippedProjectEventFile(eventFile1, projectName);

    // This should not be scheduled - it should go on end of list!
    EventWrapper dummyWrapper = createAccountIndexEventWrapper(projectName);
    final ReplicatedEventTask scheduledReplicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper.getProjectName(), eventFile2);
    Assert.assertNotNull(scheduledReplicatedEventTask);

    // now make sure that we didn't just schedule the next event file but instead we swapped it out
    // for the first skipped one as its at the HEAD of the FIFO.
    Assert.assertEquals(eventFile0.getName(), scheduledReplicatedEventTask.getEventsFileToProcess().getName());

    // get the list and check that we have 2 items in the list, which should be file 1, and file 2.  (file0 got swapped out ).
    Deque<File> skippedList = scheduling.getSkippedEventFilesQueueForProject(dummyWrapper.getProjectName());
    Assert.assertEquals(2, skippedList.size());
    Assert.assertEquals(eventFile1, skippedList.toArray()[0]);
    Assert.assertEquals(eventFile2, skippedList.toArray()[1]);
  }

  @Test
  public void testAddSkippedProjectEventFileMaintainsOrder() throws IOException {
    // check ordering of files in the event directory - make names and randomly stick them into the list
    // then check they are the order we added them.
    List<File> simpleList = new ArrayList<>();
    final String projectName = "TestMe";

    // keep this file outside of the add list, so we can add later but it should bubble to the top of the priority
    // queue as its ordering is earlier.
    File firstEventsFile = createDummyEventFile("testDummyOrdering-0" + 0);

    for (int index = 0; index < 10; index++) {
      File newFile = createDummyEventFile("testDummyOrdering-1" + index);
      simpleList.add(newFile);
      // make sure the list isn't reordering as we add.
      assertFileNamesMatch(newFile, simpleList.get(index));
      scheduling.addSkippedProjectEventFile(newFile, projectName);
    }

    // Now lets see that our add to skipped list maintained the same order as out local array.
    Deque<File> skippedEvents = scheduling.getSkippedEventFilesQueueForProject(projectName);
    Assert.assertArrayEquals(simpleList.toArray(), skippedEvents.toArray());

    // try plucking off first item must match
    assertFileNamesMatch(simpleList.iterator().next(), skippedEvents.getFirst());

    // try iterator - again must match .
    int index = 0;
    for (File skippedFile : skippedEvents) {
      File sourceFile = simpleList.get(index);
      assertFileNamesMatch(sourceFile, skippedFile);
      index++;
    }

    // Now lets PREPEND the first one we created, and check we have one more but its not at the end, its at the start!
    scheduling.prependSkippedProjectEventFile(firstEventsFile, projectName);

    File nextSkippedToBeProcessed = scheduling.getFirstSkippedEventFileForProject(projectName);
    assertFileNamesMatch(firstEventsFile, nextSkippedToBeProcessed);

    // make sure this is not POP off the queue, its only a PEEK.
    File sameSkippedFile = scheduling.getFirstSkippedEventFileForProject(projectName);
    assertFileNamesMatch(firstEventsFile, sameSkippedFile);
  }


  @Test
  public void testPrependSkippedProjectEventFileMaintainsOrder() throws IOException {
    // check ordering of files in the event directory - make names and randomly stick them into the list
    // then check they are the order we added them.
    List<File> simpleList = new ArrayList<>();
    final String projectName = "TestMe";

    // keep this file outside of the add list, so we can add later but it should bubble to the top of the priority
    // queue as its ordering is earlier.
    File firstEventsFile = createDummyEventFile("testDummyOrdering-0" + 0);

    for (int index = 0; index < 10; index++) {
      File newFile = createDummyEventFile("testDummyOrdering-1" + index);
      simpleList.add(newFile);
      // make sure the list isn't reordering as we add.
      assertFileNamesMatch(newFile, simpleList.get(index));
      scheduling.addSkippedProjectEventFile(newFile, projectName);
    }

    // Now lets see that our add to skipped list maintained the same order as out local array.
    Deque<File> skippedEvents = scheduling.getSkippedEventFilesQueueForProject(projectName);
    Assert.assertArrayEquals(simpleList.toArray(), skippedEvents.toArray());

    // try plucking off first item must match
    assertFileNamesMatch(simpleList.iterator().next(), skippedEvents.getFirst());

    // try iterator - again must match .
    int index = 0;
    for (File skippedFile : skippedEvents) {
      File sourceFile = simpleList.get(index);
      assertFileNamesMatch(sourceFile, skippedFile);
      index++;
    }

    // Now lets add the first one we created, and check we have one more but its not at the end, its at the start!
    // I EXpect this to fail now - MUST be prepend.
    scheduling.prependSkippedProjectEventFile(firstEventsFile, projectName);

    File nextSkippedToBeProcessed = scheduling.getFirstSkippedEventFileForProject(projectName);
    assertFileNamesMatch(firstEventsFile, nextSkippedToBeProcessed);

  }

  @Test
  public void testAddProjectWIPEventShallowCopyIsDirtyCopy() throws IOException {

    EventWrapper dummyWrapper = createAccountIndexEventWrapper("All-Users");
    ReplicatedEventTask replicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper.getProjectName(), createDummyEventFile());
    Assert.assertNotNull(replicatedEventTask);
    Assert.assertEquals(dummyWrapper.getProjectName(), replicatedEventTask.getProjectname());
    Assert.assertEquals(1, scheduling.getNumberOfCoreProjectsInProgress());

    scheduling.addForTestingInProgress(replicatedEventTask);


    dummyWrapper = createAccountIndexEventWrapper("All-Projects");
    ReplicatedEventTask replicatedEventTaskAllProjects = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper.getProjectName(), createDummyEventFile());
    Assert.assertNotNull(replicatedEventTaskAllProjects);
    Assert.assertEquals(dummyWrapper.getProjectName(), replicatedEventTaskAllProjects.getProjectname());
    Assert.assertEquals(2, scheduling.getNumberOfCoreProjectsInProgress());

    scheduling.addForTestingInProgress(replicatedEventTaskAllProjects);

    HashMap<String, ReplicatedEventTask> dirtyWIP = scheduling.getCopyEventsFilesInProgress();

    // iteration -> file a ( scheduled file a )  dirtyWIP = file.
    // iteration 2 ->
    // file a, ( dirty WIP )
    // file b
    //  ( the remote worker gets lock -> deletes file on disk, and removes from WIP ).
    // dirty wip contains Filea after remove of real WIP (file a).
    Assert.assertEquals(2, dirtyWIP.size());

    // remove Filea - like remote worker has finished.
    scheduling.clearEventsFileInProgress(replicatedEventTask, false);
    Assert.assertEquals(2, dirtyWIP.size());
    Assert.assertEquals(1, scheduling.getNumEventFilesInProgress());
  }

  @Test
  public void testLastModifiedEventTimeResolution() throws IOException {

    // Do a tight loop of checking - to try to make sure, as it's hard to catch sometimes.
    File incomingDirectory = dummyTestCoordinator.getReplicatedConfiguration().getIncomingReplEventsDirectory();

    FS.FileStoreAttributes fileStoreAttributes = FS.FileStoreAttributes.get(incomingDirectory.toPath());

    Duration dirFSResolution = fileStoreAttributes.getFsTimestampResolution();
    Duration dirFSRacyInterval = fileStoreAttributes.getMinimalRacyInterval();

    Duration incomingDirResolution = dirFSResolution.plus(dirFSRacyInterval);

    // sorry but sometimes the resolution tester gets really low values like 6ms etc, and very occasionally the update period
    // is a bit over that.  so we double the allowance worker out - and just make sure the directory update happens within this slot.
    Duration directoryResolutionAllowance = incomingDirResolution.multipliedBy(2);

    System.out.printf("Incoming Directory has file system resolution of %sms, raciness %sms so sleep period is %sms.%n",
      toMillisDecimal(dirFSResolution), toMillisDecimal(dirFSRacyInterval), toMillisDecimal(incomingDirResolution));
    System.out.printf("Calculated timestamp difference allowance is %sms%n", toMillisDecimal(directoryResolutionAllowance));

    long raceWithinThresholdCount = 0;
    long raceThresholdExceededCount = 0;
    final long raceThresholdExceededLimit = 1;

    // e.g. on my ubuntu 20 machine, over 100000 iterations it needs raciness allowance ~ 60times
    for (int index = 0; index < 100000; index++) {
      File eventFile = createDummyEventFile("event-resolution-testing-" + index);
      final FileTime fileLastModified = Files.getLastModifiedTime(eventFile.toPath());
      final FileTime directoryLastModified = Files.getLastModifiedTime(incomingDirectory.toPath());

      if (fileLastModified.equals(directoryLastModified)) {
        // Pass, no race
        continue;
      }

      Duration timeDiff = Duration.between(fileLastModified.toInstant(), directoryLastModified.toInstant()).abs();
      if (timeDiff.toNanos() <= directoryResolutionAllowance.toNanos()) {
        // racy allowance worked
        raceWithinThresholdCount++;
      } else {
        // Difference between event file and directory last modified is > than the allowance.
        raceThresholdExceededCount++;

        System.out.printf("Event file lastModified (%s) and directory lastModified (%s) timestamp difference (%sms) exceeded the threshold of %sms%n",
                fileLastModified.toMillis(), directoryLastModified.toMillis(), toMillisDecimal(timeDiff), toMillisDecimal(directoryResolutionAllowance));
        System.out.printf("On iteration %s: Counted %s races within threshold; counted %s / %s races exceeded threshold%n",
                index, raceWithinThresholdCount, raceThresholdExceededCount, raceThresholdExceededLimit);

        // If it happens too often, fail the test.
        Assert.assertFalse(
                String.format("Event file and directory last modified difference exceeded the raciness threshold (%sms) too many times! (%s / %s)%n",
                        toMillisDecimal(directoryResolutionAllowance), raceThresholdExceededCount, raceThresholdExceededLimit),
                raceThresholdExceededCount > raceThresholdExceededLimit
        );
      }
    }

    // On success, just print the stats of how many times the racy allowance helped.
    System.out.println("Racy allowance accounted for " + raceWithinThresholdCount + " attempts.");
    System.out.println("Racy allowance was exceeded " + raceThresholdExceededCount + " times. (Limit=" + raceThresholdExceededLimit + ")");
  }

  /** Get a duration in ms without truncation. */
  private double toMillisDecimal(Duration duration) {
    return duration.toNanos() / 1000000.0;
  }


  @Test
  public void testGetOldestNonCoreProjectEvent() throws IOException {
    // check ordering of files in the event directory - make names and randomly stick them into the list
    // then check they are the order we added them.
    List<File> orderedEventsList1 = new ArrayList<>();
    List<File> orderedEventsList2 = new ArrayList<>();

    final String projectNameA = "TestMe-ProjectA";
    final String projectNameB = "TestMe-ProjectB";
    final String projectNameC = "TestMe-ProjectC";

    final int orderedMaxEvents = 10;

    // create 2 lists of event files.  By chronological order, which easily enough
    // is alphanumeric ordering also!
    for (int index = 0; index < orderedMaxEvents; index++) {
      File newFile = createDummyEventFile("testDummyOrdering-0" + index);
      orderedEventsList1.add(newFile);
      // make sure the list isn't reordering as we add.
      assertFileNamesMatch(newFile, orderedEventsList1.get(index));
    }

    for (int index = 0; index < orderedMaxEvents; index++) {
      File newFile = createDummyEventFile("testDummyOrdering-1" + index);
      orderedEventsList2.add(newFile);
      // make sure the list isn't reordering as we add.
      assertFileNamesMatch(newFile, orderedEventsList2.get(index));
    }

    // Now lets add the first 5 events from project list 2.
    // this allow us to have any of the events from list 1, as older, and still gives us
    // 5 more from list 2.
    final int copyElementsCount = 5;
    for (int index = 0; index < copyElementsCount; index++) {
      scheduling.addSkippedProjectEventFile(orderedEventsList2.get(index), projectNameA);
    }

    Deque<File> skippedEvents = scheduling.getSkippedEventFilesQueueForProject(projectNameA);
    // check has exactly 5 members.
    Assert.assertEquals(copyElementsCount, skippedEvents.size());

    // check the oldest member here, in different ways.
    File expectedEvent = orderedEventsList2.get(0);

    // 1) Queue must have it at the head -> first element in FIFO ( peek its value ).
    assertFileNamesMatch(expectedEvent, skippedEvents.getFirst());
    // 2) The getFirstskipped must return it.
    assertFileNamesMatch(expectedEvent, scheduling.getFirstSkippedEventFileForProject(projectNameA));
    // 3) Get oldest non core project must return it.
    assertFileNamesMatch(expectedEvent, scheduling.getOldestNonCoreProjectEvent().getEventsFileToProcess());
    Assert.assertEquals(projectNameA, scheduling.getOldestNonCoreProjectEvent().getProjectname());

    // add the next 5 to project B, this should change nothing about oldest entry yet..
    for (int index = copyElementsCount; index < orderedMaxEvents; index++) {
      scheduling.addSkippedProjectEventFile(orderedEventsList2.get(index), projectNameB);
    }

    // oldest non core project must still return same oldest event.
    assertFileNamesMatch(expectedEvent, scheduling.getOldestNonCoreProjectEvent().getEventsFileToProcess());
    Assert.assertEquals(projectNameA, scheduling.getOldestNonCoreProjectEvent().getProjectname());

    // now add on the last element from the older list orderedEventsList2.
    // add to project B, to get oldest project to swap.
    expectedEvent = orderedEventsList1.get(0);

    // add all of the older list1 to project C, even though these are added later they are older
    // for this project, and should still return as oldest non core.
    for (int index = 0; index < orderedMaxEvents; index++) {
      scheduling.addSkippedProjectEventFile(orderedEventsList1.get(index), projectNameC);
    }
    assertFileNamesMatch(expectedEvent, scheduling.getOldestNonCoreProjectEvent().getEventsFileToProcess());
    Assert.assertEquals(projectNameC, scheduling.getOldestNonCoreProjectEvent().getProjectname());
  }


  @Test
  public void testGetOldestNonCoreProjectEventIgnoresInProgress() throws IOException {
    // check ordering of files in the event directory - make names and randomly stick them into the list
    // then check they are the order we added them.
    List<File> orderedEventsList = new ArrayList<>();

    final String projectNameA = "TestMe-ProjectA";
    final String projectNameB = "TestMe-ProjectB";

    final int orderedMaxEvents = 10;

    for (int index = 0; index < orderedMaxEvents; index++) {
      File newFile = createDummyEventFile("testDummyOrdering-1" + index);
      orderedEventsList.add(newFile);
      // make sure the list isn't reordering as we add.
      assertFileNamesMatch(newFile, orderedEventsList.get(index));
    }

    // Now lets add the first 5 events from project list 2.
    // this allow us to have any of the events from list 1, as older, and still gives us
    // 5 more from list 2.
    final int copyElementsCount = 5;
    for (int index = 0; index < copyElementsCount; index++) {
      scheduling.addSkippedProjectEventFile(orderedEventsList.get(index), projectNameA);
    }

    // add the next 5 to project B, this should change nothing about oldest entry yet..
    for (int index = copyElementsCount; index < orderedMaxEvents; index++) {
      scheduling.addSkippedProjectEventFile(orderedEventsList.get(index), projectNameB);
    }

    // check the oldest member here - should be projectA.
    File expectedEvent = orderedEventsList.get(0);

    assertFileNamesMatch(expectedEvent, scheduling.getOldestNonCoreProjectEvent().getEventsFileToProcess());
    Assert.assertEquals(projectNameA, scheduling.getOldestNonCoreProjectEvent().getProjectname());

    Assert.assertEquals(0, scheduling.getNumEventFilesInProgress());

    // Now if we make projectA in progress, it should skip over projectA, and take the
    // projectB file instead.
    scheduling.tryScheduleReplicatedEventsTask(projectNameA, scheduling.getFirstSkippedEventFileForProject(projectNameA));

    // check we now have one in progress.
    Assert.assertEquals(1, scheduling.getNumEventFilesInProgress());

    // in progress must have changed the oldest non-core result
    // get the first element of the second half of the batch.
    final File newOldestEvent = orderedEventsList.get(copyElementsCount);
    Assert.assertEquals(projectNameB, scheduling.getOldestNonCoreProjectEvent().getProjectname());
    assertFileNamesMatch(newOldestEvent, scheduling.getOldestNonCoreProjectEvent().getEventsFileToProcess());
  }


  @Test
  public void testGetCoreProjectNextEventItem() throws IOException {
    // check ordering of files in the event directory - make names and randomly stick them into the list
    // then check they are the order we added them.
    List<File> orderedEventsList2 = new ArrayList<>();


    final int orderedMaxEvents = 10;

    for (int index = 0; index < orderedMaxEvents; index++) {
      File newFile = createDummyEventFile("events_1" + index);
      orderedEventsList2.add(newFile);
      // make sure the list isn't reordering as we add.
      assertFileNamesMatch(newFile, orderedEventsList2.get(index));
    }
    // just create vars for handyness sake.
    final String allProjectsName = dummyTestCoordinator.getReplicatedConfiguration().getAllProjectsName();
    final String allUsersName = dummyTestCoordinator.getReplicatedConfiguration().getAllUsersName();

    // Now lets add the first 5 events to all-projects.
    // this allow us to have any of the events from list 1, as older, and still gives us
    // 5 more from list 2.
    final int copyElementsCount = 5;
    for (int index = 0; index < copyElementsCount; index++) {
      scheduling.addSkippedProjectEventFile(orderedEventsList2.get(index),
          allProjectsName);
    }

    // add the next 5 to all users, this should change nothing about all-projects oldest
    // or non core entries yet.
    for (int index = copyElementsCount; index < orderedMaxEvents; index++) {
      scheduling.addSkippedProjectEventFile(orderedEventsList2.get(index), allUsersName);
    }

    Assert.assertNull("Should be no non core project events",
        scheduling.getOldestNonCoreProjectEvent());

    // get first member -> its the oldest ( confirmed by other tests in this suite ).
    File currentOldestBeingChecked = scheduling.getFirstSkippedEventFileForProject(allProjectsName);
    assertFileNamesMatch(orderedEventsList2.get(0), currentOldestBeingChecked);

    currentOldestBeingChecked = scheduling.getFirstSkippedEventFileForProject(allUsersName);
    assertFileNamesMatch(orderedEventsList2.get(5), currentOldestBeingChecked);

    // schedule the oldest all-projects to its in progress, and see if the pick off next works
    ReplicatedEventTask rtScheduled =
        scheduling.tryScheduleReplicatedEventsTask(allProjectsName, orderedEventsList2.get(0));

    // check it scheduled this oldest item.
    assertFileNamesMatch(orderedEventsList2.get(0), rtScheduled.getEventsFileToProcess());
    Assert.assertEquals(allProjectsName, rtScheduled.getProjectname());

    scheduling.tryScheduleExistingSkippedCoreReplicatedEventTask(allProjectsName);

  }


  @Test
  public void testDecodingOfEventfileNameRepoSha1Member() throws IOException, InvalidEventJsonException {

    // Create a new dummy event file - MUST have correct naming, to decode the repoSha1 to
    // a real project name.
    final String projectName = createUniqueString("MyProjectX");
    final File actualEventfile = createRealEventFileForProject(projectName);

    final String expectedSha1 = getProjectNameSha1(projectName);

    final String decodedSha1 =
        dummyTestCoordinator.getReplicatedIncomingEventWorker().decodeProjectSha1IdFromEventFilename(actualEventfile.getName());

    Assert.assertEquals("Event file name must decode project sha1, correctly and match",
        expectedSha1, decodedSha1);

    final String obtainedProjectName =
        dummyTestCoordinator.getReplicatedIncomingEventWorker().getProjectNameFromEventFile(actualEventfile);

    Assert.assertEquals(projectName, obtainedProjectName);

    // This project name must be cached now - lets check .
    Assert.assertTrue("Mapping cache entry for repo sha must exist",
        dummyTestCoordinator.getReplicatedIncomingEventWorker().doesProjectShaToNameMappingExist(decodedSha1));
    Assert.assertTrue("Mapping cache entry for repo name must exist",
        dummyTestCoordinator.getReplicatedIncomingEventWorker().doesProjectNameMappingExist(projectName));

    // make sure its not returning gibberish - request any old name.
    Assert.assertFalse("Mapping cache entry must not exist for gibberish project",
        dummyTestCoordinator.getReplicatedIncomingEventWorker().doesProjectNameMappingExist("Gibberish-Project"));
  }


  @Test
  public void testDecodingInvalidEventFileNameFallbackToReadProjectname() throws IOException, InvalidEventJsonException {

    // Now lets put the actual event wrapper info into the dummy invalid event filename.
    File createdIncorrectNamedEvent = createDummyEventFile("events-invalid-naming-");

    // create valid event info in the file, as it needs to deserialize correctly.
    final String projectName = createUniqueString("SomeUniqueProject");
    EventWrapper dummyWrapper = createAccountIndexEventWrapper(projectName);

    // constructor used for incoming directly event files.
    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, createdIncorrectNamedEvent.getName(), projectName);

    persistedEventInformation.appendToFile(dummyWrapper, false);
    File realValidEventfile = persistedEventInformation.getFinalEventFile();

    persistedEventInformation.setFileReady();
    if ( !persistedEventInformation.atomicRenameTmpFilename() ){
      // failed to atomic rename from tmpEventsBatch to final filename.
      throw new IOException("Unable to atomic move from tmpEventsBatch to final file name: " + realValidEventfile.getAbsolutePath());
    }

    // Now the file is in the invalid dummy entry - try to decode it and make sure
    // 1) decode returns null.
    // 2) getProjectName still returns real projectname.
    final String decodedSha1 =
        dummyTestCoordinator.getReplicatedIncomingEventWorker().decodeProjectSha1IdFromEventFilename(createdIncorrectNamedEvent.getName());

    Assert.assertNull("Event file name must decode project sha1 to null", decodedSha1);

    final String obtainedProjectName =
        dummyTestCoordinator.getReplicatedIncomingEventWorker().getProjectNameFromEventFile(createdIncorrectNamedEvent);

    Assert.assertEquals(projectName, obtainedProjectName);

    // This project name must NOT be cached now as we never were able to get valid repoSha1
    Assert.assertFalse("Mapping cache entry must NOT exist",
        dummyTestCoordinator.getReplicatedIncomingEventWorker().doesProjectNameMappingExist(obtainedProjectName));
  }

  @Test
  public void testTryScheduleExistingSkippedNonCoreReplicatedEventTask() throws Exception {
      final String projectNameA = "TestMe-ProjectA";
      final String projectNameB = "TestMe-ProjectB";
      final String projectNameC = "TestMe-ProjectC";

      // Add 2 events, with project A having the oldest event.
      File projectAEvent1 = createDummyEventFile("testDummyOrdering-1");
      scheduling.addSkippedProjectEventFile(projectAEvent1, projectNameA);
      File projectBEvent1 = createDummyEventFile("testDummyOrdering-2");
      scheduling.addSkippedProjectEventFile(projectBEvent1, projectNameB);

      // check the oldest member here - should be projectA.
      Assert.assertEquals(projectAEvent1, scheduling.getOldestNonCoreProjectEvent().getEventsFileToProcess());
      Assert.assertEquals(projectNameA, scheduling.getOldestNonCoreProjectEvent().getProjectname());
      Assert.assertEquals(0, scheduling.getNumEventFilesInProgress());

      // Now try and schedule an existing skipped non core task
      // It should schedule the next oldest, which is from project B
      scheduling.tryScheduleExistingSkippedNonCoreReplicatedEventTask();

      // check we now have one in progress.
      Assert.assertEquals(1, scheduling.getNumEventFilesInProgress());
      Assert.assertEquals(true, scheduling.hasProjectEventInProgress(projectNameA));

      // The next oldest event should now be for project B
      Assert.assertEquals(projectBEvent1, scheduling.getOldestNonCoreProjectEvent().getEventsFileToProcess());
      Assert.assertEquals(projectNameB, scheduling.getOldestNonCoreProjectEvent().getProjectname());

      // Now try adding an even older event and see if it get's scheduled correctly.
      File projectCEvent1 = createDummyEventFile("testDummyOrdering-0");
      scheduling.addSkippedProjectEventFile(projectCEvent1, projectNameC);
      scheduling.tryScheduleExistingSkippedNonCoreReplicatedEventTask();

      // There should now be 2 events in progress.
      Assert.assertEquals(2, scheduling.getNumEventFilesInProgress());
      Assert.assertEquals(true, scheduling.hasProjectEventInProgress(projectNameA));
      Assert.assertEquals(true, scheduling.hasProjectEventInProgress(projectNameC));

      // Again the next oldest event should now be for project B
      Assert.assertEquals(projectBEvent1, scheduling.getOldestNonCoreProjectEvent().getEventsFileToProcess());
      Assert.assertEquals(projectNameB, scheduling.getOldestNonCoreProjectEvent().getProjectname());
  }

  @Test
  public void testTryScheduleExistingSkippedNonCoreReplicatedEventTaskNoHeadroom() throws Exception {
      // Reducing asserts in this test as they are duplicates from: testTryScheduleExistingSkippedNonCoreReplicatedEventTask
      final String projectNameA = "TestMe-ProjectA";
      final String projectNameB = "TestMe-ProjectB";
      final String projectNameC = "TestMe-ProjectC";

      // Create 3 events so we can try and schedule when no threads are available.
      File projectAEvent1 = createDummyEventFile("testDummyOrdering-1");
      scheduling.addSkippedProjectEventFile(projectAEvent1, projectNameA);
      File projectBEvent1 = createDummyEventFile("testDummyOrdering-2");
      scheduling.addSkippedProjectEventFile(projectBEvent1, projectNameB);
      File projectCEvent1 = createDummyEventFile("testDummyOrdering-3");
      scheduling.addSkippedProjectEventFile(projectCEvent1, projectNameC);

      // ProjectA should have the first event to be scheduled.
      Assert.assertEquals(projectNameA, scheduling.getOldestNonCoreProjectEvent().getProjectname());

      // Schedule the first event and confirm project B has the next event.
      Assert.assertNotNull(scheduling.tryScheduleExistingSkippedNonCoreReplicatedEventTask());
      Assert.assertEquals(projectNameB, scheduling.getOldestNonCoreProjectEvent().getProjectname());

      // Schedule the second event and confirm project C has the next event.
      Assert.assertNotNull(scheduling.tryScheduleExistingSkippedNonCoreReplicatedEventTask());
      Assert.assertEquals(projectNameC, scheduling.getOldestNonCoreProjectEvent().getProjectname());

      // Try to schedule a third event but as there are no worker threads left
      // it should return null and the next event should not change.
      Assert.assertNull(scheduling.tryScheduleExistingSkippedNonCoreReplicatedEventTask());
      Assert.assertEquals(projectNameC, scheduling.getOldestNonCoreProjectEvent().getProjectname());
  }

  @Test
  public void testTryScheduleExistingSkippedNonCoreReplicatedEventTaskNoSkippedEvents() throws Exception {
      // Try and schedule with no skipped events.
      Assert.assertNull(scheduling.tryScheduleExistingSkippedNonCoreReplicatedEventTask());
      Assert.assertEquals(null, scheduling.getOldestNonCoreProjectEvent());
      Assert.assertEquals(0, scheduling.getNumEventFilesInProgress());
  }

  @Test
  public void tryScheduleExistingSkippedCoreReplicatedEventTaskNotCoreProject() throws Exception {
      final String projectNameA = "TestMe-ProjectA";
      File projectAEvent1 = createDummyEventFile("testDummyOrdering-1");
      scheduling.addSkippedProjectEventFile(projectAEvent1, projectNameA);

      // Try and schedule but as it is not a core project, it won't be scheduled.
      Assert.assertNull(scheduling.tryScheduleExistingSkippedCoreReplicatedEventTask(projectNameA));

      // Now try and schedule as a non-core project
      Assert.assertNotNull(scheduling.tryScheduleExistingSkippedNonCoreReplicatedEventTask());
      Assert.assertEquals(1, scheduling.getNumEventFilesInProgress());
  }

  @Test
  public void tryScheduleExistingSkippedCoreReplicatedEventTaskProjectAlreadyHasEventInProgress() throws Exception {
      final String allUsers = "All-Users";
      File allUsersEvent1 = createDummyEventFile("testDummyOrdering-1");
      scheduling.addSkippedProjectEventFile(allUsersEvent1, allUsers);
      File allUsersEvent2 = createDummyEventFile("testDummyOrdering-2");
      scheduling.addSkippedProjectEventFile(allUsersEvent2, allUsers);

      // Schedule the first task
      ReplicatedEventTask replicatedEventTask = scheduling.tryScheduleExistingSkippedCoreReplicatedEventTask(allUsers);
      Assert.assertNotNull(replicatedEventTask);
      Assert.assertTrue(scheduling.hasProjectEventInProgress(allUsers));
      Assert.assertEquals(1, scheduling.getNumEventFilesInProgress());

      // Now try and schedule another task while one is in progress
      Assert.assertNull(scheduling.tryScheduleExistingSkippedCoreReplicatedEventTask(allUsers));
      Assert.assertNull(scheduling.tryScheduleReplicatedEventsTask(allUsers, allUsersEvent2));
      Assert.assertTrue(scheduling.hasProjectEventInProgress(allUsers));
      Assert.assertEquals(1, scheduling.getNumEventFilesInProgress());

      // Stop the event currently in progress.
      scheduling.clearEventsFileInProgress(replicatedEventTask, true);

      // Try and reschedule the task.
      replicatedEventTask = scheduling.tryScheduleExistingSkippedCoreReplicatedEventTask(allUsers);
      Assert.assertNotNull(replicatedEventTask);
      Assert.assertTrue(scheduling.hasProjectEventInProgress(allUsers));
      Assert.assertEquals(1, scheduling.getNumEventFilesInProgress());

      // Stop the event currently in progress.
      scheduling.clearEventsFileInProgress(replicatedEventTask, true);

      // Try and schedule an event but none are currently in the skip list.
      Assert.assertNull(scheduling.tryScheduleExistingSkippedCoreReplicatedEventTask(allUsers));
      Assert.assertFalse(scheduling.hasProjectEventInProgress(allUsers));
      Assert.assertEquals(0, scheduling.getNumEventFilesInProgress());
  }

  private void assertFileNamesMatch(final File expectedEvent, final File eventsFileToCompare) {
    Assert.assertEquals(expectedEvent.getName(), eventsFileToCompare.getName());
  }

  private File createRealEventFileForProject(final String projectName) throws IOException {

    EventWrapper dummyWrapper = createAccountIndexEventWrapper(projectName);
    // get a persister so we can get the real file name.
    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, dummyWrapper);

    persistedEventInformation.appendToFile(dummyWrapper, false);

    File realValidEventfile = persistedEventInformation.getFinalEventFile();

    persistedEventInformation.setFileReady();
    if ( !persistedEventInformation.atomicRenameTmpFilename() ){
      // failed to atomic rename from tmpEventsBatch to final filename.
      throw new IOException("Unable to atomic move from tmpEventsBatch to final file name: " + realValidEventfile.getAbsolutePath());
    }

    return realValidEventfile;
  }

  /**
   * Test equals() and hashcode() contracts.
   */
  @Test
  @Ignore
  public void equalsContract() {
    EqualsVerifier.forClass(ReplicatedScheduling.class).suppress(Warning.NULL_FIELDS, Warning.ANNOTATION).verify();
  }

}
