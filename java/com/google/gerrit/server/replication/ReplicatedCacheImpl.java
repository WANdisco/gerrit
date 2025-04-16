package com.google.gerrit.server.replication;

import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheStats;
import com.google.common.collect.ImmutableMap;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.server.replication.feeds.ReplicatedOutgoingCacheEventsFeed;
import org.eclipse.jgit.annotations.NonNull;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

import static java.util.Objects.requireNonNull;

/**
 * Class is used to intercept any cache calls made in the cache classes and forward on those calls to the
 * relevant underlying cache implementation, e.g. a CaffeinatedGuavaCache etc. Some cache operations such as
 * invalidate, invalidateAll etc. are important for cache invalidation, and we want to replicate these calls, so we take a
 * decision whether we want to replicate these calls within those methods after calling the operation for the local site.
 * @param <K> : Any Key object stored by a Gerrit cache
 * @param <V>: Any Value object stored by a Gerrit cache.
 */
public class ReplicatedCacheImpl<K, V> implements Cache<K, V>{

    private final ReplicatedEventsCoordinator replicatedEventsCoordinator;

    private final String cacheName;

    private final Cache<K, V> cache;

    private String projectToReplicateAgainst;

    private ProjectNameCallback<K> projectNameCallback;


    // Constructor for caches who queue their events against either the AllUsers or AllProjects DSMs.
    public ReplicatedCacheImpl(ReplicatedEventsCoordinator replicatedEventsCoordinator, String cacheName, Cache<K, V> cache,
                               @NonNull String projectToReplicateAgainst) {
        this.cacheName = requireNonNull(cacheName, "Cache Name cannot be null");
        this.cache = requireNonNull(cache, "Cache cannot be null");

        if (Strings.isNullOrEmpty(projectToReplicateAgainst)) {
            throw new IllegalArgumentException("Project to replicate against cannot be null");
        }
        this.projectToReplicateAgainst = projectToReplicateAgainst;
        this.replicatedEventsCoordinator = replicatedEventsCoordinator;
        replicatedEventsCoordinator.getReplicatedCacheWatcher().watchCache(cacheName, cache);

    }

    // Overloaded constructor by which a Callback is passed to be used to determine the project name to queue events against.
    // In some cases we know that certain caches should queue their events against the AllUsers or AllProjects
    // repository. In cases where we don't queue events against either of these repos, we need to determine the project
    // name via callback.
    public ReplicatedCacheImpl(ReplicatedEventsCoordinator replicatedEventsCoordinator, String cacheName, Cache<K, V> cache,
                               @NonNull ProjectNameCallback<K> projectNameCallback) {
        this.cacheName = requireNonNull(cacheName, "Cache Name cannot be null");
        this.cache = requireNonNull(cache, "Cache cannot be null");
        this.projectNameCallback = requireNonNull(projectNameCallback, "ProjectName Callback cannot be null");
        this.replicatedEventsCoordinator = replicatedEventsCoordinator;
        replicatedEventsCoordinator.getReplicatedCacheWatcher().watchCache(cacheName, cache);
    }

    @Override
    public @Nullable V getIfPresent(Object key) {
        return cache.getIfPresent(key);
    }

    @Override
    @SuppressWarnings({"NullAway", "PMD.ExceptionAsFlowControl", "PMD.PreserveStackTrace"})
    public V get(K key, Callable<? extends V> loader) throws ExecutionException {
        return cache.get(key, loader);
    }

    @Override
    public ImmutableMap<K, V> getAllPresent(Iterable<?> keys) {
        return cache.getAllPresent(keys);
    }

    @Override
    public void put(K key, V value) {
        cache.put(key, value);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        cache.putAll(m);
    }

    /**
     * Non-replicated invalidate call. We do not queue for replication here.
     * Our ReplicatedCacheWrapper will mainly call this method.
     */
    public void invalidateNoRepl(Object key){
        cache.invalidate(key);
    }

    /**
     * Replicated invalidate call. First calls invalidate for the local site then
     * queues the invalidate cache operation for replication. We determine the project name via callback if
     * the projectName is null.
     * @param key: A key of a given type stored by a cache.
     */
    @Override
    public void invalidate(Object key) {
        cache.invalidate(key);

        queueEvictionForReplication(key,
                projectNameCallback != null ? projectNameCallback.getProjectName((K) key) : projectToReplicateAgainst);
    }

    /**
     * Asks the replicated coordinator for the instance of the ReplicatedOutgoingCacheEventsFeed and calls
     * replicateEvictionFromCache on it. This queues a cache event for replication with the coordinator.
     * @param key Any object type stored by a given cache.
     * @param projectName The project to queue the cache event against, e.g. AllProjects, AllUsers, other
     */
    private void queueEvictionForReplication(Object key, String projectName) {
        if (replicatedEventsCoordinator.isReplicationEnabled()) {
            replicatedEventsCoordinator.getReplicatedOutgoingCacheEventsFeed()
                    .replicateCacheInvalidate(cacheName, key, projectName);
        }
    }

    /**
     * Asks the replicated coordinator for the instance of the ReplicatedOutgoingCacheEventsFeed and calls
     * replicateEvictionFromCache on it. This queues a cache event for replication with the coordinator.
     * @param keys An Iterable of keys for a given type stored by a cache.
     * @param projectName The project to queue the cache event against, e.g. AllProjects, AllUsers, other
     */
    private void queueEvictAllForReplication(Iterable<?> keys, String projectName) {
        if (replicatedEventsCoordinator.isReplicationEnabled()) {
            replicatedEventsCoordinator.getReplicatedOutgoingCacheEventsFeed()
                    .replicateCacheInvalidateAll(cacheName, keys, projectName);
        }
    }

    /**
     * Replicated invalidateAll call. First calls invalidateAll for the local site then
     * queues the invalidateAll cache operation for replication.
     * @param keys: An Iterable of keys for a given type stored by a cache.
     */
    @Override
    public void invalidateAll(Iterable<?> keys) {
        cache.invalidateAll(keys);
        queueEvictAllForReplication(keys,
                projectNameCallback != null ? projectNameCallback.getProjectName((K) keys) : projectToReplicateAgainst);
    }

    /**
     * Replicated invalidateAll call that uses the invalidateAllWildCard mechanism.
     * First calls invalidateAll for the local site then queues the invalidateAll for replication.
     */
    @Override
    public void invalidateAll() {
        cache.invalidateAll();
        if ( Strings.isNullOrEmpty(projectToReplicateAgainst)) {
            throw new CacheReplicationException(String.format("Failed to replicate invalidateAll() call for cache %s as the project to replicate against is null", cacheName));
        }
        queueEvictionForReplication(ReplicatedOutgoingCacheEventsFeed.invalidateAllWildCard, projectToReplicateAgainst);
    }

    /**
     * Non-replicated invalidateAll call. Will only perform the invalidateAll for the local site.
     */
    public void invalidateAllNoRepl() {
        cache.invalidateAll();
    }

    @Override
    public long size() {
        return cache.size();
    }

    @Override
    public CacheStats stats() {
        return cache.stats();
    }

    @Override
    public ConcurrentMap<K, V> asMap() {
        return cache.asMap();
    }

    @Override
    public void cleanUp() {
        cache.cleanUp();
    }
}
