package com.google.gerrit.server.replication;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.replication.configuration.ReplicatedConfiguration;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gson.Gson;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import com.wandisco.gerrit.gitms.shared.events.GerritEventMetadata;
import com.wandisco.gerrit.gitms.shared.util.ObjectUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.gerrit.server.replication.configuration.ReplicationConstants.DEFAULT_NANO;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.ENC;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.NEXT_EVENTS_FILE;
import static com.wandisco.gerrit.gitms.shared.util.StringUtils.getProjectNameSha1;

public class PersistedEventInformation {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  public static final String TMP_EVENTS_BATCH = "tmpEventsBatch-";

  private final long firstEventTime;
  private final File eventFile;
  private final File finalEventFile;
  private final FileOutputStream fileOutputStream;
  private boolean isFileOutputStreamClosed = false;
  private AtomicInteger numEventsWritten = new AtomicInteger(0);
  private ReplicatedConfiguration replicatedConfiguration;
  private final String projectName;
  private final File eventsFinalDirectory; // used for outgoing batch, and incoming reprocessing.  May move to passing this in, in the future, for now we have 2 constructors.
  private final File eventsTmpDirectory; // used while building the events batch in outgoing/tmp but also in incoming/tmp while rebuilding failed events, so this can change

  private final Gson gson;


  /**
   * This constructor writes to the OUTGOING directory when creating a batch of events for processing.
   * so all new events go into a file in outgoing/tmp/tmpEventsBatch-xxx and finally when it reaches an amount of time elapses
   * or a max count of events it will be atomicaly renamed into the parent location with the final event name.
   * @param replicatedEventsCoordinator
   * @param originalEvent
   * @throws IOException
   */
  public PersistedEventInformation(final ReplicatedEventsCoordinator replicatedEventsCoordinator, EventWrapper originalEvent) throws IOException {
    this(replicatedEventsCoordinator,
        createThisEventsFilename(originalEvent, replicatedEventsCoordinator.getReplicatedConfiguration().getAllProjectsName()),
        originalEvent.getProjectName(),
        false);
  }

  /**
   * Constructor only used for the incoming directory currently.
   * This is for updating(reducing) an existing event file with less events than before.
   * Basically when we process events, the processed events are removed when we enter a failure state, leaving only the failed events behind so the events being
   * retried are reduced each time and finally goes into the failed directory.
   * @param replicatedEventsCoordinator
   * @param originalEventFileName
   * @param projectName
   * @throws IOException
   */
  public PersistedEventInformation(final ReplicatedEventsCoordinator replicatedEventsCoordinator,
                                   String originalEventFileName, String projectName) throws IOException {
    this( replicatedEventsCoordinator, originalEventFileName, projectName, true);
  }

  /**
   * Generic constructor - used by both constructors, just one is used on existing event to reduce its working events list
   * in the incoming directory
   * While the other creates a new events batch of information in the outgoing events directory so has no existing file.
   * @param replicatedEventsCoordinator
   * @param finalEventFileName
   * @param projectName
   * @param useIncomingDirectory
   */
  private PersistedEventInformation(final ReplicatedEventsCoordinator replicatedEventsCoordinator,
                                    final String finalEventFileName,
                                    final String projectName,
                                    boolean useIncomingDirectory) throws IOException {
    this.firstEventTime = System.currentTimeMillis();
    //Project key doesn't exist in the map so create a new file
    //Create a file with a filename composed of the contents of the event file itself.
    this.replicatedConfiguration = replicatedEventsCoordinator.getReplicatedConfiguration();
    this.gson = replicatedEventsCoordinator.getGson();
    if ( useIncomingDirectory ){
      this.eventsFinalDirectory = replicatedConfiguration.getIncomingReplEventsDirectory();
      this.eventsTmpDirectory = replicatedConfiguration.getIncomingTemporaryReplEventsDirectory();
    }
    else {
      this.eventsFinalDirectory = replicatedConfiguration.getOutgoingReplEventsDirectory();
      this.eventsTmpDirectory = replicatedConfiguration.getOutgoingTemporaryReplEventsDirectory();
    }
    this.eventFile = getTempEventFile();
    this.fileOutputStream = new FileOutputStream(eventFile);
    // this final event file may not exist yet, until the atomic rename finally happens, this just points to the location
    // ahead of time.
    this.finalEventFile = new File(eventsFinalDirectory, finalEventFileName);
    this.projectName = projectName;
  }

  public String getProjectName() {
    return projectName;
  }

  public long getFirstEventTime() {
    return firstEventTime;
  }

  public FileOutputStream getFileOutputStream() {
    return fileOutputStream;
  }

  /**
   * Returns the event file being processed (based off call {@link #getTempEventFile()}.
   * If you want the final event file - this will only be populated or valid after
   *   the atomic rename happens {@link #getFinalEventFile()})
   *
   * So if we are in the incoming directory it will be a incoming/tmp/tmpEventsBatch-xxx
   *    file where gerrit reduces a file to only failed or not processed events (before final failed dir)
   * In outgoing it will be outgoing/tmp/tmpEventsBatch-xxx
   *    file for events being batched per project to be pickedup and proposed by gitms.
   * @return
   */
  public File getEventFile() {
    return eventFile;
  }

  /**
   * Returns a file handle to the final event file that will be the final destination once it is atomically renamed, it is not valid
   * until this operation completes. In the outgoing use case, no file is present until that time, where as in the incoming case it will be
   * the old original file content, that will be replaced by a reduced set of events after the rename.
   *
   * @return
   */
  public File getFinalEventFile() {
    return finalEventFile;
  }

  public AtomicInteger getNumEventsWritten() {
    return numEventsWritten;
  }

  public boolean isFileOutputStreamClosed() {
    return isFileOutputStreamClosed;
  }

  public void setFileOutputStreamClosed(boolean fileOutputStreamClosed) {
    isFileOutputStreamClosed = fileOutputStreamClosed;
  }

  public File getEventsFinalDirectory() {
    return eventsFinalDirectory;
  }

  public File getEventsTmpDirectory() {
    return eventsTmpDirectory;
  }

  /**
   * Utility method to get a .tmp event file name.
   * @return filename of the format events-<randomuuid>.tmp
   * @throws IOException
   */
  private File getTempEventFile() throws IOException {
    return File.createTempFile(TMP_EVENTS_BATCH, ".tmp", eventsTmpDirectory);
  }

  /**
   * Closes the FileOutputStream (if open) associated with the .tmp event file
   * performs an FD.sync if isSyncFiles is set/
   * @return true if FileOutputStream successfully closed and FD sync completed.
   */
  public boolean setFileReady() {
    if (numEventsWritten.get() == 0) {
      logger.atFine().log("RE No events to send. Waiting...");
      return false;
    }

    logger.atFine().log("RE Closing file and renaming to be picked up");
    try {
      if(!isFileOutputStreamClosed()) {
        getFileOutputStream().close();
        setFileOutputStreamClosed(true);
      }
    } catch (IOException ex) {
      logger.atWarning().withCause(ex).log("RE unable to close the file to send");
    }

    if (replicatedConfiguration.isSyncFiles()) {
      try {
        getFileOutputStream().getFD().sync();
      } catch (IOException ex) {
        logger.atWarning().withCause(ex).log("RE unable to sync the file to send");
      }
    }
    return true;
  }


  /**
   * This is the only method writing events to the event files.
   * write the events-<randomnum>.tmp file, increase the NumEventsWritten and
   * @param bytes : The bytes for EventWrapper instance
   * @return true if bytes written successfully.
   */
  public boolean writeEventsToFile(byte[] bytes) {
    try{
      if(getFileOutputStream() != null) {

        getFileOutputStream().write(bytes);
        //An event has been written, increment the projectEvent numEvents counter.
        getNumEventsWritten().incrementAndGet();

        logger.atFine().log("Number of events written to the events file [ %s ] for " +
                "project [ %s ] is currently : [ %d ].", eventFile, getProjectName(), getNumEventsWritten().get() );
      }

    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Unable to write the JSON event bytes to [ %s ] :", e.getMessage());
      return false;
    }
    return true;
  }


  /**
   * This will create append to the current file the last event received. If the
   * project name of the this event is different from the the last one, then we
   * need to create a new file anyway, because we want to pack events in one
   * file only if the are for the same project
   *
   * @param originalEvent : The EventWrapper instance that was polled from the queue.
   * @return true if the event was successfully appended to the file
   * @throws IOException
   */
  public boolean appendToFile(final EventWrapper originalEvent, boolean updateMetrics)
      throws IOException {

    // If the project is the same, write the file
    final String wrappedEvent = gson.toJson(originalEvent) + '\n';
    byte[] bytes = wrappedEvent.getBytes(ENC);

    logger.atFine().log("RE Last json to be sent: %s", wrappedEvent);
    if(updateMetrics) {
      ReplicatorMetrics.totalPublishedLocalEvents.incrementAndGet();
      ReplicatorMetrics.totalPublishedLocalEventsBytes.addAndGet(bytes.length);
      ReplicatorMetrics.totalPublishedLocalGoodEventsBytes.addAndGet(bytes.length);
      ReplicatorMetrics.totalPublishedLocalGoodEvents.incrementAndGet();
      ReplicatorMetrics.totalPublishedLocalEventsByType.add(originalEvent.getEventOrigin());
    }

    return writeEventsToFile(bytes);
  }


  /**
   * Rename the outgoing events-<randomnum>.tmp file to the unique filename that was created upon
   * construction of the PersistedEventInformation instance.
   * See {@link PersistedEventInformation#createThisEventsFilename(EventWrapper, String)} ()} ()} for how
   * this filename is composed.
   * @return Returns TRUE when it has successfully renamed the temp file to its final named variety(atomically)
   */
  public boolean atomicRenameTmpFilename() {

    //The final event file name is set upon construction if we encounter a project that is not
    //in our outgoingEventInformationMap
    if (finalEventFile == null) {
      logger.atSevere().log("RE finalEventFileName was not set correctly, losing events. Temporary Events file [ %s ]at this location may be used to rerun these events - contact WANdisco support.",
          getEventFile().getAbsolutePath());
      return false;
    }

    final File projectTmpEventFile = getEventFile();
    final File finalEventFile = getFinalEventFile();

    logger.atFinest().log(".tmp event [ %s ] file for projectName [ %s ] will be renamed to [ %s ] in the parent outgoing directory.",
        getProjectName(), projectTmpEventFile.getName(), getFinalEventFile().getName());

    // Documentation states the following for 'renameTo'
    // * Many aspects of the behavior of this method are inherently
    // * platform-dependent: The rename operation might not be able to move a
    // * file from one filesystem to another, it might not be atomic, and it
    // * might not succeed if a file with the destination abstract pathname
    // * already exists. The return value should always be checked to make sure
    // * that the rename operation was successful.
    // We should therefore consider an alternative in future.
    // Note the rename is now also moving the file into the parent location from outgoing/tmp/x to outgoing/y.  As its a subdirectory
    // it will be on the same filesystem to keep atomic move possible.  While keeping unnecessary temporary files out of the final location.
    boolean renamed = projectTmpEventFile.renameTo(finalEventFile);

    if (!renamed) {
      logger.atSevere().log("RE Could not rename temporary events file [ %s ] to its final location file [ %s ] in the outgoing directory to be processed.  This will result in losing events but they still exist in the temporary file and can be processed if the file correctly moved.  Please rename the temporary file to its final name and location manually.",
          projectTmpEventFile.getAbsolutePath(), finalEventFile.getAbsolutePath());
      return false;
    }

    ReplicatorMetrics.totalPublishedLocalEventsProsals.incrementAndGet();
    return true;
  }


  /**
   * We record the time that the first event was written to the file, we compare this against the
   * time now. If the difference is greate than or equal to the getMaxSecsToWaitBeforeProposingEvents then
   * return true.
   * @return true if the difference between the current time and the time which the first event was written
   * is >= the max seconds to wait before proposing events value.
   */
  public boolean timeToWaitBeforeProposingExpired(){
    long timeNow = System.currentTimeMillis();
    return (timeNow - getFirstEventTime() ) >= replicatedConfiguration.getMaxSecsToWaitBeforeProposingEvents();
  }

  /**
   * If the number of written events to the event file is greater than or equal
   * to the maxNumberOfEventsBeforeProposing then return true. If we have
   * reached the maxNumberOfEventsBeforeProposing then we must propose,
   * otherwise we can just continue to add events to the file. The default value
   * for maxNumberOfEventsBeforeProposing is 30 although this is configurable by
   * setting gerrit.replicated.events.max.append.before.proposing in the
   * application.properties.
   *
   * @return true if the number of events written to the file so far is >= the max number of events
   * allowed before proposing.
   */
  public boolean exceedsMaxEventsBeforeProposing() {
    return getNumEventsWritten().get() >= replicatedConfiguration.getMaxNumberOfEventsBeforeProposing();
  }


  /**
   * Allow the creation of an events file name to be used by our real code, and integration testing
   * to keep naming consistent.
   * @param originalEvent
   * @return String containing the new Events filename
   */
  public static String createThisEventsFilename(final EventWrapper originalEvent, final String allProjectsName) throws IOException {
    // Creating a GerritEventMetadata object from the inner event JSON of the
    // EventWrapper object.
    GerritEventMetadata eventData = ObjectUtils
        .createObjectFromJson(originalEvent.getEvent(), GerritEventMetadata.class);

    if (eventData == null) {
      logger.atSevere().log("Unable to set event filename, could not create "
          + "GerritEventMetadata object from JSON %s", originalEvent.getEvent());
      throw new IOException("Unable to create a new GerritEventMetadata object from the event supplied.");
    }

    // If there are event types added in future that do not support the
    // projectName member
    // then we should generate an error. The default sha1 will be used.
    if (originalEvent.getProjectName() == null) {
      //This message is logged at INFO level due to the replication plugin causing at lot of spam on the dashboard.
      logger.atInfo().log("The following Event Type %s has a Null project name. "
              + "Unable to set the event filename using the sha1 of the project name. "
              + "Using All-Projects as the default project, and updating the event data to match",
          originalEvent.getEvent());
      originalEvent.setProjectName(allProjectsName);
    }

    String eventTimestamp = eventData.getEventTimestamp();
    // The java.lang.System.nanoTime() method returns the current value of
    // the most precise available system timer, in nanoseconds. The value
    // returned represents elapsed time of the current JVM from some fixed but arbitrary *origin* time
    // (*potentially* in the future, so values may be negative)
    // and provides nanosecond precision, but not necessarily nanosecond
    // accuracy.

    // The long value returned will be represented as a padded hexadecimal value
    // to 16 digits in order to have a
    // guaranteed fixed length as System.nanoTime() varies in length on each OS.
    // If we are dealing with older event files where eventNanoTime doesn't
    // exist as part of the event
    // then we will set the eventNanoTime portion to 16 zeros, same length as a
    // nanoTime represented as HEX.
    String eventNanoTime = eventData.getEventNanoTime() != null
        ? ObjectUtils.getHexStringOfLongObjectHash(
        Long.parseLong(eventData.getEventNanoTime()))
        : DEFAULT_NANO;
    String eventTimeStr = String.format("%sx%s", eventTimestamp, eventNanoTime);
    String objectHash =
        ObjectUtils.getHexStringOfIntObjectHash(originalEvent.hashCode());

    // event file is now following the format
    // events_<eventTimeStamp>x<eventNanoTime>_<nodeId>_<repo-sha1>_<hashOfEventContents>.json

    // The NEXT_EVENTS_FILE variable is formatted with the timestamp and nodeId
    // of the event and
    // a sha1 of the project name. This ensures that it will remain unique under
    // heavy load across projects.
    // Note that a project name includes everything below the root so for
    // example /path/subpath/repo01 is a valid project name.
    return String.format(NEXT_EVENTS_FILE, eventTimeStr,
        eventData.getNodeIdentity(),
        getProjectNameSha1(originalEvent.getProjectName()), objectHash);
  }

  /**
   * NOTE Using a reduced set for toString, equals and hashcode, to help with performance and reduced static output in the logs.
   * We do not need to output the replicated configuration for every call, or the incoming/outgoing directories.
   * As this information doesn't change we can safely leave it out, or let another member be the main comparison information like eventFile or numEventsWritten etc.
   *
   * @param o
   * @return
   */
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PersistedEventInformation that = (PersistedEventInformation) o;
    return firstEventTime == that.firstEventTime &&
        Objects.equals(eventFile, that.eventFile) &&
        Objects.equals(finalEventFile, that.finalEventFile) &&
        Objects.equals(numEventsWritten, that.numEventsWritten) &&
        Objects.equals(projectName, that.projectName) &&
        Objects.equals(eventsFinalDirectory, that.eventsFinalDirectory) &&
        Objects.equals(eventsTmpDirectory, that.eventsTmpDirectory);
  }

  @Override
  public int hashCode() {
    return Objects.hash(firstEventTime, eventFile, finalEventFile, numEventsWritten, projectName, eventsFinalDirectory, eventsTmpDirectory);
  }

  /**
   * Reduced set for toString.  we do not need to output the replicated configuration for every call, or the incoming/outgoing directories
   * and trying to output the file output stream isn't good.  so this is the actual information we require for each different persistedeventinformation request
   * If you are interested in the replication configuration it is output on startup and at other times, and it static then for the duration.
   * @return
   */
  @Override
  public String toString() {
    return new StringJoiner(", ", PersistedEventInformation.class.getSimpleName() + "[", "]")
        .add("firstEventTime=" + firstEventTime)
        .add("eventFile=" + eventFile)
        .add("finalEventFile=" + finalEventFile)
        .add("numEventsWritten=" + numEventsWritten)
        .add("projectName='" + projectName + "'")
        .toString();
  }

}
