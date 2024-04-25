// Copyright (C) 2017 The Android Open Source Project
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

package com.google.gerrit.server.index.project;

import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.events.ProjectIndexedListener;
import com.google.gerrit.index.project.ProjectData;
import com.google.gerrit.index.project.ProjectIndex;
import com.google.gerrit.index.project.ProjectIndexCollection;
import com.google.gerrit.index.project.ProjectIndexer;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.logging.TraceContext;
import com.google.gerrit.server.logging.TraceContext.TraceTimer;
import com.google.gerrit.server.plugincontext.PluginSetContext;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.inject.Provider;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

public class ProjectIndexerImpl implements ProjectIndexer {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public interface Factory {
    ProjectIndexerImpl create(ProjectIndexCollection indexes);
    ProjectIndexerImpl create(@Nullable ProjectIndex index);
  }

  private final ProjectCache projectCache;
  private final PluginSetContext<ProjectIndexedListener> indexedListener;
  @Nullable private final ProjectIndexCollection indexes;
  @Nullable private final ProjectIndex index;
  private final Provider<ReplicatedEventsCoordinator> providedEventsCoordinator;
  private ReplicatedEventsCoordinator replicatedEventsCoordinator;

  @AssistedInject
  ProjectIndexerImpl(
      ProjectCache projectCache,
      PluginSetContext<ProjectIndexedListener> indexedListener,
      @Assisted ProjectIndexCollection indexes,
      Provider<ReplicatedEventsCoordinator> providedEventsCoordinator) {
    this.projectCache = projectCache;
    this.indexedListener = indexedListener;
    this.indexes = indexes;
    this.index = null;
    this.providedEventsCoordinator = providedEventsCoordinator;

  }

  @AssistedInject
  ProjectIndexerImpl(
      ProjectCache projectCache,
      PluginSetContext<ProjectIndexedListener> indexedListener,
      @Assisted @Nullable ProjectIndex index,
      Provider<ReplicatedEventsCoordinator> providedEventsCoordinator) {
    this.projectCache = projectCache;
    this.indexedListener = indexedListener;
    this.indexes = null;
    this.index = index;
    this.providedEventsCoordinator = providedEventsCoordinator;

  }

  public ReplicatedEventsCoordinator getProvidedEventsCoordinator(){
    if(replicatedEventsCoordinator == null){
      replicatedEventsCoordinator = providedEventsCoordinator.get();
    }
    return replicatedEventsCoordinator;
  }

  /**
   * Asks the replicated coordinator for an instance of the ReplicatedOutgoingProjectIndexFeed
   * and calls replicateReindex on it with the account Id.
   * @param projectName
   * @param deleteFromIndex
   * @throws IOException
   */
  public void replicateReindex(Project.NameKey projectName, boolean deleteFromIndex) throws IOException {
    if(getProvidedEventsCoordinator().isReplicationEnabled()) {
      getProvidedEventsCoordinator().getReplicatedOutgoingProjectIndexEventsFeed()
          .replicateReindex(projectName, deleteFromIndex);
    }
  }


  @Override
  public void index(Project.NameKey nameKey) throws IOException {
    indexImplementation(nameKey, getProvidedEventsCoordinator().isReplicationEnabled());
  }

  @Override
  public void indexNoRepl(Project.NameKey nameKey) throws IOException {
    indexImplementation(nameKey, false);
  }

  @Override
  public void deleteIndex(Project.NameKey nameKey) throws IOException {
    deleteIndexImpl(nameKey, getProvidedEventsCoordinator().isReplicationEnabled());
  }

  @Override
  public void deleteIndexNoRepl(Project.NameKey nameKey) throws IOException {
    deleteIndexImpl(nameKey, false);
  }

  public void indexImplementation(Project.NameKey nameKey, boolean replicate) throws IOException {
    ProjectState projectState = projectCache.get(nameKey);
    if (projectState != null) {
      logger.atFine().log("Replace project %s in index", nameKey.get());
      ProjectData projectData = projectState.toProjectData();
      for (ProjectIndex i : getWriteIndexes()) {
        try (TraceTimer traceTimer =
            TraceContext.newTimer(
                "Replacing project %s in index version %d",
                nameKey.get(), i.getSchema().getVersion())) {
          i.replace(projectData);
        }
      }
      fireProjectIndexedEvent(nameKey.get());
    } else {
      logger.atFine().log("Delete project %s from index", nameKey.get());
      for (ProjectIndex i : getWriteIndexes()) {
        try (TraceTimer traceTimer =
            TraceContext.newTimer(
                "Deleting project %s in index version %d",
                nameKey.get(), i.getSchema().getVersion())) {
          i.delete(nameKey);
        }
      }
    }
    if ( replicate ) {
      replicateReindex(nameKey, false);
    }
  }


  public void deleteIndexImpl(Project.NameKey nameKey, boolean replicate) throws IOException {
    logger.atFine().log("Delete project %s from index", nameKey.get());
    for (ProjectIndex i : getWriteIndexes()) {
      try (TraceTimer traceTimer =
               TraceContext.newTimer(
                   "Deleting project %s in index version %d",
                   nameKey.get(), i.getSchema().getVersion())) {
        i.delete(nameKey);
      }
    }

    if ( replicate ) {
      replicateReindex(nameKey, true);
    }
  }

  private void fireProjectIndexedEvent(String name) {
    indexedListener.runEach(l -> l.onProjectIndexed(name));
  }

  private Collection<ProjectIndex> getWriteIndexes() {
    if (indexes != null) {
      return indexes.getWriteIndexes();
    }

    return index != null ? Collections.singleton(index) : ImmutableSet.of();
  }
}
