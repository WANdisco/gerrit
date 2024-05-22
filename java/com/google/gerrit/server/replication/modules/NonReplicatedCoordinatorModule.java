package com.google.gerrit.server.replication.modules;

import com.google.gerrit.server.replication.coordinators.NonReplicatedEventsCoordinatorImpl;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.inject.Scopes;

public class NonReplicatedCoordinatorModule extends LifecycleModule {
  /**
   * This module is to be bound whenever we want to operate in a non replicated fashion
   * such as when running vanilla. A lot of classes such as the ProjectCacheImpl etc make use
   * of a ReplicatedEventsCoordinator. We need to sure we provide the correct bindings for when
   * we are running in a non-replicated mode. The test classes for example take multiple routes
   * throughout the code that will expect an instance of a ReplicatedEventsCoordinator to be bound.
   * This class is hooked in at the correct points so that we provide the correct bindings whenever
   * the code is running in non replicated mode.
   */
  @Override
  protected void configure() {
    DynamicItem.itemOf(binder(), ReplicatedEventsCoordinator.class);
    DynamicItem.bind(binder(), ReplicatedEventsCoordinator.class)
        .to(NonReplicatedEventsCoordinatorImpl.class).in(Scopes.SINGLETON);
  }
}
