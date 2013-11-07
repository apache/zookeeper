package org.apache.bookkeeper.client;

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

import org.apache.bookkeeper.util.MathUtils;

/**
 * A specific {@link DistributionSchedule} that places entries in round-robin
 * fashion. For ensemble size 3, and quorum size 2, Entry 0 goes to bookie 0 and
 * 1, entry 1 goes to bookie 1 and 2, and entry 2 goes to bookie 2 and 0, and so
 * on.
 * 
 */
class RoundRobinDistributionSchedule implements DistributionSchedule {
    int quorumSize;
    int ensembleSize;

    // covered[i] is true if the quorum starting at bookie index i has been
    // covered by a recovery reply
    boolean[] covered = null;
    int numQuorumsUncovered;

    public RoundRobinDistributionSchedule(int quorumSize, int ensembleSize) {
        this.quorumSize = quorumSize;
        this.ensembleSize = ensembleSize;
    }

    @Override
    public int getBookieIndex(long entryId, int replicaIndex) {
        return (int) ((entryId + replicaIndex) % ensembleSize);
    }

    @Override
    public int getReplicaIndex(long entryId, int bookieIndex) {
        // NOTE: Java's % operator returns the sign of the dividend and is hence
        // not always positive

        int replicaIndex = MathUtils.signSafeMod(bookieIndex - entryId, ensembleSize);

        return replicaIndex < quorumSize ? replicaIndex : -1;

    }

    public synchronized boolean canProceedWithRecovery(int bookieIndexHeardFrom) {
        if (covered == null) {
            covered = new boolean[ensembleSize];
            numQuorumsUncovered = ensembleSize;
        }

        if (numQuorumsUncovered == 0) {
            return true;
        }

        for (int i = 0; i < quorumSize; i++) {
            int quorumStartIndex = MathUtils.signSafeMod(bookieIndexHeardFrom - i, ensembleSize);
            if (!covered[quorumStartIndex]) {
                covered[quorumStartIndex] = true;
                numQuorumsUncovered--;

                if (numQuorumsUncovered == 0) {
                    return true;
                }
            }

        }

        return false;

    }

}
