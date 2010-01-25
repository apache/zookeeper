/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.quorum;

import org.apache.zookeeper.server.ZooKeeperServerBean;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.quorum.LearnerHandler;
import org.apache.zookeeper.server.quorum.Leader;

/**
 * Leader MBean interface implementation.
 */
public class LeaderBean extends ZooKeeperServerBean implements LeaderMXBean {
    private final Leader leader;
    
    public LeaderBean(Leader leader, ZooKeeperServer zks) {
        super(zks);
        this.leader = leader;
    }
    
    public String getName() {
        return "Leader";
    }

    public String getCurrentZxid() {
        return "0x" + Long.toHexString(zks.getZxid());
    }
    
    public String followerInfo() {
        StringBuilder sb = new StringBuilder();
        for (LearnerHandler handler : leader.learners) {
            sb.append(handler.toString()).append("\n");
        }
        return sb.toString();
    }

}
