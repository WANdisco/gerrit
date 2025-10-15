
/********************************************************************************
 * Copyright (c) 2014-2021 WANdisco
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Apache License, Version 2.0
 *
 ********************************************************************************/

package com.google.gerrit.server.replication;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import com.wandisco.gerrit.gitms.shared.events.exceptions.InvalidEventJsonException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.security.InvalidParameterException;
import java.util.List;
import java.util.Objects;

import static com.google.gerrit.server.replication.workers.ReplicatedIncomingEventWorker.readFileToByteArrayOutputStream;


public class ReplicatedEventTask implements Runnable {

  private final String projectName;
  private final File eventsFileToProcess;
  private final ReplicatedEventsCoordinator replicatedEventsCoordinator;
  // this is filled in later on, only when we open a replicated event task and actually start to process it.
  // Its useful to know when we complete a replicated task how many events are within the task file.
  private long numEventsToProcess;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public String getProjectname() {
    return projectName;
  }

  public File getEventsFileToProcess() {
    return eventsFileToProcess;
  }

  public ReplicatedEventTask(final String projectName, final File eventsFile, ReplicatedEventsCoordinator replicatedEventsCoordinator) throws InvalidParameterException {
    this.projectName = projectName;
    this.eventsFileToProcess = eventsFile;
    this.replicatedEventsCoordinator = replicatedEventsCoordinator;
    numEventsToProcess = 0;

    if (!eventsFileToProcess.exists()) {
      throw new InvalidParameterException(
          String.format("Replicated Events file: %s for project: %s does not exist at the path supplied.",
              eventsFile.getAbsolutePath(), projectName));
    }
  }

  @Override
  public void run() {
    // Run main processing on the events file we have been supplied, if we are asked to shutdown, do not
    // delete this events file, let it sit here, and it will be processed when the server next comes alive.!
    logger.atFine().log("ReplicatedEventsThread about to start new task, processing project: %s, with eventsFile: %s",
        projectName, eventsFileToProcess.getAbsolutePath());

    if (!replicatedEventsCoordinator.isReplicationEnabled()) {
      // if the indexer isn't really running we could read the file but couldn't do anything with it.
      // This is used by our unit tests, so lets exit now and keep the queue correct for testing.
      logger.atFine().log("GerritIndexerRunning is false = Skipping work in worker thread for project %s on eventFile: %s",
          projectName, eventsFileToProcess.getAbsolutePath());
      return;
    }

    try (ByteArrayOutputStream bos = readFileToByteArrayOutputStream(eventsFileToProcess, replicatedEventsCoordinator
                 .getReplicatedConfiguration().isIncomingEventsAreGZipped())) {

      // we used to process the events directly - but instead, we need to check what projects its for,
      // and try to hand off this file for another thread to do the processing.
      // handy to know when not to process ( used also by unit tests to test scheduling )
      replicatedEventsCoordinator.getReplicatedIncomingEventWorker()
          .processEventInformationBytes(bos.toByteArray(), this);

      // When we finish the task, do we update the WIP list from here?
      // do we need to signal finished?
    } catch (IOException e) {
      //If we throw for any reason then we need to check for failure backoff. For example the bos wasn't
      //returned due to file corruption or file system problem etc we at least backoff and retry several times
      //before moving to the failed folder.
      logger.atSevere().withCause(e).log("Problem when dealing with events byte stream. %s", e.getMessage());
      replicatedEventsCoordinator.getReplicatedIncomingEventWorker().checkForFailureBackoff(this,
          replicatedEventsCoordinator.getReplicatedScheduling(), false, false,
          null, null);
    }
  }

  /**
   * Perform only a subset of calls, to read the file, and get the event wrappers all the same way that
   * the real code does, just doesn't increment the metrics, or publish the events to gerrit.
   *
   * TEST ONLY
   */
  public List<EventWrapper> getEventsDirectlyFromFile() throws IOException, InvalidEventJsonException {

    try (ByteArrayOutputStream bos = readFileToByteArrayOutputStream(eventsFileToProcess, replicatedEventsCoordinator
        .getReplicatedConfiguration().isIncomingEventsAreGZipped())) {

      // we used to process the events directly - but instead, we need to check what projects its for,
      // and try to hand off this file for another thread to do the processing.
      // handy to know when not to process ( used also by unit tests to test scheduling )
      return replicatedEventsCoordinator.getReplicatedIncomingEventWorker().checkAndSortEvents(bos.toByteArray());
    }
  }

  public long getNumEventsToProcess() {
    return numEventsToProcess;
  }

  public void setNumEventsToProcess(long numEventsToProcess) {
    this.numEventsToProcess = numEventsToProcess;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ReplicatedEventTask)) return false;
    ReplicatedEventTask that = (ReplicatedEventTask) o;
    return Objects.equals(projectName, that.projectName) &&
        Objects.equals(eventsFileToProcess, that.eventsFileToProcess);
  }

  @Override
  public int hashCode() {
    return Objects.hash(projectName, eventsFileToProcess);
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("ReplicatedEventTask{");
    sb.append("projectName='").append(projectName).append('\'');
    sb.append(", eventsFileToProcess=").append(eventsFileToProcess);
    sb.append(", replicatedEventsCoordinator=").append(replicatedEventsCoordinator);
    sb.append('}');
    return sb.toString();
  }

  public String toFriendlyInfo() {
    final StringBuilder sb = new StringBuilder("ReplicatedEventTask{");
    sb.append("projectName='").append(projectName).append('\'');
    sb.append(", eventsFileToProcess=").append(eventsFileToProcess.getName());
    sb.append('}');
    return sb.toString();
  }
}
