package com.google.gerrit.server.replication.configuration;

public final class ReplicationConstants {

  /**
   * GERRIT REPLICATED EVENT DIRECTORIES
   * The directories inside gerrit/events/replicated-events WANdisco replicated events will be placed.
   */
  public static final String REPLICATED_EVENTS_DIRECTORY_NAME = "replicated_events";
  public static final String OUTGOING_DIR = "outgoing";
  public static final String INCOMING_DIR = "incoming";
  public static final String FAILED_DIR = "failed"; // now this is used for incoming failed events usually so /incoming/failed/events_xx


  /*****************************************************************************************
   * CONFIGURE REPLICATION DISABLED
   * Setting this flag to true will ensure Gerrit runs in vanilla and. No replicated classes will
   * be used when replication is disabled.
   */
  public static final String REPLICATION_DISABLED = "gerritms_replication_disabled";


  /*****************************************************************************************
   * GERRIT EVENT FILE CONFIGURATION
   * Allows for change in configuration of wait times, number of events in files
   * as shown by statistics this means less than 2K gzipped proposals
   */
  public static final String GERRIT_REPLICATED_EVENTS_ENABLED_SYNC_FILES = "gerrit.replicated.events.enabled.sync.files";
  public static final String GERRIT_REPLICATED_EVENTS_BASEPATH = "gerrit.replicated.events.basepath";
  public static final String GERRIT_EVENT_BASEPATH = "gerrit.events.basepath";

  public static final String GERRIT_REPLICATED_EVENTS_INCOMING_ARE_GZIPPED = "gerrit.replicated.events.incoming.gzipped";
  public static final String GERRIT_MAX_MS_TO_WAIT_BEFORE_PROPOSING_EVENTS = "gerrit.replicated.events.secs.before.proposing";
  public static final String GERRIT_CACHE_NAMES_NOT_TO_BE_RELOADED = "gerrit.replicated.cache.names.not.to.reload";
  public static final String GERRIT_REPLICATED_CACHE_NAMES_TO_IGNORE = "gerrit.replicated.cache.names.to.ignore";
  public static final String GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING = "gerrit.replicated.events.max.append.before.proposing";

  public static final String GERRIT_EVENTS_BACKOFF_INITIAL_PERIOD = "gerrit.replicated.events.initial.backoff.period";
  public static final String GERRIT_EVENTS_BACKOFF_CEILING_PERIOD = "gerrit.replicated.events.ceiling.backoff.period";
  public static final String GERRIT_MAX_NUM_EVENTS_RETRIES = "gerrit.replicated.events.max.backoff.retries";

  public static final String GERRIT_FILE_SYSTEM_RESOLUTION = "gerrit.replicated.filesystem.resolution.ms";

  public static final String GERRIT_MAX_LOGGING_PERIOD_SECS = "gerrit.replicated.logging.atmost.period";

  public static final String GERRIT_MINUTES_SINCE_CHANGE_LAST_INDEXED_CHECK_PERIOD = "gerrit.minutes.since.change.last.indexed.check.period";
  public static final String GERRIT_REPLICATED_EVENTS_ENABLED_RECEIVE = "gerrit.replicated.events.enabled.receive";
  public static final String GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL = "gerrit.replicated.events.enabled.receive.original";
  //Events can be skipped by providing a comma seperated list of event types.
  //e.g, TopicChangedEvent, ReviewerDeletedEvent, ReviewerUpdatedEvent
  public static final String GERRIT_EVENT_TYPES_TO_BE_SKIPPED = "gerrit.event.types.disabled.list";

  public static final String GERRIT_REPLICATED_EVENTS_ENABLED_SEND = "gerrit.replicated.events.enabled.send";


  /*****************************************************************************************
   * REPLICATED THREAD CONFIGURATION
   * These settings will affect running threads in the ReplicatedScheduling class
   */
  public static final String GERRIT_REPLICATED_EVENT_WORKER_POOL_SIZE = "gerrit.replicated.events.worker.pool.size";
  public static final String GERRIT_REPLICATED_EVENT_WORKER_POOL_MIN_SIZE = "gerrit.replicated.events.worker.pool.min.size";
  public static final String GERRIT_REPLICATED_EVENT_WORKER_CORE_PROJECTS = "gerrit.replicated.events.worker.coreprojects";

  public static final String GERRIT_REPLICATED_EVENT_WORKER_POOL_IDLE_TIME_SECS = "gerrit.replicated.events.worker.pool.idle.period.secs";
  public static final String GERRIT_MAX_SECS_TO_WAIT_ON_POLL_AND_READ = "gerrit.max.secs.to.wait.on.poll.and.read";


  /*****************************************************************************************
   * DEFAULT VALUES
   * Default values if no configuration provided in application.properties
   */
  public static final String DEFAULT_MAX_EVENTS_PER_FILE = "30";

  // We must have no smaller than 1 worker thread (plus x core project workers),
  // and >= min worker threads in the pool(enforced by us at startup).
  // By default 10(max)+2(core) e.g. 12 total and min=max.
  public static final String DEFAULT_EVENT_WORKER_POOL_SIZE = "10";
  public static final String DEFAULT_MAX_SECS_TO_WAIT_BEFORE_PROPOSING_EVENTS = "5";
  public static final String DEFAULT_MAX_SECS_TO_WAIT_ON_POLL_AND_READ = "1";
  public static final String DEFAULT_MINUTES_SINCE_CHANGE_LAST_INDEXED_CHECK_PERIOD = "60";
  public static final String DEFAULT_GERRIT_MAX_NUM_EVENTS_RETRIES = "10"; // 10 backoff retries
  public static final String DEFAULT_GERRIT_EVENTS_BACKOFF_INITIAL_PERIOD = "1"; // backoff 1 secs - doubling per retry.
  public static final String DEFAULT_GERRIT_EVENTS_BACKOFF_CEILING_PERIOD = "10"; // 10s backoff max ceiling period.
  public static final String DEFAULT_MAX_LOGGING_PERIOD_VALUE_SECS = "300"; // 300s or 5mins for atMost flogger logging period.
  public static final String DEFAULT_BASE_DIR = System.getProperty("java.io.tmpdir");


  /*****************************************************************************************
   * GITMS CONFIG
   * Any configuration specifically relating to GitMS and not Gerrit.
   */
  public static final String DEFAULT_MS_APPLICATION_PROPERTIES = "/opt/wandisco/git-multisite/replicator/properties/";

  /*****************************************************************************************
   * MISC CONFIG
   */
  // Information present in the Vanilla server config area - not our replicated application.properties file.
  public static final String ENC = "UTF-8"; // From BaseCommand



  /*****************************************************************************************
   * Expected errors from GitMS responses
   */
  // Possibly in a future release we want to move some of this checking to gerrit-gitms-shared and let gerrit/gitms use common validation
  public final static String REJECTED_OLD_REPOSITORY_STATE_PREFIX = "Reason : Client sent an update for ref:";
  public static final String REJECTED_CLIENT_SHOULD_UPDATE_SUFFIX = "Client should update to latest revision.";
  public static final String REJECTED_OTHER_REASON = "REJECTED_OTHER_REASON";
  public static final String CLIENT_ERROR = "CLIENT_ERROR";
}