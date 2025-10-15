package com.google.gerrit.server.replication.configuration;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.server.cache.SkipCacheReplication;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.AllProjectsNameProvider;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.config.AllUsersNameProvider;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.wandisco.gerrit.gitms.shared.exception.ConfigurationException;
import com.wandisco.gerrit.gitms.shared.properties.GitMsApplicationProperties;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.StringUtils;
import org.reflections.Reflections;
import org.reflections.scanners.FieldAnnotationsScanner;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.gerrit.server.replication.configuration.ReplicationConstants.DEFAULT_BASE_DIR;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.DEFAULT_EVENT_WORKER_POOL_SIZE;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.DEFAULT_GERRIT_EVENTS_BACKOFF_CEILING_PERIOD;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.DEFAULT_GERRIT_EVENTS_BACKOFF_INITIAL_PERIOD;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.DEFAULT_GERRIT_MAX_NUM_EVENTS_RETRIES;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.DEFAULT_MAX_EVENTS_PER_FILE;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.DEFAULT_MAX_LOGGING_PERIOD_VALUE_SECS;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.DEFAULT_MAX_SECS_TO_WAIT_BEFORE_PROPOSING_EVENTS;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.DEFAULT_MAX_SECS_TO_WAIT_ON_POLL_AND_READ;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.DEFAULT_MINUTES_SINCE_CHANGE_LAST_INDEXED_CHECK_PERIOD;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.DEFAULT_MS_APPLICATION_PROPERTIES;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.FAILED_DIR;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_CACHE_NAMES_NOT_TO_BE_RELOADED;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_EVENTS_BACKOFF_CEILING_PERIOD;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_EVENTS_BACKOFF_INITIAL_PERIOD;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_EVENT_BASEPATH;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_EVENT_TYPES_TO_BE_SKIPPED;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_MAX_LOGGING_PERIOD_SECS;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_MAX_MS_TO_WAIT_BEFORE_PROPOSING_EVENTS;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_MAX_NUM_EVENTS_RETRIES;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_MAX_SECS_TO_WAIT_ON_POLL_AND_READ;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_MINUTES_SINCE_CHANGE_LAST_INDEXED_CHECK_PERIOD;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_REPLICATED_CACHES_ALLOWED_TO_REPLICATE_VALUES;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_REPLICATED_CACHE_NAMES_TO_IGNORE;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_REPLICATED_EVENTS_BASEPATH;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_REPLICATED_EVENTS_ENABLED_RECEIVE;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_REPLICATED_EVENTS_ENABLED_SYNC_FILES;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_REPLICATED_EVENTS_INCOMING_ARE_GZIPPED;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_REPLICATED_EVENT_WORKER_POOL_IDLE_TIME_SECS;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_REPLICATED_EVENT_WORKER_POOL_SIZE;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_REPLICATED_EVENT_WORKER_POOL_MIN_SIZE;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_REPLICATED_EVENT_WORKER_CORE_PROJECTS;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.INCOMING_DIR;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.OUTGOING_DIR;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.REPLICATED_EVENTS_DIRECTORY_NAME;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_FILE_SYSTEM_RESOLUTION;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_REPLICATED_EVENTS_ENABLED_SEND;

/**
 * really we want to register this as a singleton in the guice bindings and let it be auto injected into any
 * class that needs it. but for now I am adding a getConfig call to the replicator main instance which can be used
 * by the others - and simply updated with injection later.
 */
@Singleton
public class ReplicatedConfiguration {

  /**
   * In the Daemon com.google.gerrit.pgm.Daemon#createCfgInjector() this module is used as a child module to
   * bind this class with the cfgInjector. Later on in the com.google.gerrit.pgm.Daemon#createSysInjector()
   * method we can then get an instance of this class from the cfgInjector to perform a check
   * whether replication is enabled or not.
   **/
  public static class Module extends LifecycleModule {
    @Override
    protected void configure() {
      bind(ReplicatedConfiguration.class);
    }
  }

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private String thisNodeIdentity = null;

  /*******************************************************************
   * Replicated events configuration
   */
  private boolean receiveIncomingStreamAPIEvents = true;
  private boolean receiveIncomingStreamAPIReplicatedEventsAndPublish = true;
  private boolean receiveReplicatedEventsEnabled = true;
  private boolean replicatedStreamEventsSend = true;
  private boolean localRepublishEnabled = false;
  private boolean incomingEventsAreGZipped = false; // on the landing node, is events file to be unzipped.
  private int maxNumberOfEventsBeforeProposing;
  private File replicatedEventsBaseDirectory = null;
  private File outgoingReplEventsDirectory = null;
  private File outgoingTemporaryReplEventsDirectory = null;
  private File incomingReplEventsDirectory = null;
  private File incomingTemporaryReplEventsDirectory = null;
  private File incomingFailedReplEventsDirectory = null;
  private List<String> eventSkipList = new ArrayList<>(); //Events that we skip replication for

  /*******************************************************************
   * Thread / workers wait times
   * Wait time variables
   */
  private long maxSecsToWaitBeforeProposingEvents;
  private long eventWorkerDelayPeriodMs;

  private Long fileSystemResolutionPeriodMs; // used as sleep period for event worker checks.
  private long minutesSinceChangeLastIndexedCheckPeriod;
  // New replicated event worker pool items.
  private int minNumberOfEventWorkerThreads;
  private int maxNumberOfEventWorkerThreads;
  private int maxIdlePeriodEventWorkerThreadInSeconds;

  private int indexMaxNumberBackoffRetries; // max number of backoff retries before failing an event group(file).
  private long indexBackoffInitialPeriodMs; // back off initial period that we start backoff doubling from per retry.
  private long indexBackoffCeilingPeriodMs; // max period in time of backoff doubling before, wait stays at this ceiling
  private List<Long> indexBackoffPeriods; // sequence of backoff periods in increasing value.

  private int loggingMaxPeriodValueMs;// get the value for logging something atMaxEvery Y ms.

  private Set<String> coreProjects = new TreeSet<>();

  // replicated cache config
  private final ArrayList<String> cacheNamesNotToReload = new ArrayList<>();

  // Setting allowedPutOperationCaches as an immutable collection
  private final List<String> defaultAllowedPutOperationCaches = List.of("web_sessions");
  private List<String> allowedPutOperationCaches = new ArrayList<>();

  // If a cache name appears in this list then we do not replicate that cache.
  // Caches will still appear in the watchedCaches list, however.
  private List<String> cachesToIgnore = new ArrayList<>();
  private final List<String> defaultIgnoreCaches = Arrays.asList("lfs_locks", "jira_server_project", "its_rules_project",
          "change_notes", "default_preferences", "pure_revert", "groups_external_persisted", "groups_byuuid_persisted",
          "persisted_projects", "git_tags", "oauth_tokens", "web_sessions");
  private static List<String> alwaysIgnoreCaches = new ArrayList<>();

  private static List<String> overrideIgnoreCaches = new ArrayList<>();


  private static final String GERRIT_BASE_PKG = "com.google.gerrit";
  private static final String GERRIT_PLUGINS_PKG = "com.googlesource.gerrit.plugins";


  private int workerNonCoreMaxThreads;
  private int workerNonCoreMinThreads;

  private FS.FileStoreAttributes fileStoreAttributes;

  private long dirResolutionNs;
  private long dirRacynessNs;
  private Duration incomingDirResolution;

  private String fileSystemResolutionConfig;
  private boolean syncFiles = false;
  private String defaultBaseDir;

  // Hold onto the AllUsers / AllProjects name, as we dont support, or allow for
  // the repository configuration to change mid flight.  So its perfectly fine to setup
  // the actual name once here and just continue to use it... This applies to core projects,
  // for scheduling, outgoing project naming for events, default repo name use for
  // DSM assignment etc. As this is used for DSM lookup it MUST only every be performed with
  // Gerrit offline.
  private AllUsersName allUsersName;
  private AllProjectsName allProjectsName;
  private Config gerritServerConfig;
  private AllowReplication allowReplication;
  private GitMsApplicationProperties gitMsApplicationProperties;

  /**
   * Construct this singleton class, and read the configuration.. Only thing forcing singleton
   * at moment is that it's only constructed within our static replicator instance, but we will move
   * to proper singleton injected bindings later!
   *
   * @throws ConfigInvalidException If an error occurs while parsing the config file.
   */
  @Inject
  public ReplicatedConfiguration(@GerritServerConfig Config config,
                                 AllProjectsNameProvider allProjectsNameProvider,
                                 AllUsersNameProvider allUsersNameProvider) throws ConfigInvalidException {

    this.gerritServerConfig = config;
    this.allProjectsName = allProjectsNameProvider.get();
    this.allUsersName = allUsersNameProvider.get();
    this.allowReplication = AllowReplication.getInstance(config);

    // we record if a failure happened when reading the configuration - we only read config
    // on startup of our singleton now - prevents any locking issues or required checking - also
    // we now throw if something wrong and stop the service starting!!
    readConfiguration(null);

    if(allowReplication.isReplicationEnabled()) {
      try {
        File applicationProperties = getApplicationPropsFile(getFileBasedGitConfig());
        this.gitMsApplicationProperties = new GitMsApplicationProperties(applicationProperties.getAbsolutePath());
      } catch (IOException | ConfigurationException e) {
        throw new ConfigInvalidException("Error loading the .gitconfig file. Unable to continue without valid GerritMS configuration.", e.getCause());
      }
    }
  }

  /**
   * TESTING ONLY:
   * construction used by testing to allow supplying of properties for controlling real behaviour
   * in classes without requiring mocks.
   *
   * @param testingProperties the properties used for the test
   */
  @VisibleForTesting
  public ReplicatedConfiguration(Properties testingProperties) throws ConfigInvalidException {
    readConfiguration(testingProperties);
  }

  /**
   * TESTING ONLY:
   * construction used by testing to allow supplying of properties for controlling real behaviour
   * in classes without requiring mocks.
   * This allows the additional configuration of a gerrit server configuration, this allows control
   * of all-users provider and other changes which stem from the main gerrit.config and not application.properties.
   *
   * @param testingProperties the properties used for the test
   */
  @VisibleForTesting
  public ReplicatedConfiguration(@GerritServerConfig Config config,
                                 AllUsersName allUsersName,
                                 AllProjectsName allProjectsName,
                                 Properties testingProperties ) throws ConfigInvalidException {
    this.gerritServerConfig = config;
    this.allUsersName = allUsersName;
    this.allProjectsName = allProjectsName;

    readConfiguration(testingProperties);
  }

  /**
   * Get GerritServerConfig
   * @return Instance of the Gerrit Server Configuration - N.B. not the replication configuration.
   */
  public Config getGerritServerConfig() {
    return gerritServerConfig;
  }


  public GitMsApplicationProperties getGitMsApplicationProperties() {
    return gitMsApplicationProperties;
  }

  /**
   * Get the instance of the ConfigureReplication which determines if Gerrit
   * replication is disabled or not. If disabled Gerrit will run in vanilla.
   *
   * @return instance of ConfigureReplication which can be used to determine
   * if replication is disabled or not
   */
  public AllowReplication getAllowReplication() {
    return allowReplication;
  }

  public int getMaxNumberOfEventWorkerThreads() {
    return maxNumberOfEventWorkerThreads;
  }

  public int getMinNumberOfEventWorkerThreads() {
    return minNumberOfEventWorkerThreads;
  }

  /**
   * Returns the total number of core worker threads, without the core threads affecting the count so we can work out
   * if we can schedule normal project event files.
   */
  public int getNumberOfNonCoreWorkerThreads() {
    return maxNumberOfEventWorkerThreads - coreProjects.size();
  }

  public int getMaxIdlePeriodEventWorkerThreadInSeconds() {
    return maxIdlePeriodEventWorkerThreadInSeconds;
  }

  public Set<String> getCoreProjects() {
    return coreProjects;
  }

  public boolean isCoreProject(final String projectName) {
    return coreProjects.contains(projectName);
  }
  public boolean isReplicationEnabled(){
    // If configureReplication is null then we are most likely in a test and replication should be false.
    if(allowReplication == null){
      return false;
    }
    return allowReplication.isReplicationEnabled();
  }

  public boolean isReceiveIncomingStreamAPIEvents() {
    return receiveIncomingStreamAPIEvents;
  }

  public boolean isReceiveIncomingStreamAPIReplicatedEventsAndPublish() {
    return receiveIncomingStreamAPIReplicatedEventsAndPublish;
  }

  public boolean isReceiveReplicatedEventsEnabled() {
    return receiveReplicatedEventsEnabled;
  }

  public boolean isReplicatedStreamEventsSendEnabled() {
    return replicatedStreamEventsSend;
  }

  public boolean isLocalRepublishEnabled() {
    return localRepublishEnabled;
  }

  public int getMaxIndexBackoffRetries() {
    return indexMaxNumberBackoffRetries;
  }

  public long getIndexBackoffInitialPeriodMs() {
    return indexBackoffInitialPeriodMs;
  }

  public long getIndexBackoffCeilingPeriodMs() {
    return indexBackoffCeilingPeriodMs;
  }

  public List<String> getEventSkipList() {
    return eventSkipList;
  }

  /**
   * index into our sequence of backoff periods to get the length of time a given failure should
   * be held off.
   *
   * @return A long value for the backoff period which represents the length of time a
   * given failure should be held off
   */
  public long getIndexBackoffPeriodMs(int numAttemptedRetries) throws IndexOutOfBoundsException {
    // we have calculated this up front for simplicity of working it out, and speedy access now.
    if (numAttemptedRetries <= 0) {
      throw new IndexOutOfBoundsException("Invalid index - param is 1 based matching our retry counter!");
    }
    if (numAttemptedRetries > indexMaxNumberBackoffRetries) {
      throw new IndexOutOfBoundsException(
          String.format("Requesting number: %s when max number of requested retries is %s.",
              numAttemptedRetries, indexMaxNumberBackoffRetries));
    }
    return indexBackoffPeriods.get(numAttemptedRetries - 1);
  }

  public int getLoggingMaxPeriodValueMs() {
    return loggingMaxPeriodValueMs;
  }

  /**
   * Finds the location of the application.properties on the system if no properties are supplied directly
   * to the method. Properties are supplied directly during testing.
   * <p>
   * Only called on startup - no real downside to coarse locking - no multi threading performance worries.
   *
   * @param suppliedProperties : Supplied properties to be passed to the method during testing.
   * @throws ConfigInvalidException If an error occurs while parsing the config file.
   */
  private void readConfiguration(Properties suppliedProperties) throws ConfigInvalidException {

    try {
      //Supplied props null and replication disabled
      if (suppliedProperties == null && !allowReplication.isReplicationEnabled()) {
        return;
      }

      // Testing only route, e.g. properties have been supplied for testing.
      // We allow supplied properties to be given to us so that we can create dummy config information
      // on the fly for tests easily without complicated mocking.
      if (suppliedProperties != null) {

        readAndDefaultConfigurationFromProperties(suppliedProperties);

        // For tests an instance of ConfigureReplication will not have been created. GerritServerConfig
        // is only injected via guice in the main constructor also. Both of these will be null during testing.
        // There are some tests where we need to access these objects therefore we create instances of them
        // here when they are both null.
        if(allowReplication == null && gerritServerConfig == null){
          gerritServerConfig = new Config();
          allowReplication = AllowReplication.getInstance(gerritServerConfig);
        }
        return;
      }

      // Get a FileBasedConfig instance of the .gitconfig located on the filesystem.
      FileBasedConfig config = getFileBasedGitConfig();

      // Use the .gitconfig FileBasedConfig instance to determine where the GitMS application.properties are located.
      Properties loadedGitMSApplicationProperties = findAndLoadGitMSApplicationProperties(config);
      readAndDefaultConfigurationFromProperties(loadedGitMSApplicationProperties);
    } catch (IOException e) {
      throw new ConfigInvalidException("Error loading the .gitconfig file. Unable to continue without valid GerritMS configuration.", e.getCause());
    }
  }

  // If there is a hard configuration value in the Replicated configuration for gerrit, we use it as an override for the file system
  // accuracy of this location - and it indicates the value of how long we need to wait to be able to trust the folder modification time.
  // If the configuration isn't specified, we will try to get the best default value for this file system location and file system type.
  // Note the wait is in ms, as the incoming/outgoing workers have a check period of 500ms by default, any value less than 500ms is fine by default,
  // if larger than 500ms ( default ), we will output warning that the worker delay period should be increased.
  public void attemptGetIncomingEventsDirectoryFilesystemAccuracy(){

    if ( fileSystemResolutionPeriodMs != null ){
      // someone has already set this up - either via config override, or default for the system, lets leave it alone!
      return;
    }

    // If there is no directory yet, we can do nothing, so just exit.
    if ( incomingReplEventsDirectory == null ){
      return;
    }
    if ( !incomingReplEventsDirectory.exists()){
      return;
    }

    // otherwise let's get the file system resolution, and racyness for this filesystem path, as the accuracy and reliance
    // of the last modified time depends on this location and file system type.
    fileStoreAttributes = FS.FileStoreAttributes.get(incomingReplEventsDirectory.toPath());

    dirResolutionNs = fileStoreAttributes.getFsTimestampResolution().toNanos();
    dirRacynessNs = fileStoreAttributes.getMinimalRacyInterval().toNanos();
    incomingDirResolution = Duration.ofNanos(dirResolutionNs + dirRacynessNs);

    fileSystemResolutionPeriodMs = incomingDirResolution.toMillis();
  }

  /**
   * Get a FileBasedConfig instance of the .gitconfig located on
   * the filesystem. This is acquired by making a call to getGitConfig
   * which performs a lookup of a property and system env var to check
   * what GIT_CONFIG is set to.
   * @return a FileBasedConfig instance of the .gitconfig file.
   * @throws IOException If an error occurs reading the config file.
   */
  private FileBasedConfig getFileBasedGitConfig() throws IOException {
    // Used for internal integration tests at WANdisco
    String gitConfigLoc = getGitConfig();

    FileBasedConfig config =
        new FileBasedConfig(new File(gitConfigLoc), FS.DETECTED);
    try {
      config.load();
    } catch (ConfigInvalidException e) {
      // Configuration file is not in the valid format, throw exception back.
      throw new IOException(e);
    }
    return config;
  }

  /**
   * Finds where the .gitconfig is located in the system based on
   * either a system property or environment variable.
   * @return : A String for the value of GIT_CONFIG. If GIT_CONFIG is not set as an
   *            env or system property then a default property is used.
   */
  private String getGitConfig() {
    String gitConfigLoc =
        System.getProperty("GIT_CONFIG", System.getenv("GIT_CONFIG"));
    if (Strings.isNullOrEmpty(gitConfigLoc)
        && System.getenv("GIT_CONFIG") == null) {
      gitConfigLoc = System.getProperty("user.home") + "/.gitconfig";
    }
    return gitConfigLoc;
  }


  /**
   * Gets where the GitMS application.properties file is located
   * @param config : A FileBasedConfig instance of the file .gitconfig
   * @throws FileNotFoundException If config backing file is not found.
   * @throws ConfigInvalidException If some other IOException is caught while parsing the config.
   */
  private Properties findAndLoadGitMSApplicationProperties(FileBasedConfig config) throws FileNotFoundException, ConfigInvalidException {
    File applicationProperties = getApplicationPropsFile(config);

    Properties gitmsApplicationProperties = new Properties();
    try (FileInputStream propsFile = new FileInputStream(applicationProperties)) {
      gitmsApplicationProperties.load(propsFile);
    } catch (IOException e) {
        // we cant continue with invalid properties file - throw!
        throw new ConfigInvalidException("Failed to load/read GerritMS properties file. Unable to continue with invalid GerritMS replicated properties file: " +
            applicationProperties.getAbsolutePath(), e.getCause());
    }
    return gitmsApplicationProperties;
  }


  /**
   * Reads the value of gitmsconfig in the core section of the .gitconfig file
   * Performs some validation checking on the path to ensure that the application.properties
   * exists at that path.
   * @param config : A FileBasedConfig instance of the file .gitconfig
   * @return : A File instance is returned using the location of the gitmsconfig value in the .gitconfig
   * @throws FileNotFoundException If config file is not found.
   */
  private File getApplicationPropsFile(FileBasedConfig config) throws FileNotFoundException {
    File applicationProperties;
    try {
      String appProperties = config.getString("core", null, "gitmsconfig");
      applicationProperties = new File(appProperties);
      // GER-662 NPE thrown if GerritMS is started without a reference to a
      // valid GitMS application.properties file.
    } catch (NullPointerException exception) {
      throw new FileNotFoundException(
          "GerritMS cannot continue without a valid GitMS application.properties file referenced in its .gitconfig file." +  exception);
    }

    if (!applicationProperties.exists() || !applicationProperties.canRead()) {
      applicationProperties = new File(DEFAULT_MS_APPLICATION_PROPERTIES, "application.properties");
    }

    if (!applicationProperties.exists() || !applicationProperties.canRead()) {
      defaultBaseDir = DEFAULT_BASE_DIR + File.separator
          + REPLICATED_EVENTS_DIRECTORY_NAME;
    }
    return applicationProperties;
  }


  /**
   * Reads the properties instance given and uses default values for setting the properties if
   * the properties are not set as part of the Properties instance.
   *
   * @param props : An Properties instance representing properties set via the GitMS application.properties
   *              or supplied properties given during testing.
   */
  private void readAndDefaultConfigurationFromProperties(Properties props) throws ConfigInvalidException {
    syncFiles = Boolean.parseBoolean(props.getProperty(
        GERRIT_REPLICATED_EVENTS_ENABLED_SYNC_FILES, "false"));

    // The user can set a different path specific for the replicated
    // events. If it's not there
    // then the usual GERRIT_EVENT_BASEPATH will be taken.
    defaultBaseDir = props.getProperty(GERRIT_REPLICATED_EVENTS_BASEPATH);
    if (defaultBaseDir == null) {
      defaultBaseDir = props.getProperty(GERRIT_EVENT_BASEPATH);
      if (defaultBaseDir == null) {
        defaultBaseDir = DEFAULT_BASE_DIR;
      }
      defaultBaseDir += File.separator + REPLICATED_EVENTS_DIRECTORY_NAME;
    }

    incomingEventsAreGZipped = Boolean.parseBoolean(props.getProperty(
        GERRIT_REPLICATED_EVENTS_INCOMING_ARE_GZIPPED, "false"));

    // Getting the node identity that will be used to determine the
    // originating node for each instance.
    thisNodeIdentity = props.getProperty("node.id");

    // Configurable for the maximum amount of events allowed in the
    // outgoing events file before proposing.
    maxNumberOfEventsBeforeProposing = Integer.parseInt(removeLFromLong(
        props.getProperty(GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING,
            DEFAULT_MAX_EVENTS_PER_FILE)));

    // Configurable for the maximum amount of seconds to wait before
    // proposing events in the outgoing events file.
    maxSecsToWaitBeforeProposingEvents =
        Long.parseLong(sanitizeLongValueAndConvertToMilliseconds(props
            .getProperty(GERRIT_MAX_MS_TO_WAIT_BEFORE_PROPOSING_EVENTS,
                DEFAULT_MAX_SECS_TO_WAIT_BEFORE_PROPOSING_EVENTS)));

    // Configurable for the wait time for threads waiting on an event to
    // be received and published.
    eventWorkerDelayPeriodMs =
        Long.parseLong(sanitizeLongValueAndConvertToMilliseconds(
            props.getProperty(GERRIT_MAX_SECS_TO_WAIT_ON_POLL_AND_READ,
                DEFAULT_MAX_SECS_TO_WAIT_ON_POLL_AND_READ)));

    // max number of backoff retries before failing an event group(file).
    indexMaxNumberBackoffRetries =
        Math.max(1, Integer.parseInt(removeLFromLong(
            props.getProperty(GERRIT_MAX_NUM_EVENTS_RETRIES,
                DEFAULT_GERRIT_MAX_NUM_EVENTS_RETRIES))));

    // back off initial period that we start backoff doubling from per retry
    indexBackoffInitialPeriodMs =
        Long.parseLong(sanitizeLongValueAndConvertToMilliseconds(props
            .getProperty(GERRIT_EVENTS_BACKOFF_INITIAL_PERIOD,
                DEFAULT_GERRIT_EVENTS_BACKOFF_INITIAL_PERIOD)));

    // max period in time of backoff doubling before, wait stays at this ceiling
    indexBackoffCeilingPeriodMs =
        Long.parseLong(sanitizeLongValueAndConvertToMilliseconds(props
            .getProperty(GERRIT_EVENTS_BACKOFF_CEILING_PERIOD,
                DEFAULT_GERRIT_EVENTS_BACKOFF_CEILING_PERIOD)));

    // Read in a comma separated list of events that should be skipped. Arrays.asList returns
    // a fixed size list and cannot be mutated so using an ArrayList here instead that takes a default list with an
    // initial size in order to later on have the ability to use addAll() to join the default list
    // with the events in property file list.
    final List<String> defaultTypesToSkip
        = new ArrayList<>(Arrays.asList("RefReplicatedEvent", "RefReplicationDoneEvent"));
    eventSkipList = getPropertyAsList(props, GERRIT_EVENT_TYPES_TO_BE_SKIPPED, defaultTypesToSkip);
    //Setting all to lowercase so user doesn't have to worry about correct casing.
    replaceAllAsLowerCase(eventSkipList);

    // Now we have the index backoff information - lets calculate the sequence of backoffs.
    indexBackoffPeriods = new ArrayList<>(indexMaxNumberBackoffRetries);

    for (int index = 0; index < indexMaxNumberBackoffRetries; index++) {
      indexBackoffPeriods.add(Math.min(indexBackoffCeilingPeriodMs, (long) (indexBackoffInitialPeriodMs * Math.pow(2, index))));
    }

    // get the value for logging something atMaxEvery Y Timeunit.  Note we take the value in seconds,
    // but we allow the use of 0.5 etc in our props file.  We convert to MS then for each of knowing which timeunit
    // to use.
    loggingMaxPeriodValueMs = Integer.parseInt(sanitizeLongValueAndConvertToMilliseconds(
        props.getProperty(GERRIT_MAX_LOGGING_PERIOD_SECS, DEFAULT_MAX_LOGGING_PERIOD_VALUE_SECS)));


    // Configurable for the time period to check since the change was last
    // indexed, The change will need reindexed
    // if it has been in the queue more than the specified check period.
    // Default is 1 hour.
    minutesSinceChangeLastIndexedCheckPeriod =
        TimeUnit.MINUTES.toMillis(Long.parseLong(props.getProperty(
            GERRIT_MINUTES_SINCE_CHANGE_LAST_INDEXED_CHECK_PERIOD,
            DEFAULT_MINUTES_SINCE_CHANGE_LAST_INDEXED_CHECK_PERIOD)));


    // Replicated CACHE properties
    try {
      String[] tempCacheNames =
          props.getProperty(GERRIT_CACHE_NAMES_NOT_TO_BE_RELOADED,
              "invalid_cache_name").split(",", -1);
      for (String s : tempCacheNames) {
        String st = s.trim();
        if (st.length() > 0) {
          cacheNamesNotToReload.add(st);
        }
      }
    } catch (Exception e) {
      // we can continue with some defaults - just record this problem.
      throw new ConfigInvalidException("Not able to load cache properties", e.getCause());
    }

    // The only cache (that performs puts) we fully support at present is the web_sessions cache
    // If another cache is added via this flag, then it may cause issues as we don't fully support it.
    // If this parameter is set with a value of something else, such as "none", then it will turn off
    // the cache put replication for all caches, e.g. gerrit.replicated.caches.allowed.to.replicate.values=none
    allowedPutOperationCaches = getPropertyAsList(props, GERRIT_REPLICATED_CACHES_ALLOWED_TO_REPLICATE_VALUES,
            defaultAllowedPutOperationCaches);

    // The default cache list is populated by looking for all the caches in the system that have the
    // @SkipCacheReplication annotation. The list can then be added to if there are any caches that are currently
    // set with the @ReplicatedCache annotation, but you want to add here. Setting the property with the name will add
    // a cache that is currently replicated here. We check two packages, the base package and the gerrit plugins package.
    alwaysIgnoreCaches = Stream.of(getSkippedCachesByAnnotation(GERRIT_BASE_PKG), getSkippedCachesByAnnotation(GERRIT_PLUGINS_PKG))
            .flatMap(Collection::stream).collect(Collectors.toList());

    // When a user specifies the property gerrit.replicated.cache.names.to.ignore in the application.properties for GitMS
    // the getPropertyAsList method is used to split apart the comma separated list supplied by the property and add each item found to
    // a combined list containing all the cache names already ignored due to being annotated with @SkipCacheReplication
    // and an overrideList comprising, of either a default list of plugin caches OR an overridden list of properties specified
    // by the property: gerrit.replicated.cache.names.to.ignore

    // At present, we have 3 plugins, each with their own caches that are marked with @ReplicatedCache. We still want to ignore
    // these caches for now by default.
    // The property 'gerrit.replicated.cache.names.to.ignore' is an override.
    // If this property is set, it needs to be given *ALL* the cache names to ignore
    //
    // Examples:
    // * If we want to ignore all of these then don't set gerrit.replicated.cache.names.to.ignore or set empty
    //   - Overall the caches to ignore will be caches annotated with @SkipCacheReplication + defaultIgnoreCaches
    //
    // * If we want to only ignore the alwaysIgnoreCaches, i.e only the caches annotated with @SkipCacheReplication
    //   and start replicating ALL the defaultIgnoreCaches then a value of "none" can be supplied to the property,
    //   e.g. gerrit.replicated.cache.names.to.ignore = none
    //
    // * if we want to start replicating lfs_locks, then we need to provide the override property that contains
    //   ALL the defaultIgnoreCaches minus lfs_locks
    //   e.g.
    //   - gerrit.replicated.cache.names.to.ignore = "jira_server_project, its_rules_project,
    //             change_notes, default_preferences, pure_revert, groups_external_persisted, groups_byuuid_persisted,
    //             persisted_projects, git_tags, oauth_tokens, web_sessions"
    //   - Overall the caches to ignore will be caches annotated with @SkipCacheReplication + overrideIgnoreCaches
    //
    // * IMPORTANT: gerrit.replicated.cache.names.to.ignore is an override. If we want to start ignoring a replicated
    //   cache marked with @ReplicatedCache, e.g. accounts then we need to provide the override property that specifies
    //   accounts as well as all the other defaultIgnoreCaches if we want to keep these being as ignored.
    //   For example:
    //   - gerrit.replicated.cache.names.to.ignore = "accounts, lfs_locks, jira_server_project, its_rules_project,
    //            change_notes, default_preferences, pure_revert, groups_external_persisted, groups_byuuid_persisted,
    //            persisted_projects, git_tags, oauth_tokens, web_sessions"

    overrideIgnoreCaches = getPropertyAsList(props, GERRIT_REPLICATED_CACHE_NAMES_TO_IGNORE, defaultIgnoreCaches);
    cachesToIgnore.addAll ( alwaysIgnoreCaches );
    cachesToIgnore.addAll ( overrideIgnoreCaches );

    // If set to false we'll only replicate cache and index events.  see GER-1946
    replicatedStreamEventsSend =  Boolean.parseBoolean(props.getProperty(GERRIT_REPLICATED_EVENTS_ENABLED_SEND, "true"));

    // Used by Gerrit to decide whether to read the incoming server events or not.
    receiveIncomingStreamAPIEvents =
            Boolean.parseBoolean(props.getProperty(GERRIT_REPLICATED_EVENTS_ENABLED_RECEIVE, "true"));
    // This is the decision to publish replicated events to our local event stream
    receiveIncomingStreamAPIReplicatedEventsAndPublish =
            Boolean.parseBoolean(props.getProperty(GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL, "true"));

    receiveReplicatedEventsEnabled = receiveIncomingStreamAPIEvents || receiveIncomingStreamAPIReplicatedEventsAndPublish;

    workerNonCoreMaxThreads = Integer.parseInt(
            props.getProperty(GERRIT_REPLICATED_EVENT_WORKER_POOL_SIZE, DEFAULT_EVENT_WORKER_POOL_SIZE));

    if ( workerNonCoreMaxThreads < 1 ) {
      workerNonCoreMaxThreads = Integer.parseInt(DEFAULT_EVENT_WORKER_POOL_SIZE);
    }

    // Now add on the amount of core threads, so that the max number of threads is the max thread pool size
    // get the real name for special projects.
    coreProjects.add(getAllProjectsName());
    coreProjects.add(getAllUsersName());

    // get any additional core projects - that we consider as high priority projects ( normally just all-users, all-projects, but we do allow
    // adding some key projects in addition.  note adding them here will take any events that they have for these projects in preference to other
    // projects even if they other projects have newer event files.
    try {
      String[] coreProjectNames =
          props.getProperty(GERRIT_REPLICATED_EVENT_WORKER_CORE_PROJECTS, "").split(",", -1);
      for (String s : coreProjectNames) {
        String st = s.trim();
        // if we get a valid core project name, and the coreprojects list doesn't already contain this name,
        // then add it. ( this avoids any duplicates if someone adds all-users etc again ).
        if (!st.isEmpty() && !coreProjects.contains(st)) {
          coreProjects.add(st);
        }
      }
    } catch (Exception e) {
      // we can continue with some defaults - just record this problem.
      throw new ConfigInvalidException("Not able to load additional coreprojects from application.properties", e.getCause());
    }
    // allow addition of extra core project items, so that we try to always schedule work for them ( this is usually considered to be high
    // priority repositories, where even though they may have some event files behind others, they are still prioritized for workload, just like
    // we do for all-users, all-projects above.
    // Check happens here in 2 steps.
    // 1) If no prop is specified, default to the max size already in the system.
    // 2) If value specified, make sure its greater than min size of 1, and <= max size ( worker base num threads ) note this is without
    // the notion of core projects yet so this isn't the totals.
    workerNonCoreMinThreads = Integer.parseInt(
        props.getProperty(GERRIT_REPLICATED_EVENT_WORKER_POOL_MIN_SIZE, Integer.toString(workerNonCoreMaxThreads)));

    if (workerNonCoreMinThreads < 1) {
      workerNonCoreMinThreads = workerNonCoreMaxThreads;
    }

    if (workerNonCoreMinThreads > workerNonCoreMaxThreads) {
      workerNonCoreMinThreads = workerNonCoreMaxThreads;
    }

    // Total number is the core project size, plus number of normal project threads. Note we add the core project count onto
    // the number of threads.  So if you want 20 project threads, it will be 22 in size, as it adds on all-projects/all-users which should
    // be considered as always processing events.  You can add other projects to the core, but this will also bump the count.
    maxNumberOfEventWorkerThreads = workerNonCoreMaxThreads + coreProjects.size();
    minNumberOfEventWorkerThreads = workerNonCoreMinThreads + coreProjects.size();


    // Default event worker idle period is 60X5 for 5mins.  Don't want a heavy thread churn.
    maxIdlePeriodEventWorkerThreadInSeconds = Integer.parseInt(
        props.getProperty(GERRIT_REPLICATED_EVENT_WORKER_POOL_IDLE_TIME_SECS, "300"));

    // Setup all events directories now we know the base directory.
    replicatedEventsBaseDirectory = new File(defaultBaseDir);
    outgoingReplEventsDirectory = new File(replicatedEventsBaseDirectory, OUTGOING_DIR);
    outgoingTemporaryReplEventsDirectory = new File(outgoingReplEventsDirectory, "tmp");

    incomingReplEventsDirectory = new File(replicatedEventsBaseDirectory, INCOMING_DIR);
    incomingFailedReplEventsDirectory = new File(incomingReplEventsDirectory, FAILED_DIR);
    incomingTemporaryReplEventsDirectory = new File(incomingReplEventsDirectory, "tmp");

    // Lets setup the file system resolution / racyness value for this file system / directory combo.
    fileSystemResolutionConfig = props.getProperty(GERRIT_FILE_SYSTEM_RESOLUTION);

    // only if the value is specified - do we take its value - otherwise leave this as null, to be filled in as a better default
    // later.
    if ( !Strings.isNullOrEmpty(fileSystemResolutionConfig)) {
      // period of time you need to wait to guarantee an update of the file system.
      // This is used to workaround file system accuracy issues, note certain JDKs have only 1second accuracy.
      fileSystemResolutionPeriodMs = Long.parseLong(fileSystemResolutionConfig);
    }else {
      // the directory may be present - lets go and find out its accuracy.
      attemptGetIncomingEventsDirectoryFilesystemAccuracy();
    }
  }

  /**
   * Get the dynamic name for All-Projects
   *
   * @return String value of the name of the All-Projects core project
   */
  public String getAllProjectsName() {
    return allProjectsName != null ? allProjectsName.get() : AllProjectsNameProvider.DEFAULT;
  }

  /**
   * Get the dynamic name for All-Users
   *
   * @return String value of the name of the All-Users core project
   */
  public String getAllUsersName() {
    return allUsersName != null ? allUsersName.get() : AllUsersNameProvider.DEFAULT;
  }

  public String getThisNodeIdentity() {
    return thisNodeIdentity;
  }

  public int getMaxNumberOfEventsBeforeProposing() {
    return maxNumberOfEventsBeforeProposing;
  }

  public long getMaxSecsToWaitBeforeProposingEvents() {
    return maxSecsToWaitBeforeProposingEvents;
  }

  public long getEventWorkerDelayPeriodMs() {
    return eventWorkerDelayPeriodMs;
  }

  public File getReplicatedEventsBaseDirectory() {
    return replicatedEventsBaseDirectory;
  }

  public File getOutgoingReplEventsDirectory() {
    return outgoingReplEventsDirectory;
  }

  public File getOutgoingTemporaryReplEventsDirectory() {
    return outgoingTemporaryReplEventsDirectory;
  }
  public File getIncomingReplEventsDirectory() {
    return incomingReplEventsDirectory;
  }

  public File getIncomingTemporaryReplEventsDirectory() {
    return incomingTemporaryReplEventsDirectory;
  }

  public long getFileSystemResolutionPeriodMs(){
    return fileSystemResolutionPeriodMs;
  }

  public File getIncomingFailedReplEventsDirectory() {
    return incomingFailedReplEventsDirectory;
  }

  public boolean isIncomingEventsAreGZipped() {
    return incomingEventsAreGZipped;
  }

  public ArrayList<String> getCacheNamesNotToReload() {
    return cacheNamesNotToReload;
  }

  public boolean isSyncFiles() {
    return syncFiles;
  }

  public String getDefaultBaseDir() {
    return defaultBaseDir;
  }

  public final boolean isCacheToBeReloaded(String cacheName) {
    return !cacheNamesNotToReload.contains(cacheName);
  }

  public final boolean isCacheToBeIgnored(final String cacheName) {
    return cachesToIgnore.contains(cacheName);
  }

  public final boolean isCacheAllowedToPerformPuts(final String cacheName) {
    return allowedPutOperationCaches.contains(cacheName);
  }

  /**
   * Using the Reflections library, looks at all classes in the system. Any class annotated with
   * '@SkipCacheReplication' is a cache that we want to ignore.
   * @return A list structure of all the caches in the system that have been annotated with the @SkipCacheReplication
   * annotation.
   */
  public List<String> getSkippedCachesByAnnotation(final String pkg) {
    Set<String> skippedCaches = new TreeSet<>();

    Reflections reflections = new Reflections(pkg, new FieldAnnotationsScanner());
    Set<Field> fields = reflections.getFieldsAnnotatedWith(SkipCacheReplication.class);

    fields.stream()
            .peek(field -> field.setAccessible(true))
            .map(field -> {
              try {
                return (String) field.get(0);
              } catch (IllegalAccessException e) {
                return null;
              }
            })
            .filter(Objects::nonNull) // remove any null values that may have occurred due to an IllegalAccessException being thrown
            .forEach(skippedCaches::add);

    return new ArrayList<>(skippedCaches);
  }

  /**
   * Returns the number of minutes since the change was last indexed
   *
   * @return Returns number of Minutes since the last Indexed check period.
   */
  public long getMinutesSinceChangeLastIndexedCheckPeriod() {
    return minutesSinceChangeLastIndexedCheckPeriod;
  }

  private static String removeLFromLong(String property) {
    if (property != null && property.length() > 1 && (property.endsWith("L") || property.endsWith("l"))) {
      return property.substring(0, property.length() - 1);
    }
    return property;
  }


  private static String convertToMs(final String property){
    return Long.toString(Long.parseLong(property) * 1000);
  }

  /**
   * Using milliseconds so that the user can specify sub second periods
   *
   * @param property the string value taken from the properties file
   * @return the string value in milliseconds
   */
  public static String sanitizeLongValueAndConvertToMilliseconds(final String property) {
    //String the L or l off the property value, e.g 5L -> 5
    final String sanitizedProp = removeLFromLong(property);

    if (sanitizedProp.contains(".")) {
      double x = Double.parseDouble(sanitizedProp) * 1000;
      long y = (long) x;
      return Long.toString(y);
    }
    //With the L or l stripped off, we need to convert to milliseconds now, e.g 5 -> 5000
    return convertToMs(sanitizedProp);
  }


    /**
   * Version of getProperty that returns a property that contains a comma separated
   * list of values as a list.
   * e.g property=value1, value2, value3
   * will return a List with value1, value2, value3 as entries in the list.
   * @param propertyName This is the name in the GitMS application.properties to look for.
   * @param defaultPropEntriesToSkip The default entries for the property to skip.
   * @return A list with all the event types to be skipped.
   */
  public List<String> getPropertyAsList(final Properties properties, final String propertyName,
                                        final List<String> defaultPropEntriesToSkip) {
    //The call to getProperty will return a single string which could contain multiple values
    //that need to be split into individual values.
    final String strValue = properties.getProperty(propertyName);

    if(StringUtils.isEmptyOrNull(strValue)){
      return new ArrayList<>(defaultPropEntriesToSkip);
    }

    // This is a special case whereby if we supply none as the entry for the property, then we ignore nothing.
    if(strValue.equalsIgnoreCase("none")){
      return new ArrayList<>();
    }

    List<String> propEntriesToSkip = Arrays.asList(strValue.split("\\s*,\\s*"));

    if ( propEntriesToSkip.isEmpty() ){
      return new ArrayList<>(defaultPropEntriesToSkip);
    }
    return propEntriesToSkip;
  }

  //Utility method to replace all in a list of strings with lowercase string
  public static void replaceAllAsLowerCase(List<String> eventsToBeSkipped) {
    ListIterator<String> iterator = eventsToBeSkipped.listIterator();
    while (iterator.hasNext()) {
      iterator.set(iterator.next().toLowerCase());
    }
  }

  /**
   * Logging is not initialized by the time these settings are first configured in readAndDefaultConfigurationFromProperties.
   * So we must log them from the ReplicatedEventsCoordinatorImpl at a later time during the LifeCycle start.
   */
  public void logReplicatedConfigurationSettings(){
    logger.atInfo().log("Replicated Event failure backoff periods: %s", indexBackoffPeriods);
    logger.atInfo().log("Property %s=%s", GERRIT_REPLICATED_EVENTS_BASEPATH, defaultBaseDir);
    logger.atFine().log("Always Ignore Caches: (SkipReplication annotated items): %s ", alwaysIgnoreCaches);
    logger.atFine().log("Default Ignore Caches: %s ", defaultIgnoreCaches);
    logger.atFine().log("Override Ignore Caches: (Caches ignored by property value): %s ", overrideIgnoreCaches);
    logger.atInfo().log("RE Replicated events are enabled, send: %s, receive: %s", replicatedStreamEventsSend, receiveReplicatedEventsEnabled);

    if ( workerNonCoreMaxThreads < 1 ) {
      logger.atSevere().log("Invalid number of worker threads indicated which is less than 1 - indicating default number %s", DEFAULT_EVENT_WORKER_POOL_SIZE);
    }

    if (workerNonCoreMinThreads < 1) {
      logger.atSevere().log("Invalid min number of worker threads indicated which is less than 1 - indicating min=max : %d", workerNonCoreMaxThreads);
    }

    if (workerNonCoreMinThreads > workerNonCoreMaxThreads) {
      logger.atSevere().log("Invalid min number of worker threads indicated, being larger than the max size.  As such setting min=max : %d", workerNonCoreMaxThreads);
    }

    logger.atInfo().log("RE Replicated events are to be processed using min worker pool size: %s, max worker pool size: %s maxIdlePeriodSecs: %s.",
            minNumberOfEventWorkerThreads, maxNumberOfEventWorkerThreads, maxIdlePeriodEventWorkerThreadInSeconds);

    logger.atInfo().log("RE Replicated events: receive=%s, original=%s, send=%s ",
            receiveIncomingStreamAPIEvents, receiveIncomingStreamAPIReplicatedEventsAndPublish, replicatedStreamEventsSend);


    if ( !Strings.isNullOrEmpty(fileSystemResolutionConfig)) {
      if (fileSystemResolutionPeriodMs > eventWorkerDelayPeriodMs) {
        logger.atWarning().log("FileSystemResolutionPeriodMs for the incoming events directory of %s ms is larger than the event worker poll period %s ms. " +
                        "This max period for polling configuration value = gerrit.max.secs.to.wait.on.poll.and.read should be increased above this value to account for slower file system resolution on this system.",
                fileSystemResolutionPeriodMs, eventWorkerDelayPeriodMs);
      }
    }

    logger.atInfo().log(
            String.format("Incoming Events Directory has file system resolution of : %s ns, racyness %s ns so modification trust period is %s ms.",
                    dirResolutionNs, dirRacynessNs, incomingDirResolution.toMillis()));

    if ( fileSystemResolutionPeriodMs > eventWorkerDelayPeriodMs ){
      logger.atWarning().log("FileSystemResolutionPeriodMs for the incoming events directory of %s ms is larger than the event worker poll period %s ms. " +
                      "This max period for polling configuration value = gerrit.max.secs.to.wait.on.poll.and.read should be increased above this value to account for slower file system resolution on this system.",
              fileSystemResolutionPeriodMs, eventWorkerDelayPeriodMs);
    }

  }


  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("ReplicatedConfiguration{");
    sb.append("replicationEnabled=").append(allowReplication.isReplicationEnabled());
    sb.append(", thisNodeIdentity='").append(getThisNodeIdentity()).append('\'');
    sb.append(", maxNumberOfEventsBeforeProposing=").append(getMaxNumberOfEventsBeforeProposing());
    sb.append(", maxSecsToWaitBeforeProposingEvents=").append(getMaxSecsToWaitBeforeProposingEvents());
    sb.append(", eventWorkerDelayPeriodMs=").append(getEventWorkerDelayPeriodMs());
    sb.append(", minutesSinceChangeLastIndexedCheckPeriod=").append(getMinutesSinceChangeLastIndexedCheckPeriod());
    sb.append(", indexMaxNumberBackoffRetries=").append(getMaxIndexBackoffRetries());
    sb.append(", indexBackoffInitialPeriodMs=").append(getIndexBackoffInitialPeriodMs());
    sb.append(", indexBackoffCeilingPeriodMs=").append(getIndexBackoffCeilingPeriodMs());
    sb.append(", indexBackoffPeriods=").append(indexBackoffPeriods);
    sb.append(", replicatedEventsBaseDirectory=").append(getReplicatedEventsBaseDirectory());
    sb.append(", outgoingReplEventsDirectory=").append(getOutgoingReplEventsDirectory());
    sb.append(", incomingReplEventsDirectory=").append(getIncomingReplEventsDirectory());
    sb.append(", incomingFailedReplEventsDirectory=").append(getIncomingFailedReplEventsDirectory());
    sb.append(", replicatedEventsReceive=").append(receiveIncomingStreamAPIEvents);
    sb.append(", replicatedEventsReplicateOriginalEvents=").append(receiveIncomingStreamAPIReplicatedEventsAndPublish);
    sb.append(", receiveReplicatedEventsEnabled=").append(receiveReplicatedEventsEnabled);
    sb.append(", replicatedStreamEventsSend=").append(replicatedStreamEventsSend);
    sb.append(", localRepublishEnabled=").append(localRepublishEnabled);
    sb.append(", incomingEventsAreGZipped=").append(incomingEventsAreGZipped);
    sb.append(", maxNumberOfEventWorkerThreads=").append(getMaxNumberOfEventWorkerThreads());
    sb.append(", minNumberOfEventWorkerThreads=").append(getMinNumberOfEventWorkerThreads());
    sb.append(", maxIdlePeriodEventWorkerThreadInSeconds=").append(getMaxIdlePeriodEventWorkerThreadInSeconds());
    sb.append(", coreProjects=").append(getCoreProjects());
    sb.append(", cacheNamesNotToReload=").append(getCacheNamesNotToReload());
    sb.append('}');
    return sb.toString();
  }
}
