package com.google.gerrit.server.replication;

import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.server.replication.customevents.AccountUserIndexEvent;
import com.google.gerrit.server.replication.customevents.IndexToReplicate;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.wandisco.gerrit.gitms.shared.events.ChainedEventComparator;
import com.wandisco.gerrit.gitms.shared.events.EventNanoTimeComparator;
import com.wandisco.gerrit.gitms.shared.events.EventTimestampComparator;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import com.wandisco.gerrit.gitms.shared.events.exceptions.InvalidEventJsonException;

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
import java.util.Optional;
import java.util.Properties;
import java.util.Random;
import java.util.zip.GZIPInputStream;

import static com.google.gerrit.server.replication.configuration.ReplicationConstants.ENC;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_REPLICATED_EVENT_WORKER_POOL_SIZE;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.REPLICATION_DISABLED;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.ACCOUNT_USER_INDEX_EVENT;

public abstract class AbstractReplicationSetup {

  protected static TestingReplicatedEventsCoordinator dummyTestCoordinator;

  /**
   * The properties set in this method are the bare minimum required for testing
   * There is the option off passing in extra properties which will be added to
   * the set of overall properties added passed to the TestingReplicatedEventsCoordinator
   * constructor. If no extra properties are required for the test pass null.
   * @param extraProperties
   * @throws Exception
   */
  public static void setupReplicatedEventsCoordinatorProps(boolean replicationDisabled, Properties extraProperties) throws Exception {
    // make sure to clear - really we want to call disable in before class and only enable for one test.
    SingletonEnforcement.clearAll();
    SingletonEnforcement.setDisableEnforcement(true);

    Properties testingProperties = new Properties();

    // SET our pool to 2 items, plus the 2 core projects.
    testingProperties.put(GERRIT_REPLICATED_EVENT_WORKER_POOL_SIZE, "2");
    testingProperties.put(REPLICATION_DISABLED, true);

    Optional<Properties> extra = Optional.ofNullable(extraProperties);
    extra.ifPresent(testingProperties::putAll);

    dummyTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);

    // Some tests may require that replication is enabled. This will not be real replication but will set up the required
    // testing properties in the ReplicationConfiguration class.
    dummyTestCoordinator.getReplicatedConfiguration()
            .getAllowReplication().setReplicationDisabledServerConfig(replicationDisabled);

    GerritEventFactory.setupEventWrapper();
  }

  // Event dummy creation helpers with random unique information where needed.
  public EventWrapper createIndexEventWrapper(String projectName) throws IOException {
    int randomIndexId = new Random().nextInt(1000);
    IndexToReplicate indexToReplicate = new IndexToReplicate(randomIndexId, projectName,
        new Timestamp(System.currentTimeMillis()), dummyTestCoordinator.getThisNodeIdentity());

    return GerritEventFactory.createReplicatedIndexEvent(indexToReplicate);
  }

  public static EventWrapper createAccountIndexEventWrapper(String projectName) throws IOException {
    int randomIndexId = new Random().nextInt(1000);
    AccountUserIndexEvent accountUserIndexEvent = new AccountUserIndexEvent(new Account.Id(randomIndexId), dummyTestCoordinator.getThisNodeIdentity());
    return GerritEventFactory.createReplicatedAccountIndexEvent(
        projectName, accountUserIndexEvent, ACCOUNT_USER_INDEX_EVENT);
  }


  public static ByteArrayOutputStream readFileToByteArrayOutputStream(File file, boolean incomingEventsAreGZipped) throws IOException {

    // ByteArrayOutputStream is an implementation of OutputStream that can write data into a byte array.
    // The buffer keeps growing as ByteArrayOutputStream writes data to it.
    try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
         FileInputStream plainFileReader = new FileInputStream(file);) {
      // If the incoming events are Gzipped, then the reader will be a GZipInputStream otherwise
      // it will be a FileInputStream.
      try (InputStream reader = incomingEventsAreGZipped ? new GZIPInputStream(plainFileReader) : plainFileReader) {
        copyStreamInformation(reader, bos);
        return bos;
      }
    }
  }

  private static void copyStreamInformation(InputStream source, OutputStream dest)
      throws IOException {
    byte[] buf = new byte[8192];

    int read;
    while ((read = source.read(buf)) > 0) {
      dest.write(buf, 0, read);
    }
  }

  public static List<EventWrapper> getEvents(String[] events, final Gson gson) throws InvalidEventJsonException {
    List<EventWrapper> eventDataList = new ArrayList<>();

    for (String event : events) {

      if (event == null) {
        throw new InvalidEventJsonException(
            "Event file is invalid, missing / null events.");
      }

      EventWrapper originalEvent;
      try {
        originalEvent = gson.fromJson(event, EventWrapper.class);
      } catch (JsonSyntaxException e) {
        throw new InvalidEventJsonException(
            String.format("Event file contains Invalid JSON. \"%s\", \"%s\"",
                event, e.getMessage()));
      }

      if (checkValidEventWrapperJson(originalEvent)) {
        eventDataList.add(originalEvent);
      }
    }
    return eventDataList;
  }


  public static void copyFile(InputStream source, OutputStream dest)
      throws IOException {
    try (InputStream fis = source) {
      byte[] buf = new byte[8192];
      int read;
      while ((read = fis.read(buf)) > 0) {
        dest.write(buf, 0, read);
      }
    }
  }

  public static boolean checkValidEventWrapperJson(EventWrapper originalEvent) throws InvalidEventJsonException {
    if (originalEvent == null) {
      throw new InvalidEventJsonException("Internal error: event is null after deserialization");
    }
    // If the JSON is invalid we will not have been able to get eventTimestamp or eventNanoTime information
    // from it required for sorting, so all we can do is throw an exception here. If the JSON is empty this case
    // will cover {} or ""
    if (originalEvent.getEvent().length() <= 2) {
      throw new InvalidEventJsonException("Internal error, event JSON is invalid ");
    }

    return true;
  }

  public byte[] getEventBytes(final EventWrapper eventWrapper) throws UnsupportedEncodingException {
    Gson gson = dummyTestCoordinator.getGson();
    final String wrappedEvent = gson.toJson(eventWrapper) + '\n';
    return wrappedEvent.getBytes(ENC);
  }

  public static List<EventWrapper> checkAndSortEvents(byte[] eventsBytes, final Gson gson)
      throws InvalidEventJsonException {

    List<EventWrapper> eventDataList;
    String[] events =
        new String(eventsBytes, StandardCharsets.UTF_8).split("\n");

    eventDataList = getEvents(events, gson);

    //sort the event data list using a chained comparator.
    Collections.sort(eventDataList,
        new ChainedEventComparator(
            new EventTimestampComparator(),
            new EventNanoTimeComparator()));

    return eventDataList;
  }

}
