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

import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class Vote {
    private static final Logger LOG = LoggerFactory.getLogger(Vote.class);
    
    public Vote(long id, 
                    long zxid) {
        this.version = 0x0;
        this.id = id;
        this.zxid = zxid;
        this.electionEpoch = -1;
        this.peerEpoch = -1;
        this.state = ServerState.LOOKING;
    }
    
    public Vote(long id, 
                    long zxid, 
                    long peerEpoch) {
        this.version = 0x0;
        this.id = id;
        this.zxid = zxid;
        this.electionEpoch = -1;
        this.peerEpoch = peerEpoch;
        this.state = ServerState.LOOKING;
    }

    public Vote(long id, 
                    long zxid, 
                    long electionEpoch, 
                    long peerEpoch) {
        this.version = 0x0;
        this.id = id;
        this.zxid = zxid;
        this.electionEpoch = electionEpoch;
        this.peerEpoch = peerEpoch;
        this.state = ServerState.LOOKING;
    }
    
    public Vote(int version,
                    long id, 
                    long zxid, 
                    long electionEpoch, 
                    long peerEpoch, 
                    ServerState state) {
        this.version = version;
        this.id = id;
        this.zxid = zxid;
        this.electionEpoch = electionEpoch;
        this.state = state;
        this.peerEpoch = peerEpoch;
    }
    
    public Vote(long id, 
                    long zxid, 
                    long electionEpoch, 
                    long peerEpoch, 
                    ServerState state) {
        this.id = id;
        this.zxid = zxid;
        this.electionEpoch = electionEpoch;
        this.state = state;
        this.peerEpoch = peerEpoch;
        this.version = 0x0;
    }
    
    final private int version;
    
    final private long id;
    
    final private long zxid;
    
    final private long electionEpoch;
    
    final private long peerEpoch;
    
    public int getVersion() {
        return version;
    }
    
    public long getId() {
        return id;
    }

    public long getZxid() {
        return zxid;
    }

    public long getElectionEpoch() {
        return electionEpoch;
    }

    public long getPeerEpoch() {
        return peerEpoch;
    }

    public ServerState getState() {
        return state;
    }

    final private ServerState state;
    
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Vote)) {
            return false;
        }
        Vote other = (Vote) o;
        
        
        /*
         * There are two things going on in the logic below.
         * First, we compare votes of servers out of election
         * using only id and peer epoch. Second, if one version
         * is 0x0 and the other isn't, then we only use the
         * leader id. This case is here to enable rolling upgrades.
         * 
         * {@see https://issues.apache.org/jira/browse/ZOOKEEPER-1805}
         */
        if ((state == ServerState.LOOKING) ||
                (other.state == ServerState.LOOKING)) {
            return (id == other.id
                    && zxid == other.zxid
                    && electionEpoch == other.electionEpoch
                    && peerEpoch == other.peerEpoch);
        } else {
            if ((version > 0x0) ^ (other.version > 0x0)) {
                return id == other.id;
            } else {
                return (id == other.id
                        && peerEpoch == other.peerEpoch);
            }
        } 
    }

    @Override
    public int hashCode() {
        return (int) (id & zxid);
    }

    public String toString() {
        return String.format("(%d, %s, %s)",
                                id,
                                Long.toHexString(zxid),
                                Long.toHexString(peerEpoch));
    }
}
