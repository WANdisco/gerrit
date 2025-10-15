package com.google.gerrit.server.replication;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableSortedSet;
import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.Project;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.replication.exceptions.ReplicatedCacheException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;


public class ReplicatedCacheWatcherTest {

    private ReplicatedCacheWatcher cacheWatcher;

    @Before
    public void setUp() {
        cacheWatcher = new ReplicatedCacheWatcher();
    }

    @After
    public void tearDown() {
        // Unregisters the singleton after each test
        cacheWatcher.stop();
    }

    @Test
    public void testWatchCache_AddsNewCache() {
        String cacheName = "testCache";
        Cache<String, String> cache = CacheBuilder.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .build();

        cacheWatcher.watchCache(cacheName, cache, String.class);

        ReplicatedCacheWrapper<?, ?> replicatedCache = cacheWatcher.getReplicatedCache(cacheName);
        assertNotNull("Cache should be added and retrievable", replicatedCache);
        assertEquals("Cache name should match", cacheName, replicatedCache.getCacheName());
        assertSame("Cache instance should match", cache, replicatedCache.getCache());
    }

    @Test(expected = ReplicatedCacheException.class)
    public void testWatchCache_SameCacheNameButCacheObjectIsNotTheSame() {
        String cacheName = "testCache";
        Cache<String, String> cache1 = CacheBuilder.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .build();
        Cache<String, String> cache2 = CacheBuilder.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .build();

        cacheWatcher.watchCache(cacheName, cache1, String.class);
        cacheWatcher.watchCache(cacheName, cache2, String.class);

        ReplicatedCacheWrapper<?, ?> replicatedCache = cacheWatcher.getReplicatedCache(cacheName);
        assertNotNull("Cache should still exist", replicatedCache);
        assertSame("Cache should not be overridden", cache1, replicatedCache.getCache());
    }


    @Test(expected = ReplicatedCacheException.class)
    public void testWatchCache_SameCacheName_CacheObjectHasSameValsButObjectsAreDifferent() {
        String cacheName = "testCache";
        Cache<String, String> cache1 = CacheBuilder.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .build();

        cache1.put("Jim", "Bob");
        cache1.put("Billy", "Bob");
        cache1.put("Rob", "Bob");

        Cache<String, String> cache2 = CacheBuilder.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .build();

        cache2.put("Jim", "Bob");
        cache2.put("Billy", "Bob");
        cache2.put("Rob", "Bob");

        cacheWatcher.watchCache(cacheName, cache1, String.class);
        cacheWatcher.watchCache(cacheName, cache2, String.class);

        ReplicatedCacheWrapper<?, ?> replicatedCache = cacheWatcher.getReplicatedCache(cacheName);
        assertNotNull("Cache should still exist", replicatedCache);
        assertSame("Cache should not be overridden", cache1, replicatedCache.getCache());
    }


    @Test
    public void testWatchCache_SameCacheNameAndCacheObjectIsTheSame() {
        String cacheName = "testCache";
        Cache<String, String> cache1 = CacheBuilder.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .build();

        cache1.put("A", "B");
        cache1.put("C", "D");
        cache1.put("E", "F");

        Cache<String, String> cache2 = cache1;

        cacheWatcher.watchCache(cacheName, cache1, String.class);
        cacheWatcher.watchCache(cacheName, cache2, String.class);

        ReplicatedCacheWrapper<?, ?> replicatedCache = cacheWatcher.getReplicatedCache(cacheName);
        assertNotNull("Cache should still exist", replicatedCache);
        assertSame("Cache should not be overridden", cache1, replicatedCache.getCache());
        assertSame("Cache should not be overridden", cache2, replicatedCache.getCache());

    }

    @Test
    public void testWatchCache_SameCacheNameAndSameCacheObjectHasChangedValues() {
        String cacheName = "testCache";
        Cache<String, String> cache1 = CacheBuilder.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .build();

        cache1.put("Jim", "Bob");
        cache1.put("Billy", "Bob");
        cache1.put("Rob", "Bob");

        Cache<String, String> cache2 = cache1;

        cache2.put("Jim", "Jim");
        cache2.put("Billy", "Billy");
        cache2.put("Rob", "Rob");

        cacheWatcher.watchCache(cacheName, cache1, String.class);
        cacheWatcher.watchCache(cacheName, cache2, String.class);

        ReplicatedCacheWrapper<?, ?> replicatedCache = cacheWatcher.getReplicatedCache(cacheName);
        assertNotNull("Cache should still exist", replicatedCache);
        assertSame("Cache should not be overridden", cache1, replicatedCache.getCache());
        assertSame("Cache should not be overridden", cache2, replicatedCache.getCache());

    }

    @Test
    public void testWatchObject_AddsNewObject() {
        String objectName = "testObject";
        DummyProjectCache projectCache = new DummyProjectCache();
        cacheWatcher.watchObject(objectName, projectCache);

        Object retrievedObject = cacheWatcher.getReplicatedCacheObject(objectName);
        assertNotNull("Object should be added and retrievable", retrievedObject);
        assertSame("Object should match the added one", projectCache, retrievedObject);
    }

    @Test(expected = ReplicatedCacheException.class)
    public void testWatchObject_DoesNotOverrideExistingObjectAndThrows() {
        String objectName = "testObject";
        DummyProjectCache projectCache1 = new DummyProjectCache();
        DummyProjectCache projectCache2 = new DummyProjectCache();

        // Add the first object
        cacheWatcher.watchObject(objectName, projectCache1);

        // Attempt to add a different object for the same cache name
        cacheWatcher.watchObject(objectName, projectCache2);

        Object retrievedObject = cacheWatcher.getReplicatedCacheObject(objectName);
        assertNotNull("Object should still exist", retrievedObject);
        assertSame("Object should not be overridden", projectCache1, retrievedObject);
    }

    @Test
    public void testGetAllReplicatedCaches_ReturnsCopy() {
        String cacheName1 = "cache1";
        String cacheName2 = "cache2";
        Cache<String, String> cache1 = CacheBuilder.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .build();
        Cache<String, String> cache2 = CacheBuilder.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .build();

        cacheWatcher.watchCache(cacheName1, cache1, String.class);
        cacheWatcher.watchCache(cacheName2, cache2, String.class);

        Map<String, ReplicatedCacheWrapper> allCaches = cacheWatcher.getAllReplicatedCaches();
        assertEquals("Should return all caches", 2, allCaches.size());
        assertTrue("Should contain cache1", allCaches.containsKey(cacheName1));
        assertTrue("Should contain cache2", allCaches.containsKey(cacheName2));

        allCaches.remove(cacheName1); // Attempt to modify returned map
        assertNotNull("Original map should remain unchanged", cacheWatcher.getReplicatedCache(cacheName1));
    }

    @Test
    public void testGetReplicatedCacheObjects_ReturnsCopy() {
        String objectName1 = "object1";
        String objectName2 = "object2";
        DummyProjectCache projectCache1 = new DummyProjectCache();
        DummyProjectCache projectCache2 = new DummyProjectCache();

        cacheWatcher.watchObject(objectName1, projectCache1);
        cacheWatcher.watchObject(objectName2, projectCache2);

        Map<String, Object> allObjects = cacheWatcher.getReplicatedCacheObjects();
        assertEquals("Should return all objects", 2, allObjects.size());
        assertTrue("Should contain object1", allObjects.containsKey(objectName1));
        assertTrue("Should contain object2", allObjects.containsKey(objectName2));

        // Attempt to modify returned map but copy should be returned
        allObjects.remove(objectName1);
        assertNotNull("Original map should remain unchanged", cacheWatcher.getReplicatedCacheObject(objectName1));
    }



    private static class DummyProjectCache implements ProjectCache{

        @Override
        public ProjectState getAllProjects() {
            return null;
        }

        @Override
        public ProjectState getAllUsers() {
            return null;
        }

        @Override
        public Optional<ProjectState> get(Project.NameKey projectName) throws StorageException {
            return Optional.empty();
        }

        @Override
        public void evict(Project.NameKey p) throws IOException {

        }

        @Override
        public void evictAndReindex(Project p) throws IOException {

        }

        @Override
        public void evictAndReindex(Project.NameKey p) throws IOException {

        }

        @Override
        public void remove(Project p) throws IOException {

        }

        @Override
        public void remove(Project.NameKey name) throws IOException {

        }

        @Override
        public ImmutableSortedSet<Project.NameKey> all() {
            return null;
        }

        @Override
        public void refreshProjectList() {

        }

        @Override
        public Set<AccountGroup.UUID> guessRelevantGroupUUIDs() {
            return null;
        }

        @Override
        public ImmutableSortedSet<Project.NameKey> byName(String prefix) {
            return null;
        }

        @Override
        public void onCreateProject(Project.NameKey newProjectName) throws IOException {
        }
    }
}
