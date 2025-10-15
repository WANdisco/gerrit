package com.google.gerrit.server.replication.modules;

import com.google.gerrit.server.replication.gson.GsonTypeAdapterRegistry;
import com.google.gerrit.server.replication.GerritEventFactory;
import com.google.gerrit.server.replication.feeds.ReplicatedOutgoingServerEventsFeed;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

import java.util.Map;

public class ReplicationModule extends LifecycleModule {

  @Override
  protected void configure() {
    /* Sets up the bindings for the ReplicatedEventsCoordinator*/
    install(new ReplicatedCoordinatorModule());
    /* The ReplicatedOutgoingEventsFeed differs to the other feeds in that it sets up an EventListener
     * and registers that listener with the EventBroker. It also does not need a member variable inside the
     * ReplicatedEventsCoordinator as no other class needs to access it via a getter. For this reason it is
     * distinct from the other feeds and does not need to be instantiated by the ReplicatedEventsCoordinator.*/
    install(new ReplicatedOutgoingServerEventsFeed.Module());
    /* GerritEventFactory is a utility class full of static methods. It needs static injection for the
       provided Gson instance*/
    install(new GerritEventFactory.Module());
  }

  @Provides
  @Named("wdGson")
  @Singleton
  public Gson provideGson(){
    /* Configure how we wish to specify/change any data going through our gson serialization.
     */
    GsonBuilder builder = new GsonBuilder();
    GsonTypeAdapterRegistry.registerAdapters(builder, GsonTypeAdapterRegistry.getDefaultAdaptersMap());
    return builder.create();
  }


  // Registers the default typeAdapter mappings that we know upfront however if we want the ability to register
  // a type adapter from anywhere, then we need the ability to register additional adapters with the GsonBuilder instance
  public static Gson registerAdditionalTypeAdaptersWithGson(Map<GsonTypeAdapterRegistry.AdapterKind, Object> additionalAdapterMap) {

    GsonBuilder builder = new GsonBuilder();
    // Register the known default set of adapters
    GsonTypeAdapterRegistry.registerAdapters(builder, GsonTypeAdapterRegistry.getDefaultAdaptersMap());

    // Now register any additional type adapters if there are any.
    if(additionalAdapterMap != null){
      GsonTypeAdapterRegistry.registerAdapters(builder, additionalAdapterMap);
    }

    return builder.create();
  }

}
