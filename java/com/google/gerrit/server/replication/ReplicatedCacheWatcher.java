package com.google.gerrit.server.replication;

import com.google.common.cache.Cache;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.replication.exceptions.ReplicatedCacheException;
import com.google.gerrit.server.replication.processors.ReplicatedStoppable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A store of all the caches we watch and want to replicate within the system.
 */
public class ReplicatedCacheWatcher implements ReplicatedStoppable {

    private static final FluentLogger logger = FluentLogger.forEnclosingClass();
    private final Map<String, ReplicatedCacheWrapper<?, ?>> caches = new ConcurrentHashMap<>();
    private final Map<String, Object> cacheObjects = new ConcurrentHashMap<>();

    public ReplicatedCacheWatcher() {
        SingletonEnforcement.registerClass(ReplicatedCacheWatcher.class);
    }

    public <K, V> void watchCache(String cacheName, Cache<K, V> cache, Class<K> keyClass) {
        ReplicatedCacheWrapper<K, V> existingCacheWrapper = getReplicatedCache(cacheName);
        // We may not have a wrapper if the cache has never been added before
        if(existingCacheWrapper != null) {
            // Get the actual cache from the wrapper as we need to compare it with the cache passed to the method
            Cache<K, V> existingCache = existingCacheWrapper.getCache();

            if (existingCache != null) {
                // If a different cache is being added for the same cache key then this is a problem, throw an exception
                if (existingCache != cache) {
                    throw new ReplicatedCacheException(
                            String.format("Cache name '%s' is already associated with a different Cache instance.", cacheName)
                    );
                }
            }
        } else {
            // Cache has never been added before.
            caches.put(cacheName, new ReplicatedCacheWrapper<>(cacheName, cache, keyClass));
            logger.atInfo().log("CACHE New cache named %s inserted", cacheName);
        }
    }

    public void watchObject(String cacheName, ProjectCache projectCache) {
        Object existingObject = cacheObjects.get(cacheName);

        if (existingObject != null) {
            // If a different object is being added for the same cache key then this is a problem, throw an exception
            if (existingObject != projectCache) {
                throw new ReplicatedCacheException(
                        String.format("Cache name '%s' is already associated with a different ProjectCache instance.", cacheName)
                );
            }
        } else {
            // Adding the object only if it doesn't already exist
            cacheObjects.put(cacheName, projectCache);
        }
    }


    @SuppressWarnings("unchecked")
    public <K, V> ReplicatedCacheWrapper<K, V> getReplicatedCache(final String cacheName) {
        return (ReplicatedCacheWrapper<K, V>) caches.get(cacheName);
    }


    public Object getReplicatedCacheObject(final String cacheName) {
        return cacheObjects.get(cacheName);
    }

    public Map<String, ReplicatedCacheWrapper> getAllReplicatedCaches() {
        // returning a copy to avoid escaping references.
        return new ConcurrentHashMap<>(caches);
    }

    public Map<String, Object> getReplicatedCacheObjects() {
        // returning a copy to avoid escaping references
        return new ConcurrentHashMap<>(cacheObjects);
    }

    @Override
    public void stop() {
        SingletonEnforcement.unregisterClass(ReplicatedCacheWatcher.class);
    }
}
