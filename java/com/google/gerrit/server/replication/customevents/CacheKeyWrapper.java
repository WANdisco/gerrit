
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
import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;


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
   * Value can be one or many objects
   */
  public Object value;

  /**
   * keyType which is a 1:1 mapping of the keyType(s) className, to the key object(s)
   */
  public Object keyType;

  /**
   * valueType which is a 1:1 mapping of the valueType(s) className, to the value object(s)
   */
  public Object valueType;


  public transient boolean replicated = false;

  // internal flag (use) - to guarantee we only rebuild this class once.
  private transient boolean rebuiltOriginalData = false;


  // Overloaded constructor that takes an Iterable of keys for a given cache type. Each key is added to a list of keys
  public CacheKeyWrapper(String cacheName, Iterable<?> keys, String nodeIdentity) {
    super(nodeIdentity);
    this.cacheName = cacheName;

    List<Object> keyList = new ArrayList<>();
    List<String> keyTypeList = new ArrayList<>();

    final long sizeOfKeys = StreamSupport.stream(keys.spliterator(), false).count();

    if(sizeOfKeys == 0){
      throw new RuntimeException(
                String.format("Invalid setup of the CacheKeyWrapper, trying to construct a CacheKeyWrapper " +
                        "with no keys, Keys size %s", sizeOfKeys));
    }

    // If sizeOfKeys is 1 or more, this will add each keyItem to the corresponding keyList / keyTypeList. They
    // need to be in a List<?> type due to serialization/deserialization in the IncomingCacheEventProcessor.
    // For Cache Object Method calls we expect an instanceof List and this constructor will be used for CacheObjectCallWrapper,
    // we therefore need to set key / keyType as keyList and keyTypeList.
    keys.forEach(keyItem -> {
      Verify.verifyNotNull(keyItem);
      keyList.add(keyItem);
      keyTypeList.add(keyItem.getClass().getName());
    });

    this.key = keyList;

    // ensure keyType matches list type, and size.
    if (keyList.size() != keyTypeList.size()) {
      throw new RuntimeException(String.format("Invalid setup of the CacheKeyWrapper as keyList and keyTypeList must be a List to be supported and 1:1 mapping of size(ensuring order). " +
                      "Key List: %s...  Key Type List: %s", keyList, keyTypeList));
    }

    this.keyType = keyTypeList;
  }


  public CacheKeyWrapper(String cacheName, Object key, String nodeIdentity) {
    super(nodeIdentity);
    this.cacheName = cacheName;
    Verify.verifyNotNull(key);
    this.key = key;

    if (key instanceof List<?> keyList) {
        if (keyList.isEmpty()) {
        throw new IllegalArgumentException(
                "Invalid setup of the CacheKeyWrapper: attempting to construct with an empty List.");
      }

      // Use a local generic method to infer and process the type safely
      this.keyType = extractKeyType(keyList);

      // Ensure keyType is actually a List<String> and has the same size as keyList
      if (keyType == null || keyList.size() != ((List<?>) keyType).size()) {
        throw new IllegalStateException(
                String.format("Invalid CacheKeyWrapper setup: key and keyType must be Lists with a 1:1 size mapping. Key: %s  KeyType: %s",
                        key, keyType));
      }
    } else {
      this.keyType = key.getClass().getName();
    }
  }

  private static List<String> extractKeyType(List<?> keyList) {
    return keyList.stream()
            .map(obj -> obj.getClass().getName())
            .collect(Collectors.toList());
  }

  // Overload constructor primarily used in conjunction with cache put operations.
  // at present, it is a 1:1 relationship with key and value, i.e keys are single objects and values are
  // single objects. We should add handling to this constructor in future to deal with values being a Collection.
  // For now, the only cache put operations we deal with are for the web_sessions cache and its put operations
  // are for single objects for key / value.
  public CacheKeyWrapper(String cacheName, Object key, Object value, String nodeIdentity) {
    super(nodeIdentity);
    this.cacheName = cacheName;
    Verify.verifyNotNull(key);
    this.key = key;
    Verify.verifyNotNull(value);
    this.value = value;
    this.keyType = key.getClass().getName();
    this.valueType = value.getClass().getName();
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
    if (!Objects.equals(value, that.value)) {
      return false;
    }
    if (!Objects.equals(valueType, that.valueType)) {
      return false;
    }
    return Objects.equals(keyType, that.keyType);
  }

  @Override
  public int hashCode() {
    int result = cacheName != null ? cacheName.hashCode() : 0;
    result = 31 * result + key.hashCode();
    result = 31 * result + value.hashCode();
    result = 31 * result + keyType.hashCode();
    result = 31 * result + valueType.hashCode();
    result = 31 * result + (replicated ? 1 : 0);
    return result;
  }

  @Override
  public String toString() {
    return new StringJoiner(", ", CacheKeyWrapper.class.getSimpleName() + "[", "]")
        .add("cacheName='" + cacheName + "'")
        .add("key=" + key)
        .add("value=" + value)
        .add("keyType=" + keyType)
        .add("valueType=" + valueType)
        .add("replicated=" + replicated)
        .add(super.toString())
        .toString();
  }

  /**
   * now the key field is being sent over for free, but as the type of the field, is lost in generics if its an object/{@code List<Object>}
   * etc we loose Project.NameKey to be a LinkedTreeMap of values.
   * As such lets take any key field now and reconstitute it, if its a complex type that has our new wdTypeArguments field present
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
    // If we have already rebuilt our original members, do nothing more.
    if (rebuiltOriginalData) {
      return;
    }

    // Ensure key is a List before proceeding
    if (!(key instanceof List<?> methodArgs)) {
      throw new IllegalStateException("Expected key to be a List but found: " + key.getClass().getName());
    }

    if (!(keyType instanceof List<?> keyTypes)) {
      throw new IllegalStateException("Expected keyType to be a List but found: " + keyType.getClass().getName());
    }

      if (methodArgs.size() != keyTypes.size()) {
      throw new IllegalStateException("Mismatch in key and keyType sizes: key size = "
              + methodArgs.size() + ", keyType size = " + keyTypes.size());
    }

    List<Object> reconstructedArgs = new ArrayList<>(methodArgs.size());

    for (int index = 0; index < methodArgs.size(); index++) {
      Object item = methodArgs.get(index);
      String itemType = (String) keyTypes.get(index);
      reconstructedArgs.add(getObjectAsRealType(item, itemType));
    }

    key = reconstructedArgs;
    // Set the rebuilt data
    rebuiltOriginalData = true;
  }



  private void rebuildOriginalAsObject() {
    if (rebuiltOriginalData) {
      return;
    }
    if (key != null) {
      key = getObjectAsRealType(key, keyType.toString());
    }

    if(value != null){
      value = getObjectAsRealType(value, valueType.toString());
    }
    // record this last, so we can call and get exception each time until we fix the issue :p
    rebuiltOriginalData = true;
  }

  /**
   * Ensure to call to rebuild the original type before returning it.
   */
  public Object getKeyAsOriginalType() {
    rebuildOriginal();
    return this.key;
  }

  public Object getValueAsOriginalType() {
    // It is unlikely that the original has not been rebuilt as we do the same for the key
    if(!rebuiltOriginalData){
      rebuildOriginal();
      return value;
    }
    return value;
  }

  private Object getObjectAsRealType(final Object item, final String itemType) {
    // We can use the itemType for helper information for all serialization of objects where it was reconstructed as a
    // type that does not match the original recorded type.
    if (item.getClass().getName().equals(itemType)) {
      return item;
    }

    // get the type.
    final String itemJson = GerritEventFactory.getGson().toJson(item);
    final Object reconstructed;
    try {
      reconstructed = GerritEventFactory.getGson().fromJson(itemJson, Class.forName(itemType));
      return reconstructed;
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

}
