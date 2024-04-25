package com.google.gerrit.server.replication;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.FluentLogger;
import com.google.inject.Singleton;

import java.security.InvalidParameterException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class SingletonEnforcement {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static Set<String> singletonEnforcement = new HashSet<>();
  private static boolean disableEnforcement = false;


  /**
   * SimpleSingletonEnforcement is a simple way of enforcing a class to be a singleton
   * pattern - it will throw if this has already been constructed.
   * <p>
   * N.B. Adding a clear method to be used by tests that may to create this more than once.
   *
   * @param className
   */
  public static void registerClass(Class className) {
    registerClass(className.getSimpleName());
  }

  public static void registerClass(String className) {
    if (disableEnforcement) {
      return;
    }

    synchronized (singletonEnforcement) {
      if (singletonEnforcement.contains(className)) {
        throw new InvalidParameterException("Invalid class - breaks singleton rules - " + className);
      }
      logger.atFine().log("Registering %s", className);
      singletonEnforcement.add(className);
    }
  }

  /**
   * SimpleSingletonEnforcement is a simple way of enforcing a class to be a singleton
   * pattern - this will remove the entry registered for a given class, so it can be recreated if required.
   *
   * @param className
   */
  public static void unregisterClass(Class className) {
    unregisterClass(className.getSimpleName());
  }

  public static void unregisterClass(String className) {
    if (disableEnforcement) {
      return;
    }

    synchronized (singletonEnforcement) {
      if (!singletonEnforcement.contains(className)) {
        return;
      }
      logger.atFine().log("Unregistering %s", className);
      singletonEnforcement.remove(className);
    }
  }

  /**
   * Clear existing registered classnames for doing our own unit testing.
   */
  public static void clearAll() {
    logger.atFine().log("SingletonEnforcer: clearing all set entries");
    if (disableEnforcement) {
      return;
    }

    synchronized (singletonEnforcement) {
      singletonEnforcement.clear();
    }
  }

  public static Set<String> getSingletonEnforcement() {
    return Collections.unmodifiableSet(singletonEnforcement);
  }

  public static void setDisableEnforcement(boolean disableEnforcement) {
    SingletonEnforcement.disableEnforcement = disableEnforcement;
  }
}
