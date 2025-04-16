package com.google.gerrit.server.replication;

import com.google.common.cache.Cache;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.project.ProjectCache;
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

    public void watchCache(String cacheName, Cache<?, ?> cache) {
        caches.put(cacheName, new ReplicatedCacheWrapper<>(cacheName, cache));
        logger.atInfo().log("CACHE New cache named %s inserted", cacheName);
    }


    public void watchObject(String cacheName, ProjectCache projectCache) {
        cacheObjects.put(cacheName, projectCache);
    }


    public ReplicatedCacheWrapper<?, ?> getReplicatedCache(final String cacheName) {
        return caches.get(cacheName);
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
