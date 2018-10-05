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

/**
 * Provides live statistics about a running Leader.
 */
public class ProposalStats {
    /**
     * Size of the last generated proposal. This should fit into server's jute.maxbuffer setting.
     */
    private int lastProposalSize = -1;

    /**
     * Size of the smallest proposal which has been generated since the server was started.
     */
    private int minProposalSize = -1;

    /**
     * Size of the largest proposal which has been generated since the server was started.
     */
    private int maxProposalSize = -1;

    public synchronized int getLastProposalSize() {
        return lastProposalSize;
    }

    synchronized void setLastProposalSize(int value) {
        lastProposalSize = value;
        if (minProposalSize == -1 || value < minProposalSize) {
            minProposalSize = value;
        }
        if (value > maxProposalSize) {
            maxProposalSize = value;
        }
    }

    public synchronized int getMinProposalSize() {
        return minProposalSize;
    }

    public synchronized int getMaxProposalSize() {
        return maxProposalSize;
    }

    public synchronized void reset() {
        lastProposalSize = -1;
        minProposalSize = -1;
        maxProposalSize = -1;
    }

    public synchronized String toString() {
        return String.format("%d/%d/%d", lastProposalSize, minProposalSize, maxProposalSize);
    }
}
