// Copyright (C) 2013 The Android Open Source Project
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

package com.google.gerrit.server.change;

import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.MultimapBuilder;
import com.google.gerrit.extensions.api.changes.IncludedInInfo;
import com.google.gerrit.extensions.config.ExternalIncludedIn;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.plugincontext.PluginSetContext;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import static com.google.common.collect.ImmutableSortedSet.toImmutableSortedSet;
import static java.util.Comparator.naturalOrder;

@Singleton
public class IncludedIn {
  private final GitRepositoryManager repoManager;
  private final PermissionBackend permissionBackend;
  private final PluginSetContext<ExternalIncludedIn> externalIncludedIn;

  @Inject
  IncludedIn(
      GitRepositoryManager repoManager,
      PermissionBackend permissionBackend,
      PluginSetContext<ExternalIncludedIn> externalIncludedIn) {
    this.repoManager = repoManager;
    this.permissionBackend = permissionBackend;
    this.externalIncludedIn = externalIncludedIn;
  }

  public IncludedInInfo apply(Project.NameKey project, String revisionId)
      throws RestApiException, IOException, PermissionBackendException {
    try (Repository r = repoManager.openRepository(project);
        RevWalk rw = new RevWalk(r)) {
      rw.setRetainBody(false);
      RevCommit rev;
      try {
        rev = rw.parseCommit(ObjectId.fromString(revisionId));
      } catch (IncorrectObjectTypeException err) {
        throw new BadRequestException(err.getMessage());
      } catch (MissingObjectException err) {
        throw new ResourceConflictException(err.getMessage());
      }

      IncludedInResolver.Result d = IncludedInResolver.resolve(r, rw, rev);

      // Filter branches and tags according to their visbility by the user
      ImmutableSortedSet<String> filteredBranches =
          sortedShortNames(filterReadableRefs(project, d.branches()).keySet());
      ImmutableSortedSet<String> filteredTags =
          sortedShortNames(filterReadableRefs(project, d.tags()).keySet());

      ListMultimap<String, String> external = MultimapBuilder.hashKeys().arrayListValues().build();
      externalIncludedIn.runEach(
          ext -> {
            ListMultimap<String, String> extIncludedIns =
                ext.getIncludedIn(project.get(), rev.name(), filteredTags, filteredBranches);
            if (extIncludedIns != null) {
              external.putAll(extIncludedIns);
            }
          });

      return new IncludedInInfo(
          filteredBranches, filteredTags, (!external.isEmpty() ? external.asMap() : null));
    }
  }

  /**
   * Filter readable branches or tags according to the caller's refs visibility.
   *
   * @param project specific Gerrit project.
   * @param inputRefs a list of branches (in short name) as strings
   */
  private Map<String, Ref> filterReadableRefs(Project.NameKey project, List<Ref> inputRefs)
      throws IOException, PermissionBackendException {
    PermissionBackend.ForProject perm = permissionBackend.currentUser().project(project);
    try (Repository repo = repoManager.openRepository(project)) {
      return perm.filter(refListToMap(inputRefs), repo, PermissionBackend.RefFilterOptions.defaults());
    }
  }

  private ImmutableSortedSet<String> sortedShortNames(Collection<String> refs) {
    return refs.stream()
        .map(Repository::shortenRefName)
        .collect(toImmutableSortedSet(naturalOrder()));
  }

  /**
   * Note [Stu]: This is a utility method to bridge the change in refs format between 2.16 and 3.x
   * This method can be removed during 1.12 development - See GER-1926.
   *
   * @param inputRefs List<Ref> In 3.x Map<String,Ref> has been replaced with List<Ref>
   * @return Older Map expected by 2.16 JGit interfaces.
   */
  private Map<String, Ref> refListToMap(List<Ref> inputRefs) {
    return inputRefs.stream().collect(Collectors.toMap(a -> a.getName(), b -> b));
  }
}
