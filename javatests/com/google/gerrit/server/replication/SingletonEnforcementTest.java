package com.google.gerrit.server.replication;

import com.wandisco.gerrit.gitms.shared.util.StringUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.security.InvalidParameterException;

public class SingletonEnforcementTest {

  @Rule
  public ExpectedException exception = ExpectedException.none();

  @Before
  public void before(){
    SingletonEnforcement.setDisableEnforcement(false);
  }

  @After
  public void afterEachTest(){
    SingletonEnforcement.clearAll();
  }

  @AfterClass
  public static void afterClass(){
    // lets keep enforcement off in tests.
    SingletonEnforcement.setDisableEnforcement(true);
  }

  @Test
  public void testSingletonEnforcement(){
    String uniqueName = StringUtils.createUniqueString("SomeTestclass");

    SingletonEnforcement.registerClass(uniqueName);

    exception.expect(InvalidParameterException.class);

    // now we should not be able to call it again without error
    SingletonEnforcement.registerClass(uniqueName);
  }

  @Test
  public void testSingletonEnforcementUnregister(){
    String uniqueName1 = StringUtils.createUniqueString("SomeTestclass1");
    String uniqueName2 = StringUtils.createUniqueString("SomeTestclass2");
    String uniqueName3 = StringUtils.createUniqueString("SomeTestclass3");
    String uniqueName4 = StringUtils.createUniqueString("SomeTestclass4");

    SingletonEnforcement.registerClass(uniqueName1);
    SingletonEnforcement.registerClass(uniqueName2);
    SingletonEnforcement.registerClass(uniqueName3);

    SingletonEnforcement.unregisterClass(uniqueName3);
    Assert.assertEquals(2, SingletonEnforcement.getSingletonEnforcement().size());

    SingletonEnforcement.registerClass(uniqueName4);
    Assert.assertEquals(3, SingletonEnforcement.getSingletonEnforcement().size());
  }

  @Test
  public void testSingletonEnforcementDisable(){
    String uniqueName = StringUtils.createUniqueString("SomeTestclass");


    SingletonEnforcement.registerClass(uniqueName);
    SingletonEnforcement.setDisableEnforcement(true);
    SingletonEnforcement.registerClass(uniqueName);
    Assert.assertEquals(1, SingletonEnforcement.getSingletonEnforcement().size());
  }


  @Test
  public void testSingletonEnforcementClear(){
    SingletonEnforcement.registerClass(StringUtils.createUniqueString("SomeTestclass1"));
    SingletonEnforcement.clearAll();
    SingletonEnforcement.registerClass(StringUtils.createUniqueString("SomeTestclass2"));
    SingletonEnforcement.registerClass(StringUtils.createUniqueString("SomeTestclass3"));
    SingletonEnforcement.registerClass(StringUtils.createUniqueString("SomeTestclass4"));

    Assert.assertEquals(3, SingletonEnforcement.getSingletonEnforcement().size());
  }
}
