
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

import com.google.common.collect.ConcurrentHashMultiset;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.Multiset;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;

import java.util.concurrent.atomic.AtomicLong;

// Statistics used by ShowReplicatorStats
public class ReplicatorMetrics {
  public static AtomicLong totalPublishedForeignEventsProsals = new AtomicLong(0);
  public static AtomicLong totalPublishedForeignEvents = new AtomicLong(0);
  public static AtomicLong totalPublishedForeignGoodEvents = new AtomicLong(0);
  public static AtomicLong totalPublishedForeignGoodEventsBytes = new AtomicLong(0);
  public static AtomicLong totalPublishedForeignEventsBytes = new AtomicLong(0);
  public static final Multiset<EventWrapper.Originator> totalPublishedForeignEventsByType =
      HashMultiset.create();

  public static AtomicLong totalPublishedLocalEventsProsals = new AtomicLong(0);
  public static AtomicLong totalPublishedLocalEvents = new AtomicLong(0);
  public static AtomicLong totalPublishedLocalGoodEvents = new AtomicLong(0);
  public static AtomicLong totalPublishedLocalGoodEventsBytes = new AtomicLong(0);
  public static AtomicLong totalPublishedLocalEventsBytes = new AtomicLong(0);
  public static final Multiset<EventWrapper.Originator> totalPublishedLocalEventsByType =
      HashMultiset.create();

  public static long lastCheckedIncomingDirTime = 0;
  public static long lastCheckedOutgoingDirTime = 0;
  public static int lastIncomingDirValue = -1;
  public static int lastOutgoingDirValue = -1;
  public static long DEFAULT_STATS_UPDATE_TIME = 20000L;

  // Used for statistics on caches.
  private static final Multiset<String> cacheInvalidatesPerformed = ConcurrentHashMultiset.create();
  private static final Multiset<String> cacheInvalidatesSent = ConcurrentHashMultiset.create();
  private static final Multiset<String> cacheGetsPerformed = ConcurrentHashMultiset.create();

  public static void addToCacheInvalidatesPerformed(final String cacheName){ cacheInvalidatesPerformed.add(cacheName); }

  public static void addToCacheInvalidatesSent(final String cacheName){ cacheInvalidatesSent.add(cacheName); }

  public static void addToCacheGetsPerformed(final String cacheName){ cacheGetsPerformed.add(cacheName); }

  public static ImmutableMultiset<String> getCacheInvalidatesPerformed() { return ImmutableMultiset.copyOf(cacheInvalidatesPerformed); }

  public static ImmutableMultiset<String> getCacheInvalidatesSent() { return ImmutableMultiset.copyOf(cacheInvalidatesSent); }

  public static ImmutableMultiset<String> getCacheGetsPerformed() { return ImmutableMultiset.copyOf(cacheGetsPerformed); }

  public static long getTotalPublishedForeignEventsProposals() { return totalPublishedForeignEventsProsals.get(); }

  public static long getTotalPublishedForeignEvents() { return totalPublishedForeignEvents.get(); }

  public static long getTotalPublishedForeignGoodEvents() { return totalPublishedForeignGoodEvents.get(); }

  public static long getTotalPublishedForeignEventsBytes() { return totalPublishedForeignEventsBytes.get(); }

  public static long getTotalPublishedForeignGoodEventsBytes() { return totalPublishedForeignGoodEventsBytes.get(); }

  public static long getTotalPublishedLocalEventsProposals() { return totalPublishedLocalEventsProsals.get(); }

  public static long getTotalPublishedLocalEvents() { return totalPublishedLocalEvents.get(); }

  public static long getTotalPublishedLocalGoodEvents() { return totalPublishedLocalGoodEvents.get(); }

  public static long getTotalPublishedLocalEventsBytes() { return totalPublishedLocalEventsBytes.get(); }

  public static long getTotalPublishedLocalGoodEventsBytes() { return totalPublishedLocalGoodEventsBytes.get(); }

  public static ImmutableMultiset<EventWrapper.Originator> getTotalPublishedForeignEventsByType() {
    return ImmutableMultiset.copyOf(totalPublishedForeignEventsByType);
  }

  public static ImmutableMultiset<EventWrapper.Originator> getTotalPublishedLocalEventsByType() {
    return ImmutableMultiset.copyOf(totalPublishedLocalEventsByType);
  }


}
