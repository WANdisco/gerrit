package com.google.gerrit.server.replication;

import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.replication.configuration.ReplicatedConfiguration;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.server.replication.processors.ReplicatedStoppable;
import com.google.inject.Singleton;
import com.wandisco.gerrit.gitms.shared.util.TimerLogging;

import java.io.File;
import java.io.IOException;
import java.security.InvalidParameterException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Responsible for scheduling the work which is to be completed for events.
 * It is responsible for monitoring the core threads which are reserved along with sending
 * replicated tasks out to the thread pool for completion by any thread in the pool.
 */
@Singleton
public class ReplicatedScheduling implements ReplicatedStoppable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final ConcurrentHashMap<String, ReplicatedEventTask> eventsFilesInProgress; // map of project to event file of work in progress.
  private final HashMap<String, Deque<File>> skippedProjectsEventFiles; // map of project to event file of work in progress.
  /**
   * list of projects that we are to stop processing for a period of time.
   * We do this -> backoff period of time by recording when we added the project(failed the project event)
   * And a max number of failures - see ProjectBackoffPeriod for more information.
   * <p>
   * Note: uses configuration
   * indexMaxNumberBackoffRetries // max number of backoff retries before failing an event group(file).
   * indexBackoffInitialPeriod // back off initial period that we start backoff doubling from per retry.
   * indexBackoffCeilingPeriod // max period in time of backoff doubling before, wait stays at this ceiling
   */
  private final ConcurrentHashMap<String, ProjectBackoffPeriod> skipProcessingAndBackoffThisProjectForNow;
  private final ReplicatedThreadPoolExecutor replicatedWorkThreadPoolExecutor;
  private final ReplicatedEventsCoordinator replicatedEventsCoordinator;
  private final ReplicatedConfiguration replicatedConfiguration;
  private ScheduledFuture<?> scheduledIncomingFuture;
  private ScheduledFuture<?> scheduledOutgoingFuture;
  private ScheduledThreadPoolExecutor workerPool;

  private boolean schedulerShutdownRequested = false;
  private boolean allEventsFilesHaveBeenProcessedSuccessfully = false;
  private long eventDirLastModifiedTime = 0;

  /**
   * We only create this class from the replicatedEventsCoordinator.
   * This is a singleton and it's enforced by our SingletonEnforcement below that if anyone else tries to create
   * this class it will fail.
   * Sorry by adding a getInstance, make this class look much more public than it is,
   * and people expect they can just call getInstance - when in fact they should always request it via the
   * ReplicatedEventsCoordinator.getReplicatedXWorker() methods.
   */
  public ReplicatedScheduling(ReplicatedEventsCoordinator replicatedEventsCoordinator) {

    this.replicatedEventsCoordinator = replicatedEventsCoordinator;
    this.replicatedConfiguration = replicatedEventsCoordinator.getReplicatedConfiguration();

    // we keep a ConcurrentHashMap of the projects which are having events being processed on them, to avoid us having
    // multiple events files for the same project being operated on in parallel - this is quicker than looping
    // the actual thread pool and checking which tasks are in existence. Work in progress event files are added to the
    // map whenever scheduleReplicatedEventsTask() is called successfully. Entries are deleted from the map whenever
    // the event file processing has completed successfully or in error scenarios where the DB is out of date or processing
    // on the event file failed.
    eventsFilesInProgress = new ConcurrentHashMap<>(replicatedEventsCoordinator
        .getReplicatedConfiguration().getMaxNumberOfEventWorkerThreads());

    // We keep a LinkedHashMap of the skipped project events files to allow the following.
    // project A - event 1
    // project A - event 2
    // project B - event 1
    // We need to preserve the ordering of projectA lookups being event1, then event2.
    // we do not order the MAP by project in any way so we can use a standard hashmap for fast lookup of the
    // list/queue used per project stored internally.
    skippedProjectsEventFiles = new HashMap<>();

    // Allow us to skip processing of any project when we hit particular error cases - like DB stale, we back off processing
    // all further events for this project for now, until we finish this iteration.
    skipProcessingAndBackoffThisProjectForNow = new ConcurrentHashMap<>();

    /* Creating a new ScheduledThreadPoolExecutor instance with the given pool size of 2 (one for each worker).
     It is a fixed-sized Thread Pool so once the corePoolSize is given, you can not increase the
     size of the Thread Pool.*/
    workerPool = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(2,
        new ReplicatedThreadFactory("Replicated-Incoming-Outgoing-worker-pool"));

    // pool is started, we will flip this later on shutdown.
    schedulerShutdownRequested = false;

    // make sure to indicate we dont run periodic tasks after the shutdown request, lets clean up cleanly.
    // setContinueExistingPeriodicTasksAfterShutdownPolicy ( Default: False is fine )
    // setExecuteExistingDelayedTasksAfterShutdownPolicy ( Default: True so lets flip this to prevent tasks after shutdown )
    workerPool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);

    // Note the maxNumberOfEventWorkerThreads member is the configuration value, plus the number of reserved core
    // threads. e.g. maxNumber element says 10 and core projects has 2, then maxNumberOfEventWorkerThreads = 12.
    // The idea being that we always keep All-Projects and others processing ahead of other projects, as they have
    // important change schema in them that would prevent other projects working correctly.
    replicatedWorkThreadPoolExecutor = new ReplicatedThreadPoolExecutor(replicatedEventsCoordinator);
    SingletonEnforcement.registerClass(ReplicatedScheduling.class);
  }

  /**
   * This starts the ReplicatedIncomingEventWorker and ReplicatedOutgoingEventWorker threads
   * <p>
   * Scheduling the incomingEventWorker & outgoingEventWorker to execute after an initial period of 500ms
   * and then after that repeats periodically with a delay period specified by getEventWorkerDelayPeriod()
   * <p>
   * N.B :  For the scheduleWithFixedDelay, the period is interpreted as the delay between the end of the previous execution,
   * until the start of the next. The delay is then between finished executions, not between the beginning of executions.
   * This will give the workers the appropriate delay time between finished executions of either
   * pollAndWriteOutgoingEvents or readAndPublishIncomingEvents
   */
  public void startScheduledWorkers() {
    logger.atInfo().log("Scheduled ReplicatedIncomingEventWorker to run at fixed rate of every %d ms with initial delay of %d ms",
        replicatedConfiguration.getEventWorkerDelayPeriodMs(), replicatedConfiguration.getEventWorkerDelayPeriodMs());
    scheduledIncomingFuture =
        workerPool.scheduleWithFixedDelay(replicatedEventsCoordinator.getReplicatedIncomingEventWorker(),
            replicatedConfiguration.getEventWorkerDelayPeriodMs(),
            replicatedConfiguration.getEventWorkerDelayPeriodMs(),
            TimeUnit.MILLISECONDS);

    logger.atInfo().log("Scheduled ReplicatedOutgoingEventWorker to run at fixed rate of every %d ms with initial delay of %d ms",
        replicatedConfiguration.getEventWorkerDelayPeriodMs(), replicatedConfiguration.getEventWorkerDelayPeriodMs());
    scheduledOutgoingFuture =
        workerPool.scheduleWithFixedDelay(replicatedEventsCoordinator.getReplicatedOutgoingEventWorker(),
            replicatedConfiguration.getEventWorkerDelayPeriodMs(),
            replicatedConfiguration.getEventWorkerDelayPeriodMs(),
            TimeUnit.MILLISECONDS);
  }


  @Override
  public void stop() {
    logger.atInfo().log("Shutting down scheduled thread pool for incoming / outgoing workers");

    // lets record the timing and log it at the end ( using try() autoclosable to always log regardless of how we exit ).
    try ( TimerLogging timerLogging = new TimerLogging("ReplicatedThreadPool shutdown completed,")) {
      // Indicate that we are starting shutdown of the incoming/outgoing threads, and the ReplicatedEventThreadPool.
      schedulerShutdownRequested = true;

      // Null checking is being performed here as these scheduledFuture instances are only
      // created upon a lifecycle start. This lifecycle start will not be available in testing however
      // we still have the ability in tests to shutdown the worker pools as they are created upon construction.
      if (scheduledIncomingFuture != null && scheduledOutgoingFuture != null) {
        // Calling cancel on these scheduledFuture instances will attempt to cancel execution of these tasks.
        // This attempt will fail if the tasks have already completed,
        // have already been cancelled, or could not be cancelled for some other reason.
        // Setting boolean true if the thread executing these tasks should be interrupted;
        // otherwise, in-progress tasks are allowed to complete when set to false.

        // Shutdown the outgoing thread, but allow it to finish its current iteration.
        scheduledOutgoingFuture.cancel(false);

        // shutdown incoming but we need to force this - it could go on for ages as it really depends on volume
        // and size of event files being processed - it could have 1000s left to do.
        scheduledIncomingFuture.cancel(true);
      }

      // Shutting down the worker thread pool, the ScheduledExecutorService needs to be shut down
      // when we are finished using it. If not, it will keep the JVM running,
      // even when all other threads have been shut down.
      // We can't afford to wait for this to process maybe 1000 event files, its best to stop it scheduling
      // new work ASAP - regardless of any Interrupted exception thrown - they wont cause any BAD side effects
      workerPool.shutdownNow();

      // quickly on shutdown log what is happening.
      logger.atInfo().log("Logging current state of the ReplicatedThreadPool on shutdown: %s", replicatedWorkThreadPoolExecutor);
      replicatedWorkThreadPoolExecutor.shutdown();
      try {
        // we issued the shutdown - but lets wait and try our best to let it complete before letting
        // the rest of gerrit shutdown.

        // this may throw an interrupted exception if it was interrupted whilst waiting either
        // way we tried our best, we can't wait any longer just let errors happen, we wont delete
        // any scheduled files unless they completed.
        final boolean terminatedOK =
            replicatedWorkThreadPoolExecutor.awaitTermination(5, TimeUnit.SECONDS);

        logger.atInfo().log("replicatedWorkThreadPoolExecutor indicated completed termination: %s",
            terminatedOK );

        if ( !terminatedOK ){
          // we didnt' terminate in time - shutdown NOW forced.
          logger.atInfo().log("replicatedWorkThreadPoolExecutor starting forced shutdown." );
          replicatedWorkThreadPoolExecutor.shutdownNow();
        }
      }
      catch(InterruptedException e){
        // dont really mind - we can log at TRACE
        logger.atFinest().log("replicatedWorkThreadPoolExecutor was interrupted during the awaitTermination with exception: %s", e.getMessage());
      }

      // get the in progress event count for a good indicator if the above worked, it should always be 0 now.
      logger.atInfo().log("Replicated event pool indicated in progress events count: %s",
          getNumEventFilesInProgress());

      //We don't need an awaitTermination here as we don't have anything else to do.
      SingletonEnforcement.unregisterClass(ReplicatedScheduling.class);
    } catch (Exception e) {
      // Ignore any closable exceptions during shutdown
    }
    // Shutting down the worker thread pool, the ScheduledExecutorService needs to be shut down
    // when we are finished using it. If not, it will keep the JVM running,
    // even when all other threads have been shut down.
    // N.B These do not need to be null checked as they are created only upon construction.
    workerPool.shutdownNow();
    // quickly on shutdown log what is happening.
    logger.atInfo().log("Logging current state of the ReplicatedThreadPool on shutdown: %s", replicatedWorkThreadPoolExecutor);
    replicatedWorkThreadPoolExecutor.shutdownNow();
    //We don't need an awaitTermination here as we don't have anything else to do.
    SingletonEnforcement.unregisterClass(ReplicatedScheduling.class);
  }

  /**
   * tryScheduleReplicatedEventsTask is the entrypoint to attempt to schedule a new work item for a replicated events
   * file to be processed.  It works out if it has room in the thread pool to attempt this work. It also queues skipped
   * items so that we know not to work ahead of ourselves later on with another file.
   * <p>
   * Logical responses:
   * 4 threads.  2Core 2 project-worker
   * AllProject, ProjectA, ProjectB
   * Schedule new entry as follows should give:
   * AllProjects - false
   * AllUsers - true
   * ProjectC - false
   * <p>
   * AllProject, AllUsers, ProjectB
   * AllProjects - false
   * AllUsers - false
   * ProjectC - true
   * ( NB only exception to this is if projectC has an already skipped event in our skip list,
   * we take it instead of this event, and mark it skipped instead, like a swap out. )
   */
  public ReplicatedEventTask tryScheduleReplicatedEventsTask(final String projectName, final File eventsFileToProcess) {

    // check if we can schedule this task - if this project is already in progress in the queue already,
    // we do not schedule it, but instead return false, and this file is left alone and skipped over for now.
    if (Strings.isNullOrEmpty(projectName)) {
      throw new InvalidParameterException("projectName must be supplied and not null.");
    }

    // We could take a potentially dirty copy of what is in progress at this point in time,
    // We don't care if someone finishes while I make my decisions as long as we are the
    // only person scheduling new work - it doesn't matter.  Just ensure to keep the
    // schedule method below private and only used / protected by this method/lock.
    synchronized (getEventsFileInProgressLock()) {

      // We need to check have we got max number of threads / wip.  If we have don't queue anymore at moment,
      // onto next file please.
      if (getNumEventFilesInProgress() >= replicatedConfiguration.getMaxNumberOfEventWorkerThreads()) {
        // we have the max number of threads in progress - just return now and we will re-queue this later.
        // Add this file to the skipped list, for decision-making later on.
        addSkippedProjectEventFile(eventsFileToProcess, projectName);
        logger.atInfo().atMostEvery(replicatedConfiguration.getLoggingMaxPeriodValueMs(), TimeUnit.MILLISECONDS)
            .log("Not scheduling event file: %s. for project: %s, as all worker threads are busy for now, please consider increasing the worker pool. ConfigurationDetails: %s. ",
                eventsFileToProcess, projectName, replicatedConfiguration);
        return null;
      }

      final boolean hasNonCoreEventProcessingHeadroom = getHasNonCoreEventProcessingHeadroom();

      if (shouldStillSkipThisProjectForNow(projectName)) {
        // we have to skip all processing I could add this to the skipped list, and I will just for metrics of how many
        // skipped at the end.
        addSkippedProjectEventFile(eventsFileToProcess, projectName);
        logger.atFine().log("Not scheduling event file: %s. for project: %s, as we are skipping / backing off this project for now.", eventsFileToProcess, projectName);
        return null;
      }

      if (eventsFilesInProgress.containsKey(projectName)) {
        // make a quick check to decide whether this is a skipped over file, or if its actually in progress from an earlier
        // iteration.
        ReplicatedEventTask wipTask = eventsFilesInProgress.get(projectName);
        if (wipTask.getEventsFileToProcess().equals(eventsFileToProcess)) {
          // this exact file is in progress - lets just skip over it, but DO NOT add to skip list as its in progress
          // if it succeeds its completed, and if it fails its prepended onto the start of the skipped list.
          logger.atFine().log("Not scheduling file: %s as its in progress for the project already. ", eventsFileToProcess);
          return null;
        }
        // We already have this project in progress, just schedule this as a skipped entry for this project so we can
        // come back to it.
        addSkippedProjectEventFile(eventsFileToProcess, projectName);
        logger.atFine().log("Not scheduling event file: %s. for project: %s, as we have already a project in progress for this project.", eventsFileToProcess, projectName);
        return null;
      }

      boolean isCoreProject = isCoreProject(projectName);

      // If its a core project, and we haven't core project in progress - just schedule now - simple decision as we
      // have a reserved thread for these types.
      if (isCoreProject) {
        return scheduleReplicatedEventsTask(projectName, eventsFileToProcess);
      }

      if (!hasNonCoreEventProcessingHeadroom) {
        // we are close to the limit - and no room for new non-core projects - bounce this one.
        addSkippedProjectEventFile(eventsFileToProcess, projectName);
        logger.atInfo().atMostEvery(replicatedConfiguration.getLoggingMaxPeriodValueMs(), TimeUnit.MILLISECONDS)
            .log("Not scheduling event file: %s. for project: %s, as all (project-only) worker threads are busy for now, please consider increasing the worker pool. ConfigurationDetails: %s. ",
                eventsFileToProcess, projectName, replicatedConfiguration);
        return null;
      }

      // ok it looks like this project isn't in progress - lets go and queue it.
      return scheduleReplicatedEventsTask(projectName, eventsFileToProcess);
    }
  }

  /**
   * Lets see if we can schedule another piece of work from our existing backlog.
   * If we have headroom available, check the oldest ( not in progress non core project ) and if we have any available.
   * Then try to schedule it.
   *
   * @return ReplicatedEventTask
   */
  public ReplicatedEventTask tryScheduleExistingSkippedNonCoreReplicatedEventTask() {

    ReplicatedEventTask oldestEventReplicatedInfo;

    // Before checking any counters - we need to be inside in the inprogress lock.
    // This will make sure that any assumptions about work in progress are made safely, yes work might finish
    // and make more threads available - but no new work can be scheduled without this.
    synchronized (getEventsFileInProgressLock()) {
      if (!getHasNonCoreEventProcessingHeadroom()) {
        // sorry we dont have any headroom currently for any non-core events.
        // just exit now and carry on.
        return null;
      }

      // get the oldest event information for any non core project that has skipped event files.
      oldestEventReplicatedInfo = getOldestNonCoreProjectEvent();
    }

    // at this point if we have any event lets use it.
    if (oldestEventReplicatedInfo == null) {
      // nothing to schedule - lets exit.
      return null;
    }

    // we can schedule it now as the oldest we have for a non core project.
    return tryScheduleReplicatedEventsTask(oldestEventReplicatedInfo.getProjectname(), oldestEventReplicatedInfo.getEventsFileToProcess());
  }

  /**
   * Work through the entire skipped list of project information.
   * Take the oldest event from each non core project, and remember the oldest.
   * Finally return this oldest member when all non-core projects have been checked.
   *
   *
   * @return oldest event information, or if none are available simply return null.
   */
  public ReplicatedEventTask getOldestNonCoreProjectEvent() {

    File oldestEvent = null;
    String oldestEventProjectName = null;

    // now that we have headroom, we need to go through our map of skipped over events, and if there are any,
    // keep the oldest event file for its project. Iterate all non core projects, keeping the oldest pointer.
    // Do not consider any projects that are backed off for failure reason - as they can't be scheduled :p
    // NOTE: Noone but this scheduler has access to add / remove items from the skipped list.
    // Only exception is the request for new work for core projects by its thread worker but even it is protected by
    // the same lock - getEventsFileInProgressLock
    for (Map.Entry<String, Deque<File>> skippedProjectEventFiles : skippedProjectsEventFiles.entrySet()) {
      final String projectName = skippedProjectEventFiles.getKey();
      if (isCoreProject(projectName)) {
        continue;
      }

      // otherwise we have a normal project.
      // if its backed off - check should we still be skipping it.
      if (shouldStillSkipThisProjectForNow(projectName)) {
        // sorry we should still be skipping this project
        continue;
      }

      // check is this project in progress already, if so skip over it.
      if (hasProjectEventInProgress(projectName)){
        continue;
      }

      final File oldestEventForThisProject = getFirstSkippedEventFileForProject(projectName);
      if (oldestEventForThisProject == null) {
        // there is nothing in the queue for this project, its empty ( all items have already been processed )
        continue;
      }

      // ok we have the oldest event for this project, is it older or younger then our current oldest.
      // we can alpha numerical compare the 2 event file names, as we event timestamp is the left most priority.
      // we used this so we can simple arrays.sort a file list and get it into correct ordering across all projects.
      if (oldestEvent == null) {
        // initial record - lets remember this one and move on.
        oldestEvent = oldestEventForThisProject;
        oldestEventProjectName = projectName;
        continue;
      }

      if (oldestEventForThisProject.getName().compareTo(oldestEvent.getName()) < 0) {
        // the name is less than, which alphanumerically means this name is older as we use the timestamp then millisec from left to right
        // take this as the oldest event and project now.
        oldestEvent = oldestEventForThisProject;
        oldestEventProjectName = projectName;
      }
    }

    if ( oldestEvent == null ){
      return null;
    }

    // otherwise create the basic skelton of a replicated event task to convey the min amount of info to attempt
    // to schedule this.
    return new ReplicatedEventTask(oldestEventProjectName, oldestEvent, replicatedEventsCoordinator);
  }

  /**
   * tryScheduleExistingSkippedCoreReplicatedEventTask is used from a very specific context, which
   * is from within a worker thread afterExecute.  This allows core projects, to check and pick
   * up the next bit of work for that specific core project when it finished its previous item.
   * This keeps us processing core projects as quickly as we possibly can, as its a pull operation
   * rather than a push operation which is how normal items are scheduled.
   *
   * Note there is no checking for available headroom here as this is called within a worker thread.
   *
   * @param projectName ( core project name to be checked ).
   * @return the replicated event task
   */
  public ReplicatedEventTask tryScheduleExistingSkippedCoreReplicatedEventTask(final String projectName) {

    if (!isCoreProject(projectName)){
      logger.atSevere().log(
          "tryScheduleExistingSkippedCoreReplicatedEventTask can only be used by CORE project worker threads, it was called for project: %s", projectName);
      // We would have thrown here but it is difficult to control recovery expectations
      // from the worker thread if this happened.  Its simpler to say we couldn't schedule by returning null
      return null;
    }

    // we have a core project - lets see if we have already another item queued for this project - if not we will schedule the next
    // oldest bit of work for this core project before we exit.
    // let's lock and delete the file along with update the in progress map as a joint operation.
    synchronized (getEventsFileInProgressLock()) {
      // we have the lock - is anything in progress for this project - if so the normal scheduler has beat us to it.
      if (hasProjectEventInProgress(projectName)) {
        // nothing more to do - get out
        logger.atFinest().log("Core Project: %s already has another task scheduled for it already.", projectName);
        return null;
      }

      // ok there is no event file in progress now for this project - lets get the oldest one and use it.
      final File oldestEventFileSkippedForCoreProject = getFirstSkippedEventFileForProject(projectName);

      if (oldestEventFileSkippedForCoreProject == null) {
        // we have no more work available to be performed, we can just exit now.
        return null;
      }

      // now we have the oldest and we still have the overall lock - lets try to schedule this as the next bit of work, this will of course
      // make all the correct checks etc, but this is just the same type of oldest event scheduling we do for the NonCore projects in
      // the incoming worker just before we process each new file.
      ReplicatedEventTask rt = tryScheduleReplicatedEventsTask(projectName, oldestEventFileSkippedForCoreProject);
      if ( rt != null ){
        // successfully scheduled next core project task from the worker thread...
        // if it wasn't scheduled it maybe the project has been backed off due to failure so dont log it as an error.
        logger.atFinest().log("Successfully scheduled more work for the coreproject: %s from remote worker.", projectName);
      }
      return rt;
    }
  }

  private boolean getHasNonCoreEventProcessingHeadroom() {
    // Now before we add it to the schedule, if its not in the reserved list, we are currently processing
    // a normal project.  So lets check our headroom i.e. NumWorkers - CoreProjectsInFlight.
    final int numCoreProjectsInProgress = getNumberOfCoreProjectsInProgress();
    final int numEventFilesInProgress = getNumEventFilesInProgress();

    final boolean hasNonCoreEventProcessingHeadroom =
        (numEventFilesInProgress - numCoreProjectsInProgress) < replicatedConfiguration.getNumberOfNonCoreWorkerThreads();

    return hasNonCoreEventProcessingHeadroom;
  }

  /**
   * Used to get hold of the threadpool, useful to dump out the current state of the pool, num in queue,
   * num workers busy etc.
   */
  public ReplicatedThreadPoolExecutor getReplicatedWorkThreadPoolExecutor() {
    return replicatedWorkThreadPoolExecutor;
  }

  public boolean isAllWorkerThreadsActive() {
    return replicatedWorkThreadPoolExecutor.getActiveCount() == replicatedWorkThreadPoolExecutor.getMaximumPoolSize();
  }

  public boolean isCoreProject(String projectName) {
    return replicatedConfiguration.isCoreProject(projectName);
  }

  /**
   * Add a file for a given project to the skipped event files map per project, this allows correct ordering
   * so that a given event file can be plucked from the FIFO when the backoff failure period has elapsed.
   */
  public void addSkippedProjectEventFile(File eventsFileToProcess, String projectName) {

    // This shouldn't be necessary to lock as the only person adding to skipped events and then working on it is the
    // same scheduling thread, but to protect us from future changes adding the lock here.
    synchronized (getEventsFileInProgressLock()) {
      if (!getSkippedProjectsEventFiles().containsKey(projectName)) {
        getSkippedProjectsEventFiles().put(projectName, new ArrayDeque<File>());
        logger.atFine().log("Creating new ArrayDeque pointing key for project: %s", projectName);
      }

      // get the queue
      Deque<File> eventsBeingSkipped = getSkippedProjectsEventFiles().get(projectName);
      if (eventsBeingSkipped.contains(eventsFileToProcess)) {
        logger.atWarning().log("Caller has attempted to add an already skipped over event file to the end of the list - track this down eventFile: %s.",
            eventsFileToProcess);
        return;
      }

      eventsBeingSkipped.addLast(eventsFileToProcess);
    }
  }

  /**
   * Add a file for a given project to the skipped event files map per project but to the start of the list.
   * This allows us to maintain correct ordering for failed events which need to go back onto the start of
   * the skipped over list, and not to the end
   */
  public void prependSkippedProjectEventFile(File eventsFileToProcess, String projectName) {
    // This shouldn't be necessary to lock as the only person adding to skipped events and then working on it is the
    // same scheduling thread, but to protect us from future changes adding the lock here.
    synchronized (getEventsFileInProgressLock()) {
      if (!getSkippedProjectsEventFiles().containsKey(projectName)) {
        getSkippedProjectsEventFiles().put(projectName, new ArrayDeque<File>());
        logger.atFine().log("PrependSkippedProjectEventFile: Creating new ArrayDeque pointing key for project: %s", projectName);
      }

      Deque<File> eventsBeingSkipped = getSkippedProjectsEventFiles().get(projectName);
      if (eventsBeingSkipped.contains(eventsFileToProcess)) {
        logger.atWarning().log("Caller has attempted to prepend an already skipped over event file to the end of the list - track this down eventFile: %s.",
            eventsFileToProcess);
        return;
      }

      eventsBeingSkipped.addFirst(eventsFileToProcess);
    }
  }

  /**
   * Return the actual hashmap of all events being skipped for all projects.
   */
  public HashMap<String, Deque<File>> getSkippedProjectsEventFiles() {
    return skippedProjectsEventFiles;
  }


  public void removeSkippedProjectEventFile(File eventsFileToProcess, String projectName) {
    if (!getSkippedProjectsEventFiles().containsKey(projectName)) {
      logger.atWarning().log("Unable to delete skipped event file for project %s as its already gone", projectName);
      return;
    }

    getSkippedProjectsEventFiles().get(projectName).remove(eventsFileToProcess);

    if (getSkippedProjectsEventFiles().containsKey(projectName) &&
        getSkippedEventFilesQueueForProject(projectName) != null &&
        getSkippedEventFilesQueueForProject(projectName).isEmpty()) {
      // lets remove this project set now - so its null and no key exists pointing to it
      getSkippedProjectsEventFiles().remove(projectName);
      logger.atFine().log("Deleting pointing key as no items remain in the set for project: %s", projectName);
    }
  }

  public File getFirstSkippedEventFileForProject(String projectName) {
    if (!getSkippedProjectsEventFiles().containsKey(projectName)) {
      return null;
    }

    // get first item in our linked set.
    return getSkippedProjectsEventFiles().get(projectName).getFirst();
  }

  public Deque<File> getSkippedEventFilesQueueForProject(String projectName) {
    if (!getSkippedProjectsEventFiles().containsKey(projectName)) {
      return null;
    }

    // get the queue for this project
    return getSkippedProjectsEventFiles().get(projectName);
  }

  public boolean containsSkippedEventFilesForProject(String projectName) {
    return getSkippedProjectsEventFiles().containsKey(projectName);
  }

  public boolean isAllEventsFilesHaveBeenProcessedSuccessfully() {
    return allEventsFilesHaveBeenProcessedSuccessfully;
  }

  public void setAllEventsFilesHaveBeenProcessedSuccessfully(boolean allEventsFilesHaveBeenProcessedSuccessfully) {
    this.allEventsFilesHaveBeenProcessedSuccessfully = allEventsFilesHaveBeenProcessedSuccessfully;
  }
  public boolean isSchedulerShutdownRequested() {
    return schedulerShutdownRequested;
  }
  public long getEventDirLastModifiedTime() {
    return eventDirLastModifiedTime;
  }

  public void setEventDirLastModifiedTime(long eventDirLastModifiedTime) {
    this.eventDirLastModifiedTime = eventDirLastModifiedTime;
  }

  public int getNumEventFilesInProgress() {
    return eventsFilesInProgress.size();
  }

  public int getNumberOfCoreProjectsInProgress() {
    int numCoreProjectsInProgress = 0;

    for (String project : replicatedEventsCoordinator.getReplicatedConfiguration().getCoreProjects()) {
      if (eventsFilesInProgress.containsKey(project)) {
        // its got this project bump core project wip counter.
        numCoreProjectsInProgress++;
      }
    }

    return numCoreProjectsInProgress;
  }

  /**
   * we have skipped processing events if we have anything in the skipped in this iteration flagged,
   * or anything in the skipped events list.
   */
  public boolean hasSkippedAnyEvents() {
    return !(skipProcessingAndBackoffThisProjectForNow.isEmpty() && skippedProjectsEventFiles.isEmpty());
  }

  /**
   * Create a new ReplicatedEventTask and schedule it to be picked
   * up by the waiting thread pool of workers.
   */
  private ReplicatedEventTask scheduleReplicatedEventsTask(final String projectName, final File eventsFileToProcess) {
    // It should always be protected and only called from trySchedule, calling lock again will have no impact
    // when our thread holds the lock already - shouldn't need reentrant locking.
    synchronized (getEventsFileInProgressLock()) {
      // create the task and add it to the queue for awaiting thread pool to pick up when free / might even
      // start up a new thread, if we are less than max pool size.

      // This is a cool piece of logic where we have worked out we can do a project e.g. projectC, but if we have a event
      // already skipped for this said project we can swap it out and do that event now instead...
      final File eventsFileToReallyProcess = checkSwapoutSkippedProjectEvent(eventsFileToProcess, projectName);

      // if something went wrong we can skip a project even at this late state..
      if (eventsFileToReallyProcess == null) {
        logger.atWarning().log("Something went wrong considering event file: %s, it will be picked up later.", eventsFileToProcess);
        return null;
      }

      // Otherwise create a new task for this event file given and let's schedule/execute it now.
      final ReplicatedEventTask newTask = new ReplicatedEventTask(projectName, eventsFileToReallyProcess, replicatedEventsCoordinator);

      // We only call execute if we actually want to run these remote tasks. for testing, I allow them to be queued, but not
      // really removed from the queue by the remote thread - the easiest way is to check the indexer really running state.
      if (replicatedEventsCoordinator.isReplicationEnabled()) {
        // Keep this order, if it fails to queue the task - it will throw, and not add to inProgress map.
        replicatedWorkThreadPoolExecutor.execute(newTask);
      }
      addEventsFileInProgress(newTask);
      return newTask;
    }
  }

  /**
   * DO NOT CALL THIS for anything other than testing....
   */
  public void addForTestingInProgress(final ReplicatedEventTask newTask) {
    addEventsFileInProgress(newTask);
  }

  /**
   * Add a project specific events task to our list of Events in progress.
   */
  private void addEventsFileInProgress(final ReplicatedEventTask newTask) {
    if (newTask == null) {
      logger.atSevere().log("Null task supplied - can't do anything with this.");
      return;
    }

    // We only put the events file in progress into the WIP map after we call execute as we are in a lock here, and if
    // execute throws because the event can't be queued we dont want it in this map of in progress.
    synchronized (eventsFilesInProgress) {
      if (eventsFilesInProgress.containsKey(newTask.getProjectname())) {
        logger.atWarning().log("RE eventsFileInProgress already contains a project ReplicatedEventTask %s for this project, attempting self heal!", newTask);
      }
      // this will force update the WIP - we should never be overwriting an entry - means we have scheduled the same project
      // twice - which isn't correct.investigate.
      eventsFilesInProgress.put(newTask.getProjectname(), newTask);
    }
  }

  /**
   * Clear out an event from being in progress so we can queue another one.
   * <p>
   * Normally a failure to remove we want to log, but the item might already have been deleted, so we pass
   * and allow items to not exist as an option (used by afterExecute) to make sure our list stays clean and can heal
   * in unforseen circumstances.
   */
  public void clearEventsFileInProgress(ReplicatedEventTask newTask, boolean allowItemToNotExist) {

    synchronized (eventsFilesInProgress) {
      // We only put the events file in progress into the WIP map after we call execute as we are in a lock here, and if
      // execute throws because the event can't be queued we don't want it in this map of in progress.
      if (!eventsFilesInProgress.remove(newTask.getProjectname(), newTask)) {
        if (allowItemToNotExist) {
          return;
        }
        logger.atSevere().log("Something strange happened clearing out wip for events file: %s, clearing this entire project to recover.", newTask);
        eventsFilesInProgress.remove(newTask.getProjectname());
      }
    }
  }

  /**
   * Returns a shallow copy of the items in the map, so we have a snapshot in time of what
   * was inprogress - usually taken to match the current listing of event files information.
   */
  public HashMap<String, ReplicatedEventTask> getCopyEventsFilesInProgress() {
    // Take a copy now to protect against changes later on.
    // take under lock to prevent external effect.
    synchronized (getEventsFileInProgressLock()) {
      HashMap<String, ReplicatedEventTask> shallowCopy = new HashMap<>(); // this isn't concurrent as doesn't get updated.
      shallowCopy.putAll(eventsFilesInProgress);
      return shallowCopy;
    }
  }

  /**
   * We use the literal class we are updating as the lock as its eagerly initialized
   * and always present. But could also be any static object.
   */
  public Object getEventsFileInProgressLock() {
    return eventsFilesInProgress;
  }

  /**
   * TRUE = Contains a event in progress for this project.
   */
  public boolean hasProjectEventInProgress(final String projectname) {
    // do we have a WIP for this project.
    return eventsFilesInProgress.containsKey(projectname) && (eventsFilesInProgress.get(projectname) != null);
  }

  /**
   * Very specific code, to check if this project has skipped projects already in existence.
   * If it does it adds this event file to the end of the skipped list and then takes the first one out FIFO,
   * therefore it will perform the file it just took of the list as the next event to be processed keeping existing
   * ordering for this project intact.
   */
  private File checkSwapoutSkippedProjectEvent(final File eventsFileToProcess, final String projectName) {
    // Only one caller / thread ever checks this, and only the same thread can swap out,
    // so no locking needed currently but for clarity I am locking - it won't add contention but helps for clarity
    // that we can't be interrupted.
    synchronized (getEventsFileInProgressLock()) {
      if (!skippedProjectsEventFiles.containsKey(projectName)) {
        return eventsFileToProcess;
      }

      // time to swap it out, note this specifically allows the exception of being called with the oldest event for this project.
      // in this very specific case, the list stays as it is, and the getFirstSkippedProject call just takes the HEAD of the list.
      // anything else must only add new files to this skipped list, which get added to the end.
      final File oldestEventFileSkipped = getFirstSkippedEventFileForProject(projectName);

      if (oldestEventFileSkipped != null && oldestEventFileSkipped.equals(eventsFileToProcess)) {
        // if this event being skipped over is HEAD ( oldest event ) we allow us to schedule the oldest work element, and
        // its not really a swap out - its allowed to use the existing file,
        // lets remove the item we got back.
        removeSkippedProjectEventFile(oldestEventFileSkipped, projectName);
        logger.atInfo().log("Allow scheduling of the oldest event - supplied file: %s being scheduled from the skipped list for project: %s",
            oldestEventFileSkipped, projectName);
        return oldestEventFileSkipped;
      }

      // otherwise lets add and check again fresh.
      addSkippedProjectEventFile(eventsFileToProcess, projectName);

      final File eventsFileToReallyProcess = getFirstSkippedEventFileForProject(projectName);

      if (eventsFileToReallyProcess.equals(eventsFileToProcess)) {
        logger.atSevere().log("Invalid ordering of events processing discovered, where it picked up last event as LIFO not FIFO. File: %s", eventsFileToProcess);
        // Failing this entire project, and do no more processing on it.
        // When we finish this iteration we can pick up this project fresh with new state - and hopefully recover!
        addSkipThisProjectsEventsForNow(projectName);
        return null;
      }

      // lets remove the item we got back.
      removeSkippedProjectEventFile(eventsFileToReallyProcess, projectName);
      logger.atInfo().log("Swapped out supplied file: %s for an earlier file in the skipped list: %s",
          eventsFileToProcess, eventsFileToReallyProcess);
      return eventsFileToReallyProcess;
    }
  }

  /**
   * Clear the entire list of skipped over event files - this is created uniquely per iteration.
   */
  public void clearSkippedProjectsEventFiles() {
    skippedProjectsEventFiles.clear();
  }

  /**
   * Skip over this projects events for all events, this one and upcoming until we finish this processing iteration
   * then we can re-evaluate it all again. Useful for backing off a project while the DB catches up.
   */
  public synchronized void addSkipThisProjectsEventsForNow(final String projectName) {
    skipProcessingAndBackoffThisProjectForNow.put(projectName, new ProjectBackoffPeriod(projectName, replicatedConfiguration));
  }

  /**
   * clear all projects from the list,
   * we do this when we start a new iteration of the WIP / events files.
   */
  public synchronized void clearAllSkipThisProjectsEventsForNow() {
    skipProcessingAndBackoffThisProjectForNow.clear();
  }

  /**
   * clear just this specific project from the list of skipped events information
   * Used when a event file has maxed out its retries and moves to failed, or in fact has processed successfully.
   */
  public synchronized void clearSkipThisProjectsEventsForNow(final String projectName) {
    skipProcessingAndBackoffThisProjectForNow.remove(projectName);
  }

  /**
   * Returns true if this project should have no further events processed for it, for this iteration.
   */
  public synchronized boolean containsSkipThisProjectForNow(final String projectName) {
    return skipProcessingAndBackoffThisProjectForNow.containsKey(projectName);
  }

  /**
   * used by tests for confirmation of data - mostly the real code uses the shouldStillSkipThisProjectForNow below.
   */
  public synchronized ProjectBackoffPeriod getSkipThisProjectForNowBackoffInfo(final String projectName) {
    return skipProcessingAndBackoffThisProjectForNow.get(projectName);
  }

  /**
   * get the current time in ms, and compare against a project in the skip list start time.
   * <p>
   * if no project in list ( isn't a real value use case ) is used, it will return false, as
   * if a project was there but is no longer to be skipped for ease of use.
   */
  public synchronized boolean shouldStillSkipThisProjectForNow(final String projectName) {
    if (!skipProcessingAndBackoffThisProjectForNow.containsKey(projectName)) {
      return false;
    }
    ProjectBackoffPeriod skipInfo = skipProcessingAndBackoffThisProjectForNow.get(projectName);

    long startTime = skipInfo.getStartTimeInMs();
    long currentTime = System.currentTimeMillis();
    long maxBackOffPeriod = skipInfo.getCurrentBackoffPeriodMs();

    if ((currentTime - startTime) > maxBackOffPeriod) {
      // Decided to retry this project eventually.
      logger.atFine().log("Decided we might be able to reschedule work for skipped project: %s",
          skipInfo);
      return false;
    }

    return true;
  }
}
