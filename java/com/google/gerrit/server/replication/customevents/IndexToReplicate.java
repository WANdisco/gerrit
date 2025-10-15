package com.google.gerrit.server.replication.customevents;

import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.util.Objects;

/**
 * Holds information needed to index the change on the nodes, and also to make it replicate across the other nodes
 */
public class IndexToReplicate extends ReplicatedEvent {
  public int indexNumber;
  public String projectName;
  public Instant lastUpdatedOn;
  public long currentTime;
  public int timeZoneRawOffset;
  public boolean delete;
  public boolean safeToIgnoreMissingChange;


  public IndexToReplicate(int indexNumber, String projectName, Instant lastUpdatedOn, String thisNodeIdentity) {
    super(thisNodeIdentity);
    final long currentTimeMs = super.getEventTimestamp();
    setBaseMembers(indexNumber, projectName, lastUpdatedOn, currentTimeMs, getRawOffset(currentTimeMs), false, false);
  }

  public IndexToReplicate(int indexNumber, String projectName, Instant lastUpdatedOn, boolean delete, String thisNodeIdentity, boolean safeToIgnoreMissingChange) {
    super(thisNodeIdentity);
    final long currentTimeMs = super.getEventTimestamp();
    setBaseMembers(indexNumber, projectName, lastUpdatedOn, currentTimeMs, getRawOffset(currentTimeMs), delete, safeToIgnoreMissingChange);
  }

  public IndexToReplicate(IndexToReplicateDelayed delayed, String thisNodeId) {
    this(delayed.indexNumber, delayed.projectName, delayed.lastUpdatedOn, delayed.currentTime, getRawOffset(delayed.currentTime), false, thisNodeId, false);
  }

  public IndexToReplicate(IndexToReplicate index, String thisNodeId){
    this(index.indexNumber, index.projectName, index.lastUpdatedOn, index.currentTime, index.timeZoneRawOffset, index.delete, thisNodeId, index.safeToIgnoreMissingChange);
  }

  protected IndexToReplicate(int indexNumber, String projectName, Instant lastUpdatedOn, long currentTime, int rawOffset, boolean delete, String thisNodeIdentity, boolean safeToIgnoreMissingChange) {
    super(thisNodeIdentity);
    setBaseMembers(indexNumber, projectName, lastUpdatedOn, currentTime, rawOffset, delete, safeToIgnoreMissingChange);
  }

  private void setBaseMembers(int indexNumber, String projectName, Instant lastUpdatedOn, long currentTime, int rawOffset, boolean delete, boolean safeToIgnoreMissingChange) {
    this.indexNumber = indexNumber;
    this.projectName = projectName;
    this.lastUpdatedOn = lastUpdatedOn;
    this.currentTime = currentTime;
    this.timeZoneRawOffset = rawOffset;
    this.delete = delete;
    this.safeToIgnoreMissingChange = safeToIgnoreMissingChange;
  }

  /**
   * Get the offset for the time zone of the jvm in milliseconds.
   * @param currentTime The time to get the time zone for.
   * @return The time zone offset in milliseconds.
   */
  public static int getRawOffset(final long currentTime) {
    // Get the default time zone rules.
    ZoneRules zoneRules = ZoneId.systemDefault().getRules();

    // Get the time zone offset. (This takes into consideration DST)
    ZoneOffset zoneOffset = zoneRules.getOffset(Instant.ofEpochMilli(currentTime));

    // Convert from seconds to milliseconds.
    return zoneOffset.getTotalSeconds() * 1000;
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("IndexToReplicate{");
    sb.append("indexNumber=").append(indexNumber);
    sb.append(", projectName='").append(projectName).append('\'');
    sb.append(", lastUpdatedOn=").append(lastUpdatedOn);
    sb.append(", currentTime=").append(currentTime);
    sb.append(", timeZoneRawOffset=").append(timeZoneRawOffset);
    sb.append(", delete=").append(delete);
    sb.append(", safeToIgnoreMissingChange=").append(safeToIgnoreMissingChange);
    sb.append('}');
    return sb.toString();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof IndexToReplicate)) return false;
    IndexToReplicate that = (IndexToReplicate) o;
    return indexNumber == that.indexNumber &&
        currentTime == that.currentTime &&
        timeZoneRawOffset == that.timeZoneRawOffset &&
        delete == that.delete &&
        safeToIgnoreMissingChange == that.safeToIgnoreMissingChange &&
        Objects.equals(projectName, that.projectName) &&
        Objects.equals(lastUpdatedOn, that.lastUpdatedOn);
  }

  @Override
  public int hashCode() {
    return Objects.hash(indexNumber, projectName, lastUpdatedOn, currentTime, timeZoneRawOffset, delete, safeToIgnoreMissingChange);
  }
}
