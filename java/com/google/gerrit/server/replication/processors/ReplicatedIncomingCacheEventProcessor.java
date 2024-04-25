package com.google.gerrit.server.replication.processors;

import com.google.common.cache.Cache;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.replication.customevents.CacheKeyWrapper;
import com.google.gerrit.server.replication.customevents.CacheObjectCallWrapper;
import com.google.gerrit.server.replication.ReplicatedCacheWrapper;
import com.google.gerrit.server.replication.ReplicatorMetrics;
import com.google.gerrit.server.replication.SingletonEnforcement;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.server.replication.exceptions.ReplicatedEventsImmediateFailWithoutBackoffException;
import com.google.gerrit.server.replication.exceptions.ReplicatedEventsUnknownTypeException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gson.internal.LinkedTreeMap;
import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.CACHE_EVENT;

public class ReplicatedIncomingCacheEventProcessor extends AbstractReplicatedEventProcessor {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Map<String, ReplicatedCacheWrapper> caches = new ConcurrentHashMap<>();
  private final Map<String, Object> cacheObjects = new ConcurrentHashMap<>();
  public static String projectCache = "ProjectCacheImpl";


  /**
   * We only create this class from the replicatedEventsCoordinator.
   * This is a singleton and its enforced by our SingletonEnforcement below that if anyone else tries to create
   * this class it will fail.
   * Sorry by adding a getInstance, make this class look much more public than it is,
   * and people expect they can just call getInstance - when in fact they should always request it via the
   * ReplicatedEventsCoordinator.getReplicatedXWorker() methods.
   *
   * @param replicatedEventsCoordinator
   */
  public ReplicatedIncomingCacheEventProcessor(ReplicatedEventsCoordinator replicatedEventsCoordinator) {
    super(CACHE_EVENT, replicatedEventsCoordinator);
    logger.atInfo().log("Creating main processor for event type: %s", eventType);
    subscribeEvent(this);
    SingletonEnforcement.registerClass(ReplicatedIncomingCacheEventProcessor.class);
  }

  @Override
  public void stop() {
    SingletonEnforcement.unregisterClass(ReplicatedIncomingCacheEventProcessor.class);
    unsubscribeEvent(this);
  }


  @Override
  public void processIncomingReplicatedEvent(final ReplicatedEvent replicatedEvent) {
    applyCacheMethodOrEviction((CacheKeyWrapper) replicatedEvent);
  }

  private void applyCacheMethodOrEviction(CacheKeyWrapper cacheKeyWrapper) {

    cacheKeyWrapper.replicated = true;
    cacheKeyWrapper.setNodeIdentity(Objects.requireNonNull(replicatedEventsCoordinator.getThisNodeIdentity()));

    // explitally call this before anyone else makes any enquiries, although they should all be protected internally.
    cacheKeyWrapper.rebuildOriginal();

    if (cacheKeyWrapper instanceof CacheObjectCallWrapper) {
      CacheObjectCallWrapper originalObj = (CacheObjectCallWrapper) cacheKeyWrapper;
      // Invokes a particular method on a cache. The CacheObjectCallWrapper carries the method
      // to be invoked on the cache. At present, we make only two replicated cache method calls from ProjectCacheImpl, but
      // it is written generically to be called on any project dsm, with any number of args.
      if ( originalObj.key instanceof List ){
        applyMethodCallOnCache(originalObj.cacheName,
            originalObj.methodName,
            originalObj.getMethodArgs(),
            originalObj.getMethodArgsTypes());
        return;
      }
      logger.atSevere().log("Event cannot be processed, as the information is not of the expected type. The CacheKeyWrapper key field should be a List<>, CacheKeyWrapperDetails: %s",
            cacheKeyWrapper.toString());
      throw new ReplicatedEventsImmediateFailWithoutBackoffException("Invalid Event JSON - the key information has not been supplied correctly for cache key wrapper: " + cacheKeyWrapper);
    }

    // Perform an eviction for a specified key on the specified local cache
    applyReplicatedEvictionFromCache(cacheKeyWrapper.cacheName, cacheKeyWrapper.getKeyAsOriginalType());
  }


  private void applyReplicatedEvictionFromCache(String cacheName, Object key) {
    boolean evicted = false;
    boolean reloaded = false;
    ReplicatedCacheWrapper wrapper = caches.get(cacheName);
    if (wrapper == null) {
      logger.atSevere().log("CACHE call could not be made, as cache does not exist. %s", cacheName);
      throw new ReplicatedEventsUnknownTypeException(
          String.format("CACHE call on replicated eviction could not be made, as cache does not exist. %s", cacheName));
    }

    if (replicatedEventsCoordinator.isCacheToBeEvicted(cacheName)) {
      logger.atFine().log("CACHE %s to evict %s...", cacheName, key);
      evicted = wrapper.evict(key);
      if (replicatedEventsCoordinator.getReplicatedConfiguration().isCacheToBeReloaded(cacheName)) {
        logger.atFine().log("CACHE %s to reload key %s...", cacheName, key);
        reloaded = wrapper.reload(key);
      } else {
        logger.atFine().log("CACHE %s *not* to reload key %s...", cacheName, key);
      }
    } else {
      logger.atFine().log("CACHE %s to *not* to evict %s...", cacheName, key);
    }

    if (evicted) {
      ReplicatorMetrics.addEvictionsPerformed(cacheName);
    }
    if (reloaded) {
      ReplicatorMetrics.addReloadsPerformed(cacheName);
    }
  }

  private void applyMethodCallOnCache(final String cacheName, final String methodName, final List<Object> methodArgs, final List<String> methodArgTypes) {
    Object obj = cacheObjects.get(cacheName);
    if (obj == null) {
      // Failed to get a cache by the given name - return indicate failure - this wont change.
      logger.atSevere().log("CACHE method call could not be made, as cache does not exist. %s", cacheName);
      throw new ReplicatedEventsUnknownTypeException(
          String.format("CACHE call could not be made, as cache does not exist. %s", cacheName));
    }


    try {
      // Calling signature requests is different depending on whether we have arguments or not.
      if (methodArgs.isEmpty()) {
        logger.atFine().log("Looking for method %s with no arguments...", methodName);
        Method method = obj.getClass().getMethod(methodName);
        method.invoke(obj);
        logger.atFine().log("Success for %s!", methodName);
      }
      else {
        List<Class<?>> remainingArgClassTypes = null;

        // we have been given methodArgs - lets check arg types and then invoke.
        // check method arg types, use them if present - otherwise its an older api type and we need to work them out.
        // any mismatch though it invalid and throw.
        if (methodArgTypes.isEmpty()) {
          logger.atWarning().atMostEvery(5, TimeUnit.MINUTES).log("Cache call must be from an old event file - as it doesn't contain event types - attempting fallback for compatibility. CacheName %s, MethodName %s, MethodArgs %s",
              cacheName, methodName, methodArgs);
          // Fallback by using the actual arg to get its source classType
          List<Class<?>> classTypes = new ArrayList<>();
          for (int n = 0; n < methodArgs.size(); n++) {
            classTypes.add(methodArgs.get(n).getClass());
          }

          // Filter and remove any nulls as we cannot have nulls when invoking method due to signature mismatch.
          remainingArgClassTypes = Arrays.stream((Class<?>[])classTypes.toArray()).filter(Objects::nonNull).collect(Collectors.toList());
        } else if ( methodArgs.size() != methodArgTypes.size() ) {
          throw new ReplicatedEventsUnknownTypeException(
              String.format("Unable to continue as method arg types count %s doesn't match the event args count: %s",
              methodArgs.size(), methodArgTypes.size()));
        } else {
          // default approach - use the types supplied and get actual class from each as they are 1:1 mapping.
          remainingArgClassTypes = methodArgTypes.stream().map(u -> {
            try {
              return Class.forName(u);
            } catch (ClassNotFoundException e) {
              throw new ReplicatedEventsUnknownTypeException("Unable to find events base argument type: " + u.toString(), e);
            }
          }).collect(Collectors.toList());
        }

        // We have remaining arguments so lets look for a method signature that matches.
        logger.atFine().log("Looking for method %s with the following signature %s",
                methodName, remainingArgClassTypes);

        // The remainingArgClassTypes array is a filtered array (no nulls) of class types. If a method is
        // found with a matching name and matching signature of class types then we will be able to invoke
        // against that method.
        Class<?>[] remainingTypesArray = remainingArgClassTypes.toArray(new Class<?>[0]);
        Method method = obj.getClass().getMethod(methodName, remainingTypesArray);

        // Add the first argument at index 0 so they call all be passed together in a single array for invocation.
        method.invoke(obj, methodArgs.toArray());
        logger.atFine().log("Success for %s!", methodName);
      }
    } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException |
             SecurityException  ex) {
      final String err = String.format("CACHE method call has been lost, could not call %s. %s Reason: %s", cacheName, methodName, ex.getMessage());
      logger.atSevere().withCause(ex).log(err);
      throw new ReplicatedEventsUnknownTypeException(err);
    }
  }

  public void watchCache(String cacheName, Cache cache) {
    caches.put(cacheName, new ReplicatedCacheWrapper(cache));
    logger.atInfo().log("CACHE New cache named %s inserted", cacheName);
  }

  public void watchObject(String cacheName, ProjectCache projectCache) {
    cacheObjects.put(cacheName, projectCache);
  }


}
