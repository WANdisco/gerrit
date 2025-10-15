
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
 
package com.google.gerrit.server.replication.customevents;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This is a wrapper for the cache method call to be replicated,
 * so that it's easier to rebuild the "original" on the landing
 * node.
 *
 */
public class CacheObjectCallWrapper extends CacheKeyWrapper {
  public String methodName;

  public CacheObjectCallWrapper(String cacheName, String method, List<?> methodArgs, String thisNodeIdentity) {
    super(cacheName, methodArgs, thisNodeIdentity);
    this.methodName = method;
  }

  /**
   * Helper to turn the key(Object) field in the base class, to be the {@code List<Object>key}, so it can be used correctly
   * by the cache object call code, which may expect 0, 1, or many args.
   * Note this must be a 1:1 mapping as an arrayList with key field.
   * @return {@code List<Object>} representing the actual methodArgs
   */
  public List<Object> getMethodArgs() {
    Object originalKey = getKeyAsOriginalType();

    if (originalKey instanceof List<?>) {
      return new ArrayList<>((List<?>) originalKey); // Ensures a mutable List<Object>
    }
    // If originalKey is not a List, return a single-item list with it,
    // this ensures a mutable list even for a single item
    return new ArrayList<>(Collections.singletonList(originalKey));
  }

  /**
   * Helper to turn the keyType(Object) field in the base class, to be the {@code List<String>keyType}, so it can be used correctly
   * by the cache object call code, which may expect 0, 1, or many args.
   * Note this must be a 1:1 mapping as an arrayList with key field.
   * @return {@code List<String>} representing the types of each methodArgs field
   */
  public List<String> getMethodArgsTypes() {
    if (keyType instanceof List<?> keyTypeList) {

      if (keyTypeList.stream().allMatch(item -> item instanceof String)) {
        return keyTypeList.stream().map(String.class::cast).collect(Collectors.toList());
      } else {
        throw new IllegalStateException("Expected keyType to be a List<String> but found: " + keyType);
      }
    }
    return Collections.singletonList(keyType.toString());
  }


  @Override public String toString() {
    final StringBuilder sb = new StringBuilder("CacheObjectCallWrapper{");
    sb.append("methodName='").append(methodName).append('\'');
    sb.append(", ").append("[").append(super.toString()).append("]").append('\'');
    sb.append('}');
    return sb.toString();
  }

}
