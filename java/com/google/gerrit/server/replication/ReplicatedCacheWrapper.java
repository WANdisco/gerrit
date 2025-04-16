package com.google.gerrit.server.replication;

import com.google.common.base.Objects;
import com.google.common.cache.Cache;
import com.google.common.cache.LoadingCache;
import com.google.common.flogger.FluentLogger;

import static java.util.Objects.requireNonNull;
import static com.google.gerrit.server.replication.feeds.ReplicatedOutgoingCacheEventsFeed.invalidateAllWildCard;

public class ReplicatedCacheWrapper<K, V> {

  private final String cacheName;
  private final Cache<K, V> cache;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public ReplicatedCacheWrapper(String cacheName, Cache<K, V> theCache) {
    this.cacheName = requireNonNull(cacheName);
    this.cache = requireNonNull(theCache);

    if(theCache instanceof ReplicatedCacheImpl){
      throw new IllegalArgumentException("ReplicatedCacheWrapper should not be constructed with an instance of a ReplicatedCache");
    }
  }

  /**
   * Apply the invalidateAll on the local site for an Iterable of keys for a given cache type.
   * - If we are called here and the underlying cache implementation is a CaffeinatedGuavaCache or
   *    *   CaffeinatedLoadingGuavaCache then we should call the invalidateAll or invalidate on the cache directly.
   *    *   Otherwise, we may be an instance of a ReplicatedCacheImpl, in which case we should call a non replicated
   *    *   version of the method.
   * @param keys An Iterable of the type of instance stored by a given cache.
   */
  public void invalidateAll(Iterable<?> keys) {
      cache.invalidateAll(keys);
      ReplicatorMetrics.addToCacheInvalidatesPerformed(cacheName);
  }

  /**
   * Apply the invalidation on the local site for a single key.
   * - If we are called here and the underlying cache implementation is a CaffeinatedGuavaCache or
   *   CaffeinatedLoadingGuavaCache then we should call the invalidateAll or invalidate on the cache directly.
   *   Otherwise, we may be an instance of a ReplicatedCacheImpl, in which case we should call a non replicated
   *   version of the method.
   * @param key An instance stored by a given cache.
   */
  public void invalidate(Object key) {
    if (key.toString().equals(invalidateAllWildCard)) {
      cache.invalidateAll();
    } else {
      cache.invalidate(key);
    }
    ReplicatorMetrics.addToCacheInvalidatesPerformed(cacheName);
  }

  /**
   * Apply the getAll on the local site for an Iterable of keys for a given cache object type.
   * @param keys An Iterable of the type of instance stored by a given cache.
   */
  public void getAll(Iterable<?> keys) {
    if (cache instanceof LoadingCache) {
      try {
        Object obj = ((LoadingCache) cache).getAll(keys);
        logger.atFine().log("%s loaded into the cache (1).", obj);
      } catch (Exception ex) {
        logger.atSevere().withCause(ex).log("Error while trying to get a key from the cache!");
      }
    } else {
      Object obj = ((Cache<?, ?>) cache).getAllPresent(keys);
      logger.atFine().log("%s loaded into the cache (2).", obj);
    }
    ReplicatorMetrics.addToCacheGetsPerformed(cacheName);
  }


  /**
   * Apply the cache get on the local site for a single key.
   * @param key An instance of a stored cache type.
   */
  public void get(Object key) {
    if (cache instanceof LoadingCache) {
      try {
        Object obj = ((LoadingCache) cache).get(key);
        logger.atFine().log("%s loaded into the cache (1).", obj);
      } catch (Exception ex) {
        logger.atSevere().withCause(ex).log("Error while trying to get key from the cache!");
      }
    } else {
      Object obj = ((Cache<?, ?>) cache).getIfPresent(key);
      logger.atFine().log("%s loaded into the cache (2).", obj);
    }
    ReplicatorMetrics.addToCacheGetsPerformed(cacheName);
  }

  @SuppressWarnings("EqualsGetClass")
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ReplicatedCacheWrapper<?, ?> that = (ReplicatedCacheWrapper<?, ?>) o;
    return Objects.equal(cache, that.cache);
  }

  @Override
  public int hashCode() {
    int hash = 7;
    hash = 97 * hash + Objects.hashCode(this.cache);
    return hash;
  }
}
