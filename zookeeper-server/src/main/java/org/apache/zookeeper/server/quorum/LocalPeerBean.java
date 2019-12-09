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


import static org.apache.zookeeper.common.NetUtils.formatInetAddr;

/**
 * Implementation of the local peer MBean interface.
 */
public class LocalPeerBean extends ServerBean implements LocalPeerMXBean {
    private final QuorumPeer peer;
    
    public LocalPeerBean(QuorumPeer peer) {
        this.peer = peer;
    }

    public String getName() {
        return "replica." + peer.getId();
    }

    public boolean isHidden() {
        return false;
    }

    public int getTickTime() {
        return peer.getTickTime();
    }
    
    public int getMaxClientCnxnsPerHost() {
        return peer.getMaxClientCnxnsPerHost();
    }

    public int getMinSessionTimeout() {
        return peer.getMinSessionTimeout();
    }
    
    public int getMaxSessionTimeout() {
        return peer.getMaxSessionTimeout();
    }
    
    public int getInitLimit() {
        return peer.getInitLimit();
    }
    
    public int getSyncLimit() {
        return peer.getSyncLimit();
    }
    
    public int getTick() {
        return peer.getTick();
    }
    
    public String getState() {
        return peer.getServerState();
    }
    
    public String getQuorumAddress() {
        return formatInetAddr(peer.getQuorumAddress());
    }
    
    public int getElectionType() {
        return peer.getElectionType();
    }

    public String getElectionAddress() {
        return formatInetAddr(peer.getElectionAddress());
    }

    public String getClientAddress() {
        if (null != peer.cnxnFactory) {
            return formatInetAddr(peer.cnxnFactory.getLocalAddress());
        } else {
            return "";
        }
    }

    public String getLearnerType(){
        return peer.getLearnerType().toString();
    }

    public long getConfigVersion(){
        return peer.getQuorumVerifier().getVersion();
    }

    @Override
    public String getQuorumSystemInfo() {
        return peer.getQuorumVerifier().toString();
    }

    @Override
    public boolean isPartOfEnsemble() {
        return peer.getView().containsKey(peer.getId());
    }

    @Override
    public boolean isLeader() {
        return peer.isLeader(peer.getId());
    }
}
