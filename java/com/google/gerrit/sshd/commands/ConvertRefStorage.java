// Copyright (C) 2021 The Android Open Source Project
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

package com.google.gerrit.sshd.commands;

import static com.google.common.base.Preconditions.checkState;
import static com.google.gerrit.sshd.CommandMetaData.Mode.MASTER_OR_SLAVE;

import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.annotations.RequiresCapability;
import com.google.gerrit.server.git.DelegateRepository;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;
import com.google.inject.Inject;
import java.io.IOException;
import java.util.Optional;

import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.ReplicationConfiguration;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.ExplicitBooleanOptionHandler;

@RequiresCapability(GlobalCapability.ADMINISTRATE_SERVER)
@CommandMetaData(
    name = "convert-ref-storage",
    description = "Convert ref storage to reftable (experimental)",
    runsAt = MASTER_OR_SLAVE)
public class ConvertRefStorage extends SshCommand {
  @Inject private GitRepositoryManager repoManager;

  private enum StorageFormatOption {
    reftable,
    refdir,
  }

  @Option(name = "--format", usage = "storage format to convert to (reftable or refdir)")
  private StorageFormatOption storageFormat = StorageFormatOption.reftable;

  @Option(
      name = "--backup",
      aliases = {"-b"},
      usage = "create backup of old ref storage format",
      handler = ExplicitBooleanOptionHandler.class)
  private boolean backup = true;

  @Option(
      name = "--reflogs",
      aliases = {"-r"},
      usage = "write reflogs to reftable",
      handler = ExplicitBooleanOptionHandler.class)
  private boolean writeLogs = true;

  @Option(
      name = "--project",
      aliases = {"-p"},
      metaVar = "PROJECT",
      required = true,
      usage = "project for which the storage format should be changed")
  private ProjectState projectState;

  @Override
  public void run() throws Exception {
    // ConvertRefStorage, we need to support the matrix of all 3 replicated states
    // * (1) replicated flag NOT SET,
    // * (2) replicated=true,
    // * (3) replicated=false
    // in a git repository, along with the conversion storage flags, which are refdir and reftable.

    //    (1). replicated flag NOT SET case: Allow Both
    //         This is to allow default vanilla behaviour and unit tests to operate correctly.
    //         This would never actually be used in production.

    //    (2). replicated=true case: Deny for both refdir and reftable conversion.
    //         This is because we cannot currently replicate when reftable operations are used. When its refdir it implies
    //         that the reftable format already exists for the repository, and as such we cannot do the conversion back
    //         to refdir in a replicated manner.

    //    (3). replicated=false case:  Allow conversion to refdir, deny conversion to reftable.
    //         This is to allow customers to fix unsupported reftable repositories in a safe un-replicated manner,
    //         while preventing any further unsupported conversion to reftable.
      enableGracefulStop();
      Project.NameKey projectName = projectState.getNameKey();
      try (Repository repo = repoManager.openRepository(projectName)) {

        Optional<String> replicatedKeyOpt = Optional.ofNullable(
                ReplicationConfiguration.getRepositoryCoreConfigKey("replicated", repo));

        if (replicatedKeyOpt.isEmpty()) {
          // Allow both, as the replicated key is NOT SET in .git/config
          performConversion(repo);
        } else {
          // If the key is present, then parse the boolean value of it.
          boolean isReplicatedRepo = Boolean.parseBoolean(replicatedKeyOpt.get());

          // If it's a replicated repo, 'replicated = true' in .git/config OR the storageFormat is 'reftable', then
          // die
          if(isReplicatedRepo || storageFormat.name().equals(ConfigConstants.CONFIG_REF_STORAGE_REFTABLE)){
            throw die(String.format("Reftable replication is NOT supported. " +
                            "Value of 'replicated' key in .git/config is: replicated = %s, storage format specified is %s",
                    isReplicatedRepo, storageFormat.name()));
          }

          // Perform conversion ONLY IF replicated = false and 'refdir' format
          performConversion(repo);
        }

      } catch (RepositoryNotFoundException e) {
        throw die("'" + projectName + "': not a git archive", e);
      } catch (IOException e) {
        throw die("Error converting: '" + projectName + "': " + e.getMessage(), e);
      }
    }

  private void performConversion(Repository repo) throws IOException {
    if (repo instanceof DelegateRepository) {
      ((DelegateRepository) repo).convertRefStorage(storageFormat.name(), writeLogs, backup);
    } else {
      checkState(
              repo instanceof FileRepository, "Repository is not an instance of FileRepository!");
      ((FileRepository) repo).convertRefStorage(storageFormat.name(), writeLogs, backup);
    }
  }
}
