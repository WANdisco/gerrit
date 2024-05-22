package com.google.gerrit.server.replication.coordinators;

import com.google.inject.Injector;

public interface SysInjectable {
  Injector getSysInjector();

  void setSysInjector(Injector sysInjector);
}
