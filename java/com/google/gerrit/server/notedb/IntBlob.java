// Copyright (C) 2018 The Android Open Source Project
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

package com.google.gerrit.server.notedb;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.lib.Constants.OBJ_BLOB;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import com.google.common.primitives.Ints;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Project;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.git.RefUpdateUtil;
import com.google.gerrit.server.extensions.events.GitReferenceUpdated;
import java.io.IOException;
import java.util.Optional;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * An object blob in a Git repository that stores a single integer value.
 * Cirata Note: We provide another interface that allows us to add a tag that will be stored as
 * a tuple in the blob with the original number. On parsing we will discard the tag part and return the
 * original number.
 * <p>
 * e.g. In our replicated use case when sequences numbers are stored from different originating nodes, we encode
 * the NodeId along with the sequence number so that the object hashes are disambiguated.
 */
@AutoValue
public abstract class IntBlob {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final String SEQUENCE_TUPLE_DELIMITER = ":";

  public static Optional<IntBlob> parse(Repository repo, String refName) throws IOException {
    try (ObjectReader or = repo.newObjectReader()) {
      return parse(repo, refName, or);
    }
  }

  public static Optional<IntBlob> parse(Repository repo, String refName, RevWalk rw)
      throws IOException {
    return parse(repo, refName, rw.getObjectReader());
  }

  private static Optional<IntBlob> parse(Repository repo, String refName, ObjectReader or)
      throws IOException {
    Ref ref = repo.exactRef(refName);
    if (ref == null) {
      return Optional.empty();
    }
    ObjectId id = ref.getObjectId();
    ObjectLoader ol = or.open(id, OBJ_BLOB);
    if (ol.getType() != OBJ_BLOB) {
      // In theory this should be thrown by open but not all implementations may do it properly
      // (certainly InMemoryRepository doesn't).
      throw new IncorrectObjectTypeException(id, OBJ_BLOB);
    }
    String str = CharMatcher.whitespace().trimFrom(new String(ol.getCachedBytes(), UTF_8));
    Integer value = decodeTaggedInteger(str);
    if (value == null) {
      throw new StorageException("invalid value in " + refName + " blob at " + id.name());
    }
    return Optional.of(IntBlob.create(id, value));
  }

  public static RefUpdate tryStore(
      Repository repo,
      RevWalk rw,
      Project.NameKey projectName,
      String refName,
      @Nullable ObjectId oldId,
      int val,
      GitReferenceUpdated gitRefUpdated)
      throws IOException {
    logger.atFine().log(
        "storing value %d on %s in %s (oldId: %s)",
        val, refName, projectName, oldId == null ? "null" : oldId.name());
    return tryStoreImplementation(repo, rw, projectName, refName, oldId, Integer.toString(val), gitRefUpdated);
  }

  /**
   * Overload that allows a tag to be supplied along with the value to store.
   * The contents of the blob will then become a tuple of 'tag:value'
   */
  public static RefUpdate tryStore(
      Repository repo,
      RevWalk rw,
      Project.NameKey projectName,
      String refName,
      @Nullable ObjectId oldId,
      String tag,
      int val,
      GitReferenceUpdated gitRefUpdated)
      throws IOException {
    final String taggedVal = tagInteger(tag, val);
    logger.atFine().log(
        "storing tagged value %s on %s in %s (oldId: %s)",
        taggedVal, refName, projectName, oldId == null ? "null" : oldId.name());
    return tryStoreImplementation(repo, rw, projectName, refName, oldId, taggedVal, gitRefUpdated);
  }

  private static RefUpdate tryStoreImplementation(
      Repository repo,
      RevWalk rw,
      Project.NameKey projectName,
      String refName,
      @Nullable ObjectId oldId,
      String val,
      GitReferenceUpdated gitRefUpdated)
      throws IOException {
    ObjectId newId;
    try (ObjectInserter ins = repo.newObjectInserter()) {
      newId = ins.insert(OBJ_BLOB, val.getBytes(UTF_8));
      ins.flush();
      logger.atFine().log(
          "successfully stored %s on %s as %s in %s", val, refName, newId.name(), projectName);
    }
    RefUpdate ru = repo.updateRef(refName);
    if (oldId != null) {
      ru.setExpectedOldObjectId(oldId);
    }
    ru.disableRefLog();
    ru.setNewObjectId(newId);
    ru.setForceUpdate(true); // Required for non-commitish updates.
    RefUpdate.Result result = ru.update(rw);
    if (refUpdated(result)) {
      gitRefUpdated.fire(projectName, ru, null);
    }
    return ru;
  }

  public static void store(
      Repository repo,
      RevWalk rw,
      Project.NameKey projectName,
      String refName,
      @Nullable ObjectId oldId,
      int val,
      GitReferenceUpdated gitRefUpdated)
      throws IOException {
    RefUpdateUtil.checkResult(tryStore(repo, rw, projectName, refName, oldId, val, gitRefUpdated));
  }

  private static boolean refUpdated(RefUpdate.Result result) {
    return result == RefUpdate.Result.NEW || result == RefUpdate.Result.FORCED;
  }

  /* Convert a given change sequence number into the content to store in the blob. For vanilla or non-replicated
   * Gerrit this will just be the sequence number, but for replicated flow make a tuple incorporating node-id so that
   * the blob sent to GitMS will hash differently. This will cause GitMS to differentiate sequence numbers coming from
   * different nodes.
   *
   * NOTE: Do not to attempt to switch between replication on or off once installed without manually fixing
   *  the sequence information.
   */
  private static String tagInteger(String tag, int val) {
    // The format of the tuple in the replicated scenario will be NodeId:Sequence#.
    return Strings.isNullOrEmpty(tag)
        ? String.valueOf(val)
        : String.format("%s%s%d", tag, SEQUENCE_TUPLE_DELIMITER, val);
  }

  /**
   * Convert a sequenceString back into the expected sequence number. For vanilla or non-replicated Gerrit we only need
   * parse the string as an int. For replicated flow we need to unpack the sequence number out of the encoded tuple.
   * Note: This is public as it's also used to revert WD changes to sequences: {@link com.google.gerrit.pgm.wandisco.RevertRepoSequence}
   */
  public static Integer decodeTaggedInteger(String sequenceString) {
    final int separatorIndex = sequenceString.lastIndexOf(SEQUENCE_TUPLE_DELIMITER);

    return separatorIndex < 0 ?
        Ints.tryParse(sequenceString) :
        Ints.tryParse(sequenceString.substring(separatorIndex + SEQUENCE_TUPLE_DELIMITER.length()));
  }

  @VisibleForTesting
  static IntBlob create(AnyObjectId id, int value) {
    return new AutoValue_IntBlob(id.copy(), value);
  }

  public abstract ObjectId id();

  public abstract int value();
}
