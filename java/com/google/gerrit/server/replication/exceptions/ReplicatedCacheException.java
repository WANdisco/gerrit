package com.google.gerrit.server.replication.exceptions;

/**
 * Custom exception to allow us to log when things go wrong regarding
 * the registration / management of replicated caches
 */
public class ReplicatedCacheException extends RuntimeException {

  public ReplicatedCacheException(final String message) {
    super(message);
  }


  public ReplicatedCacheException(final Throwable throwable) {
    super(throwable);
  }

  public ReplicatedCacheException(final String message, final Throwable throwable) {
    super(message, throwable);
  }
}
