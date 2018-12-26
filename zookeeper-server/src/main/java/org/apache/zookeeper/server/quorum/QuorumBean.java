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

import org.apache.zookeeper.jmx.ZKMBeanInfo;
import org.apache.zookeeper.server.quorum.QuorumPeer;

public class QuorumBean implements QuorumMXBean, ZKMBeanInfo {
    private final QuorumPeer peer;
    private final String name;
    
    public QuorumBean(QuorumPeer peer){
        this.peer = peer;
        name = "ReplicatedServer_id" + peer.getId();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isHidden() {
        return false;
    }

    @Override
    public int getQuorumSize() {
        return peer.getQuorumSize();
    }

    public int getSyncLimit() {
        return peer.getSyncLimit();
    }

    public int getInitLimit() {
        return peer.getInitLimit();
    }

    public void setInitLimit(int initLimit) {
        peer.setInitLimit(initLimit);
    }

    public void setSyncLimit(int syncLimit) {
        peer.setSyncLimit(syncLimit);
    }

    @Override
    public boolean isSslQuorum() {
        return peer.isSslQuorum();
    }

    @Override
    public boolean isPortUnification() {
        return peer.shouldUsePortUnification();
    }
}
