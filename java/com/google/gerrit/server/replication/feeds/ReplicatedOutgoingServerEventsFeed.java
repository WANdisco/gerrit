package com.google.gerrit.server.replication.feeds;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.events.ChangeEvent;
import com.google.gerrit.server.events.EventTypes;
import com.google.gerrit.server.events.ProjectEvent;
import com.google.gerrit.server.events.RefEvent;
import com.google.gerrit.server.events.SkipReplication;
import com.google.gerrit.server.replication.GerritEventFactory;
import com.google.gerrit.server.replication.ReplicatedChangeEventInfo;
import com.google.gerrit.server.replication.configuration.ReplicatedConfiguration;
import com.google.gerrit.server.replication.SingletonEnforcement;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventBroker;
import com.google.gerrit.server.events.EventListener;
import com.google.gerrit.server.events.RefUpdatedEvent;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Singleton //This class is guice bound
public class ReplicatedOutgoingServerEventsFeed implements LifecycleListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private ReplicatedEventsCoordinator coordinator;
  private ReplicatedConfiguration configuration;
  private EventBroker eventBroker;

  public static class Module extends LifecycleModule {
    @Override
    protected void configure() {
      bind(ReplicatedOutgoingServerEventsFeed.class);
      /* We need to bind the listener to this class as its required to started the lifecycle*/
      listener().to(ReplicatedOutgoingServerEventsFeed.class);
    }
  }

  @Inject
  public ReplicatedOutgoingServerEventsFeed(ReplicatedConfiguration configuration,
                                            ReplicatedEventsCoordinator coordinator,
                                            EventBroker eventBroker) {
    this.eventBroker = eventBroker;
    this.coordinator = coordinator;
    this.configuration = configuration;
    SingletonEnforcement.registerClass(ReplicatedOutgoingServerEventsFeed.class);
  }

  /**
   * Invoked when the server is starting.
   */
  @Override
  public void start() {
    this.eventBroker.registerUnrestrictedEventListener("OutgoingServerEventsFeedListener", this.listener);
  }

  /**
   * Invoked when the server is stopping.
   */
  @Override
  public void stop() {
    SingletonEnforcement.unregisterClass(ReplicatedOutgoingServerEventsFeed.class);
  }


  public final EventListener listener = new EventListener() {

    @Override
    public void onEvent(Event event) {
      // Only send these events if GERRIT_REPLICATED_EVENTS_ENABLED_SEND flag is true
      if (!configuration.isReplicatedStreamEventsSendEnabled()) {
        logger.atFine().atMostEvery(15, TimeUnit.MINUTES)
            .log("GERRIT_REPLICATED_EVENTS_ENABLED_SEND flag is false so not queuing this event for replication.  Event type %s skipped.", event.getType());
        return;
      }

      //If the event is in a skip list then we do not queue it for replication.
      if (!isEventToBeSkipped(event)) {
        event.setNodeIdentity(configuration.getThisNodeIdentity());
        try {
          ReplicatedChangeEventInfo changeEventInfo = getChangeEventInfo(event);
          if ( changeEventInfo == null ){
            // we don't support this event, skip over it - we can't queue it for replication.
            return;
          }
          coordinator.queueEventForReplication(GerritEventFactory.createReplicatedChangeEvent(event, changeEventInfo));
        } catch (IOException e) {
          logger.atSevere().withCause(e).log("Unable to queue server event for replication %s", e.getMessage());
        }
      } else if ( !event.hasBeenReplicated ){ // note we also skip when the event has been replicated from another node - so dont log incorrectly!
        logger.atFine().atMostEvery(15, TimeUnit.MINUTES).log("Event type %s is present in the event skip list. Skipping.", event.getType());
      }
    }
  };

  /**
   * isEventToBeSkipped uses 3 things.
   * 1) has the event previously been replicated - if so we dont do it again!!
   * 2) IS the event in a list of events we are not to replicate ( a skip list )
   * 3) Is the event annotated with the @SkipReplication annotation, if it is, skip it.
   *    Using the SkipReplication annotation should be used with caution as there are normally
   *    multiple events associated with a given operation in Gerrit and skipping one could
   *    leave the repository in a bad state.
   *
   * @param event
   * @return
   */
  public boolean isEventToBeSkipped(Event event) {
    if (event.hasBeenReplicated) {
      // dont cause cyclic loop replicating forever./
      return true;
    }

    //If the event contains a skipReplication annotation then we skip the event
    Class eventClass = EventTypes.getClass(event.type);

    if(eventClass.isAnnotationPresent(SkipReplication.class)){
      return true;
    }

    return isEventInSkipList(event);
  }

  /**
   * This checks against the list of event class names to be skipped
   * Skippable events are configured by a parameter in the application.properties
   * as a comma separated list of class names for event types,
   * e.g. TopicChangedEvent, ReviewerDeletedEvent.
   *
   * @param event
   * @return
   */
  public boolean isEventInSkipList(Event event) {
    //Doesn't matter if the list is empty, check if the list contains the class name.
    //All events are stored in the list as lowercase, so we check for our lowercase class name.
    return configuration.getEventSkipList().contains(event.getClass().getSimpleName().toLowerCase()); //short name of the class
  }

  /**
   * Since the event can be of many types, and since the Gerrit engineers didn't want
   * to put the ChangeAttribute in the main abstract class, we have to analyze every
   * single event type and extract the relevant information
   *
   * @param newEvent
   * @return false if the event is not supported
   */
  public ReplicatedChangeEventInfo getChangeEventInfo(Event newEvent) {
    ReplicatedChangeEventInfo changeEventInfo = new ReplicatedChangeEventInfo();

    //When we call a setter on the ChangeEventInfo instance, it sets the member value of
    //supported to true. There are four different categories of supported events below, namely
    //PatchSetEvents, ChangeEvents, RefEvents and ProjectEvents.

    //PatchSetEvents and ChangeEvents. Note a PatchSetEvent is a subclass of a ChangeEvent
    if (newEvent instanceof ChangeEvent) {
      handleChangeEvent((ChangeEvent) newEvent, changeEventInfo);
      // RefUpdatedEvent is a RefEvent but has a specific check if the refUpdate field is null, therefore cannot
      // be grouped with the rest of the RefEvents
    } else if (newEvent instanceof RefUpdatedEvent) {
      handleRefUpdatedEvent((RefUpdatedEvent) newEvent, changeEventInfo);
      //If it's an instance of a RefEvent that is not a RefUpdatedEvent
    } else if (newEvent instanceof RefEvent) {
      handleRefEvent((RefEvent) newEvent, changeEventInfo);
      //ProjectEvents
    } else if (newEvent instanceof ProjectEvent) {
      handleProjectEvent((ProjectEvent) newEvent, changeEventInfo);
    } else {
      logger.atInfo().log("RE %s is not supported!", newEvent.getClass().getName());
      changeEventInfo.setSupported(false);
    }
    return changeEventInfo;
  }

  private void handleChangeEvent(ChangeEvent newEvent, ReplicatedChangeEventInfo changeEventInfo) {
    changeEventInfo.setChangeAttribute(newEvent.change.get());
  }

  private void handleProjectEvent(ProjectEvent newEvent, ReplicatedChangeEventInfo changeEventInfo) {
    changeEventInfo.setProjectName(newEvent.getProjectNameKey().get());
  }

  private void handleRefEvent(RefEvent newEvent, ReplicatedChangeEventInfo changeEventInfo) {
    changeEventInfo.setProjectName(newEvent.getProjectNameKey().get());
    changeEventInfo.setBranchName(new Branch.NameKey(newEvent.getProjectNameKey().get(), completeRef(newEvent.getRefName())));
  }

  private void handleRefUpdatedEvent(RefUpdatedEvent newEvent, ReplicatedChangeEventInfo changeEventInfo) {
    if(newEvent.refUpdate == null){
      logger.atInfo().log("RE %s is not supported, project name or ref update is null!", newEvent.getClass().getName());
      changeEventInfo.setSupported(false);
      return;
    }

    changeEventInfo.setProjectName(newEvent.refUpdate.get().project);
    changeEventInfo.setBranchName(new Branch.NameKey(new Project.NameKey(newEvent.refUpdate.get().project),
        completeRef(newEvent.refUpdate.get().refName)));

  }

  /**
   * Helper method for the authentication in Gerrit
   */
  public static String completeRef(String refName) {
    if (refName == null) {
      return "";
    }
    // A refName can contain a "/" for example 'refs/heads/foo/bar' is a valid ref.
    // if refName starts with refs/heads/ already then just return refName otherwise prepend it with 'refs/heads'
    return refName.contains("refs/heads/") ? refName : "refs/heads/" + refName;
  }

}
