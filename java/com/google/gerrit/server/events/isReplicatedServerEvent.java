package com.google.gerrit.server.events;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This interface was designed to be used as an annotation.
 * The annotation is to be placed on server events as a declaration that we know this event as a replicated type event.
 * Note it does not declare the fact that we will be definately replicating it as this can be decided using other
 * annotations and configuration.
 *
 * Note if we wish to skip an event, but still declare we know it ( and dont question its use again ) it should additionally
 * have the {@link SkipReplication} SkipReplication interface declaration.  This means we will never replicate this event regardless
 * of other system configuration. ( e.g. skipped event list  ).
 *
 * We also support additional configuration on a per-node basis, regarding  whether we can send or receive events
 * from / to other nodes.  For this see {@link com.google.gerrit.server.replication.configuration.ReplicatedConfiguration}
 * members replicatedEventsReceive, replicatedEventsReplicateOriginalEvents, receiveReplicatedEventsEnabled, replicatedEventsEnabled
 *
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface isReplicatedServerEvent {
}
