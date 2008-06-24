/**
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.jmx.server.quorum;

import org.apache.zookeeper.jmx.server.ZooKeeperServerBean;
import org.apache.zookeeper.server.quorum.QuorumPeer;

/**
 * Implementation of the local peer MBean interface.
 */
public class LocalPeerBean extends ZooKeeperServerBean implements LocalPeerMXBean {

    private QuorumPeer peer;
    
    public LocalPeerBean(QuorumPeer peer){
        this.peer=peer;
    }

    public String getName() {
        return "replica."+peer.getId();
    }

    public boolean isHidden() {
        return false;
    }

    public String getQuorumAddress() {
        return peer.getQuorumAddress().getHostName()+":"+
            peer.getQuorumAddress().getPort();
    }

}
