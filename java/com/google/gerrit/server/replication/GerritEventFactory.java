package com.google.gerrit.server.replication;

import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.replication.customevents.AccountIndexEventBase;
import com.google.gerrit.server.replication.customevents.CacheKeyWrapper;
import com.google.gerrit.server.replication.customevents.DeleteProjectChangeEvent;
import com.google.gerrit.server.replication.customevents.IndexToReplicate;
import com.google.gerrit.server.replication.customevents.ProjectIndexEvent;
import com.google.gerrit.server.replication.customevents.ProjectInfoWrapper;
import com.google.gerrit.server.replication.gson.GsonTypeAdapterRegistry;
import com.google.gerrit.server.replication.modules.ReplicationModule;
import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.wandisco.gerrit.gitms.shared.events.DeleteProjectMessageEvent;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.CACHE_EVENT;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.DELETE_PROJECT_EVENT;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.DELETE_PROJECT_MESSAGE_EVENT;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.GERRIT_EVENT;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.INDEX_EVENT;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.PROJECTS_INDEX_EVENT;

public class GerritEventFactory {

  // ReadWriteLock for separate read and write locks
  private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

  /**
   * GerritEventFactory is a utility class made up of static methods. In order to dependency inject these
   * we must use requestStaticInjection().
   * @see <a href="https://google.github.io/guice/api-docs/4.2/javadoc/index.html?com/google/inject/spi/StaticInjectionRequest.html">
   *   google.github.io</a>
   */
  public static class Module extends LifecycleModule {
    @Override
    protected void configure() {
      requestStaticInjection(GerritEventFactory.class);
    }
  }

  /**
   * Used only by integration / unit testing to setup the Injected fields that wont be initialized e.g. GSon.
   */
  public static void setupEventWrapper(){
    lock.writeLock().lock();
    try {
      if (gson == null) {
        gson = new ReplicationModule().provideGson();
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  // If this method is called by anyone, then we will refresh the gson instance
  public static void registerAdditionalTypeAdapter(GsonTypeAdapterRegistry.AdapterKind adapterKind, Map<Class<?>, Object> typeAdapter){
    Map<GsonTypeAdapterRegistry.AdapterKind, Object> adapterMap = Map.of(adapterKind, typeAdapter);

    lock.writeLock().lock();
    try {
      // Update the Gson instance with additional type adapters
      Gson updatedGson = ReplicationModule.registerAdditionalTypeAdaptersWithGson(adapterMap);
      refreshCachedGson(updatedGson);
    } finally {
      lock.writeLock().unlock();
    }
  }


  // If we have modified Gson to take extra typeAdapters, we need to overwrite the instance
  // of it we have. Taking a write lock for this to ensure only 1 thread at a time can do this.
  public static void refreshCachedGson(Gson newGson){
    lock.writeLock().lock();
    try {
      if (gson != null) {
        gson = newGson;
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  // Multiple threads can acquire the read lock simultaneously if no thread holds the write lock.
  public static Gson getGson(){
    lock.readLock().lock();
    try {
      if (gson != null) {
        return gson;
      }
    } finally {
      lock.readLock().unlock();
    }

    // Upgrade to write lock if gson is null
    lock.writeLock().lock();
    try {
      // Double-check if gson is still null to avoid race conditions
      if (gson == null) {
        setupEventWrapper();
      }
      return gson;
    } finally {
      lock.writeLock().unlock();
    }
  }


  // Gson performs the serialization/deserialization of objects using its inbuilt adapters.
  // Java objects can be serialised to JSON strings and deserialized back using JsonSerializer
  // and the JsonDeserializer respectively. SupplierSerializer/SupplierDeserializer and EventDeserializer
  // extend these JsonSerializer/JsonDeserializer
  @Inject
  @Named("wdGson")
  private static Gson gson;

  public static EventWrapper createReplicatedChangeEvent(Event changeEvent,
                                                         ReplicatedChangeEventInfo info) throws IOException {
    String eventString = gson.toJson(changeEvent);
    return new EventWrapper(eventString,
                            changeEvent.getClass().getName(),
                            info.getProjectName(),
                            GERRIT_EVENT);
  }

  /**
   * This type of cache eventWrapper sending specific cache events to any project, note this level does not
   * support NULL  project name, any all-projects routing will need to have been decided at the caller!
   *
   * @param cacheNameAndKey Wrapper around cache name to affect.
   * @return Outgoing cache event.
   */
  public static EventWrapper createReplicatedCacheEvent(String projectName, CacheKeyWrapper cacheNameAndKey) throws IOException {
    String eventString = gson.toJson(cacheNameAndKey);
    return new EventWrapper(eventString,
        cacheNameAndKey.getClass().getName(),
        projectName,
        CACHE_EVENT);
  }

  public static EventWrapper createReplicatedProjectsIndexEvent(String projectName,
                                                                ProjectIndexEvent indexEvent) throws IOException {
    final String eventString = gson.toJson(indexEvent);
    return new EventWrapper(eventString,
                            indexEvent.getClass().getName(),
                            projectName,
                            PROJECTS_INDEX_EVENT);
  }


  public static EventWrapper createReplicatedIndexEvent(IndexToReplicate indexToReplicate) throws IOException {
    String eventString = gson.toJson(indexToReplicate);
    return new EventWrapper(eventString,
                            indexToReplicate.getClass().getName(),
                            indexToReplicate.projectName,
                            INDEX_EVENT);
  }

  public static EventWrapper createReplicatedDeleteProjectChangeEvent(DeleteProjectChangeEvent deleteProjectChangeEvent) throws IOException {
    String eventString = gson.toJson(deleteProjectChangeEvent);
    return new EventWrapper(eventString,
                            deleteProjectChangeEvent.getClass().getName(),
                            deleteProjectChangeEvent.project.getName(),
                            DELETE_PROJECT_EVENT);
  }

  public static EventWrapper createReplicatedDeleteProjectEvent(ProjectInfoWrapper projectInfoWrapper) throws IOException {
    String eventString = gson.toJson(projectInfoWrapper);
    return new EventWrapper(eventString,
                            projectInfoWrapper.getClass().getName(),
                            projectInfoWrapper.projectName,
                            DELETE_PROJECT_EVENT);
  }

  public static EventWrapper createReplicatedDeleteProjectMessageEvent(DeleteProjectMessageEvent deleteProjectMessageEvent ) throws IOException {
    String eventString = gson.toJson(deleteProjectMessageEvent);
    return new EventWrapper(eventString,
                            deleteProjectMessageEvent.getClass().getName(),
                            deleteProjectMessageEvent.getProject(),
                            DELETE_PROJECT_MESSAGE_EVENT);
  }

  //Will create an EventWrapper for either an ACCOUNT_USER_INDEX_EVENT or a ACCOUNT_GROUP_INDEX_EVENT
  public static EventWrapper createReplicatedAccountIndexEvent(String projectName,
                                                               AccountIndexEventBase accountIndexEventBase,
                                                               EventWrapper.Originator originator) throws IOException {
    String eventString = gson.toJson(accountIndexEventBase);
    return new EventWrapper(eventString,
                            accountIndexEventBase.getClass().getName(),
                            projectName,
                            originator);
  }

}
