package com.google.gerrit.server.replication;
@FunctionalInterface
public interface ProjectNameCallback<T> {
    String getProjectName(T value);
}