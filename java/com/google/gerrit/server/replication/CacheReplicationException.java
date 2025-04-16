package com.google.gerrit.server.replication;

public class CacheReplicationException extends RuntimeException {

    public CacheReplicationException(String message) {
        super(message);
    }
}