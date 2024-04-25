package com.google.gerrit.server.replication;

import com.google.common.cache.Cache;
import com.google.common.cache.LoadingCache;
import com.google.common.flogger.FluentLogger;
import java.util.Objects;

import static com.google.gerrit.server.replication.feeds.ReplicatedOutgoingCacheEventsFeed.evictAllWildCard;

public class ReplicatedCacheWrapper {
  Object cache;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public ReplicatedCacheWrapper(Object theCache) {
    this.cache = theCache;
  }

  public boolean evict(Object key) {
    boolean done = false;
    if (cache instanceof Cache) {
    if (key.toString().equals(evictAllWildCard)) {
      ((Cache) cache).invalidateAll();
    } else {
      ((Cache) cache).invalidate(key);
    }
      done = true;
    } else {
      logger.atSevere().log("CACHE is not supported!, Class is missing: %s", cache.getClass().getName());
    }
    return done;
  }

  public boolean reload(Object key) {
    boolean done = false;
    if (cache instanceof LoadingCache) {
      try {
        Object obj = ((LoadingCache) cache).get(key);
        logger.atFine().log("%s loaded into the cache (1).", obj);
        done = true;
      } catch (Exception ex) {
        logger.atSevere().withCause(ex).log(
            "Error while trying to reload a key from the cache!");
      }
    } else if (cache instanceof Cache) {
      Object obj = ((Cache) cache).getIfPresent(key);
      logger.atFine().log("%s loaded into the cache (2).", obj);
      done = true;
    } else {
      logger.atSevere().log("CACHE is not supported!, Class is missing: %s", cache.getClass().getName());
    }
    return done;
  }

  @Override
  public int hashCode() {
    int hash = 7;
    hash = 97 * hash + Objects.hashCode(this.cache);
    return hash;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    final ReplicatedCacheWrapper other = (ReplicatedCacheWrapper) obj;
    return Objects.equals(this.cache, other.cache);
  }
}
