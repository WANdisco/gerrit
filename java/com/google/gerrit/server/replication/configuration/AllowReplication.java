package com.google.gerrit.server.replication.configuration;

import com.google.common.base.Strings;
import com.google.gerrit.server.replication.SingletonEnforcement;
import com.google.inject.Singleton;
import org.eclipse.jgit.lib.Config;

import static com.google.gerrit.server.replication.configuration.ReplicationConstants.REPLICATION_DISABLED;

/**
 * AllowReplication is a class which controls whether the product should be considered a replicated
 * version of gerrit or not.
 *
 * Note this has 3 main control methods, although only 2 can be used by customers,
 * the product default is purely internal.
 *
 *
 * 1) Product Default flag: gerritmsReplicationDisabledProductDefault
 *  This is a static flag, to ensure product behaviour can be setup before any guice
 *  bindings happen, or any configuration objects created.  This can only be called
 *  very early in the lifecycle.
 *
 *  For example on use - Daemon -> AllowReplication.setReplicationDisabled_DefaultBehaviour
 *
 *
 * 2) GerritServerConfig -> wandisco.gerritmsReplicationDisabled
 *
 * <p>Used as the method of controlling our product default behaviour,
 * either by an entrypoint, or by integration tests.
 *
 * gerritmsReplicationDisabled=true means that GerritMs replication should be disabled,
 *   which is to be the default behaviour across entry points.
 *   It was always the expected behaviour, as they didn't want other entrypoints to replicate
 *   but as it wasn't explicitly specified anywhere different entrypoints may or may not have
 *   replicated.  We have now fixed this to AllowReplication.isReplicationEnabled()=false
 *   is now implicitly the default.
 *  <p>
 *  As such we now only turn on replication for specific entrypoints as we decide to support them,
 *  instead of the alternative which was to replicate everything by default.
 *
 *  The only replication we currently support is the DAEMON service entrypoint, but as others come
 *  online we have a new method: setReplicationDisabledServerConfig_DefaultBehaviour(),
 *  which can be used to set the default product behaviour from the entrypoint.
 *
 *
 * 3) Override Behaviour - GERRITMS_REPLICATION_DISABLED=true
 *
 * <p>Default behaviour can be overriden, and is determined by the use of the getOverrideBehaviour method.
 * This allows for the specification of a System.env or System.Property by the user
 * such as the GERRITMS_REPLICATION_DISABLED=true flag.
 *
 * <p>The override property is useful as its non persistent, this is therefore the preferred route
 *  for customer override. Leaving the gerrit server configuration only for default product and
 *  testing behaviour, note the gerrit server config should be internal only and never be made available.
 *
 * It has 3 main use cases:
 *
 * - 1) Allows testing to force code down different replicated?true:false code paths for checking.
 *
 * - 2) Docker framework can perform an easy installation without gitms being installed and act entirely
 *  as a vanilla war.
 *  We use this to install gerrit without gitms as a vanilla gerrit, which we can test on its own
 *  to ensure we haven't changed behaviour, then we can enable the replicated
 *  environment afterwards removing the requirements for 2 gerrit wars, and continue with normal replicated
 *  testing.
 *
 * - 3) Allows dev/customers to override default product behaviour.
 *  This has been used to date to override the previous default of replicated=true to be replicated=false
 *  e.g. to force "init" and "copy approvals" to perform operations without replication and gitms running.
 *  ( This was required for noteDb migration etc, so in all versions before this patch in 1.12.1+ that used our
 *  replicated war during upgrade. )
 *
 *  Note: A communication between gerrit and JGit to keep the behaviour
 *  in sync, requires that a copy of the default behaviour be supplied to JGIT using
 *  a java.property called: GERRITMS_JGIT_REPLICATION_DISABLED_DEFAULT.  This will never
 *  be setup by anyone in any calling code, or by CLI. It is only ever set by overselves to match
 *  the product default behaviour, and later read by JGit.
 *
 */
@Singleton // Not guice bound but makes it clear that it's a singleton
public final class AllowReplication {

  private static final String GERRITMS_REPLICATION_DISABLED_CONFIG_NAME = "gerritmsReplicationDisabled";
  private static final String WANDISCO_CONFIG_SECTION = "wandisco";
  private static final String JGIT_REPLICATION_DISABLED_DEFAULT = "gerritms_jgit_replication_disabled_default";

  // A flag, which allow there to be no application properties and for us to behave like a normal
  // vanilla non replicated environment.   This value is cached off once the precedence order has
  // been evalulated, although it can be reset using: resetCachedReplicationDisabledState()
  private Boolean replicationDisabled = null;

  // Static product default behaviour.  Default is replication disabled, but this
  // default can be flipped before any Gerrit Server config, or environment has been evaluated.
  // Although it is only the default and will be overridden by any of the other values.
  private static Boolean gerritmsReplicationDisabledProductDefault = true;
  private Config config;

  private static volatile AllowReplication INSTANCE;

  private AllowReplication(Config config) {
    this.config = config;
  }

  // Get singleton instance
  public static AllowReplication getInstance(Config config) {
    if (INSTANCE == null) {
      synchronized (AllowReplication.class) {
        if (INSTANCE == null) {
          INSTANCE = new AllowReplication(config);
          SingletonEnforcement.registerClass(AllowReplication.class);
        }
      }
    }

    return INSTANCE;
  }

  /**
   * Method to inverse the isReplicationDisabled method, to allow it to be easily supplied to
   * implementations which expected it to be the isreplicated flag, allowing us to control the
   * turning off of replication more easily.
   *
   * @return true if replication is ENABLED
   */
  public boolean isReplicationEnabled() {
    return !isReplicationDisabled();
  }

  /**
   * A specific version of getReplicationDisabled which reads the value from GerritServerConfig.
   *
   * <p>Used as a default controllable behaviour, either by an entrypoint, or by integration tests.
   *
   * @return returns true is GerritMs replication should be disabled, which is to be the default
   *     behaviour across entry points.
   *     <p>N.B. gerritmsReplicationDisabled=true by default!
   *     <p>We now only turn on replication for specific entrypoints as we decide to support them,
   *     instead of the alternative which was to replicate everything by default - including init
   *     which was incorrect. The only replication we should support is the DAEMON service
   *     entrypoint, so it makes sense to make the new default to gerritmsReplicationDisabled=true.
   */
  public boolean getReplicationDisabledServerConfig() {
    return config.getBoolean(WANDISCO_CONFIG_SECTION, null, GERRITMS_REPLICATION_DISABLED_CONFIG_NAME, true);
  }

  /**
   * Setup the expected product default behaviour.
   * used by entrypoints to change the default replication disabled behaviour.
   *
   * e.g. Daemon now calls this to change product default of
   * gerritmsReplicationDisabledProductDefault=false to enable replication by default.
   *
   * The same can be performed in any entrypoint that requires replication,
   * but the default is to not replicate.
   *
   * @param replicationDisabled Toggle whether to use replicated functionality.
   */
  public static void setReplicationDisabled_DefaultBehaviour(boolean replicationDisabled) {
    gerritmsReplicationDisabledProductDefault = replicationDisabled;
    // as long as we have nothing supplied by the user as an override, lets set the
    // default user environment to match, this is only used to communicate default behaviour changes
    // to jgit, someone is changing default behaviour
    System.setProperty(JGIT_REPLICATION_DISABLED_DEFAULT, replicationDisabled ? "TRUE" : "FALSE");
  }

  /**
   * This only changes or sets this expected behaviour if someone has not already specified one
   * outside of the process in the server configuration. This should not really ever happen in
   * production, but it might be handy for internal testing of different scenarios. Either way, if
   * the value is specified, do not override what they have given.
   *
   * @param replicationDisabled Toggle whether to use replicated functionality or not, only if the
   *     value is not already specified.
   */
  public void setReplicationDisabledServerConfig(boolean replicationDisabled) {
    config.setBoolean(WANDISCO_CONFIG_SECTION, null, GERRITMS_REPLICATION_DISABLED_CONFIG_NAME, replicationDisabled);
    // if someone has updated the default product behaviour, ensure we clear our cached value, this is only used by tests which flip
    // flop the behaviour.  Note: if they have pass in the override flag - this reset will not help as order or precedence,
    // ensures its higher up, this is only for flipping and checking default product behaviour via server config.
    // If you require a forced behaviour, use forceReplicationDisabledCachedState instead which overrides all disabled state indications.
    resetCachedReplicationDisabledState();
  }

  /**
   * Forcibly set the replication state - ignoring product default behaviour and any overrides.  This ensure tests can
   * perform any code path they wish, even if the rest of tests are forced to behave with the override flag.  Otherwise
   * we would need to implement a TLS or locked type approach where we cache off what value is there now, set it to something, then
   * reset it back as we leave the test which is more effort and difficult to make safe in our singleton class.
   *
   * @param replicationDisabled  Force the replication state to this value.
   */
  public void forceReplicationDisabledCachedState(boolean replicationDisabled) {
    // ignore any flags or order of behaviour - ensure we set the cached replication state to that supplied, so we can forcibly
    // fip behaviour regardless of system.env / properties etc.
    this.replicationDisabled = replicationDisabled;
  }
  /**
   * A very core configuration override now which allows the full replication element of GerritMS to
   * be disabled and essentially for it to return to default vanilla behaviour.
   *
   * <p>This can take the form of an internal gerrit server configuration value, which sets the
   * product default behaviour, and an override value which is only used to change what is expected
   * by default.
   *
   * <p>e.g. Someone wishing to stop replication of the daemon entrypoint can pass in the override
   * to make it behave as the vanilla war would do.
   *
   * @return true if replication is DISABLED
   */
  private boolean isReplicationDisabled() {

    // use cached value first.
    if (replicationDisabled != null) {
      return replicationDisabled;
    }

    resetCachedReplicationDisabledState();
    return replicationDisabled;
  }

  public void resetCachedReplicationDisabledState(){
    // use override if specified.
    if (getOverrideBehaviourExists(REPLICATION_DISABLED)) {
      replicationDisabled = getOverrideBehaviour(REPLICATION_DISABLED);
      return;
    }

    // If no system env found for override, then check does the gerritMsReplicationDisabled
    // Gerrit server config value exist, and if so use it.
    if (ConfigurationHelper.checkValueExists(
        config, WANDISCO_CONFIG_SECTION, null, GERRITMS_REPLICATION_DISABLED_CONFIG_NAME)) {
      replicationDisabled = getReplicationDisabledServerConfig();
      return;
    }

    // Lastly - product default fallback.
    // Pick up the default behaviour flag gerritmsReplicationDisabledProductDefault
    replicationDisabled = gerritmsReplicationDisabledProductDefault;
    System.setProperty(JGIT_REPLICATION_DISABLED_DEFAULT,
        gerritmsReplicationDisabledProductDefault ? "TRUE" : "FALSE");
  }

  // used by integration tests, to wipe previous test state on the static class
  public void clearCacheReplicationState(){
    // remove any replication disabled cached state, and wipe
    // any persisted operations.
    gerritmsReplicationDisabledProductDefault = true;  // Revert default
    replicationDisabled = null;
    // reset jgit behaviour.
    System.setProperty(JGIT_REPLICATION_DISABLED_DEFAULT, "TRUE");

    if (ConfigurationHelper.checkValueExists(
        config, WANDISCO_CONFIG_SECTION, null, GERRITMS_REPLICATION_DISABLED_CONFIG_NAME)) {
      config.unset(WANDISCO_CONFIG_SECTION, null, GERRITMS_REPLICATION_DISABLED_CONFIG_NAME);
    }
  }

  /**
   * Utility method to get the system override properties and returns them as a boolean indicating
   * whether they are enabled / disabled.
   *
   * @param overrideName : Name of the flag to check for in the system env or properties
   * @return boolean value of the system env or property val.
   */
  private boolean getOverrideBehaviour(String overrideName) {

    // work out system env value first... Note as env is case-sensitive and properties usually lower
    // case, we will
    // use what the client has passed in, but also request toUpper for the environment option JIC.
    // e.g. 'replication_disabled' the property would be 'REPLICATION_DISABLED' the environment var.
    String env = System.getenv(overrideName);
    if (Strings.isNullOrEmpty(env)) {
      // retry with uppercase
      env = System.getenv(overrideName.toUpperCase());
    }
    return Boolean.parseBoolean(System.getProperty(overrideName, env));
  }

  /**
   * Does a given value exist in system.env or system.property for a given property name..
   *
   * @param overrideName Name of the override flag to be searched for.
   * @return boolean TRUE if the override exists and has a value, otherwise FALSE.
   */
  private boolean getOverrideBehaviourExists(String overrideName) {
    // work out system env value first... Note as env is case-sensitive and properties usually lower
    // case, we will
    // use what the client has passed in, but also request toUpper for the environment option JIC.
    // e.g. 'replication_disabled' the property would be 'REPLICATION_DISABLED' the environment var.
    String env = System.getenv(overrideName);
    if (Strings.isNullOrEmpty(env)) {
      // retry with uppercase
      env = System.getenv(overrideName.toUpperCase());
    }

    return !Strings.isNullOrEmpty(System.getProperty(overrideName, env));
  }
}
