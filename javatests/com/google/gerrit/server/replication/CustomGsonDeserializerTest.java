package com.google.gerrit.server.replication;

import com.google.gerrit.server.replication.customevents.CacheObjectCallWrapper;
import com.google.gerrit.server.replication.customevents.IndexToReplicate;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.wandisco.gerrit.gitms.shared.events.ChainedEventComparator;
import com.wandisco.gerrit.gitms.shared.events.EventNanoTimeComparator;
import com.wandisco.gerrit.gitms.shared.events.EventTimestampComparator;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import com.wandisco.gerrit.gitms.shared.events.exceptions.InvalidEventJsonException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPInputStream;

import static com.google.gerrit.server.replication.configuration.ReplicationConstants.ENC;

public class CustomGsonDeserializerTest extends AbstractReplicationSetup {

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
  public void testUsingCustomGsonDeserializer_TypesInSerializedEventCorrect() throws IOException, InvalidEventJsonException, ClassNotFoundException {
    final String projectName = "ProjectA";
    final Gson providedGson = dummyTestCoordinator.getGson();
    EventWrapper dummyWrapper1 = createIndexEventWrapper(projectName);

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

    //Check that after sorting events we have the correct format
    for (EventWrapper ev : sortedEvents) {
      // turn the event back into its source event type, if the integer in the index info isn't serialized and back correctly
      // it wont construct the object here!
      IndexToReplicate indexInfo = (IndexToReplicate) dummyTestCoordinator.getGson().fromJson(ev.getEvent(), Class.forName(ev.getClassName()) );
      // Now request the actual index event info.
      Assert.assertNotNull(indexInfo);
    }

    ReplicatedEventTask replicatedEventTask = new ReplicatedEventTask(projectName,
        persistedEventInformation.getEventFile(), dummyTestCoordinator);

    FailedEventUtil.persistRemainingEvents(dummyTestCoordinator, replicatedEventTask, sortedEvents);


    //Check after atomically writing the events to the original file during persistance we still have the correct
    //format
    String[] postWriteEvent;
    List<EventWrapper> afterAtomicRenameEvents;
    try (ByteArrayOutputStream bos = readFileToByteArrayOutputStream(persistedEventInformation.getEventFile(),
        dummyTestCoordinator.getReplicatedConfiguration().isIncomingEventsAreGZipped())) {
      //Check and sort events deserializes to EventWrapper and then adds to the sortedEvents list
      postWriteEvent = new String(bos.toByteArray(), StandardCharsets.UTF_8).split("\n");

      afterAtomicRenameEvents = getEvents(postWriteEvent, providedGson);
    }

    for (EventWrapper ev : afterAtomicRenameEvents) {
      // turn the event back into its source event type, if the integer in the index info isn't serialized and back correctly
      // it wont construct the object here!
      IndexToReplicate indexInfo = (IndexToReplicate) dummyTestCoordinator.getGson().fromJson(ev.getEvent(), Class.forName(ev.getClassName()) );
      // Now request the actual index event info.
      Assert.assertNotNull(indexInfo);
    }

  }

}
