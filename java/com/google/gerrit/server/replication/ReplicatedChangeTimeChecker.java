package com.google.gerrit.server.replication;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Change;
import com.google.gerrit.server.replication.configuration.ReplicatedConfiguration;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.server.replication.customevents.IndexToReplicate;
import com.google.gerrit.server.replication.customevents.IndexToReplicateComparable;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;


/**
 * Small utility class to work out the change time of a replicated event, and whether we can perform this action in the DB
 * as yet.
 * It will take into account timezone offsets between servers when comparing times.
 */
public class ReplicatedChangeTimeChecker {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final int thisNodeTimeZoneOffset; /* QUESTION: What unit is timezone offset in, and do we need it after the 'change' migration to java.time.Instant? */
  private final Change changeOnDb;
  private final IndexToReplicateComparable indexToReplicate;
  private final ReplicatedConfiguration replicatedConfiguration;
  private boolean changeIndexedMoreThanXMinutesAgo;
  private Instant normalisedChangeTimestamp;
  private Instant normalisedIndexToReplicate;

  public ReplicatedChangeTimeChecker(int thisNodeTimeZoneOffset, Change changeOnDb,
                                     IndexToReplicateComparable indexToReplicate,
                                     ReplicatedEventsCoordinator replicatedEventsCoordinator) {
    this.thisNodeTimeZoneOffset = thisNodeTimeZoneOffset;
    this.changeOnDb = changeOnDb;
    this.indexToReplicate = indexToReplicate;
    this.replicatedConfiguration = replicatedEventsCoordinator.getReplicatedConfiguration();
  }

  /**
   * Is the time since the change was last indexed greater than the configurable time
   * gerrit.minutes.since.last.index.check.period
   * @return Returns true whether it has been over the amount of minutes specified by the
   *    * value provided in the configurable (gerrit.minutes.since.last.index.check.period)
   */
  public boolean isChangeIndexedMoreThanXMinutesAgo() {
    return changeIndexedMoreThanXMinutesAgo;
  }

  public Instant getNormalisedChangeTimestamp() {
    return normalisedChangeTimestamp;
  }

  public Instant getNormalisedIndexToReplicate() {
    return normalisedIndexToReplicate;
  }

  public boolean isTimeStampBefore() {
    return normalisedChangeTimestamp.isBefore(normalisedIndexToReplicate);
  }

  public boolean isTimeStampEqual() {
    return normalisedChangeTimestamp.equals(normalisedIndexToReplicate);
  }

  public ReplicatedChangeTimeChecker invoke() {
    int landedIndexTimeZoneOffset = indexToReplicate.timeZoneRawOffset;
    logger.atFine().log("landedIndexTimeZoneOffset=%s",landedIndexTimeZoneOffset);
    logger.atFine().log("indexToReplicate.lastUpdatedOn.getTime() = %s", indexToReplicate.lastUpdatedOn);

    changeIndexedMoreThanXMinutesAgo = changeIndexedLastTime(thisNodeTimeZoneOffset, indexToReplicate, landedIndexTimeZoneOffset);
    logger.atFine().log("changeOnDb.getLastUpdatedOn().getTime() = %s", changeOnDb.getLastUpdatedOn());

    normalisedChangeTimestamp = changeOnDb.getLastUpdatedOn().minusMillis(thisNodeTimeZoneOffset);
    normalisedIndexToReplicate = indexToReplicate.lastUpdatedOn.minusMillis(landedIndexTimeZoneOffset);
    return this;
  }


  /**
   * Calculates the time when the change was last indexed and works
   * out whether it has been over the amount of minutes specified by the
   * value provided in the configurable (gerrit.minutes.since.last.index.check.period)
   * @return True if the amount of time is over the allowed amount of minute since last index check
   */
  public boolean changeIndexedLastTime(long thisNodeTimeZoneOffset,
                                       IndexToReplicate indexToReplicate, long landedIndexTimeZoneOffset ){
    final Instant baseline = Instant.now().minusMillis(thisNodeTimeZoneOffset);
    final Instant landing = indexToReplicate.lastUpdatedOn.minusMillis(landedIndexTimeZoneOffset);
    final Duration checkPeriod = Duration.of(replicatedConfiguration.getMinutesSinceChangeLastIndexedCheckPeriod(), ChronoUnit.MINUTES);

    return Duration.between(baseline, landing).compareTo(checkPeriod) > 0;
  }
}
