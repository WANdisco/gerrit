package com.google.gerrit.pgm;

import com.google.gerrit.extensions.config.FactoryModule;
import com.google.gerrit.index.IndexType;
import com.google.gerrit.lifecycle.LifecycleManager;
import com.google.gerrit.lucene.LuceneIndexModule;
import com.google.gerrit.pgm.util.BatchProgramModule;
import com.google.gerrit.pgm.util.SiteProgram;
import com.google.gerrit.server.LibModuleLoader;
import com.google.gerrit.server.ModuleOverloader;
import com.google.gerrit.server.account.storage.notedb.AccountNoteDbReadStorageModule;
import com.google.gerrit.server.account.storage.notedb.AccountNoteDbWriteStorageModule;
import com.google.gerrit.server.approval.RecursiveApprovalCopier;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.index.IndexModule;
import com.google.gerrit.server.index.options.AutoFlush;
import com.google.gerrit.server.index.options.BuildBloomFilter;
import com.google.gerrit.server.index.options.IsFirstInsertForEntry;
import com.google.gerrit.server.notedb.RepoSequence;
import com.google.gerrit.server.plugins.PluginGuiceEnvironment;
import com.google.gerrit.server.replication.configuration.AllowReplication;
import com.google.gerrit.server.replication.configuration.ConfigurationHelper;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.server.replication.modules.NonReplicatedCoordinatorModule;
import com.google.gerrit.server.replication.modules.ReplicatedCoordinatorModule;
import com.google.gerrit.server.util.GuiceUtils;
import com.google.gerrit.server.util.ReplicaUtil;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.multibindings.OptionalBinder;
import org.eclipse.jgit.lib.Config;
import org.kohsuke.args4j.Option;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.gerrit.server.replication.configuration.ReplicationConstants.COPY_APPROVAL_MIGRATION_DISABLED;


public class CopyApprovals extends SiteProgram {

  private Injector dbInjector;
  private Injector sysInjector;
  private Injector cfgInjector;
  private Config globalConfig;
  private AllowReplication allowReplication;

  //  Using the same approach as reindex operations which also use better performance setup for lucence
  //  changes, and the index operations.
  //  Using the --threads, and index.batchThreads from gerrit.config to avoid needing 2 different
  // configs, as they both have the same requirements for offline setup.
  @Option(
      name = "--threads",
      usage = "Number of threads to use for copy-approvals migration. Default is index.batchThreads from config.")
  private int threads = 0;

  @Inject
  private RecursiveApprovalCopier recursiveApprovalCopier;

  @Override
  public int run() throws Exception {
    mustHaveValidSite();
    dbInjector = createDbInjector();
    cfgInjector = dbInjector.createChildInjector();
    globalConfig = dbInjector.getInstance(Key.get(Config.class, GerritServerConfig.class));
    allowReplication = AllowReplication.getInstance(globalConfig);

    // update lucene and indexing config to match reindex behaviour for fast performance for
    // upgrade or entrypoint direct calls.
    overrideConfig();
    LifecycleManager dbManager = new LifecycleManager();
    dbManager.add(dbInjector);
    dbManager.start();

    // GER-1889: We cannot allow the plugin environment to copy instance information when in a shared context.
    PluginGuiceEnvironment.setAllowCopyOfReplicatedCoordinatorInstance(false);

    sysInjector = createSysInjector();
    sysInjector.getInstance(PluginGuiceEnvironment.class).setDbCfgInjector(dbInjector, cfgInjector);
    LifecycleManager sysManager = new LifecycleManager();
    sysManager.add(sysInjector);
    sysManager.start();

    sysInjector.injectMembers(this);

    try {
      if (recursiveApprovalCopier.isCopyApprovalsMigrationDisabled()) {
        // incorrect environment, someone has disabled copy approvals to upgrade the system, but forgotten to turn
        // it back on before calling this entrypoint!
        throw new Exception(
            "CopyApprovals migration has been disabled, which is invalid when calling this entry point directly. " +
                "Please check your env, or properties for: " + COPY_APPROVAL_MIGRATION_DISABLED);
      }
      recursiveApprovalCopier.persistStandalone();
      return 0;
    } catch (Exception e) {
      throw die(e.getMessage(), e);
    } finally {
      sysManager.stop();
      dbManager.stop();
    }
  }

  private Injector createSysInjector() {
    Map<String, Integer> versions = new HashMap<>();
    boolean replica = ReplicaUtil.isReplica(globalConfig);
    List<Module> modules = new ArrayList<>();
    modules.add(new WorkQueue.WorkQueueModule());

    Module indexModule;
    IndexType indexType = IndexModule.getIndexType(dbInjector);
    if (indexType.isLucene()) {
      // Uses --threads variable, with index.batchThreads default.
      indexModule =
          LuceneIndexModule.singleVersionWithExplicitVersions(
              versions, threads, replica, AutoFlush.DISABLED);
    } else if (indexType.isFake()) {
      // Use Reflection so that we can omit the fake index binary in production code. Test code does
      // compile the component in.
      try {
        Class<?> clazz = Class.forName("com.google.gerrit.index.testing.FakeIndexModule");
        Method m =
            clazz.getMethod(
                "singleVersionWithExplicitVersions", Map.class, int.class, boolean.class);
        indexModule = (Module) m.invoke(null, versions, threads, replica);
      } catch (NoSuchMethodException
               | ClassNotFoundException
               | IllegalAccessException
               | InvocationTargetException e) {
        throw new IllegalStateException("can't create index", e);
      }
    } else {
      throw new IllegalStateException("unsupported index.type = " + indexType);
    }
    modules.add(indexModule);
    modules.add(
        new AbstractModule() {
          @Override
          protected void configure() {
            super.configure();
            OptionalBinder.newOptionalBinder(binder(), IsFirstInsertForEntry.class)
                .setBinding()
                .toInstance(IsFirstInsertForEntry.YES);
          }
        });

    if (!GuiceUtils.hasBinding(dbInjector, ReplicatedEventsCoordinator.class)) {
      // Replication is always false, but ensure correct binding, incase we start testing
      // this as a replicated entrypoint.
      modules.add(allowReplication.isReplicationEnabled() ?
          new ReplicatedCoordinatorModule() :
          new NonReplicatedCoordinatorModule());
    }

    modules.add(
        new FactoryModule() {
          @Override
          protected void configure() {
            factory(ChangeResource.Factory.class);
          }
        });
    modules.add(new BatchProgramModule(dbInjector));
    modules.add(new AccountNoteDbWriteStorageModule());
    modules.add(new AccountNoteDbReadStorageModule());
    modules.add(new RepoSequence.RepoSequenceModule());


    // Uses --threads variable, with index.batchThreads default.
    return dbInjector.createChildInjector(
        ModuleOverloader.override(
            modules, LibModuleLoader.loadReindexModules(cfgInjector, versions, threads, replica)));
  }

  /**
   * Override the default product behaviour if not gerrit.config values are explicitly set.
   */
  private void overrideConfig() {
    // GER-2273: Only set the override behaviour if the gerrit configuration doesn't have a value specified.
    // this allows us to change product default behaviour, but still allow an admin to control the values
    // by changing gerrit.config.
    // Disable auto-commit for speed; committing will happen at the end of the process
    if (IndexModule.getIndexType(dbInjector).isLucene()) {
      if (!ConfigurationHelper.checkValueExists(globalConfig, "index", "changes_open", "commitWithin")) {
        globalConfig.setLong("index", "changes_open", "commitWithin", -1);
      }
      if (!ConfigurationHelper.checkValueExists(globalConfig,"index", "changes_closed", "commitWithin")) {
        globalConfig.setLong("index", "changes_closed", "commitWithin", -1);
      }
    }

    if (!ConfigurationHelper.checkValueExists(globalConfig, "cache", "changes", "maximumWeight")) {
      // Disable change cache.
      globalConfig.setLong("cache", "changes", "maximumWeight", 0);
    }

    if (!ConfigurationHelper.checkValueExists(globalConfig, "index", null, "autoReindexIfStale")) {
      // Disable auto-reindexing if stale, since there are no concurrent writes to race with.
      globalConfig.setBoolean("index", null, "autoReindexIfStale", false);
    }
  }
}
