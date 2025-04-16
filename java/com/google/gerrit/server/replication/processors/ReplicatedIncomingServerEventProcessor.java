package com.google.gerrit.server.replication.processors;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.events.ChangeDeletedEvent;
import com.google.gerrit.server.events.ProjectEvent;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.replication.ReplicatedChangeEventInfo;
import com.google.gerrit.server.replication.SingletonEnforcement;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.server.replication.exceptions.ReplicatedEventsMissingChangeInformationException;
import com.google.gerrit.server.replication.exceptions.ReplicatedEventsUnknownTypeException;
import com.google.gerrit.server.events.ChangeEvent;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventBroker;
import com.google.gerrit.server.events.RefEvent;
import com.google.inject.Singleton;
import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;

import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.GERRIT_EVENT;

@Singleton //Not guice bound but makes it clear that it's a singleton
public class ReplicatedIncomingServerEventProcessor extends AbstractReplicatedEventProcessor {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private EventBroker eventBroker;

  /**
   * We only create this class from the replicatedEventsCoordinator.
   * This is a singleton and it's enforced by our SingletonEnforcement below that if anyone else tries to create
   * this class it will fail.
   * Sorry by adding a getInstance, make this class look much more public than it is,
   * and people expect they can just call getInstance - when in fact they should always request it via the
   * ReplicatedEventsCoordinator.getReplicatedXWorker() methods.
   */
  public ReplicatedIncomingServerEventProcessor(ReplicatedEventsCoordinator replicatedEventsCoordinator) {
    super(GERRIT_EVENT, replicatedEventsCoordinator);
    logger.atInfo().log("Creating main processor for event type: %s", eventType);
    subscribeEvent(this);
    SingletonEnforcement.registerClass(ReplicatedIncomingServerEventProcessor.class);
  }

  //The sysInjector is set up here: {@link com.google.gerrit.pgm.Daemon#createSysInjector()} () ConfigInjector}
  /**
   * Using the sysInjector to get an instance of EventBroker to avoid using the coordinator
   * having to inject an EventBroker dependency in its constructor which would cause a dependency cycle.
   *
   * @return A singleton instance of the EventBroker.
   */
  public EventBroker getEventBroker() {
    if (eventBroker == null) {
      eventBroker = replicatedEventsCoordinator.getSysInjector().getInstance(EventBroker.class);
    }
    return eventBroker;
  }

  @Override
  public void stop() {
    unsubscribeEvent(this);
  }

  /**
   * Process incoming GERRIT_EVENTS that are of type Event. Event is the base class
   * of all server events. If we are receiving an incoming event we need to put the
   * event on our event stream. This is done by posting the event to the event broker.
   * @param replicatedEvent which is cast to Event.
   */
  @Override
  public void processIncomingReplicatedEvent(final ReplicatedEvent replicatedEvent) {
    publishIncomingToEventStream((Event) replicatedEvent);
  }

  /**
   * Publish the incoming GERRIT_EVENTs to the local gerrit event stream.
   * @param event Gerrit server event that has been replicated.
   */
  private void publishIncomingToEventStream(Event event) {
    // gerrit.replicated.events.enabled.receive dictates whether this node allowed to process
    // incoming stream events from other sites
    if(!replicatedEventsCoordinator.getReplicatedConfiguration().isReceiveIncomingStreamAPIEvents()){
      return;
    }

    event.hasBeenReplicated = true;

    // If the boolean isReceiveIncomingStreamAPIReplicatedEventsAndPublish is true then we
    // want to publish those incoming server events to the event stream
    if (replicatedEventsCoordinator.getReplicatedConfiguration().isReceiveIncomingStreamAPIReplicatedEventsAndPublish()) {
      publishIncomingReplicatedEventsLocalImpl(event);
    }
  }


  /**
   * Publishes the event calling the postEvent function in ChangeHookRunner
   *
   * @param newEvent Event to publish
   */
  private void publishIncomingReplicatedEventsLocalImpl(Event newEvent) {
    ReplicatedChangeEventInfo replicatedChangeEventInfo = replicatedEventsCoordinator
        .getReplicatedOutgoingServerEventsFeed().getChangeEventInfo(newEvent);

    if (replicatedChangeEventInfo == null) {
      return;
    }

    logger.atFine().log("RE going to fire event... %s ", replicatedChangeEventInfo);

    try {
      if (newEvent instanceof ChangeDeletedEvent) {
        // If a change is deleted we won't be able to read ChangeNotes from NoteDB - we'll get a NoSuchChangeException
        // which gets retried until the event file is failed, so we just post the event for this case.
        logger.atFine().log("RE got change deleted event, not attempting to read change notes: %s", newEvent);
        getEventBroker().postEvent(newEvent);
      } else if (replicatedChangeEventInfo.getChangeAttr() != null) {
        logger.atFine().log("RE using changeAttr: %s...", replicatedChangeEventInfo.getChangeAttr());

        ChangeNotes changeNotes = replicatedEventsCoordinator.getChangeNotesFactory()
            .create(Project.nameKey(replicatedChangeEventInfo.getProjectName()),
                Change.id(replicatedChangeEventInfo.getChangeAttr().number));

        Change change = changeNotes.getChange();

        // reworked as part of GER-1767
        // If change will be null its probably either a JSon changed Test case by QE, or somehow we
        // have a stream event coming in after a deletion - either way we can't compare timestamps so lets just
        // indicate missing change, and it will delete all working events before this one and backoff.
        if (change == null) {
          logger.atWarning().log("Change %s was not present in the DB", replicatedChangeEventInfo.getChangeAttr().number);
          throw new ReplicatedEventsMissingChangeInformationException(
              String.format("Change %s was not present in the DB. It was either deleted or will be added " +
                  "by a future event", replicatedChangeEventInfo.getChangeAttr().number));
        }

        logger.atFine().log("RE got change from DB: %s", change);
        getEventBroker().postEvent(change, (ChangeEvent) newEvent);
      } else if (replicatedChangeEventInfo.getBranchName() != null) {
        logger.atFine().log("RE using branchName: %s", replicatedChangeEventInfo.getBranchName());
        getEventBroker().postEvent(replicatedChangeEventInfo.getBranchName(), (RefEvent) newEvent);
      } else if (newEvent instanceof ProjectEvent) {
        getEventBroker().postEvent(newEvent);
      } else {
        logger.atSevere().withCause(new Exception("refs is null for supported event")).log("RE Internal error, it's *supported*, but refs is null");
        throw new ReplicatedEventsUnknownTypeException("RE Internal error, it's *supported*, but refs is null");
      }
    } catch (PermissionBackendException e) {
      logger.atSevere().withCause(e).log("RE While trying to publish a replicated event");

      // Something happened requesting this event information - lets treat at a missing case, and it will retry later.
      throw new ReplicatedEventsMissingChangeInformationException(
          String.format("Change %s was not returned from the DB due to ORMException (maybe it will be later).",
              replicatedChangeEventInfo.getChangeAttr().number));

    }
  }
}
