/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.zookeeper.recipes.leader;

import java.io.Serializable;
import java.util.Comparator;

/**
 * A leader offer is a numeric id / path pair. The id is the sequential node id
 * assigned by ZooKeeper where as the path is the absolute path to the ZNode.
 */
public class LeaderOffer {

  private Integer id;
  private String nodePath;
  private String hostName;

  public LeaderOffer() {
    // Default constructor
  }

  public LeaderOffer(Integer id, String nodePath, String hostName) {
    this.id = id;
    this.nodePath = nodePath;
    this.hostName = hostName;
  }

  @Override
  public String toString() {
    return "{ id:" + id + " nodePath:" + nodePath + " hostName:" + hostName
        + " }";
  }

  public Integer getId() {
    return id;
  }

  public void setId(Integer id) {
    this.id = id;
  }

  public String getNodePath() {
    return nodePath;
  }

  public void setNodePath(String nodePath) {
    this.nodePath = nodePath;
  }

  public String getHostName() {
    return hostName;
  }

  public void setHostName(String hostName) {
    this.hostName = hostName;
  }

  /**
   * Compare two instances of {@link LeaderOffer} using only the {code}id{code}
   * member.
   */
  public static class IdComparator
          implements Comparator<LeaderOffer>, Serializable {

    @Override
    public int compare(LeaderOffer o1, LeaderOffer o2) {
      return o1.getId().compareTo(o2.getId());
    }

  }

}
