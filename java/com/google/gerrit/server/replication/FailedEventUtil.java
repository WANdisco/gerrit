
/********************************************************************************
 * Copyright (c) 2014-2018 WANdisco
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
import com.google.gerrit.server.replication.configuration.ReplicatedConfiguration;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;

import java.io.File;
import java.io.IOException;
import java.util.List;

public final class FailedEventUtil {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private FailedEventUtil(){ }


  public static void moveFileToFailed(final ReplicatedConfiguration replicatedConfiguration, final File file) {
    File failedDir = replicatedConfiguration.getIncomingFailedReplEventsDirectory();
    if (!failedDir.exists()) {
      boolean mkdirs = failedDir.mkdirs();
      if (!mkdirs) {
        logger.atSevere().log("Could not create directory for failed directory: %s", failedDir.getAbsolutePath());
        return;
      }
    }

    logger.atWarning().log("Moving event file %s into the failed directory, it can be retried manually later " +
            "by moving it back into the incoming events directory.", file.getAbsolutePath());

    renameFile(file, failedDir);

  }

  /**
   * Renames the file denoted by an abstract pathname.
   * Many aspects of the behavior of this method are inherently platform-dependent:
   *     The rename operation might not be able to move a file from one filesystem to another,
   *     it might not be atomic, and it might not succeed if a file with the destination abstract pathname already exists.
   *
   * The return value should always be checked to make sure that the rename operation was successful.
   * Note that the Files class defines the move method to move or rename a file in a platform independent manner.
   * @param file The file to be renamed
   * @param failedDir The failed directory
   */
  private static void renameFile(File file, File failedDir) {

    boolean renameOp = file.renameTo(new File(failedDir,file.getName()));

    if (!renameOp) {
        logger.atSevere().log("There was an error attempting to rename the file. " +
            "Could not move the file [ %s ] to the failed directory ", file.getAbsolutePath());
    }
  }


  /**
   * Takes a collection of events to persist. The collection will only contain the events that have not succeeded
   * or haven't succeeded yet. This can be as a result of a failure or a DB slow to catch up. This is done in
   * order to reduce the amount of retry work to only include the failed items, or items not succeeded yet.
   * We backoff a file and try items again. This happens x number of times. If there are remaining items in the file
   * after all the back offs, then a file can be moved to the failed directory.
   * This collection of events will be written back to the existing events file in progress atomically.
   * @param replicatedEventsCoordinator : ReplicatedEventsCoordinator instance to required for the
   *                                    PersistedEventInformation constructor
   * @param replicatedEventTask : The ReplicatedEventTask instance is used to get the current event file
   *                            being processed and the name of the associated project for the task.
   * @param remainingEvents : Collection of events which have failed or have not yet succeeded.
   * @throws IOException
   */
  public static void persistRemainingEvents(final ReplicatedEventsCoordinator replicatedEventsCoordinator,
                                            final ReplicatedEventTask replicatedEventTask,
                                            final List<EventWrapper> remainingEvents) throws IOException {

    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(replicatedEventsCoordinator,
            replicatedEventTask.getEventsFileToProcess().getName(), replicatedEventTask.getProjectname());

    for(EventWrapper eventWrapper : remainingEvents){
      persistedEventInformation.appendToFile(eventWrapper, false);
    }

    // Set the file ready and atomically rename the file from its .tmp name back to its original name.
    if(persistedEventInformation.setFileReady()){
      // Then do atomic rename of the .tmp file to its final event file name.
      if (persistedEventInformation.atomicRenameTmpFilename()) {
        // Rename was successful
        logger.atInfo().log("RE Removed completed events from existing event file [ %s ] for project [ %s ].",
            persistedEventInformation.getFinalEventFile().getName(),
            persistedEventInformation.getProjectName());
      }
      return;
    }

    //If we get here we haven't written any of the remaining events to the original file.
    logger.atSevere().log("Unable to write remaining events to %s", replicatedEventTask.getEventsFileToProcess());
  }
}
