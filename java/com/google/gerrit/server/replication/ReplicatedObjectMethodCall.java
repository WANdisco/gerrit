package com.google.gerrit.server.replication;

import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;

import java.util.List;

/**
 * Class to replicate important method calls made in particular by the ProjectCacheImpl. We treat the ProjectCacheImpl
 * as a cache (the call to watchObject in the constructor) and as such, any method calls made for that are replicated to other
 * sites to be called and trigger the same behaviour on those remote sites.
 */
final public class ReplicatedObjectMethodCall {

    private final ReplicatedEventsCoordinator replicatedEventsCoordinator;

    private final Object classWithMethodsToReplicate;

    private final String projectToReplicateAgainst;

    public ReplicatedObjectMethodCall(ReplicatedEventsCoordinator replicatedEventsCoordinator,
                                      Object classWithMethodsToReplicate, String projectToReplicateAgainst) {
        this.replicatedEventsCoordinator = replicatedEventsCoordinator;
        this.classWithMethodsToReplicate = classWithMethodsToReplicate;
        this.projectToReplicateAgainst = projectToReplicateAgainst;

        // At present, we only support these replicated method calls in ProjectCacheImpl which is why we cast to ProjectCache
        // below.
        replicatedEventsCoordinator.getReplicatedCacheWatcher()
                .watchObject(classWithMethodsToReplicate.getClass().getSimpleName(), (ProjectCache) classWithMethodsToReplicate);
    }


    /**
     * Calls the replicateMethodCallFromCache in getReplicatedOutgoingCacheEventsFeed from the replicated coordinator.
     * @param methodName : Method call to replicate
     * @param otherMethodArgs : Any other arguments that the method takes.
     */
    public void queueMethodCallForReplication(final String methodName, final List<Object> otherMethodArgs){
        if(replicatedEventsCoordinator.isReplicationEnabled()) {
            replicatedEventsCoordinator.getReplicatedOutgoingCacheEventsFeed()
                    .replicateMethodCallFromCache(classWithMethodsToReplicate.getClass().getSimpleName(),
                            methodName, otherMethodArgs, projectToReplicateAgainst);
        }
    }
}
