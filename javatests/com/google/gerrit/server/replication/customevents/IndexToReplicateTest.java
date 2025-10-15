package com.google.gerrit.server.replication.customevents;

import static com.google.gerrit.server.replication.customevents.IndexToReplicate.getRawOffset;
import static org.junit.Assert.assertEquals;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class IndexToReplicateTest {

  private static TimeZone orig_timezone;

  @BeforeClass
  public static void saveOriginalTimeZone() {
    orig_timezone = TimeZone.getDefault();
  }

  @AfterClass
  public static void restoreOriginalTimeZone() {
    TimeZone.setDefault(orig_timezone);
  }

  @Before
  public void setTestTimeZone() {
    TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"));
  }

  /**
   * Test getRawOffset() for a time currently not in DST.
   */
  @Test
  public void testGetRawOffsetNonDST() {
    LocalDateTime nonDstTime = LocalDateTime.of(2000, 1, 1, 12, 0);
    int offset = getRawOffset(nonDstTime.toEpochSecond(ZoneOffset.of("Z")) * 1000L);
    assertEquals(0, offset);
  }

  /**
   * Test getRawOffset() for a time currently in DST.
   */
  @Test
  public void testGetRawOffsetDST() {
    long oneHourInMs = 60 * 60 * 1000L;
    LocalDateTime dstTime = LocalDateTime.of(2000, 7, 1, 12, 0);
    int offset = getRawOffset(dstTime.toEpochSecond(ZoneOffset.of("Z")) * 1000L);
    assertEquals(oneHourInMs, offset);
  }
}
