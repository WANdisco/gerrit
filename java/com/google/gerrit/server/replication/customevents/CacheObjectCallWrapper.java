
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

import java.util.Arrays;
import java.util.List;

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
   * Helper to turn the key(Object) field in the base class, to be the List<Object>key, so it can be used correctly
   * by the cache object call code, which may expect 0, 1, or many args.
   * Note this must be a 1:1 mapping as an arrayList with key field.
   * @return List<Object> representing the actual methodArgs
   */
  public List<Object> getMethodArgs(){
    // this is the key field, rebuilt and returned as a List<?> where the class is the real class type rebuilt from original
    // calling types.
    return (List<Object>) getKeyAsOriginalType();
  }

  /**
   * Helper to turn the keyType(Object) field in the base class, to be the List<String>keyType, so it can be used correctly
   * by the cache object call code, which may expect 0, 1, or many args.
   * Note this must be a 1:1 mapping as an arrayList with key field.
   * @return List<String> representing the types of each methodArgs field
   */
  public List<String> getMethodArgsTypes(){
    if ( keyType instanceof List){
      return (List<String>) keyType;
    }
    // take this object and return it as a single item in the list (this shouldn't happen but jic ).
    return Arrays.asList(keyType.toString());
  }


  @Override public String toString() {
    final StringBuilder sb = new StringBuilder("CacheObjectCallWrapper{");
    sb.append("methodName='").append(methodName).append('\'');
    sb.append(", ").append("[").append(super.toString()).append("]").append('\'');
    sb.append('}');
    return sb.toString();
  }

}
