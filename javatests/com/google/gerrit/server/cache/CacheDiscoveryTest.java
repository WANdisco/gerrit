package com.google.gerrit.server.cache;

import com.google.common.base.Strings;
import com.google.gerrit.server.replication.SingletonEnforcement;
import com.google.gerrit.server.replication.TestingReplicatedEventsCoordinator;
import com.google.inject.name.Named;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.reflections.Reflections;
import org.reflections.scanners.FieldAnnotationsScanner;
import org.reflections.scanners.MemberUsageScanner;
import org.reflections.scanners.MethodAnnotationsScanner;
import org.reflections.scanners.MethodParameterScanner;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_REPLICATED_CACHE_NAMES_TO_IGNORE;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_REPLICATED_EVENT_WORKER_POOL_MIN_SIZE;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.GERRIT_REPLICATED_EVENT_WORKER_POOL_SIZE;
import static com.google.gerrit.server.replication.configuration.ReplicationConstants.REPLICATION_DISABLED;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;


/**
 * For reference, as of Gerrit 2.16 the following caches are in play throughout the codebase
 accounts
 adv_bases
 change_kind
 change_notes
 changeid_project
 changes
 conflicts
 diff
 diff_intraline
 diff_summary
 external_ids_map
 git_tags
 groups
 groups_bymember
 groups_byname
 groups_bysubgroup
 groups_byuuid
 groups_external
 ldap_group_existence
 ldap_groups
 ldap_groups_byinclude
 ldap_usernames
 mergeability
 oauth_tokens
 permission_sort
 plugin_resources
 project_list
 projects
 prolog_rules
 sshkeys
 web_sessions
 static_content
 */
public class CacheDiscoveryTest {

    private static final String KNOWN_CACHES_FILE = "com/google/gerrit/cachetest/known-caches.txt";
    private static final String KNOWN_SKIPPED_CACHES_FILE = "com/google/gerrit/cachetest/known-skipped-caches.txt";
    private static final String KNOWN_SKIPPED_PLUGIN_CACHES_FILE = "com/google/gerrit/cachetest/known-skipped-plugin-caches.txt";

    private static final String KNOWN_REPLICATED_CACHES_FILE = "com/google/gerrit/cachetest/known-replicated-caches.txt";
    private static final String KNOWN_REPLICATED_PLUGIN_CACHES_FILE = "com/google/gerrit/cachetest/known-replicated-plugin-caches.txt";

    private static final String KNOWN_INTERFACES_FILE = "com/google/gerrit/cachetest/known-cache-interfaces.txt";
    private final String GERRIT_BASE_PKG = "com.google.gerrit";

    private final String GERRIT_PLUGINS = "com.googlesource.gerrit.plugins";
    private final String SEARCH_STR = "Cache";
    private Reflections reflections;

    protected static TestingReplicatedEventsCoordinator dummyTestCoordinator;


    @Before
    public void setup() throws Exception {
        SingletonEnforcement.clearAll();
        SingletonEnforcement.setDisableEnforcement(true);
        Properties testingProperties = new Properties();
        // SET our pool to 2 items, plus the 2 core projects.
        testingProperties.put(GERRIT_REPLICATED_EVENT_WORKER_POOL_MIN_SIZE, "2");
        testingProperties.put(GERRIT_REPLICATED_EVENT_WORKER_POOL_SIZE, "2");
        testingProperties.put(REPLICATION_DISABLED, false);
        dummyTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);
    }


    // Find all classes in the server package. All classes are subtypes of Object.class, so using that to
    // find all classes in that package.
    public Set<Class<?>> findAllClasses(final String packages) {
        reflections = new Reflections(new ConfigurationBuilder()
                .setUrls(ClasspathHelper.forPackage(packages))
                .setScanners(new SubTypesScanner(false),
                        new TypeAnnotationsScanner(), new FieldAnnotationsScanner(),
                        new MethodAnnotationsScanner(), new MemberUsageScanner(), new MethodParameterScanner()));
        return reflections.getSubTypesOf(Object.class);
    }

    /**
     * Transforms each object type in the stream using the map function
     *  to convert one type to another.
     */
    public static <T, U> Set<U> convertList(List<T> from, Function<T, U> transform) {
        return from.stream().map(transform).collect(Collectors.toSet());
    }


    /**
     * Compares two sets, caches in the current version vs known caches file list
     * overrideFoundMsg and overrideMissingMsg are format strings expecting to have a single
     * '%s' placeholder token to be filled in by the filtered Set of caches and are used to override the message that
     * comes out whenever there are differences with the found or missing caches. The difference in the message depends
     * on the test being run and what we are checking for.
     */
    private void checkCacheSetDifferences(Set<String> cachesFoundInCurrentVersion, Set<String> knownCaches, String overrideFoundMsg, String overrideMissingMsg) {
        // Look at the known caches and filter out all the found caches. There should be none remaining.
        Set<String> knownFilterFound = knownCaches.stream()
                .filter(val -> !cachesFoundInCurrentVersion.contains(val))
                .collect(Collectors.toSet());

        // Look at the found caches and filter out all the known caches. There should be none remaining.
        Set<String> foundFilterKnown = cachesFoundInCurrentVersion.stream()
                .filter(val -> !knownCaches.contains(val))
                .collect(Collectors.toSet());

        StringBuilder errMsg = new StringBuilder();

        String msgFound = Strings.isNullOrEmpty(overrideFoundMsg) ? "New caches have been found that are not in our current known set [ %s ]" : overrideFoundMsg;
        String msgMissingRemoved = Strings.isNullOrEmpty(overrideMissingMsg) ? "Caches exist in the known set that are " +
                "not present in this version [ %s ], have the caches been removed or replaced?;" : overrideMissingMsg;

        if(!foundFilterKnown.isEmpty()) {
            errMsg.append(String.format(msgFound, foundFilterKnown));
        }

        if(!knownFilterFound.isEmpty()) {
            errMsg.append(String.format(msgMissingRemoved, knownFilterFound));
        }

        if(!Strings.isNullOrEmpty(errMsg.toString())){
            throw new AssertionError(errMsg);
        }

        // The caches entries must be equal with no differences.
        assertTrue(setsAreEqual(knownCaches, cachesFoundInCurrentVersion));
    }

    private static boolean setsAreEqual(Set<String> set1, Set<String> set2) {
        // Compare sets using lambda expression
        return set1.size() == set2.size() && set1.containsAll(set2);
    }

    /**
     * Return true / false if the class in question is a static inner class of a class and if it
     * is a subtype of CacheLoader
     */
    private static boolean hasStaticInnerClassExtendingCacheLoader(Class<?> clazz) {
        // Check if the class is a static inner class and extends CacheLoader
        return clazz.isMemberClass() && java.lang.reflect.Modifier.isStatic(clazz.getModifiers()) &&
                com.google.common.cache.CacheLoader.class.isAssignableFrom(clazz);
    }


    // get a file from the resources folder
    // works everywhere, IDEA, unit test and JAR file.
    private InputStream getFileFromResourceAsStream(String fileName) {

        // The class loader that loaded the class
        ClassLoader classLoader = getClass().getClassLoader();
        InputStream inputStream = classLoader.getResourceAsStream(fileName);

        // the stream holding the file content
        if (inputStream == null) {
            throw new IllegalArgumentException("file not found! " + fileName);
        } else {
            return inputStream;
        }
    }


    private static Set<String> readResourceFileContent(InputStream is) {
        Set<String> knownCacheSet = new HashSet<>();
        try (InputStreamReader streamReader = new InputStreamReader(is, StandardCharsets.UTF_8);
             BufferedReader reader = new BufferedReader(streamReader)) {
            String line;
            while ((line = reader.readLine()) != null) {
                knownCacheSet.add(line);
            }
        } catch (IOException e) {
            System.out.println("There was an error whilst trying to open a file in the resources folder : " + e.getMessage());
        }
        return knownCacheSet;
    }


    private static boolean isNamedWithCacheNameField(Class<?> clazz) {
        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
            if ((field.getName().contains("CACHE")
                    || field.getName().equals("NAME")
                    || field.getName().startsWith("BY")) &&
                    java.lang.reflect.Modifier.isStatic(field.getModifiers()) &&
                    java.lang.reflect.Modifier.isFinal(field.getModifiers()) &&
                    field.getType() == String.class) {
                return true;
            }
        }
        return false;
    }

    /**
     * When provided with all classes set, it will determine what classes have a cache in them and return a
     * set of all found caches.
     */
    @SuppressWarnings("EmptyCatch")
    private Set<String> extractCachesFromClasses(Set<Class<?>> allClasses) {
        Set<String> foundCaches = new TreeSet<>();

        for (Class<?> clazz : allClasses) {
            try {
                // Look at the constructors of the class and check if they have an @Named annotation. If they do, check if what's annotated is a Cache
                foundCaches.addAll(extractCacheNamesFromConstructors(clazz));
                // Now look at the static inner classes of the current class that extend ClassLoader and look at their @Named annotations
                foundCaches.addAll(extractCacheNamesFromInnerClasses(clazz));
                // Look at the method parameters annotated with @Named and check if they are a Cache.
                foundCaches.addAll(getMethodsInClassAnnotatedWithNamed(clazz));
                // Don't care about this failure.
            } catch (NoClassDefFoundError ignored) {
            }
        }
        return foundCaches;
    }

    /**
     * Look at the constructors of the class and check if they have an @Named annotation.
     * If they do, check if what's annotated is a Cache
     */
    private Set<String> extractCacheNamesFromConstructors(Class<?> clazz) {
        Set<String> found = new TreeSet<>();
        Constructor<?>[] constructors = clazz.getDeclaredConstructors();

        // Check each constructor for parameters annotated with @Named annotation
        for (Constructor<?> constructor : constructors) {
            Parameter[] parameters = constructor.getParameters();
            for (Parameter parameter : parameters) {
                Named namedAnnotation = parameter.getAnnotation(Named.class);
                if (namedAnnotation != null) {
                    // The @Named annotation will either be passed a field named CACHE_NAME or something similar
                    // If it isn't a similar name then check if the parameter has Cache in the name as this is also a good indicator
                    if (isNamedWithCacheNameField(clazz)
                            || parameter.getName().contains(SEARCH_STR)
                            || parameter.getName().contains(SEARCH_STR.toLowerCase())
                            || parameter.getParameterizedType().getTypeName().contains(SEARCH_STR)) {
                        found.add(namedAnnotation.value());
                    }
                }
            }
        }
        return found;
    }

    /**
     * Looks at the static inner classes of the class, if any of constructors are annotated with @Named, then look to see
     * if the constructor parameter is a Cache. If it is, add it to our set and return.
     */
    private Set<String> extractCacheNamesFromInnerClasses(Class<?> clazz) {
        Set<String> found = new TreeSet<>();
        
        Class<?>[] innerClasses = clazz.getDeclaredClasses();
        for (Class<?> inner : innerClasses) {
            if (hasStaticInnerClassExtendingCacheLoader(inner)) {
                Constructor<?>[] innerConstructors = inner.getDeclaredConstructors();

                // Check each constructor for parameters annotated with @Named annotation
                for (Constructor<?> innerConstructor : innerConstructors) {
                    Parameter[] innerParameters = innerConstructor.getParameters();
                    for (Parameter innerParam : innerParameters) {
                        Named namedAnnotation = innerParam.getAnnotation(Named.class);
                        if (namedAnnotation != null) {
                            // The @Named annotation will either be passed a field named CACHE_NAME or something similar
                            // If it isn't a similar name then check if the parameter has Cache in the name as this is also a good indicator
                            if (isNamedWithCacheNameField(inner)
                                    || innerParam.getName().contains(SEARCH_STR)
                                    || innerParam.getName().contains(SEARCH_STR.toLowerCase())
                                    || innerParam.getParameterizedType().getTypeName().contains(SEARCH_STR)) {
                                found.add(namedAnnotation.value());
                            }
                        }
                    }
                }
            }
        }
        return found;
    }


    /**
     * Looks at the methods in the class, if any of the methods are annotated with @Named, then look to see
     * if the method parameter is a Cache. If it is, add it to our set and return.
     */
    private Set<String> getMethodsInClassAnnotatedWithNamed(Class<?> clazz) {
        Set<String> found = new TreeSet<>();
        Set<Method> methodsAnnotatedWithNamed = reflections.getMethodsAnnotatedWith(Named.class);
        for (Method method : methodsAnnotatedWithNamed) {
            Parameter[] parameters = method.getParameters();
            for (Parameter methodParam : parameters) {
                Named namedAnnotation = methodParam.getAnnotation(Named.class);
                if (namedAnnotation != null) {
                    // The @Named annotation will either be passed a field named CACHE_NAME or something similar
                    // If it isn't a similar name then check if the parameter has Cache in the name as this is also a good indicator
                    if (isNamedWithCacheNameField(clazz)
                            || methodParam.getName().contains(SEARCH_STR)
                            || methodParam.getName().contains(SEARCH_STR.toLowerCase())
                            || methodParam.getParameterizedType().getTypeName().contains(SEARCH_STR)) {
                        found.add(namedAnnotation.value());
                    }
                }
            }
        }
        return found;
    }


    public Set<String> getAllSkippedCaches() throws IllegalAccessException {
        Set<String> skippedCaches = new TreeSet<>();
        Set<Field> fields = reflections.getFieldsAnnotatedWith(SkipCacheReplication.class);
        for (Field field : fields) {
            field.setAccessible(true);
            skippedCaches.add((String) field.get(0));
        }
        return skippedCaches;
    }


    public Set<String> getAllReplicatedCaches() throws IllegalAccessException {
        Set<String> skippedCaches = new TreeSet<>();
        Set<Field> fields = reflections.getFieldsAnnotatedWith(ReplicatedCache.class);
        for (Field field : fields) {
            field.setAccessible(true);
            skippedCaches.add((String) field.get(0));
        }
        return skippedCaches;
    }


    /**
     * This test looks at the cache interfaces that are in use through the system and compares against the known
     * list of cache interfaces we have.
     * As of Gerrit stable-3.7_WD, these are the following known cache interfaces. These interfaces will have their
     * own guice bound cache implementations, i.e GroupCache will be implemented by GroupCacheImpl etc.
     */
    @Test
    public void test_CheckForAnyNewOrMissingCacheInterfaces() {

        // Get all classes in your package
        Set<Class<?>> allClasses = findAllClasses(GERRIT_BASE_PKG);
        List<Class<?>> foundCacheInterfaces = new ArrayList<>();
        Set<String> known_2_16_cache_interfaces = readResourceFileContent(getFileFromResourceAsStream(KNOWN_INTERFACES_FILE));
        // Filter classes that end with "Cache" and are interfaces
        allClasses.forEach(clz -> {
            if (clz.isInterface() && clz.getSimpleName().endsWith("Cache")){ foundCacheInterfaces.add(clz);}});

        Set<String> foundCachesInCurrentVersion = convertList(foundCacheInterfaces, Class::getSimpleName);
        foundCachesInCurrentVersion.remove("DummyCache"); // This is the Dummy cache class created above
        foundCachesInCurrentVersion.remove("ReplicatedCache"); // This is the annotation interface which we don't care to find here.
        // Checking the found cache types against the known list of what was added in 2.16
        checkCacheSetDifferences(foundCachesInCurrentVersion, known_2_16_cache_interfaces, null, null);
    }

    /**
     * This is a cache discovery test based on finding all classes that have the @Named annotation that is passed
     * a field called CACHE_NAME, or contains _CACHE, or a field name beginning with BY or NAME. We also check if the parameter
     * name contains with word "Cache". Static inner classes, if they exist, are checked for the same information.
     * All the caches that are found are added to a set and cross-referenced against the known-caches.txt file. If any
     * differences then report an error.
     */
    @Test
    public void test_FindAllConstructorsWithNamedAnnotation() throws IOException {

        Set<String> foundCaches = new TreeSet<>();
        // Get all classes in gerrit package
        Set<Class<?>> allClasses = findAllClasses(GERRIT_BASE_PKG);
        foundCaches.addAll(extractCachesFromClasses(allClasses));

        // Look at the known-caches.txt file and compare against the found caches.
        Set<String> knownCaches = readResourceFileContent(getFileFromResourceAsStream(KNOWN_CACHES_FILE));
        //ProjectCacheImpl is a special object that we use to replicate method calls. It is not a gerrit vanilla LoadingCache or Cache
        knownCaches.remove("ProjectCacheImpl");
        checkCacheSetDifferences(foundCaches, knownCaches, null, null);
    }


    /**
     * Tests that when a cache is removed from the found caches set and then a comparison is made between
     * all the found caches and the ones we know about, then an assertion error should be thrown.
     */
    @Test
    public void testCacheIsRemoved() {

        // Get all classes in gerrit package
        Set<Class<?>> allClasses = findAllClasses(GERRIT_BASE_PKG);
        Set<String> foundCaches = new TreeSet<>(extractCachesFromClasses(allClasses));

        foundCaches.remove("accounts");

        Set<String> knownCaches = readResourceFileContent(getFileFromResourceAsStream(KNOWN_CACHES_FILE));
        AssertionError assertionError  = assertThrows(AssertionError.class, () -> {
            checkCacheSetDifferences(foundCaches, knownCaches, null, null);
        });
        String actualMessage = assertionError.getMessage();
        assertTrue(actualMessage.contains("Caches exist in the known set"));
        assertTrue(actualMessage.contains("accounts"));
    }



    @Test
    public void testCacheIsAdded() throws Exception {

        // Get all classes in gerrit package
        Set<Class<?>> allClasses = findAllClasses(GERRIT_BASE_PKG);
        Set<String> foundCaches = new TreeSet<>(extractCachesFromClasses(allClasses));

        foundCaches.add("brand_new_cache");

        Set<String> knownCaches = readResourceFileContent(getFileFromResourceAsStream(KNOWN_CACHES_FILE));

        AssertionError assertionError  = assertThrows(AssertionError.class, () -> {
            checkCacheSetDifferences(foundCaches, knownCaches, null, null);
        });

        String actualMessage = assertionError.getMessage();
        assertTrue(actualMessage.contains("New caches have been found"));
        assertTrue(actualMessage.contains("brand_new_cache"));
    }


    interface DummyCache{
        void get();
        void evict();
    }

    public static class SkipCache  implements DummyCache{
        @SkipCacheReplication
        @SuppressWarnings("unused")
        private static final String CACHE_NAME = "skipCache";

        public SkipCache() {
        }

        @Override
        public void get() {

        }

        @Override
        public void evict() {

        }
    }

    public static class NewReplCache implements DummyCache{
        @ReplicatedCache
        @SuppressWarnings("unused")
        private static final String CACHE_NAME = "newReplCache";

        public NewReplCache() {
        }

        @Override
        public void get() {

        }

        @Override
        public void evict() {

        }
    }


    @Test
    public void test_addSkipAnnotationToNewCache() throws IllegalAccessException {
        findAllClasses(GERRIT_BASE_PKG);
        Set<String> foundSkippedCaches = getAllSkippedCaches();
         Set<String> knownSkippedCaches = readResourceFileContent(getFileFromResourceAsStream(KNOWN_SKIPPED_CACHES_FILE));

        // An assertion will be thrown because the known-skipped-caches.txt list will not contain the skipCache from the
        // class above.
        AssertionError assertionError  = assertThrows(AssertionError.class, () -> {
            String msgNewSkipped = "New caches have been skipped via the @SkipCacheReplication annotation that are not in our current known skipped set [ %s ]";
            String msgMissingSkipped = "Caches exist in the known skipped set that are not present in this version [ %s ], has annotation been removed or have caches been removed or replaced?";
            checkCacheSetDifferences(foundSkippedCaches, knownSkippedCaches, msgNewSkipped, msgMissingSkipped);
        });

        String actualMessage = assertionError.getMessage();
        assertTrue(actualMessage.contains("New caches have been skipped via the @SkipCacheReplication annotation that are not in our current known skipped set"));
        assertTrue(actualMessage.contains("skipCache"));
    }


    @Test
    public void test_addIsReplicatedCacheAnnotationToNewCache() throws IllegalAccessException {
        findAllClasses(GERRIT_BASE_PKG);
        Set<String> foundReplicatedCaches = getAllReplicatedCaches();
        Set<String> knownSkippedCaches = readResourceFileContent(getFileFromResourceAsStream(KNOWN_SKIPPED_CACHES_FILE));

        // For knowing what the differences are between replicated caches and skipped caches. We look at all the
        // replicated caches we have and all the known skipped caches we have. The difference between the two should
        // equal the amount of replicated caches we have.
        Set<String> foundFilterKnown = foundReplicatedCaches.stream()
                .filter(val -> !knownSkippedCaches.contains(val))
                .collect(Collectors.toSet());

        Set<String> knownReplicatedCaches = readResourceFileContent(getFileFromResourceAsStream(KNOWN_REPLICATED_CACHES_FILE));

        // An assertion will be thrown because the known-skipped-caches.txt list will not contain the skipCache from the
        // class above.
        AssertionError assertionError  = assertThrows(AssertionError.class, () -> {
            String msgNewRepl = "New caches have been replicated via the @ReplicatedCache annotation that are not in our current known set [ %s ]";
            String msgMissingRepl = "Caches exist in the known replicated set that are not present in this version [ %s ], has @ReplicatedCache annotation been removed or have caches been removed or replaced?";
            checkCacheSetDifferences(foundFilterKnown, knownReplicatedCaches, msgNewRepl, msgMissingRepl);
        });

        String actualMessage = assertionError.getMessage();
        assertTrue(actualMessage.contains("New caches have been replicated via the @ReplicatedCache annotation that are not in our current known set"));
        assertTrue(actualMessage.contains("newReplCache"));
    }



    @Test
    public void test_checkForSkipCacheReplicationAnnotation() throws IllegalAccessException {
        findAllClasses(GERRIT_BASE_PKG);
        Set<String> foundSkippedCaches = getAllSkippedCaches();
        // We don't want our test cache interfering with the test result so remove it.
        foundSkippedCaches.remove("skipCache");

        Set<String> knownSkippedCaches = readResourceFileContent(getFileFromResourceAsStream(KNOWN_SKIPPED_CACHES_FILE));
        String msgNewSkipped = "New caches have been skipped via the @SkipCacheReplication annotation that are not in our current known skipped set [ %s ]";
        String msgMissingSkipped = "Caches exist in the known skipped set that are not present in this version [ %s ], has annotation been removed or have caches been removed or replaced?";
        checkCacheSetDifferences(foundSkippedCaches, knownSkippedCaches, msgNewSkipped, msgMissingSkipped);
    }


    @Test
    public void test_checkForReplicatedCacheAnnotation() throws IllegalAccessException {
        findAllClasses(GERRIT_BASE_PKG);
        Set<String> foundReplicatedCaches = getAllReplicatedCaches();
        // We don't want our test cache interfering with the test result so remove it.
        foundReplicatedCaches.remove("newReplCache");
        Set<String> knownSkippedCaches = readResourceFileContent(getFileFromResourceAsStream(KNOWN_SKIPPED_CACHES_FILE));

        // For knowing what the differences are between replicated caches and skipped caches. We look at all the
        // replicated caches we have and all the known skipped caches we have. The difference between the two should
        // equal the amount of replicated caches we have.
        Set<String> foundFilterKnown = foundReplicatedCaches.stream()
                .filter(val -> !knownSkippedCaches.contains(val))
                .collect(Collectors.toSet());

        Set<String> knownReplicatedCaches = readResourceFileContent(getFileFromResourceAsStream(KNOWN_REPLICATED_CACHES_FILE));

        String msgNewRepl = "New caches have been replicated via the @ReplicatedCache annotation that are not in our current known set [ %s ]";
        String msgMissingRepl = "Caches exist in the known replicated set that are not present in this version [ %s ], has @ReplicatedCache annotation been removed or have caches been removed or replaced?";

        checkCacheSetDifferences(foundFilterKnown, knownReplicatedCaches, msgNewRepl, msgMissingRepl);
        assertEquals(23, foundFilterKnown.size());
    }


    /**
     * This test only scans the Gerrit plugins package. Although these caches may be marked as @ReplicatedCache
     * they (at the time of writing this) will be ignored via a property anyway. This gives us control, so we can
     * decide to replicate them at will. The replicated / skipped caches for plugins have been kept in separate files
     * as not to confuse with main package class caches that we replicate for Gerrit, It also makes them easier to
     * identify.
     */
    @Test
    public void test_checkGerritPluginsForCaches() throws IllegalAccessException {

        Set<?> extractedCacheClasses = extractCachesFromClasses(findAllClasses(GERRIT_PLUGINS));
        // At the time of writing, there are 4 plugins that are known to have their own caches. If plugins are added in
        // future with their own caches, then this test will fail.
        assertEquals(4, extractedCacheClasses.size());

        Set<String> foundReplicatedPluginCaches = getAllReplicatedCaches();
        Set<String> knownSkippedPluginCaches = readResourceFileContent(getFileFromResourceAsStream(KNOWN_SKIPPED_PLUGIN_CACHES_FILE));

        Set<String> foundFilterKnown = foundReplicatedPluginCaches.stream()
                .filter(val -> !knownSkippedPluginCaches.contains(val))
                .collect(Collectors.toSet());

        Set<String> knownReplicatedPluginCaches = readResourceFileContent(getFileFromResourceAsStream(KNOWN_REPLICATED_PLUGIN_CACHES_FILE));

        String msgNewRepl = "New caches in the plugins package have been replicated via the @ReplicatedCache annotation that are not in our current known set [ %s ]";
        String msgMissingRepl = "Caches exist in the known replicated plugin cache set that are not present in this version [ %s ], has @ReplicatedCache annotation been removed or have caches been removed or replaced?";

        checkCacheSetDifferences(foundFilterKnown, knownReplicatedPluginCaches, msgNewRepl, msgMissingRepl);
        assertEquals(3, foundFilterKnown.size());
    }


    /**
     * Tests that we have a default set of cache names to ignore replication
     */
    @Test
    public void test_DefaultCacheNamesToIgnore() {
        Assert.assertTrue(dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("ldap_usernames"));
        Assert.assertTrue(dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("prolog_rules"));
        Assert.assertTrue(dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("mergeability"));
        Assert.assertTrue(dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("change_kind"));
        Assert.assertTrue(dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("static_content"));
    }


    @Test
    public void test_AddReplicatedCacheToIgnoreList() throws Exception {

        Properties testingProperties = new Properties();
        // SET our pool to 2 items, plus the 2 core projects.
        testingProperties.put(GERRIT_REPLICATED_EVENT_WORKER_POOL_MIN_SIZE, "2");
        testingProperties.put(GERRIT_REPLICATED_EVENT_WORKER_POOL_SIZE, "2");
        testingProperties.put(REPLICATION_DISABLED, false);
        testingProperties.put(GERRIT_REPLICATED_CACHE_NAMES_TO_IGNORE, "accounts, lfs_locks, jira_server_project, its_rules_project");
        dummyTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);

        Assert.assertTrue("Cache annotated with @SKipCacheReplication was not present in the 'isCacheToBeIgnored' list",
                dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("change_kind"));

        Assert.assertTrue("accounts was not present in the 'isCacheToBeIgnored' list",
                dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("accounts"));
        Assert.assertTrue("lfs_locks cache was not present in the 'isCacheToBeIgnored' list",
                dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("lfs_locks"));
        Assert.assertTrue("jira_server_project cache was not present in the 'isCacheToBeIgnored' list",
                dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("jira_server_project"));
        Assert.assertTrue("its_rules_project cache was not present in the 'isCacheToBeIgnored' list",
                dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("its_rules_project"));

    }

    /**
     * If the lfs_locks cache is not in the ignore list, then it should be replicating again. There should however
     * be two caches remaining in the default ignore list
     */
    @Test
    public void test_AddReplicatedCacheToIgnoreList_StartReplicatingLfsLocksCache() throws Exception {

        Properties testingProperties = new Properties();
        // SET our pool to 2 items, plus the 2 core projects.
        testingProperties.put(GERRIT_REPLICATED_EVENT_WORKER_POOL_MIN_SIZE, "2");
        testingProperties.put(GERRIT_REPLICATED_EVENT_WORKER_POOL_SIZE, "2");
        testingProperties.put(REPLICATION_DISABLED, false);
        testingProperties.put(GERRIT_REPLICATED_CACHE_NAMES_TO_IGNORE, "jira_server_project, its_rules_project, change_notes, " +
                "default_preferences, pure_revert, groups_external_persisted, groups_byuuid_persisted, persisted_projects, git_tags, oauth_tokens, web_sessions");
        dummyTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);

        Assert.assertTrue("Cache annotated with @SKipCacheReplication was not present in the 'isCacheToBeIgnored' list",
                dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("change_kind"));

        Assert.assertFalse("lfs_locks cache *WAS* still present in the 'isCacheToBeIgnored' list",
                dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("lfs_locks"));

        Assert.assertTrue("jira_server_project cache was not present in the 'isCacheToBeIgnored' list",
                dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("jira_server_project"));
        Assert.assertTrue("its_rules_project cache was not present in the 'isCacheToBeIgnored' list",
                dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("its_rules_project"));

        Assert.assertTrue("git_tags cache was not present in the 'isCacheToBeIgnored' list",
                dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("git_tags"));

        Assert.assertTrue("oauth_tokens cache was not present in the 'isCacheToBeIgnored' list",
                dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("oauth_tokens"));

        Assert.assertTrue("default_preferences cache was not present in the 'isCacheToBeIgnored' list",
                dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("default_preferences"));

    }


    @Test
    public void test_AddReplicatedCacheToIgnoreListEmpty() throws Exception {

        Properties testingProperties = new Properties();
        // SET our pool to 2 items, plus the 2 core projects.
        testingProperties.put(GERRIT_REPLICATED_EVENT_WORKER_POOL_MIN_SIZE, "2");
        testingProperties.put(GERRIT_REPLICATED_EVENT_WORKER_POOL_SIZE, "2");
        testingProperties.put(REPLICATION_DISABLED, false);
        testingProperties.put(GERRIT_REPLICATED_CACHE_NAMES_TO_IGNORE, "");
        dummyTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);

        Assert.assertTrue("Skipped cache was not present in the 'isCacheToBeIgnored' list",
                dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("change_kind"));
        Assert.assertTrue("lfs_locks cache was not present in the 'isCacheToBeIgnored' list",
                dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("lfs_locks"));
        Assert.assertTrue("jira_server_project cache was not present in the 'isCacheToBeIgnored' list",
                dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("jira_server_project"));
        Assert.assertTrue("its_rules_project cache was not present in the 'isCacheToBeIgnored' list",
                dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("its_rules_project"));

    }

    @Test
    public void test_AddReplicatedCacheToIgnoreListEmpty_ignoreNone() throws Exception {

        Properties testingProperties = new Properties();
        // SET our pool to 2 items, plus the 2 core projects.
        testingProperties.put(GERRIT_REPLICATED_EVENT_WORKER_POOL_MIN_SIZE, "2");
        testingProperties.put(GERRIT_REPLICATED_EVENT_WORKER_POOL_SIZE, "2");
        testingProperties.put(REPLICATION_DISABLED, false);
        testingProperties.put(GERRIT_REPLICATED_CACHE_NAMES_TO_IGNORE, "none");
        dummyTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);

        Assert.assertFalse("Default Ignore Cache lfs_locks was still present in the 'isCacheToBeIgnored' list",
                dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("lfs_locks"));
        Assert.assertFalse("Default Ignore Cache groups_byuuid_persisted was still present in the 'isCacheToBeIgnored' list",
                dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("groups_byuuid_persisted"));
        Assert.assertFalse("Default Ignore Cache oauth_tokens was still present in the 'isCacheToBeIgnored' list",
                dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("oauth_tokens"));
        Assert.assertFalse("Default Ignore Cache git_tags was still present in the 'isCacheToBeIgnored' list",
                dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("git_tags"));


        Assert.assertTrue("Forced Skipped Cache change_kind annotated with @SkipCacheReplication was not present in the 'isCacheToBeIgnored' list",
                dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("change_kind"));

        Assert.assertTrue("Forced Skipped Cache git_modified_files annotated with @SkipCacheReplication was not present in the 'isCacheToBeIgnored' list",
                dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("git_modified_files"));

        Assert.assertTrue("Forced Skipped Cache ldap_group_existence annotated with @SkipCacheReplication was not present in the 'isCacheToBeIgnored' list",
                dummyTestCoordinator.getReplicatedConfiguration().isCacheToBeIgnored("ldap_group_existence"));

    }
}