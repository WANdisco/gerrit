// Copyright (C) 2015 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.events;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;

import com.google.common.base.Strings;
import org.junit.Test;
import org.reflections.Reflections;
import org.reflections.scanners.MemberUsageScanner;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.reflections.ReflectionUtils.getAllFields;
public class EventTypesTest {

  //This is a constant and must not be changed as it tracked the known event classes from 2.13
  private static final List<Class<? extends Event>> knownEventClasses = new ArrayList<>(Arrays.asList(
          ChangeAbandonedEvent.class, ChangeMergedEvent.class, ChangeRestoredEvent.class, CommentAddedEvent.class,
          CommitReceivedEvent.class, HashtagsChangedEvent.class, PatchSetCreatedEvent.class,
          ProjectCreatedEvent.class, RefReceivedEvent.class,
          RefUpdatedEvent.class, ReviewerAddedEvent.class, ReviewerDeletedEvent.class,
          TopicChangedEvent.class));

  public static class TestEvent extends Event {
    private static final String TYPE = "test-event";

    public TestEvent() {
      super(TYPE);
    }
  }

  public static class AnotherTestEvent extends Event {
    private static final String TYPE = "another-test-event";

    public AnotherTestEvent() {
      super("another-test-event");
    }
  }

  @SkipReplication
  public static class SkipTestEvent extends Event {
    private static final String TYPE = "skip-test-event";

    public SkipTestEvent() {
      super(TYPE);
    }
  }


  @Test
  public void eventTypeRegistration() {
    try {
      EventTypes.register(TestEvent.TYPE, TestEvent.class);
      EventTypes.register(AnotherTestEvent.TYPE, AnotherTestEvent.class);
      assertThat(EventTypes.getClass(TestEvent.TYPE)).isEqualTo(TestEvent.class);
      assertThat(EventTypes.getClass(AnotherTestEvent.TYPE)).isEqualTo(AnotherTestEvent.class);
    }finally {
      EventTypes.unregister(TestEvent.TYPE);
      EventTypes.unregister(AnotherTestEvent.TYPE);
    }
  }

  @Test
  public void getClassForNonExistingType() {
    Class<?> clazz = EventTypes.getClass("does-not-exist-event");
    assertThat(clazz).isNull();
  }


  /**
   * This test looks at all server events in the package gerrit.server.events and
   * cross-checks them against a list of known event types for 2.13 + 2.16. Then it checks
   * whether those event types are all correctly registered in EventTypes class. Finally, it checks
   * whether each of those server events are correctly annotated with @isReplicatedServerEvent.
   * @throws Exception
   */
  @Test
  public void test_CheckForAnyNewOrMissingServerEvents() throws Exception {
    //new events as of gerrit 2.16
    List<Class<? extends Event>> knownGerrit216EventClasses = new ArrayList<>(Arrays.asList(
        VoteDeletedEvent.class, WorkInProgressStateChangedEvent.class,
        PrivateStateChangedEvent.class, AssigneeChangedEvent.class, ChangeDeletedEvent.class));
    knownEventClasses.addAll(knownGerrit216EventClasses);

    //Getting the events we actually support and compare them.
    //When the EventTypes class loads, there is a static initializer that
    //registers the events. It is these registered events that we want to compare against.
    Reflections serverEventReflections = getReflectionsForPackage("com.google.gerrit.server.events");
    // Build a set of all subTypes of AbstractChangeEvent
    Set<Class<?>> serverEventTypes = getAllServerEventTypes();

    Set<Class<?>> isReplicatedServerEventType =
            serverEventReflections.getTypesAnnotatedWith(isReplicatedServerEvent.class);

    Set<String> KnownIn213 = convertList(knownEventClasses, Class::getName);
    Set<String> knownAddedIn216 = convertList(knownGerrit216EventClasses, Class::getName);

    Set<String> combinedKnown = Stream.of(KnownIn213, knownAddedIn216)
            .flatMap(Collection::stream).collect(Collectors.toSet());

    // Checking the found server event types against the known list of what was added in 2.13 and
    // 2.16
    checkEventSetDifferences(serverEventTypes.stream().map(Class::getName)
            .collect(Collectors.toSet()), combinedKnown);

    //Checking the found server event types are all registered in the EventTypes static init block
    Set<String> registeredEventTypesInCurrentVersion = convertList(getAllEventClasses(), Class::toString);
    assertEquals("The number of found event types does not match the number of event types registered in EventTypes class" +
            "static init block", serverEventTypes.size(), registeredEventTypesInCurrentVersion.size());

    //Checking if the found server event types all are annotated correctly with @isReplicatedServerEvent
    assertEquals("There is a mismatch between the number of server events and the number of server events annotated " +
            "with @isReplicatedServerEvent", serverEventTypes.size(), isReplicatedServerEventType.size());

  }

  @Test(expected = AssertionError.class)
  public void testNewEventTypeAdded() throws Exception {
    //Converting event types of Type Class to String here.
    Set<String> registeredEventTypesInCurrentVersion = convertList(getAllEventClasses(), Class::toString);

    registeredEventTypesInCurrentVersion.add("class com.google.gerrit.server.events.BrandNewEvent");

    Set<Class<?>> serverEventTypes = getAllServerEventTypes();

    checkEventSetDifferences(registeredEventTypesInCurrentVersion,
            serverEventTypes.stream().map(Class::getName).collect(Collectors.toSet()));
  }

  @Test(expected = AssertionError.class)
  public void testEventTypeRemoved() throws Exception {
    //Converting event types of Type Class to String here.
    Set<String> eventTypesInCurrentVersion = convertList(getAllEventClasses(), Class::toString);
    Set<Class<?>> serverEventTypes = getAllServerEventTypes();

    serverEventTypes.remove(ChangeDeletedEvent.class);

    checkEventSetDifferences(eventTypesInCurrentVersion,
            serverEventTypes.stream().map(Class::getName).collect(Collectors.toSet()));
  }

  /**
   * This test is to check that we can verify the presence of the skipReplication
   * annotation on a given event class. SkipTestEvent has been annotated with the
   * @SkipReplication annotation.
   */
  @Test
  public void testSkipReplicationAnnotationPresent() {
    try {
      EventTypes.register(SkipTestEvent.TYPE, SkipTestEvent.class);
      Class eventClass = EventTypes.getClass(SkipTestEvent.TYPE);
      if (!eventClass.isAnnotationPresent(SkipReplication.class)) {
        throw new AssertionError("SkipReplication annotation not found");
      }
    }finally {
      EventTypes.unregister(SkipTestEvent.TYPE);
    }
  }


  /**
   * Finds all classes in a given package.
   * @param packageName
   * @return
   */
  public Set<Class<?>> findAllClassesUsingReflectionsLibrary(String packageName) {
    Reflections reflections = new Reflections(packageName, new SubTypesScanner(false));
    return new HashSet<>(reflections.getSubTypesOf(Object.class));
  }

  /**
   * Looks for static inner classes with the name Event in the classes for the
   * specified package.
   * @param packageName
   * @return
   */
  public Set<Class<?>> getAllClassesWithStaticEventsInPackage(String packageName){
    Map<Class<?>, Class<?>[]> classesWithInnerStaticClass = new HashMap<>();

    Set<Class<?>> allClassesInPackage =
            findAllClassesUsingReflectionsLibrary(packageName);

    // Get all the classes in the package that have a static inner class
    for(Class<?> c : allClassesInPackage){
      if(Arrays.stream(c.getDeclaredClasses()).findAny().isPresent()){
        if(Arrays.stream(c.getDeclaredClasses()).anyMatch(cls -> cls.getName().contains("Event"))) {
          classesWithInnerStaticClass.put(c, c.getDeclaredClasses());
        }
      }
    }
    return classesWithInnerStaticClass.keySet();
  }

  /**
   * Specify a package name to get the reflections information for that package.
   * Using SubTypesScanner to scan all subtypes and TypeAnnotationsScanner to get
   * all annotations set in the package classes.
   * @param pkgName
   * @return
   */
  private static Reflections getReflectionsForPackage(String pkgName) {
    return new Reflections(
            new ConfigurationBuilder()
                    .addClassLoaders( ClasspathHelper.contextClassLoader() )
                    .addUrls(ClasspathHelper.forPackage(pkgName,
                            ClasspathHelper.contextClassLoader()))
                    .setScanners(new SubTypesScanner(false), new TypeAnnotationsScanner(), new MemberUsageScanner())
                    .setExpandSuperTypes(true));
  }


  /**
   *  Finds and returns a list of all event types contained in the typesByString map within the EventTypes
   *  class. These event types are registered in the static init block within the EventTypes class.
   */
  private List<Class<?>> getAllEventClasses() throws NoSuchFieldException, IllegalAccessException {
    EventTypes eventTypes = new EventTypes();
    Class<?> clazz = eventTypes.getClass();
    Field field = clazz.getDeclaredField("typesByString");
    field.setAccessible(true);
    Map<String, String> refMap = (HashMap<String, String>) field.get(eventTypes);
    return Arrays.asList(refMap.values().toArray(new Class<?>[0]));
  }

  /**
   * Transforms each object type in the stream using the map function
   *  to convert one type to another.
   * @param from
   * @param func
   * @return
   * @param <T>
   * @param <U>
   */
  public static <T, U> Set<U> convertList(List<T> from, Function<T, U> func) {
    return from.stream().map(func).collect(Collectors.toSet());
  }

  /**
   * A server event will be of type
   *  - changeEvent
   *  - refEvent
   *  - projectEvent
   *  - patchSetEvent
   *  A server event must also have a TYPE field denoting what kind of event it is. If it doesn't contain
   *  this member in its class then it should not be considered a server event. It is most likely a supertype
   *  or base class.
   * @return
   */
  private static Set<Class<?>> getAllServerEventTypes() {
    Reflections serverEventReflections = getReflectionsForPackage("com.google.gerrit.server.events");

    // Build a set of all subtypes of the parent types such as changeEvent, refEvent etc.
    Set<Class<? extends ChangeEvent>> changeEventTypes =
            serverEventReflections.getSubTypesOf(ChangeEvent.class);

    Set<Class<? extends RefEvent>> refEventTypes =
            serverEventReflections.getSubTypesOf(RefEvent.class);

    Set<Class<? extends ProjectEvent>> projectEventTypes =
            serverEventReflections.getSubTypesOf(ProjectEvent.class);

    Set<Class<? extends PatchSetEvent>> patchSetEventTypes =
            serverEventReflections.getSubTypesOf(PatchSetEvent.class);

    Set<Class<?>> serverEventTypes = new HashSet<>();
    serverEventTypes.addAll(changeEventTypes);
    serverEventTypes.addAll(refEventTypes);
    serverEventTypes.addAll(projectEventTypes);
    serverEventTypes.addAll(patchSetEventTypes);

    // To be considered a server event, it must have a type and must be a registered type in EventTypes
    Set<Class<?>> updatedServerEventTypes = serverEventTypes.stream()
            .filter(cls -> getAllFields(cls, field -> field.getName().matches("TYPE")).size() > 0)
            .collect(Collectors.toSet());

    //Removing abstract classes. We only want subtypes
    updatedServerEventTypes.remove(ChangeEvent.class);
    updatedServerEventTypes.remove(RefEvent.class);
    updatedServerEventTypes.removeIf(e -> e.getName().contains("TestEvent"));
    return updatedServerEventTypes;
  }

  /**
   * Compares two sets, events in the current version vs events in our known_events_types.txt file
   * Throws an AssertionError if any differences found with the relevant message.
   * @param eventTypesInCurrentVersion
   * @param knownEventTypes
   */
  private void checkEventSetDifferences(Set<String> eventTypesInCurrentVersion, Set<String> knownEventTypes) {
    Set<String> oldFilterNew = knownEventTypes.stream()
        .distinct()
        .filter(val -> !eventTypesInCurrentVersion.contains(val))
        .collect(Collectors.toSet());

    Set<String> newFilterOld = eventTypesInCurrentVersion.stream()
        .distinct()
        .filter(val -> !knownEventTypes.contains(val))
        .collect(Collectors.toSet());

    StringBuilder errMsg = new StringBuilder();

    if(!oldFilterNew.isEmpty()) {
      errMsg.append(String.format("\nNew event types have been found " +
          "that are not in our current known set [ %s ], have new events been added? ", oldFilterNew));
    }

    if(!newFilterOld.isEmpty()) {
      errMsg.append(String.format("\nEvent types exist in the known set " +
          "that are not present in this version [ %s ], have they been removed? ", newFilterOld));
    }

    if(!Strings.isNullOrEmpty(errMsg.toString())){
      throw new AssertionError(errMsg);
    }
  }

}


