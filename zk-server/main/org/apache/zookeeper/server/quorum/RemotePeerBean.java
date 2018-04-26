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

/**
 * A remote peer bean only provides limited information about the remote peer,
 * and the peer cannot be managed remotely. 
 */
public class RemotePeerBean implements RemotePeerMXBean,ZKMBeanInfo {
    private QuorumPeer.QuorumServer peer;
    
    public RemotePeerBean(QuorumPeer.QuorumServer peer){
        this.peer=peer;
    }

    public void setQuorumServer(QuorumPeer.QuorumServer peer) {
        this.peer = peer;
    }

    public String getName() {
        return "replica."+peer.id;
    }
    public boolean isHidden() {
        return false;
    }

    public String getQuorumAddress() {
        return peer.addr.getHostString()+":"+peer.addr.getPort();
    }

    public String getElectionAddress() {
        return peer.electionAddr.getHostString() + ":" + peer.electionAddr.getPort();
    }

    public String getClientAddress() {
        if (null == peer.clientAddr) {
            return "";
        }
        return peer.clientAddr.getHostString() + ":"
                + peer.clientAddr.getPort();
    }

    public String getLearnerType() {
        return peer.type.toString();
    }
}
