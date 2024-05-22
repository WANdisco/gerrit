package com.google.gerrit.server.replication.customevents;


import java.time.Instant;

/**
 * Implementation which only takes the changeNumber as main comparison operator
 */
@SuppressWarnings("ComparableType") // "ErrorProne" doesn't like this comparable extension pattern...
public final class IndexToReplicateComparable extends IndexToReplicate implements Comparable<IndexToReplicate> {

  public IndexToReplicateComparable(int indexNumber, String projectName, Instant lastUpdatedOn, String thisNodeIdentity) {
    super(indexNumber, projectName, lastUpdatedOn, thisNodeIdentity);
  }

  public IndexToReplicateComparable(IndexToReplicate index, final String thisNodeId){
    super(index, thisNodeId );
  }

  @Override
  public int hashCode() {
    int hash = 3;
    return 41 * hash + this.indexNumber;
  }
  @Override
  public boolean equals(Object obj) {
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    final IndexToReplicate other = (IndexToReplicate) obj;
    return (this.indexNumber == other.indexNumber);
  }

  @Override
  public int compareTo(IndexToReplicate o) {
    if (o == null) {
      return 1;
    }

    return this.indexNumber - o.indexNumber;
  }

  @Override
  public String toString() {
    return "IndexToReplicateComparable " + super.toString();
  }

}