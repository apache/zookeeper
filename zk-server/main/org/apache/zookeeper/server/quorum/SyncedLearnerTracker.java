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

import java.util.ArrayList;
import java.util.HashSet;

import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;

public class SyncedLearnerTracker {

    protected ArrayList<QuorumVerifierAcksetPair> qvAcksetPairs = 
                new ArrayList<QuorumVerifierAcksetPair>();

    public void addQuorumVerifier(QuorumVerifier qv) {
        qvAcksetPairs.add(new QuorumVerifierAcksetPair(qv,
                new HashSet<Long>(qv.getVotingMembers().size())));
    }

    public boolean addAck(Long sid) {
        boolean change = false;
        for (QuorumVerifierAcksetPair qvAckset : qvAcksetPairs) {
            if (qvAckset.getQuorumVerifier().getVotingMembers().containsKey(sid)) {
                qvAckset.getAckset().add(sid);
                change = true;
            }
        }
        return change;
    }

    public boolean hasAllQuorums() {
        for (QuorumVerifierAcksetPair qvAckset : qvAcksetPairs) {
            if (!qvAckset.getQuorumVerifier().containsQuorum(qvAckset.getAckset()))
                return false;
        }
        return true;
    }
        
    public String ackSetsToString(){
        StringBuilder sb = new StringBuilder();
            
        for (QuorumVerifierAcksetPair qvAckset : qvAcksetPairs) {
            sb.append(qvAckset.getAckset().toString()).append(",");
        }
            
        return sb.substring(0, sb.length()-1);
    }

    public static class QuorumVerifierAcksetPair {
        private final QuorumVerifier qv;
        private final HashSet<Long> ackset;

        public QuorumVerifierAcksetPair(QuorumVerifier qv, HashSet<Long> ackset) {                
            this.qv = qv;
            this.ackset = ackset;
        }

        public QuorumVerifier getQuorumVerifier() {
            return this.qv;
        }

        public HashSet<Long> getAckset() {
            return this.ackset;
        }
    }
}
