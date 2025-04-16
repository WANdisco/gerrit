package com.google.gerrit.server.replication.configuration;

import com.google.common.base.Strings;
import org.eclipse.jgit.lib.Config;

/**
 * This class helps to extend the standard jgit:Config class.
 *
 * It would be ideal to change the jgit config interface, to avoid such changes this class
 * exists with helpers and extensions that mean we dont need to change jgit each time and change
 * its public interface ( which should mean a new version bump ).
 *
 */
public class ConfigurationHelper {


  /**
   * Get string value or null if not found.
   *
   * @param section
   *            the section
   * @param subsection
   *            the subsection for the value
   * @param name
   *            the key name
   * @return a String value from the config, <code>null</code> if not found
   */
  public static boolean checkValueExists(final Config config,
                                         final String section,
                                         String subsection,
                                         final String name) {
    return !Strings.isNullOrEmpty(config.getString(section, subsection, name));
  }
}
