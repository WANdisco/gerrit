package com.google.gerrit.server.events;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 *  This interface was designed to be used as an annotation.
 *  The annotation is to be placed on server events as a declaration that we know this event
 *  as something we have considered as a replicated type event, and we never wish to replicate this event.
 *  This is regardless of other system configuration, like skip event lists and it cannot be overridden.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface SkipReplication {
}
