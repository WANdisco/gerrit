
/********************************************************************************
 * Copyright (c) 2014-2018 WANdisco
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Apache License, Version 2.0
 *
 ********************************************************************************/
 
package com.google.gerrit.gerritconsoleapi.bindings;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.gerrit.entities.AccessSection;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.BranchOrderSection;
import com.google.gerrit.entities.LabelType;
import com.google.gerrit.entities.LabelTypes;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.SubscribeSection;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.project.ProjectConfig;
import com.google.gerrit.server.project.SectionMatcher;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public class ProjectStateMinDepends {
  private static final Logger log =
      LoggerFactory.getLogger(ProjectStateMinDepends.class);

  public interface Factory {
    ProjectStateMinDepends create(Project.NameKey project);
  }

  private boolean isAllProjects;
  private final AllProjectsName allProjectsName;
  private final SitePaths sitePaths;
  private final Project.NameKey project;
  private final GitRepositoryManager gitMgr;
  private ProjectConfig config;
  /**
   * Local access sections, wrapped in SectionMatchers for faster evaluation.
   */
  private volatile List<SectionMatcher> localAccessSections;

  @Inject
  public ProjectStateMinDepends(
      final SitePaths sitePaths,
      final AllProjectsName allProjectsName,
      @Assisted final Project.NameKey project,
      final GitRepositoryManager gitMgr) {
    this.sitePaths = sitePaths;
    this.allProjectsName = allProjectsName;
    this.project = project;
    this.gitMgr = gitMgr;
  }

  public void setConfig(ProjectConfig config){
    this.config = config;
    if ( config == null ) {
      throw new NullPointerException("No configuration given to ProjectStateMinDepends");
    }
    this.isAllProjects = config.getProject().getNameKey().equals(allProjectsName);
  }

  public Project getProject() {
    return config.getProject();
  }

  public ProjectConfig getConfig() {
    return config;
  }

  public ProjectLevelConfigNoCache getConfig(String fileName) {


    ProjectLevelConfigNoCache cfg = new ProjectLevelConfigNoCache(fileName, this);
    try (Repository git = gitMgr.openRepository(getProject().getNameKey())) {
      cfg.load(getProject().getNameKey(), git);
    } catch (IOException | ConfigInvalidException e) {
      log.warn("Failed to load " + fileName + " for " + getProject().getName(), e);
    }

    return cfg;
  }

  public long getMaxObjectSizeLimit() {
    return config.getMaxObjectSizeLimit();
  }

  /**
   * Get the sections that pertain only to this project.
   */
  List<SectionMatcher> getLocalAccessSections() {
    List<SectionMatcher> sm = localAccessSections;
    if (sm == null) {
      Collection<AccessSection> fromConfig = config.getAccessSections();
      sm = new ArrayList<>(fromConfig.size());
      for (AccessSection section : fromConfig) {
        // if all-projects do additional permissions checks
        if (isAllProjects) {
          List<Permission> copy =
              Lists.newArrayListWithCapacity(section.getPermissions().size());
          for (Permission p : section.getPermissions()) {
            if (Permission.canBeOnAllProjects(section.getName(), p.getName())) {
              copy.add(p);
            }
          }

          /* FIXME: Breaking change? no longer allowed to set permissions on AccessSection! */
          /* Rewrite using AccessSection.Builder and Permission.Builder to create the new section. */
          section = AccessSection.create(section.getName());
          /* section.setPermissions(copy); */
        }

        SectionMatcher matcher = SectionMatcher.wrap(getProject().getNameKey(),
            section);
        if (matcher != null) {
          sm.add(matcher);
        }
      }
      localAccessSections = sm;
    }
    return sm;
  }

  /**
   * Obtain all local and inherited sections. This collection is looked up
   * dynamically and is not cached. Callers should try to cache this result
   * per-request as much as possible.
   */
  List<SectionMatcher> getAllSections() {
    if (isAllProjects) {
      return getLocalAccessSections();
    }

    List<SectionMatcher> all = new ArrayList<>();
    for (ProjectStateMinDepends s : tree()) {
      all.addAll(s.getLocalAccessSections());
    }
    return all;
  }


  /**
   * @return an iterable that walks through the parents of this project. Starts
   * from the immediate parent of this project and progresses up the
   * hierarchy to All-Projects.
   */
  public Iterable<ProjectStateMinDepends> parents() {
    return null;
  }

  public boolean isAllProjects() {
    return isAllProjects;
  }

  public LabelTypes getLabelTypes() {
    Map<String, LabelType> types = new LinkedHashMap<>();
    for (ProjectStateMinDepends s : treeInOrder()) {
      for (LabelType type : s.getConfig().getLabelSections().values()) {
        String lower = type.getName().toLowerCase();
        LabelType old = types.get(lower);
        if (old == null || old.isCanOverride()) {
          types.put(lower, type);
        }
      }
    }
    List<LabelType> all = Lists.newArrayListWithCapacity(types.size());
    for (LabelType type : types.values()) {
      if (!type.getValues().isEmpty()) {
        all.add(type);
      }
    }
    return new LabelTypes(Collections.unmodifiableList(all));
  }

  public BranchOrderSection getBranchOrderSection() {
    for (ProjectStateMinDepends s : tree()) {
      BranchOrderSection section = s.getConfig().getBranchOrderSection();
      if (section != null) {
        return section;
      }
    }
    return null;
  }


  /**
   * @return an iterable that walks through this project and then the parents of
   * this project. Starts from this project and progresses up the
   * hierarchy to All-Projects.
   */
  public Iterable<ProjectStateMinDepends> tree() {
    // we are only using All-Projects for now, so it has no parents, put back in if we ever need to support inheritance/parent walking.
    return new ArrayList<>();
  }

  /**
   * @return an iterable that walks in-order from All-Projects through the
   *     project hierarchy to this project.
   */
  public Iterable<ProjectStateMinDepends> treeInOrder() {
    List<ProjectStateMinDepends> projects = Lists.newArrayList(tree());
    Collections.reverse(projects);
    return projects;
  }


  public Collection<SubscribeSection> getSubscribeSections(
      BranchNameKey branch) {
    Collection<SubscribeSection> ret = new ArrayList<>();
    for (ProjectStateMinDepends s : tree()) {
      ret.addAll(s.getConfig().getSubscribeSections().values());
    }
    return ret;
  }

}
