package com.google.gerrit.server.replication;

import com.google.common.base.Objects;
import com.google.common.cache.Cache;
import com.google.common.cache.LoadingCache;
import com.google.common.flogger.FluentLogger;
import com.google.common.reflect.TypeToken;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;
import static com.google.gerrit.server.replication.feeds.ReplicatedOutgoingCacheEventsFeed.invalidateAllWildCard;

public class ReplicatedCacheWrapper<K, V> {

  private final String cacheName;
  private final Class<K> keyClass;

  private final Cache<K, V> cache;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public ReplicatedCacheWrapper(String cacheName, Cache<K, V> theCache, Class<K> keyClass) {
    this.cacheName = requireNonNull(cacheName);
    this.cache = requireNonNull(theCache);
    this.keyClass = requireNonNull(keyClass);

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
  public void invalidate(K key) {
    if (key.toString().equals(invalidateAllWildCard)) {
      cache.invalidateAll();
    } else {
      cache.invalidate(key);
    }
    ReplicatorMetrics.addToCacheInvalidatesPerformed(cacheName);
  }


  /**
   * Apply the cache PUT on the local site for a single key/value pair.
   * @param key An instance stored by a given cache.
   * @param value A value for a key entry stored by a given cache.
   */
  public void put(K key, V value) {
    cache.put(key, value);
    ReplicatorMetrics.addToCachePutsSent(cacheName);
  }

  /**
   * Apply the getAll on the local site for an Iterable of keys for a given cache object type.
   * @param keys An Iterable of the type of instance stored by a given cache.
   */
  public void getAll(Iterable<?> keys) {
    List<K> typedKeys = new ArrayList<>();
    for (Object key : keys) {
      if (keyClass.isInstance(key)) {
        typedKeys.add(keyClass.cast(key));
      } else {
        throw new ClassCastException("Key " + key + " is not of the expected type " + keyClass.getName());
      }
    }

    if (cache instanceof LoadingCache) {
      try {
        Object obj = ((LoadingCache<K, V>) cache).getAll(typedKeys);
        logger.atFine().log("%s loaded into the cache (1).", obj);
        ReplicatorMetrics.addToCacheGetsPerformed(cacheName);
      } catch (UnsupportedOperationException ignored) {
        //  H2CacheImpl is a hybrid cache doesn't support this operation for its in-memory cache if it isn't a LoadingCache
      } catch (Exception ex) {
        logger.atSevere().withCause(ex).log("Error while trying to get keys from the cache!");
      }
    } else {
      // Use the typed keys list for the standard Cache
      Object obj = cache.getAllPresent(typedKeys);
      logger.atFine().log("%s loaded into the cache (2).", obj);
      ReplicatorMetrics.addToCacheGetsPerformed(cacheName);
    }
  }

  /**
   * Apply the cache get on the local site for a single key.
   * @param key An instance of a stored cache type.
   */
  public void get(K key) {
    if (!keyClass.isInstance(key)) {
      throw new ClassCastException("Invalid key type. Expected: "
              + keyClass.getName() + ", Found: " + key.getClass().getName());
    }

    if (cache instanceof LoadingCache) {
      try {
        K typedKey = keyClass.cast(key);
        Object obj = ((LoadingCache<K, V>) cache).get(typedKey);
        logger.atFine().log("%s loaded into the cache (1).", obj);
        ReplicatorMetrics.addToCacheGetsPerformed(cacheName);
      } catch (UnsupportedOperationException ignored) {
        // H2CacheImpl is a hybrid cache doesn't support this operation for its in-memory cache if it isn't a LoadingCache
      } catch (Exception ex) {
        logger.atSevere().withCause(ex).log("Error while trying to get key from the cache!");
      }
    } else {
      K typedKey = keyClass.cast(key);
      Object obj = cache.getIfPresent(typedKey);  // Cast cache to Cache<K, V>
      logger.atFine().log("%s loaded into the cache (2).", obj);
      ReplicatorMetrics.addToCacheGetsPerformed(cacheName);
    }
  }

  public Class<K> getKeyClass() {
    return keyClass;
  }

  public String getCacheName() {
    return cacheName;
  }

  public Cache<K, V> getCache() {
    return cache;
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
