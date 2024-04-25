package com.google.gerrit.server.replication;

import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.project.ProjectCacheImpl;
import com.google.gerrit.server.replication.customevents.CacheObjectCallWrapper;
import com.google.gson.Gson;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;
import com.wandisco.gerrit.gitms.shared.events.exceptions.InvalidEventJsonException;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.CACHE_EVENT;

public class CacheKeyMapperTest extends AbstractReplicationSetup {

  @Rule
  public ExpectedException expectedException = ExpectedException.none();
  public ReplicatedScheduling scheduling;

  @Before
  public void setupTest() throws Exception {
    // make sure we clear out and have a new coordinator for each test - sorry, but otherwise we would need to be
    // clearing out lists which would change depend on ordering!
    AbstractReplicationSetup.setupReplicatedEventsCoordinatorProps(true, null);
  }

  @Test
  public void testShowWDGsonSerializationUsingWDArgumentTypeField() throws IOException {
    final String projectName = "ProjectA";
    final Gson providedGson = dummyTestCoordinator.getGson();

    Project.NameKey myProject = new Project.NameKey(projectName);
    final String ref = "refs/heads/master";
    final String cacheName = "ProjectCacheImpl-AnyOldCache";
    final String cacheMethodToCall = "onCreateProjectNoReplication";

    List<?> args = Arrays.asList(myProject, ref);

    // Prove that using type hints serialise just fine.
    {
      final String providedTest = providedGson.toJson(myProject);
      final Project.NameKey simpleNameKey = providedGson.fromJson(providedTest, Project.NameKey.class);
      Assert.assertEquals(myProject, simpleNameKey);
    }

    // show that using a list to abstact it doesn't work!
    {
      final List<Object> list = Arrays.asList(myProject);
      Assert.assertEquals(list.get(0).getClass().getName(), Project.NameKey.class.getName());
      final String listJson = providedGson.toJson(list);
      final List<Object> newList = providedGson.fromJson(listJson, List.class);
      Assert.assertNotEquals(list.get(0).getClass().getName(), newList.get(0).getClass().getName());
    }

    // Lets look at a bigger class being reconstructed.
    CacheObjectCallWrapper cacheMethodCall = new CacheObjectCallWrapper(cacheName, cacheMethodToCall, args, dummyTestCoordinator.getThisNodeIdentity());

    // try wd gson - then standard gson.
    final String providedJson = providedGson.toJson(cacheMethodCall);

    // Turn it back.
    CacheObjectCallWrapper providedChecker = providedGson.fromJson(providedJson, CacheObjectCallWrapper.class);

    // Check that the new gson serialization has the object in its original format.
    Assert.assertNotEquals(Project.NameKey.class.getTypeName(), ((List<Object>) providedChecker.key).get(0).getClass().getTypeName());
    // Now after rebuildOriginal is called by getMethodArgs, it should all just work.
    Assert.assertEquals(Project.NameKey.class.getTypeName(), providedChecker.getMethodArgs().get(0).getClass().getTypeName());
  }


  @Test
  public void testUsingCustomGsonDeserializer_CacheObjectCallWrapper() throws IOException, InvalidEventJsonException, ClassNotFoundException {
    final String projectName = "ProjectA";
    final Gson providedGson = dummyTestCoordinator.getGson();

    Project.NameKey myProject = new Project.NameKey(projectName);
    final String ref = "refs/heads/master";
    final String cacheName = "ProjectCacheImpl-AnyOldCache";
    final String cacheMethodToCall = "onCreateProjectNoReplication";

    List<?> args = Arrays.asList(myProject, ref);

    EventWrapper dummyWrapper1 = createReplicatedEventWithCacheObjectCallWrapper(cacheName, cacheMethodToCall, args, projectName);

    // have a look - our new serialized namekey should have wdArgumenttype in it.
    final String providedTest = providedGson.toJson(myProject);

    byte[] bytes1 = getEventBytes(dummyWrapper1);

    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, dummyWrapper1);

    persistedEventInformation.writeEventsToFile(bytes1);

    List<EventWrapper> sortedEvents;
    try (ByteArrayOutputStream bos = readFileToByteArrayOutputStream(persistedEventInformation.getEventFile(),
        dummyTestCoordinator.getReplicatedConfiguration().isIncomingEventsAreGZipped())) {
      //Check and sort events deserializes to EVentWrapper and then adds to the sortedEvents list
      sortedEvents = checkAndSortEvents(bos.toByteArray(), providedGson);
    }

    // check we got 1 event file back , and it is the same object.
    Assert.assertEquals(sortedEvents.size(), 1);
    EventWrapper rebuiltWrapper = sortedEvents.get(0);

    ProjectCacheImpl projectCache = EasyMock.createNiceMock(ProjectCacheImpl.class);
    projectCache.onCreateProjectNoReplication(myProject, ref);
    EasyMock.expectLastCall();
    EasyMock.replay(projectCache);

    dummyTestCoordinator.getReplicatedIncomingCacheEventProcessor().watchObject(cacheName, projectCache);


    //Get the classname from the origin wrapper event as it will be needed
    //for de-serializing the event from json
    Class<?> eventClass = Class.forName(rebuiltWrapper.getClassName());
    Assert.assertEquals(CACHE_EVENT, rebuiltWrapper.getEventOrigin());

    ReplicatedEvent replicatedEvent = (ReplicatedEvent) providedGson.fromJson(rebuiltWrapper.getEvent(), eventClass);
    Assert.assertNotNull("ReplicatedEvent was null after de-serialising from JSON!");

    //     Can we call this on the mathod cache!
    dummyTestCoordinator.getReplicatedIncomingCacheEventProcessor().processIncomingReplicatedEvent(replicatedEvent);

    Assert.assertEquals(dummyWrapper1.getProjectName(), rebuiltWrapper.getProjectName());
    Assert.assertEquals(dummyWrapper1.getClassName(), rebuiltWrapper.getClassName());
    Assert.assertEquals(dummyWrapper1.getEventOrigin(), rebuiltWrapper.getEventOrigin());
    Assert.assertEquals(dummyWrapper1.getEvent(), rebuiltWrapper.getEvent());
    // can't compare event data directly - no comparator in gerrit-gitms-shared.
  }

  private EventWrapper createReplicatedEventWithCacheObjectCallWrapper(final String cacheName,
                                                                       final String methodName,
                                                                       final List<?> methodArgs,
                                                                       final String projectName) throws IOException {

    CacheObjectCallWrapper cacheMethodCall = new CacheObjectCallWrapper(cacheName, methodName,
        methodArgs, dummyTestCoordinator.getThisNodeIdentity());


    // Please note the supplied projectname is used by some event to actually cause replication to that DSM, but for
    // cache events this always goes to the ALL_PROJECTS dsm, as it covers project creation / deletion etc on project list.
    return GerritEventFactory.createReplicatedCacheEvent(projectName, cacheMethodCall);
  }
}
