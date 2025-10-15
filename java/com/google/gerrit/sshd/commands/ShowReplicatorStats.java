/* ******************************************************************************
 * Copyright (c) 2014-2024 WANdisco
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Apache License, Version 2.0
 *
 ********************************************************************************/

package com.google.gerrit.sshd.commands;

import com.google.common.collect.ImmutableMultiset;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.common.Version;

import static com.google.gerrit.server.permissions.GlobalPermission.VIEW_REPLICATOR_STATS;
import static com.google.gerrit.sshd.CommandMetaData.Mode.MASTER_OR_SLAVE;

import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.extensions.annotations.RequiresCapability;
import com.google.gerrit.extensions.events.LifecycleListener;

import com.google.gerrit.server.replication.ReplicatorMetrics;
import com.google.gerrit.server.replication.configuration.ReplicatedConfiguration;
import com.google.gerrit.server.util.time.TimeUtil;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;

import com.google.inject.Inject;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.channel.ChannelSession;

import com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Locale;

/**
 * Show the current WANdisco Replicator Statistics
 */
@RequiresCapability(GlobalCapability.VIEW_REPLICATOR_STATS)
@CommandMetaData(name = "show-replicator-stats", description = "Display statistics from the WD replicator",
    runsAt = MASTER_OR_SLAVE)
final class ShowReplicatorStats extends SshCommand {
  private static volatile long serverStarted;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject private CurrentUser currentUser;
  @Inject private PermissionBackend permissionBackend;
  @Inject private ReplicatedConfiguration replicatedConfiguration;

  static class StartupListener implements LifecycleListener {
    @Override
    public void start() {
      serverStarted = TimeUtil.nowMs();
    }

    @Override
    public void stop() {
    }
  }

  @Override
  public void start(ChannelSession session, final Environment env) throws IOException {
    super.start(session, env);
  }

  @Override
  protected void run() throws Failure {

    try {
      permissionBackend.user(currentUser).check(VIEW_REPLICATOR_STATS);
    } catch (@SuppressWarnings("UnusedException") AuthException | PermissionBackendException ex) {
      String msg = String.format("fatal: %s does not have \"View Replicator Stats\" capability.",
          currentUser.getUserName());
      logger.atSevere().withCause(ex).log(msg);
      throw new UnloggedFailure(msg);
    }

    printStatsTable();
  }


  /**
   * Utility method for printing the replicator statistics table.
   */
  private void printStatsTable() {
    final Instant now = Instant.now();
    final Duration uptime = Duration.between(Instant.ofEpochMilli(serverStarted), now);
    final String gerritVersion = Version.getVersion();

    // Stats Header
    stdout.println();
    stdout.print(String.format("Now: %s\n", formatTime(now)));
    stdout.print(String.format("Uptime: %s\n", formatDuration(uptime)));
    stdout.print(String.format("Gerrit Code Review: %s\n", gerritVersion));

    // Stats table formatting
    final String tableRowStr = "%-50s | %19s | %19s |\n";
    final String border = String.join("", Collections.nCopies(95, "-"));
    stdout.print(border + "+\n");
    stdout.print(String.format(tableRowStr, "Statistic", "Sent", "Received"));
    stdout.print(border + "+\n");

    ImmutableMultiset<Originator> totalPublishedForeignEventsByType = ReplicatorMetrics.getTotalPublishedForeignEventsByType();
    ImmutableMultiset<Originator> totalPublishedLocalEventsByType = ReplicatorMetrics.getTotalPublishedLocalEventsByType();

    for (Originator orig : Originator.values()) {
      stdout.print(String.format(tableRowStr, //
          orig + " messages:",
          totalPublishedLocalEventsByType.count(orig),
          totalPublishedForeignEventsByType.count(orig)));
    }
    stdout.print(String.format(tableRowStr, //
        "Total published events:",
        ReplicatorMetrics.getTotalPublishedLocalEvents(),
        ReplicatorMetrics.getTotalPublishedForeignEvents()));
    stdout.print(String.format(tableRowStr, //
        "      of which with errors:",
        ReplicatorMetrics.getTotalPublishedLocalEvents()-ReplicatorMetrics.getTotalPublishedLocalGoodEvents(),
        ReplicatorMetrics.getTotalPublishedForeignEvents()-ReplicatorMetrics.getTotalPublishedForeignGoodEvents()));
    stdout.print(String.format(tableRowStr, //
        "Total bytes published:",
        ReplicatorMetrics.getTotalPublishedLocalEventsBytes(),
        ReplicatorMetrics.getTotalPublishedForeignEventsBytes()));
    stdout.print(String.format(tableRowStr, //
        "Total MiB published:",
        (ReplicatorMetrics.getTotalPublishedLocalEventsBytes()*10/(1024*1024))/10.0,
        (ReplicatorMetrics.getTotalPublishedForeignEventsBytes()*10/(1024*1024))/10.0));
    stdout.print(String.format(tableRowStr, //
        "Total gzipped MiB published:",
        (ReplicatorMetrics.getTotalPublishedLocalEventsBytes()*6/100/(1024*1024)*10)/10.0,
        (ReplicatorMetrics.getTotalPublishedForeignEventsBytes()*6/100/(1024*1024)*10)/10.0));

    long localProposals = ReplicatorMetrics.getTotalPublishedLocalEventsProposals();
    long foreignProposals = ReplicatorMetrics.getTotalPublishedForeignEventsProposals();

    stdout.print(String.format(tableRowStr, //
        "Total proposals published:",
        localProposals,
        foreignProposals));

    stdout.print(String.format(tableRowStr, //
        "Avg Events/proposal:",
        localProposals == 0 ? "n/a": (ReplicatorMetrics.getTotalPublishedLocalEvents()*10/localProposals)/10.0,
        foreignProposals == 0 ? "n/a": (ReplicatorMetrics.getTotalPublishedForeignEvents()*10/foreignProposals)/10.0));

    stdout.print(String.format(tableRowStr, //
        "Avg bytes/proposal:",
        localProposals == 0 ? "n/a": ReplicatorMetrics.getTotalPublishedLocalEventsBytes()/localProposals,
        foreignProposals == 0 ? "n/a": ReplicatorMetrics.getTotalPublishedForeignEventsBytes()/foreignProposals));
    stdout.print(String.format(tableRowStr, //
        "Avg gzipped bytes/proposal:",
        localProposals == 0 ? "n/a": ReplicatorMetrics.getTotalPublishedLocalEventsBytes()*6/100/localProposals,
        foreignProposals == 0 ? "n/a": ReplicatorMetrics.getTotalPublishedForeignEventsBytes()*6/100/foreignProposals));
    stdout.print(String.format(tableRowStr, //ErrorLog
        "Files in Incoming directory:", "n/a", getIncomingDirFileCount()));
    stdout.print(String.format(tableRowStr, //
        "Files in Outgoing directory:", "n/a",getOutgoingDirFileCount()));

    stdout.println();
  }
  

  public int getIncomingDirFileCount() {
    int result = -1;
    if (replicatedConfiguration.getIncomingFailedReplEventsDirectory() != null) {
      long now = System.currentTimeMillis();
      if (now - ReplicatorMetrics.lastCheckedIncomingDirTime > ReplicatorMetrics.DEFAULT_STATS_UPDATE_TIME) {
        // we cache the last result for lastCheckedIncomingDirTime ms, so that
        // continuous requests do not disturb
        File[] listFilesResult =
            replicatedConfiguration.getIncomingFailedReplEventsDirectory().listFiles();

        if (listFilesResult != null) {
          result = listFilesResult.length;
          ReplicatorMetrics.lastIncomingDirValue = result;
        }
        ReplicatorMetrics.lastCheckedIncomingDirTime = now;
      }
    }
    return result;
  }

  public int getOutgoingDirFileCount() {
    int result = -1;
    if (replicatedConfiguration.getOutgoingReplEventsDirectory() != null) {
      long now = System.currentTimeMillis();
      if (now - ReplicatorMetrics.lastCheckedOutgoingDirTime > ReplicatorMetrics.DEFAULT_STATS_UPDATE_TIME) {
        // we cache the last result for lastCheckedOutgoingDirTime ms, so that
        // continuous requests do not disturb
        File[] listFilesResult =
            replicatedConfiguration.getOutgoingReplEventsDirectory().listFiles();

        if (listFilesResult != null) {
          result = listFilesResult.length;
          ReplicatorMetrics.lastOutgoingDirValue = result;
        }
        ReplicatorMetrics.lastCheckedOutgoingDirTime = now;
      }
    }
    return result;
  }

  /**
   * Format a given instant in time to a human-readable string of the form: HH:mm:ss.SSS Z
   * i.e. Hours, minutes (of hour), seconds (of minute), Fraction of second to millisecond and
   * including the 4 digit timezone offset in case the Gerrit server has been configured as non-UTC.
   *
   * @param instant Instant in time to format.
   * @return Formatted time string.
   */
  private static String formatTime(final Instant instant) {
    return DateTimeFormatter.ofPattern("HH:mm:ss.SSS Z")
            .withLocale(Locale.US)
            .withZone(ZoneId.systemDefault())
            .format(instant);
  }

  /**
   * Format a Duration of time in a human-readable string using Days as the most significant unit.
   * Days and hours will be elided until the value exceeds 1. The resulting format will be:
   *   [1d] [12h] 32m 15s 875ms
   *
   * @param duration Duration of time to format
   * @return Formatted duration string.
   */
  private static String formatDuration(final Duration duration) {
    StringBuilder str = new StringBuilder(32);

    if (duration.toDays() > 0) {
      str.append(duration.toDays()).append("d ");
    }

    if (duration.toHoursPart() > 0) {
      str.append(duration.toHoursPart()).append("h ");
    }

    str.append(duration.toMinutesPart()).append("m ");
    str.append(duration.toSecondsPart()).append("s ");
    str.append(duration.toMillisPart()).append("ms");

    return str.toString();
  }
}
