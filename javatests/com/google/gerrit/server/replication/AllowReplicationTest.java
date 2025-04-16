package com.google.gerrit.server.replication;

import com.google.gerrit.server.replication.configuration.AllowReplication;
import com.wandisco.gerrit.gitms.shared.util.StringUtils;
import org.eclipse.jgit.lib.Config;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.eclipse.jgit.lib.Constants.REPLICATION_DISABLED;

public class AllowReplicationTest {

  private Config gerritServerConfig;
  private AllowReplication allowReplication;
  private String processEnviromentOriginalValue;
  private String processPropertyOriginalValue;

  @Before
  public void setup() throws Exception {
    processPropertyOriginalValue = System.getProperty(REPLICATION_DISABLED);
    // clear any override flag which may be set in the testing environment, we will
    // put it back to its original value after the test has run.
    System.clearProperty(REPLICATION_DISABLED);

    processEnviromentOriginalValue = System.getenv(REPLICATION_DISABLED.toUpperCase());
    // if someone has overriden the system env, drop the replication disabled flag from it
    // by copying full env as is, dropping the flag we need to clear, and rewriting
    // it as a new environmen for testing.
    if ( !StringUtils.isNullOrEmpty(processEnviromentOriginalValue)){
      Map<String, String> existingEnv = new HashMap<String, String>(System.getenv().size());
      existingEnv.putAll(System.getenv());
      existingEnv.remove(REPLICATION_DISABLED.toUpperCase());
      // update the env, with the removed disabled flag.
      setEnv(existingEnv);
    }

    gerritServerConfig = new Config();
    allowReplication = AllowReplication.getInstance(gerritServerConfig);
    // used by integration tests, to wipe previous test state on the static class
    allowReplication.clearCacheReplicationState();
  }


  @After
  public void resetEnvironment() throws Exception {
    if ( StringUtils.isNullOrEmpty(processPropertyOriginalValue)){
      System.clearProperty(REPLICATION_DISABLED);
    } else {
      System.setProperty(REPLICATION_DISABLED, processPropertyOriginalValue);
    }

    if (!StringUtils.isNullOrEmpty(processEnviromentOriginalValue)) {
      Map<String, String> existingEnv = new HashMap<String, String>(System.getenv().size());
      existingEnv.putAll(System.getenv());
      existingEnv.put(REPLICATION_DISABLED.toUpperCase(), processEnviromentOriginalValue);
      setEnv(existingEnv);
    }
  }

  // Check operator precedence rules.
  @Test
  public void checkProductDefault(){
    // default is replication disabled.
    Assert.assertFalse(allowReplication.isReplicationEnabled());
  }

  @Test
  public void checkProductDefaultCanBeChanged(){
    AllowReplication.setReplicationDisabled_DefaultBehaviour(false);
    Assert.assertTrue(allowReplication.isReplicationEnabled());
  }

  @Test
  public void checkGerritServerConfigOverridesDefault_ToTrue(){
    AllowReplication.setReplicationDisabled_DefaultBehaviour(false);
    Assert.assertTrue(allowReplication.isReplicationEnabled());
    allowReplication.setReplicationDisabledServerConfig(true);
    Assert.assertFalse(allowReplication.isReplicationEnabled());
  }
  @Test
  public void checkGerritServerConfigOverridesDefault_ToFalse(){
    // Same test as toTrue, just flipping values to ensure correct
    // behaviour.
    AllowReplication.setReplicationDisabled_DefaultBehaviour(true);
    Assert.assertFalse(allowReplication.isReplicationEnabled());
    allowReplication.setReplicationDisabledServerConfig(false);
    Assert.assertTrue(allowReplication.isReplicationEnabled());
  }

  @Test
  public void checkGerritOverrideState_ToTrue(){
    AllowReplication.setReplicationDisabled_DefaultBehaviour(false);
    Assert.assertTrue(allowReplication.isReplicationEnabled());

    System.setProperty(REPLICATION_DISABLED, "true");
    allowReplication.resetCachedReplicationDisabledState();
    Assert.assertFalse(allowReplication.isReplicationEnabled());
  }

  @Test
  public void checkGerritOverrideState_ToFalse(){
    AllowReplication.setReplicationDisabled_DefaultBehaviour(true);
    Assert.assertFalse(allowReplication.isReplicationEnabled());

    System.setProperty(REPLICATION_DISABLED, "false");
    allowReplication.resetCachedReplicationDisabledState();
    Assert.assertTrue(allowReplication.isReplicationEnabled());
  }

  // Hack to overwrite the system environment with a specific one we wish.
  protected void setEnv(Map<String, String> newenv) throws Exception {
    try {
      Class<?> processEnvironmentClass = Class.forName("java.lang.ProcessEnvironment");
      Field theEnvironmentField = processEnvironmentClass.getDeclaredField("theEnvironment");
      theEnvironmentField.setAccessible(true);
      Map<String, String> env = (Map<String, String>) theEnvironmentField.get(null);
      env.putAll(newenv);
      Field theCaseInsensitiveEnvironmentField =
          processEnvironmentClass.getDeclaredField("theCaseInsensitiveEnvironment");
      theCaseInsensitiveEnvironmentField.setAccessible(true);
      Map<String, String> cienv =
          (Map<String, String>) theCaseInsensitiveEnvironmentField.get(null);
      cienv.putAll(newenv);
    } catch (NoSuchFieldException e) {
      Class[] classes = Collections.class.getDeclaredClasses();
      Map<String, String> env = System.getenv();
      for (Class cl : classes) {
        if ("java.util.Collections$UnmodifiableMap".equals(cl.getName())) {
          Field field = cl.getDeclaredField("m");
          field.setAccessible(true);
          Object obj = field.get(env);
          Map<String, String> map = (Map<String, String>) obj;
          map.clear();
          map.putAll(newenv);
        }
      }
    }
  }
}
