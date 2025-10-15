package com.google.gerrit.server.replication.modules;

import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinatorImpl;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.inject.Scopes;

public class ReplicatedCoordinatorModule extends LifecycleModule {

  @Override
  protected void configure() {
    DynamicItem.itemOf(binder(), ReplicatedEventsCoordinator.class);
    DynamicItem.bind(binder(), ReplicatedEventsCoordinator.class).to(ReplicatedEventsCoordinatorImpl.class)
        .in(Scopes.SINGLETON);
    /* The ReplicatedEventsCoordinatorImpl is managed by the LifecycleManager.*/
    listener().to(ReplicatedEventsCoordinator.class);
  }
}
