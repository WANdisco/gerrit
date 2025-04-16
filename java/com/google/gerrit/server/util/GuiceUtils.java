package com.google.gerrit.server.util;

import com.google.inject.AbstractModule;
import com.google.inject.Injector;
import com.google.inject.Key;

public class GuiceUtils {

  public static boolean hasBinding(Injector injector, Class<?> clazz) {
    return injector.getExistingBinding(Key.get(clazz)) != null;
  }

  public static boolean hasModule(final Injector injector, Class<? extends AbstractModule> clz){
    return injector.getInstance(clz) != null;
  }
}
