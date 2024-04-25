
/********************************************************************************
 * Copyright (c) 2014-2020 WANdisco
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Apache License, Version 2.0
 *
 ********************************************************************************/

package com.google.gerrit.server.replication.customevents;

import com.google.common.base.Verify;
import com.google.gerrit.server.replication.GerritEventFactory;
import com.google.gson.Gson;
import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.stream.Collectors;


/**
 * This is a wrapper for the cache message to be replicated,
 * so that it's easier to rebuild the "original" on the landing
 * node.
 */
public class CacheKeyWrapper extends ReplicatedEvent {
  public String cacheName;

  /**
   * Key can be one or many objects, as it is represented as a list<?> by CacheObjectCallWrapper
   */
  public Object key;

  /**
   * keyType which is a 1:1 mapping of the keyType(s) className, to the key object(s)
   **/
  public Object keyType;

  public transient boolean replicated = false;

  // internal flag (use) - to guarantee we only rebuild this class once.
  private transient boolean rebuiltOriginalData = false;
  private static final Gson gson = GerritEventFactory.getGson();


  public CacheKeyWrapper(String cacheName, Object key, String nodeIdentity) {
    super(nodeIdentity);
    this.cacheName = cacheName;
    Verify.verifyNotNull(key);
    this.key = key;

    if (this.key instanceof List) {
      this.keyType = ((List) key).stream().map(u -> u.getClass().getName()).collect(Collectors.toList());
      // ensure keyType matches list type, and size.
      if (!(this.keyType instanceof List) ||
          (((List<?>) key).size()) != ((List<?>) keyType).size()) {
        throw new RuntimeException(
            String.format("Invalid setup of the CacheKeyWrapper as key and keyType must be an List to be supported and 1:1 mapping of size(ensuring order). Key: %s  KeyType: %s",
                key, keyType));
      }
    } else {
      this.keyType = key.getClass().getName();
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CacheKeyWrapper)) {
      return false;
    }

    CacheKeyWrapper that = (CacheKeyWrapper) o;

    if (replicated != that.replicated) {
      return false;
    }
    if (!Objects.equals(cacheName, that.cacheName)) {
      return false;
    }
    if (!Objects.equals(key, that.key)) {
      return false;
    }
    return Objects.equals(keyType, that.keyType);
  }

  @Override
  public int hashCode() {
    int result = cacheName != null ? cacheName.hashCode() : 0;
    result = 31 * result + key.hashCode();
    result = 31 * result + keyType.hashCode();
    result = 31 * result + (replicated ? 1 : 0);
    return result;
  }

  @Override
  public String toString() {
    return new StringJoiner(", ", CacheKeyWrapper.class.getSimpleName() + "[", "]")
        .add("cacheName='" + cacheName + "'")
        .add("key=" + key)
        .add("keyType=" + keyType)
        .add("replicated=" + replicated)
        .add(super.toString())
        .toString();
  }

  /**
   * now the key field is being sent over for free, but as the type of the field, is lost in generics if its an object/List<Object>
   * etc we loose Project.NameKey to be a LinkedTreeMap of values.
   * As such lets take any key field now and reconstitute it, if its a complex type that has our new wdTypeArguments field present
   *
   * @throws ClassNotFoundException
   */
  public void rebuildOriginal() {
    // take a decision about whether we are rebuilding a single object, CacheKeyWrapper, or a List<Object> used from the
    // CacheObjectKeyWrapper usually.
    if (key == null) {
      rebuiltOriginalData = true;
      return;
    }

    if (key instanceof List) {
      rebuildOriginalAsList();
    } else {
      rebuildOriginalAsObject();
    }
  }

  private void rebuildOriginalAsList() {
    // if we have already rebuilt our original members, do nothing more.
    if (rebuiltOriginalData == true) {
      return;
    }

    // Now if the method args, contain any non primitives, we need to augment the type with its real concrete class,
    // Lets do that now.
    List<Object> methodArgs = (List<Object>) key;
    List<Object> reconstructedArgs = new ArrayList<>(methodArgs.size());
    List<String> keyTypes = (List<String>) keyType;

    for (int index = 0; index < methodArgs.size(); index++) {
      Object item = methodArgs.get(index);
      String itemType = keyTypes.get(index);
      reconstructedArgs.add(getObjectAsRealType(item, itemType));
    }

    // Now assign our key field with the newly constructed arguments list
    // ( so key changes after this point and rebuild is no longer needed to be called ).
    key = reconstructedArgs;
    // record this last, so we can call and get exception each time until we fix the issue :p
    rebuiltOriginalData = true;
  }

  private void rebuildOriginalAsObject() {
    if (rebuiltOriginalData == true) {
      return;
    }
    if (key != null) {
      key = getObjectAsRealType(key, keyType.toString());
    }
    // record this last, so we can call and get exception each time until we fix the issue :p
    rebuiltOriginalData = true;
  }

  /**
   * Ensure to call to rebuild the original type before returning it.
   *
   * @return
   */
  public Object getKeyAsOriginalType() {
    rebuildOriginal();
    return this.key;
  }

  private Object getObjectAsRealType(final Object item, final String itemType) {
    // We can use the itemType for helper information for all serialization of objects where it was reconstructed as a
    // type that does not match the original recorded type.
    if (item.getClass().getName().equals(itemType)) {
      return item;
    }

    // get the type.
    final String itemJson = gson.toJson(item);
    final Object reconstructed;
    try {
      reconstructed = gson.fromJson(itemJson, Class.forName(itemType));
      return reconstructed;
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

}
