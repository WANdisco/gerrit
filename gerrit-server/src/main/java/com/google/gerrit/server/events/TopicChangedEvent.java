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

package com.google.gerrit.server.events;

import com.google.gerrit.server.data.AccountAttribute;
import com.google.gerrit.server.data.ChangeAttribute;

public class TopicChangedEvent extends ChangeEvent {
  public final String type;
  public ChangeAttribute change;
  public AccountAttribute changer;
  public String oldTopic;

  public TopicChangedEvent(){
    this.type = "topic-changed";
  }

  public TopicChangedEvent(TopicChangedEvent e, String type){
    this(e, type, false);
  }

  public TopicChangedEvent(TopicChangedEvent e, String type, boolean replicated){
    this.type = type;
    this.change = e.change;
    this.changer = e.changer;
    this.oldTopic = e.oldTopic;
    this.replicated = replicated;
  }

  @Override
  public String getType(){
    return type;
  }

}