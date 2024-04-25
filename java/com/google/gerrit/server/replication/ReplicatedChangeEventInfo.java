package com.google.gerrit.server.replication;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.server.data.ChangeAttribute;

public class ReplicatedChangeEventInfo {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private ChangeAttribute changeAttr = null;
  private Branch.NameKey branchName = null;
  private String projectName = null;
  private boolean supported = false;


  public void setChangeAttribute(ChangeAttribute changeAttr) {
    if(changeAttr == null){
      logger.atSevere().log("Cannot set ChangeAttribute. ChangeAttribute was null");
      return;
    }
    this.changeAttr = changeAttr;
    this.projectName = changeAttr.project;
    supported = true;

  }

  public void setProjectName(String projectName) {
    this.projectName = projectName;
    this.supported = true;
  }

  public void setBranchName(Branch.NameKey branchName) {
    this.branchName = branchName;
    supported = true;
  }

  public void setSupported(boolean supported) {
    this.supported = supported;
  }

  public ChangeAttribute getChangeAttr() {
    return changeAttr;
  }

  public Branch.NameKey getBranchName() {
    return branchName;
  }

  public String getProjectName() {
    return projectName;
  }

  public boolean isSupported() {
    return supported;
  }

  @Override
  public String toString() {
    return String.format("ReplicatedChangeEventInfo{projectName=%s, branchName=%s, changeAttr=%s, supported=%s}",
        projectName, branchName, changeAttr, supported);

  }
}
