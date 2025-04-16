package com.google.gerrit.server.replication;

import com.google.common.cache.Cache;
import com.google.common.cache.LoadingCache;

/**
 * All these methods return an instance of a replicated cache. If dealing with a Cache type then a createReplicatedCache
 * method for one of the Cache types should be used. Similarly, a createReplicateLoadingCache method should be used if
 * dealing with a LoadingCache. Both versions take a ProjectNameCallback whenever we should not be using AllProjects or
 * AllUsers to replicate against.
 *
 * With the ProjectNameCallback it should be possible to glean the projectName from
 * the Key of the Cache/LoadingCache. i.e. the Key will have a method on it making it possible to get the ProjectName.
 * For example:
 * If the Key of the cache is a PureRevertKeyProto, this class has a getProject() method on it. The ProjectNameCallback
 * will take a type of PureRevertKeyProto and return a String. Therefore, a lambda expression can be supplied such
 * as proto -> proto.getProject() or more succinctly as a method reference PureRevertKeyProto::getProject
 *
 * In some cases the cache Key will be a String type. If it is, then most likely the String being supplied is a Project.NameKey
 * String. A lambda expression can be used to get the Project.NameKey like so: projectName -> projectName as the ProjectNameCallback
 * will take a type String and return String as the type.
 */
public interface ReplicatedCacheFactory {
    /**
     * Create an instance of a ReplicatedCacheImpl. If a ReplicatedCacheImpl instance is used then all cache calls
     * will call our replicated cache methods such as invalidate, invalidateAll etc and these cache operations
     * will be queued for replication.
     * @param cacheName: The final String constant name for the cache such as "permission_sort"
     * @param cache: The Cache instance itself, this must be of type Cache
     * @param projectToReplicateAgainst: The name of the project to replicate against such as AllUsers, AllProjects or other
     * @return A ReplicatedCacheImpl which is an instance of Cache, or in a non replicated environment, the same cache instance that was supplied
     * @param <K> Any object used as a key type for a Cache
     * @param <V> Any object used as a value type for a Cache
     */
    <K, V> Cache<K, V> createReplicatedCache(String cacheName, Cache<K, V> cache, String projectToReplicateAgainst);

    /**
     * Create an instance of a ReplicatedCacheImpl. If a ReplicatedCacheImpl instance is used then all cache calls
     * will call our replicated cache methods such as invalidate, invalidateAll etc. and these cache operations
     * will be queued for replication.
     * @param cacheName: The final String constant name for the cache such as "permission_sort"
     * @param cache: The Cache instance itself, this must be of type Cache
     * @param projectToReplicateAgainst: A ProjectNameCallback that can be supplied in the form of a lambda expression.
     *                                 This is the method to call in order to get the project name from the cache key type object
     * @return A ReplicatedCacheImpl which is an instance of Cache, or in a non replicated environment, the same cache instance that was supplied
     * @param <K> Any object used as a key type for a Cache
     * @param <V> Any object used as a value type for a Cache
     */
    <K, V> Cache<K, V> createReplicatedCache(String cacheName, Cache<K, V> cache, ProjectNameCallback<K> projectToReplicateAgainst);

    /**
     * Create an instance of a ReplicatedLoadingCacheImpl. If a ReplicatedLoadingCacheImpl instance is used then all cache calls
     * will call our replicated cache methods such as invalidate, invalidateAll etc. and these cache operations
     * will be queued for replication.
     * @param cacheName: The final String constant name for the cache such as "permission_sort"
     * @param cache: The LoadingCache instance itself, this must be of type LoadingCache
     * @param projectToReplicateAgainst: The name of the project to replicate against such as AllUsers, AllProjects or other
     * @return A ReplicatedLoadingCacheImpl which is an instance of LoadingCache, or in a non replicated environment, the same cache instance that was supplied
     * @param <K> Any object used as a key type for a LoadingCache
     * @param <V> Any object used as a value type for a LoadingCache
     */
    <K, V> LoadingCache<K, V> createReplicatedLoadingCache(String cacheName, LoadingCache<K, V> cache, String projectToReplicateAgainst);

    /**
     * Create an instance of a ReplicatedLoadingCacheImpl. If a ReplicatedLoadingCacheImpl instance is used then all cache calls
     * will call our replicated cache methods such as invalidate, invalidateAll etc. and these cache operations
     * will be queued for replication.
     * @param cacheName: The final String constant name for the cache such as "permission_sort"
     * @param cache: The LoadingCache instance itself, this must be of type LoadingCache
     * @param projectToReplicateAgainst: A ProjectNameCallback that can be supplied in the form of a lambda expression.
     *                                 This is the method to call in order to get the project name from the cache key type object
     * @return A ReplicatedLoadingCacheImpl which is an instance of LoadingCache, or in a non replicated environment, the same cache instance that was supplied
     * @param <K> Any object used as a key type for a LoadingCache
     * @param <V> Any object used as a value type for a LoadingCache
     */
    <K, V> LoadingCache<K, V> createReplicatedLoadingCache(String cacheName, LoadingCache<K, V> cache, ProjectNameCallback<K> projectToReplicateAgainst);
}
