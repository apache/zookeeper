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


public class Vote {
    
    public Vote(long id, long zxid) {
        this.id = id;
        this.zxid = zxid;
        this.electionEpoch = -1;
        this.peerEpoch = -1;
        this.state = ServerState.LOOKING;
    }
    
    public Vote(long id, long zxid, long peerEpoch) {
        this.id = id;
        this.zxid = zxid;
        this.electionEpoch = -1;
        this.peerEpoch = peerEpoch;
        this.state = ServerState.LOOKING;
    }

    public Vote(long id, long zxid, long electionEpoch, long peerEpoch) {
        this.id = id;
        this.zxid = zxid;
        this.electionEpoch = electionEpoch;
        this.peerEpoch = peerEpoch;
        this.state = ServerState.LOOKING;
    }
    
    public Vote(long id, long zxid, long electionEpoch, long peerEpoch, ServerState state) {
        this.id = id;
        this.zxid = zxid;
        this.electionEpoch = electionEpoch;
        this.state = state;
        this.peerEpoch = peerEpoch;
    }
    
    final private long id;
    
    final private long zxid;
    
    final private long electionEpoch;
    
    final private long peerEpoch;
    
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
        return (id == other.id && zxid == other.zxid && electionEpoch == other.electionEpoch && peerEpoch == other.peerEpoch);

    }

    @Override
    public int hashCode() {
        return (int) (id & zxid);
    }

    public String toString() {
        return "(" + id + ", " + Long.toHexString(zxid) + ", " + Long.toHexString(peerEpoch) + ")";
    }
}
