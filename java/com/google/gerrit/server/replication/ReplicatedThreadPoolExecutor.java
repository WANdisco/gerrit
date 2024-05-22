package com.google.gerrit.server.replication;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;

import java.io.File;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ReplicatedThreadPoolExecutor extends ThreadPoolExecutor {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final ReplicatedEventsCoordinator replicatedEventsCoordinator;

  // handy metrics, basically we record start time and when we terminate either in a good way or bad, we log out
  // the duration.  It uses the debug / fine output to stop polluting the logs, but can be turned on if required.
  private static final ThreadLocal<Long> startTime = new ThreadLocal<>();

  // allow the executor access to our WIP list.
  public ReplicatedThreadPoolExecutor(ReplicatedEventsCoordinator replicatedEventsCoordinator) {
    super(replicatedEventsCoordinator.getReplicatedConfiguration().getMinNumberOfEventWorkerThreads(),
        replicatedEventsCoordinator.getReplicatedConfiguration().getMaxNumberOfEventWorkerThreads(),
        replicatedEventsCoordinator.getReplicatedConfiguration().getMaxIdlePeriodEventWorkerThreadInSeconds(),
        TimeUnit.SECONDS,
        new LinkedBlockingQueue<Runnable>(replicatedEventsCoordinator.getReplicatedConfiguration().getMaxNumberOfEventWorkerThreads()),
        new ReplicatedThreadFactory(ReplicatedThreadPoolExecutor.class.getSimpleName()));

    logger.atInfo().log("Creating replicated thread pool with: minThreadCount: %s, maxThreadCount: %s, idlePeriod: %s secs queue bounded to: %s",
        replicatedEventsCoordinator.getReplicatedConfiguration().getMinNumberOfEventWorkerThreads(),
        replicatedEventsCoordinator.getReplicatedConfiguration().getMaxNumberOfEventWorkerThreads(),
        replicatedEventsCoordinator.getReplicatedConfiguration().getMaxIdlePeriodEventWorkerThreadInSeconds(),
        replicatedEventsCoordinator.getReplicatedConfiguration().getMaxNumberOfEventWorkerThreads());
    this.replicatedEventsCoordinator = replicatedEventsCoordinator;
  }

  @Override
  protected void beforeExecute(Thread t, Runnable r) {
    super.beforeExecute(t, r);
    logger.atFine().log("Thread %s: start %s", t.getName(), r);
    startTime.set(System.nanoTime());
  }

  @Override
  protected void afterExecute(Runnable r, Throwable t) {
    super.afterExecute(r, t);

    long endTime = System.nanoTime();
    long taskTime = endTime - (long) startTime.get();

    if (t == null && r instanceof Future<?>) {
      // deal with future executions from scheduled pools - which we also allow to be overridden,
      // but if they throw uncaught exception please don't let it kill our gerrit process.
      try {
        ((Future<?>) r).get(); // We don't use result of this - just want to know if it throws.
      } catch (CancellationException ce) {
        t = ce; // just log this as error - who cancelled this future??
      } catch (ExecutionException ee) {
        t = ee.getCause(); // just log this as error - but hide the reason - don't throw it will kill gerrit process.
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt(); // ignore/reset this needs to happen to not block on shutdown.
      }
    }

    // Remove the wip for this runnable / ReplicatedEventTask.
    final ReplicatedEventTask currentTaskInformation =
        (r instanceof ReplicatedEventTask && replicatedEventsCoordinator.isReplicationEnabled()) ? (ReplicatedEventTask) r : null;

    if (currentTaskInformation != null) {
      if (taskTime > 0) {
        // log out the output time for this event processor, and the num events if possible.
        logger.atFine().log("RE Worker Thread finished task: %s, with endTime %d, taskTime=%dns", currentTaskInformation, endTime, taskTime);
      }

      final String projectName = currentTaskInformation.getProjectname();

      // Belts and braces protection here.
      // - JIC something went wrong and an exception has happened somewhere between us scheduling the work in progress
      // and the Run on the ReplicatedEventTask being called ( and not executed ).
      // This would leave the WIP list being stale for this project as nothing would delete the event therefore
      // blocking the pool.
      // Log if this ever happens - its just a belt and braces and I hope it never does.
      // NOTE we must pass true here to only delete the previous replicated task..
      replicatedEventsCoordinator.getReplicatedScheduling().clearEventsFileInProgress(currentTaskInformation, true);

      if ( t == null && replicatedEventsCoordinator.getReplicatedScheduling().isCoreProject(projectName)) {
        replicatedEventsCoordinator.getReplicatedScheduling().tryScheduleExistingSkippedCoreReplicatedEventTask(projectName);
      }
    }

    if (t != null) {
      // Some kind of throwable has happened, we could be in one of 3 states.
      // 1) Replicated Task information doesn't exist - unexpected failure ( unrecoverable ).
      // 2) We have replicated state:
      //    Friendly Logging on Shutdown.
      //    if we happened to be in shutdown, this very well could be an expected issue as the rug is
      //    being pulled out from under lucene etc.
      // 3) Detailed Logging not in shutdown.
      //    Give specific details about this error which happened publishing the events in this file,
      //    so we assist and run failure backoff on this project.
      if ( currentTaskInformation == null ){
        // unexpected failure - as we have no valid replicated task info ?
        logger.atSevere().withCause(t).log("Unexpected exception from a ReplicatedThreadPool worker task, replicated information UNKNOWN.");
        return;
      }

      if ( replicatedEventsCoordinator.getReplicatedScheduling().isSchedulerShutdownRequested() ){
        logger.atInfo().log("ReplicatedThreadPool aborted in progress processing of project: %s, event file: %s - this will be rescheduled upon restart of the gerrit service.",
            currentTaskInformation.getProjectname(),
            currentTaskInformation.getEventsFileToProcess());
        return;
      }

      // Fallback to log this real error as a warning, as we will be picked up for failure / backoff and retried later
      logger.atWarning().withCause(t).log("ReplicatedThreadPool exception details from worker task - project : %s, event file: %s.",
            currentTaskInformation.getProjectname(),
            currentTaskInformation.getEventsFileToProcess());
    }

  }

  @Override
  protected void terminated() {
    try {
      // we could do work here around time, that would cover success and failure timings, but for now leave as is.
      logger.atInfo().log("Terminating ReplicatedThreadPool");
    } finally {
      super.terminated();
    }
  }

}
