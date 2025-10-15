package com.google.gerrit.server.replication;


import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.AllProjectsNameProvider;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.config.AllUsersNameProvider;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.inject.Provider;
import org.eclipse.jgit.lib.Config;

/**
 * core project name providers is a simple testing copy of the
 * all-users / all-projects name providers.  But they allow creation of the providers
 * without having a Guice Binding setup.   This allows simple testing of the official
 * Gerrit configuration and behaviour, in unit tests.
 *
 * This is mainly used by the dummyTestCoordinator and its testing config.
 */
public final class TestingCoreProjectNameProviders {

  public static class TestingAllUsersNameProvider implements Provider<AllUsersName> {
    private final AllUsersName name;

    public TestingAllUsersNameProvider(@GerritServerConfig Config cfg) {
      String n = cfg.getString("gerrit", null, "allUsers");
      if (n == null || n.isEmpty()) {
        n = AllUsersNameProvider.DEFAULT;
      }
      name = new AllUsersName(n);
    }

    @Override
    public AllUsersName get() {
      return name;
    }
  }



  public static class TestingAllProjectsNameProvider implements Provider<AllProjectsName> {
    private final AllProjectsName name;

    public TestingAllProjectsNameProvider(@GerritServerConfig Config cfg) {
      String n = cfg.getString("gerrit", null, "allProjects");
      if (n == null || n.isEmpty()) {
        n = AllProjectsNameProvider.DEFAULT;
      }
      name = new AllProjectsName(n);
    }

    @Override
    public AllProjectsName get() {
      return name;
    }
  }
}
