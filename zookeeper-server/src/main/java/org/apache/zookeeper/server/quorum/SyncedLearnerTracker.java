/*
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

    protected ArrayList<QuorumVerifierAcksetPair> qvAcksetPairs = new ArrayList<QuorumVerifierAcksetPair>();

    public void addQuorumVerifier(QuorumVerifier qv) {
        qvAcksetPairs.add(new QuorumVerifierAcksetPair(qv, new HashSet<Long>(qv.getVotingMembers().size()), new HashSet<Long>(qv.getWitnessingMembers().size())));
    }

    public boolean addAck(Long sid) {
        boolean change = false;
        for (QuorumVerifierAcksetPair qvAckset : qvAcksetPairs) {
            if (qvAckset.getQuorumVerifier().getVotingMembers().containsKey(sid)) {
                qvAckset.getAckset().add(sid);
                change = true;
            } else if(qvAckset.getQuorumVerifier().getWitnessingMembers().containsKey(sid)) {
                qvAckset.getWitnessAckset().add(sid);
                change = true;
            }
        }
        return change;
    }

    public boolean hasSid(long sid) {
        for (QuorumVerifierAcksetPair qvAckset : qvAcksetPairs) {
            if (!qvAckset.getQuorumVerifier().getVotingMembers().containsKey(sid)) {
                return false;
            }
        }
        return true;
    }

    public boolean hasAllQuorums() {
        for (QuorumVerifierAcksetPair qvAckset : qvAcksetPairs) {
            if (!qvAckset.getQuorumVerifier().containsQuorum(qvAckset.getAckset())) {
                return false;
            }
        }
        return true;
    }

    public boolean hasAllQuorumsWithWitness() {
        for (QuorumVerifierAcksetPair qvAcksetPair : qvAcksetPairs) {
            if (!qvAcksetPair.getQuorumVerifier().containsQuorumWithWitness(qvAcksetPair.getAckset(), qvAcksetPair.getWitnessAckset())) {
                return false;
            }
        }
        return true;
    }

    public String ackSetsToString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Followers : [");
        for (QuorumVerifierAcksetPair qvAckset : qvAcksetPairs) {
            sb.append(qvAckset.getAckset().toString()).append(",");
        }
        sb.append("],");

        boolean isWitnessPresent = false;
        for (QuorumVerifierAcksetPair qvAckset : qvAcksetPairs) {
            if(qvAckset.getQuorumVerifier().getWitnessingMembers().size() > 0) {
                isWitnessPresent = true;
                break;
            }
        }
        if(isWitnessPresent) {
            sb.append("Witnesses: [");
            for (QuorumVerifierAcksetPair qvAckset : qvAcksetPairs) {
                sb.append(qvAckset.getWitnessAckset().toString()).append(",");
            }
            sb.append("]");
        }
        return sb.substring(0, sb.length() - 1);
    }

    /**
     * Out of the servers in the QV, the ackset tracks, how many of them have voted in the elction.
     * */
    public static class QuorumVerifierAcksetPair {

        private final QuorumVerifier qv;
        private final HashSet<Long> ackset;
        private final HashSet<Long> witnessAckset;

        public QuorumVerifierAcksetPair(QuorumVerifier qv, HashSet<Long> ackset, HashSet<Long> witnessAckset) {
            this.qv = qv;
            this.ackset = ackset;
            this.witnessAckset = witnessAckset;
        }

        public QuorumVerifier getQuorumVerifier() {
            return this.qv;
        }

        public HashSet<Long> getAckset() {
            return this.ackset;
        }

        public HashSet<Long> getWitnessAckset() {
            return this.witnessAckset;
        }
    }

}
