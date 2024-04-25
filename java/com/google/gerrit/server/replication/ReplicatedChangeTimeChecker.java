package com.google.gerrit.server.replication;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.replication.configuration.ReplicatedConfiguration;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.server.replication.customevents.IndexToReplicate;
import com.google.gerrit.server.replication.customevents.IndexToReplicateComparable;

import java.sql.Timestamp;

/**
 * Small utility class to work out the change time of a replicated event, and whether we can perform this action in the DB
 * as yet.
 * It will take into account timezone offsets between servers when comparing times.
 */
public class ReplicatedChangeTimeChecker {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final int thisNodeTimeZoneOffset;
  private final Change changeOnDb;
  private final IndexToReplicateComparable indexToReplicate;
  private final ReplicatedConfiguration replicatedConfiguration;
  private boolean changeIndexedMoreThanXMinutesAgo;
  private Timestamp normalisedChangeTimestamp;
  private Timestamp normalisedIndexToReplicate;

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

  public Timestamp getNormalisedChangeTimestamp() {
    return normalisedChangeTimestamp;
  }

  public Timestamp getNormalisedIndexToReplicate() {
    return normalisedIndexToReplicate;
  }

  public boolean isTimeStampBefore() {
    return normalisedChangeTimestamp.before(normalisedIndexToReplicate);
  }

  public boolean isTimeStampEqual() {
    return normalisedChangeTimestamp.equals(normalisedIndexToReplicate);
  }

  public ReplicatedChangeTimeChecker invoke() {
    int landedIndexTimeZoneOffset = indexToReplicate.timeZoneRawOffset;
    logger.atFine().log("landedIndexTimeZoneOffset=%s",landedIndexTimeZoneOffset);
    logger.atFine().log("indexToReplicate.lastUpdatedOn.getTime() = %s", indexToReplicate.lastUpdatedOn.getTime());

    changeIndexedMoreThanXMinutesAgo = changeIndexedLastTime(thisNodeTimeZoneOffset, indexToReplicate, landedIndexTimeZoneOffset);
    logger.atFine().log("changeOnDb.getLastUpdatedOn().getTime() = %s", changeOnDb.getLastUpdatedOn().getTime());

    normalisedChangeTimestamp = new Timestamp(changeOnDb.getLastUpdatedOn().getTime() - thisNodeTimeZoneOffset);
    normalisedIndexToReplicate = new Timestamp(indexToReplicate.lastUpdatedOn.getTime() - landedIndexTimeZoneOffset);
    return this;
  }


  /**
   * Calculates the time when the change was last indexed and works
   * out whether it has been over the amount of minutes specified by the
   * value provided in the configurable (gerrit.minutes.since.last.index.check.period)
   * @param thisNodeTimeZoneOffset
   * @param indexToReplicate
   * @param landedIndexTimeZoneOffset
   * @return True if the amount of time is over the allowed amount of minute since last index check
   */
  public boolean changeIndexedLastTime(long thisNodeTimeZoneOffset,
                                       IndexToReplicate indexToReplicate, long landedIndexTimeZoneOffset ){
    return (System.currentTimeMillis()-thisNodeTimeZoneOffset
        - (indexToReplicate.lastUpdatedOn.getTime()-landedIndexTimeZoneOffset)) >
        replicatedConfiguration.getMinutesSinceChangeLastIndexedCheckPeriod();
  }
}
