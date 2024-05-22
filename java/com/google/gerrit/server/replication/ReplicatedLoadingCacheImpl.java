package com.google.gerrit.server.replication;

import com.google.common.cache.Cache;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import org.eclipse.jgit.annotations.NonNull;

import java.util.concurrent.ExecutionException;
import static java.util.Objects.requireNonNull;

/**
 * Class is used to intercept any loading cache style calls made in the cache classes and, we forward on those calls to the
 * relevant underlying loading cache implementation, e.g. a CaffeinatedLoadingGuavaCache etc.
 * At present, we don't replicate any of these loading cache operations but as most cache implementations in Gerrit
 * are LoadingCache based we need this class for the shim.
 * @param <K> : Any Key object stored by a Gerrit cache
 * @param <V>: Any Value object stored by a Gerrit cache.
 *  */
public class ReplicatedLoadingCacheImpl<K, V> extends ReplicatedCacheImpl<K, V> implements LoadingCache<K, V>{

    private final LoadingCache<K, V> loadingCache;

    public ReplicatedLoadingCacheImpl(ReplicatedEventsCoordinator replicatedEventsCoordinator,
                                      final String cacheName, Cache<K, V> cache, String projectToReplicateAgainst){
        super(replicatedEventsCoordinator, requireNonNull(cacheName), requireNonNull(cache), requireNonNull(projectToReplicateAgainst));
        this.loadingCache = (LoadingCache<K, V>) cache;
    }

    // Overloaded constructor that takes a callback to get the project name instead of a hardcoded string.
    public ReplicatedLoadingCacheImpl(ReplicatedEventsCoordinator replicatedEventsCoordinator,
                                      final String cacheName, Cache<K, V> cache, @NonNull ProjectNameCallback<K> projectNameCallback){
        super(replicatedEventsCoordinator, requireNonNull(cacheName), requireNonNull(cache), requireNonNull(projectNameCallback));
        this.loadingCache = (LoadingCache<K, V>) cache;
    }


    @Override
    public @Nullable V getIfPresent(Object key) {
        return loadingCache.getIfPresent(key);
    }

    @Override
    public V get(K key) throws ExecutionException {
        return loadingCache.get(key);
    }

    @Override
    public V getUnchecked(K key) {
        return loadingCache.getUnchecked(key);
    }

    @Override
    public ImmutableMap<K, V> getAll(Iterable<? extends K> keys) throws ExecutionException {
        return loadingCache.getAll(keys);
    }

    @Override
    public V apply(K key) {
        return loadingCache.apply(key);
    }

    @Override
    public void refresh(K key) {
        loadingCache.refresh(key);
    }

}
