package com.google.gerrit.server.replication.feeds;

import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.replication.customevents.CacheKeyWrapper;
import com.google.gerrit.server.replication.customevents.CacheObjectCallWrapper;
import com.google.gerrit.server.replication.GerritEventFactory;
import com.google.gerrit.server.replication.ReplicatorMetrics;
import com.google.gerrit.server.replication.SingletonEnforcement;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.inject.Singleton;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;


/**
 * This class is to manage the replication of the cache events happening in
 * the original gerrit source code.
 * When a cache is used, it is registered with this class and then when
 * a cache invalidation is performed, this invalidation is replicated on the other nodes.
 * On the other nodes a cache get can be issued too. This can be useful for
 * the web application loading data from the caches.
 * <p>
 * <p>
 * Gerrit cache is:
 * <code>
 * <p>
 * [gerrit@dger04 gitms-gerrit-longtests]$ ssh -p 29418 admin@dger03.qava.wandisco.com gerrit show-caches
 * Gerrit Code Review        2.10.2-31-g361cb34        now    10:04:59   EDT
 * uptime   13 days 22 hrs
 * <p>
 * Name                          |Entries              |  AvgGet |Hit Ratio|
 * |   Mem   Disk   Space|         |Mem  Disk|
 * --------------------------------+---------------------+---------+---------+
 * accounts                      | 13974               |   2.7ms | 99%     |
 * accounts_byemail              | 12115               |   2.9ms | 99%     |
 * accounts_byname               | 36864               |   1.4ms | 97%     |
 * adv_bases                     |                     |         |         |
 * changes                       |                     |  98.8ms |  0%     |
 * groups                        |  4071               |   1.4ms | 99%     |
 * groups_byinclude              |  1193               |   2.5ms | 93%     |
 * groups_byname                 |    92               |   5.4ms | 99%     |
 * groups_byuuid                 | 15236               |   1.1ms | 99%     |
 * groups_external               |     1               |  11.1ms | 99%     |
 * groups_members                |  4338               |   1.9ms | 99%     |
 * ldap_group_existence          |    23               |  73.7ms | 90%     |
 * ldap_groups                   |  4349               |  75.0ms | 94%     |
 * ldap_groups_byinclude         | 44136               |         | 98%     |
 * ldap_usernames                |   613               |   1.1ms | 92%     |
 * permission_sort               | 98798               |         | 99%     |
 * plugin_resources              |                     |         |  0%     |
 * project_list                  |     1               |    5.8s | 99%     |
 * projects                      |  7849               |   2.3ms | 99%     |
 * sshkeys                       |  7633               |   9.9ms | 99%     |
 * D change_kind                   | 16986 293432 130.14m| 103.1ms | 96%  98%|
 * D conflicts                     | 15885  51031  45.70m|         | 89%  90%|
 * D diff                          |     7 322355   1.56g|   8.7ms | 20%  99%|
 * D diff_intraline                |   576 304594 202.28m|   8.4ms | 23%  99%|
 * D git_tags                      |    47     58   2.10m|         | 38% 100%|
 * D web_sessions                  |       842300 341.13m|         |         |
 * <p>
 * SSH:    281  users, oldest session started   13 days 22 hrs ago
 * Tasks: 2889  total =   33 running +   2828 ready +   28 sleeping
 * Mem: 49.59g total = 15.06g used + 18.82g free + 15.70g buffers
 * 49.59g max
 * 8192 open files
 * <p>
 * Threads: 40 CPUs available, 487 threads
 * </code>
 */
@Singleton //Not guice bound but makes it clear that it's a singleton
public class ReplicatedOutgoingCacheEventsFeed extends ReplicatedOutgoingEventsFeedCommon {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  public static final String invalidateAllWildCard = "*";

  public enum CacheOperation {
    INVALIDATE, INVALIDATE_ALL, PUT
  }


  /**
   * We only create this class from the replicatedEventsCoordinator.
   * This is a singleton and, it's enforced by our SingletonEnforcement below that if anyone else tries to create
   * this class it will fail.
   * Sorry by adding a getInstance, make this class look much more public than it is,
   * and people expect they can just call getInstance - when in fact they should always request it via the
   * ReplicatedEventsCoordinator.getReplicatedXWorker() methods.
   */
  public ReplicatedOutgoingCacheEventsFeed(ReplicatedEventsCoordinator eventsCoordinator) {
    super(eventsCoordinator);
    SingletonEnforcement.registerClass(ReplicatedOutgoingCacheEventsFeed.class);
  }

  @Override
  public void stop() {
    SingletonEnforcement.unregisterClass(ReplicatedOutgoingCacheEventsFeed.class);
  }


  /**
   * projectUsedForReplication will be set to the All-Users project for certain cache event types, ALL_PROJECTS for other
   * types, but JIC there is some event which is sent via a projectDSM but also accepts sending NULL for project, we have
   * a fallback to send via ALL_PROJECTS.
   * @param projectUsedForReplication: AllUsers, AllProjects or another project. Defaults to AllProjects if null.
   * @param cacheName: Name of the cache
   * @return A String of the project name used for replication.
   */
  private String determineProjectUsedForReplication(final String projectUsedForReplication, final String cacheName){
      // Set to the supplied project name, if none is supplied this is incorrect!
      // We now force project decision back to the caller - as there are too many embedded types to try to recognise,
      // the code was getting messy.

      // N.B. This should never be triggered, so I have set to log this case for investigation rather than just ignore the event.
      if (Strings.isNullOrEmpty(projectUsedForReplication)) {
        // no project was supplied, this should NEVER happen now
        // Log as warning every hour, the general debug logging after this block will output each event, so we don't need to debug log here again.
        logger.atWarning().atMostEvery(1, TimeUnit.HOURS)
                .log("WARNING: No project name has been supplied, this should no longer happen " +
                                "- defaulting to ALL_PROJECTS for now.  CacheName: %s", cacheName);
        return replicatedEventsCoordinator.getReplicatedConfiguration().getAllProjectsName();
      }
      return projectUsedForReplication;
  }


  /**
   * Used in order to replicate an invalidateAll for all cache keys.
   * replicateCacheInvalidateAll is used to invalidate all cache entries for a given cache from remote servers.
   * The servers that are communicated with are specified by the project used.
   * @param cacheName: Cache to call invalidateAll operation on.
   * @param keys: An Iterable of keys for a given stored cache type.
   * @param projectUsedForReplication: Project to replicate the cache event for.
   */
  public void replicateCacheInvalidateAll(final String cacheName, Iterable<?> keys, final String projectUsedForReplication){
    CacheKeyWrapper cacheKeyWrapper = new CacheKeyWrapper(cacheName, keys, replicatedEventsCoordinator.getThisNodeIdentity());
    EventWrapper eventWrapper;
    final String projectName = determineProjectUsedForReplication(projectUsedForReplication, cacheName);

    //Not logging all keys here as there could be many and would be a performance hit.
    logger.atFine().log("CACHE replicated cache %s: Project: [ %s ], CacheName: [ %s ]",
            CacheOperation.INVALIDATE_ALL, projectName, cacheName);
    try{
      eventWrapper = GerritEventFactory.createReplicatedCacheEvent(projectName, cacheKeyWrapper);
      replicatedEventsCoordinator.queueEventForReplication(eventWrapper);
      ReplicatorMetrics.addToCacheInvalidatesSent(cacheName);
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Unable to create EventWrapper instance from replicated cache event : " +
                              "%s, using cacheName: %s", e.getMessage(), cacheName);
    }
  }

  /**
   * replicateCacheInvalidate is used to invalidate specific cache entries from remote servers.
   * The servers that are communicated with are specified by the project used.
   * Caches which should be on specific projects, should call the overriden method giving the relative project name.
   * @param projectUsedForReplication This is the name of the project being used for replication,
   *                                  i.e. ALL_USERS, ALL_PROJECTS or any replicated project name.
   */
  public void replicateCacheInvalidate(final String cacheName, final Object key, final String projectUsedForReplication ) {

    if(replicatedEventsCoordinator.getReplicatedConfiguration().isCacheToBeIgnored(cacheName)){
      // Cache is ignored, nothing more to do.
      return;
    }

    CacheKeyWrapper cacheKeyWrapper = new CacheKeyWrapper(cacheName, key, replicatedEventsCoordinator.getThisNodeIdentity());
    EventWrapper eventWrapper;
    final String projectName = determineProjectUsedForReplication(projectUsedForReplication, cacheName);
    
    logger.atFine().log("CACHE replicated cache %s: Project: [ %s ], CacheName: [ %s ], Key: [ %s ]",
            key.toString().equals(invalidateAllWildCard) ?
                    CacheOperation.INVALIDATE_ALL : CacheOperation.INVALIDATE, projectName, cacheName, key);

    try{
      eventWrapper = GerritEventFactory.createReplicatedCacheEvent(projectName, cacheKeyWrapper);
      replicatedEventsCoordinator.queueEventForReplication(eventWrapper);
      ReplicatorMetrics.addToCacheInvalidatesSent(cacheName);
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Unable to create EventWrapper instance from replicated cache event " +
                      ": %s, using cacheName: %s and key: %s", e.getMessage(), cacheName, key);
    }
  }


  /**
   * replicateCachePut is used to put specific entries in the cache from remote servers.
   * The servers that are communicated with are specified by the project used.
   * Caches which should be on specific projects, should call the overriden method giving the relative project name.
   * @param projectUsedForReplication This is the name of the project being used for replication,
   *                                  i.e. ALL_USERS, ALL_PROJECTS or any replicated project name.
   */
  public void replicateCachePut(final String cacheName, final Object key, final Object value, final String projectUsedForReplication ) {

    if(replicatedEventsCoordinator.getReplicatedConfiguration().isCacheToBeIgnored(cacheName)){
      // Cache is ignored, nothing more to do.
      return;
    }

    // if the CACHE is not in the allowed cachePut list then we do not want replication to occur.
    if(!replicatedEventsCoordinator.getReplicatedConfiguration().isCacheAllowedToPerformPuts(cacheName)){
      // Cache is not allowed to perform puts, exit early.
      return;
    }

    CacheKeyWrapper cacheKeyWrapper = new CacheKeyWrapper(cacheName, key, value, replicatedEventsCoordinator.getThisNodeIdentity());
    EventWrapper eventWrapper;
    final String projectName = determineProjectUsedForReplication(projectUsedForReplication, cacheName);

    logger.atFine().log("CACHE replicated cache %s: Project: [ %s ], CacheName: [ %s ], Key: [ %s ], Value: [ %s ]",
            CacheOperation.PUT, projectName, cacheName, key, value);

    try{
      eventWrapper = GerritEventFactory.createReplicatedCacheEvent(projectName, cacheKeyWrapper);
      replicatedEventsCoordinator.queueEventForReplication(eventWrapper);
      ReplicatorMetrics.addToCachePutsSent(cacheName);
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Unable to create EventWrapper instance from replicated cache event " +
              ": %s, using cacheName: %s and key: %s", e.getMessage(), cacheName, key);
    }
  }


  /**
   * Replication of a specific call to be replicated on a cache to a specific remote set of sites specified by the
   * project name.
   *
   * @param cacheName Name of cache to apply the method call to.
   * @param methodName We reflectively look for this method in the corresponding cache impl on the remote machine.
   * @param otherMethodArgs List of arguments that 'methodName' expects
   * @param projectToUseForReplication project to replicate the method call against.
   */
    public void replicateMethodCallFromCache(final String cacheName,
                                             final String methodName,
                                             final List<?> otherMethodArgs,
                                             final String projectToUseForReplication) {

      if(replicatedEventsCoordinator.getReplicatedConfiguration().isCacheToBeIgnored(cacheName)){
        // Cache is ignored, nothing more to do.
        return;
      }

    CacheObjectCallWrapper cacheMethodCall = new CacheObjectCallWrapper(cacheName, methodName,
        otherMethodArgs, replicatedEventsCoordinator.getThisNodeIdentity());

    final String projectName = determineProjectUsedForReplication(projectToUseForReplication, cacheName);

    logger.atInfo().log("CACHE About to call replicated cache method: %s, %s, [ %s ] against project DSM: %s",
            cacheName, methodName, otherMethodArgs, projectName);

    // Please note the supplied projectname is used by some event to actually cause replication to that DSM, but for
    // cache events this always goes to the ALL_PROJECTS dsm, as it covers project creation / deletion etc on project list.
    try {
      replicatedEventsCoordinator.queueEventForReplication(
          GerritEventFactory.createReplicatedCacheEvent(projectName, cacheMethodCall));
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Unable to create EventWrapper instance from replicated cache event");
    }
  }

}
