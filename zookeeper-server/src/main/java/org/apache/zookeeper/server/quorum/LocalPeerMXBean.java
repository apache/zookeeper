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

/**
 * A local zookeeper server MBean interface. Unlike the remote peer, the local
 * peer provides complete state/statistics at runtime and can be managed (just
 * like a standalone zookeeper server).
 */
public interface LocalPeerMXBean extends ServerMXBean {

    /**
     * @return the number of milliseconds of each tick
     */
    int getTickTime();

    /** Current maxClientCnxns allowed from a particular host */
    int getMaxClientCnxnsPerHost();

    /**
     * @return the minimum number of milliseconds allowed for a session timeout
     */
    int getMinSessionTimeout();

    /**
     * @return the maximum number of milliseconds allowed for a session timeout
     */
    int getMaxSessionTimeout();

    /**
     * @return the number of ticks that the initial sync phase can take
     */
    int getInitLimit();

    /**
     * @return the number of ticks that can pass between sending a request
     * and getting a acknowledgment
     */
    int getSyncLimit();

    /**
     * Set the number of ticks that the initial sync phase can take
     */
    void setInitLimit(int initLimit);

    /**
     * Set the number of ticks that can pass between sending a request
     * and getting a acknowledgment
     */
    void setSyncLimit(int syncLimit);

    /**
     * @return the current tick
     */
    int getTick();

    /**
     * @return the current server state
     */
    String getState();

    /**
     * @return the quorum address
     */
    String getQuorumAddress();

    /**
     * @return the election type
     */
    int getElectionType();

    /**
     * @return the election address
     */
    String getElectionAddress();

    /**
     * @return the client address
     */
    String getClientAddress();

    /**
     * @return the learner type
     */
    String getLearnerType();

    /**
     * @return the config version
     */
    long getConfigVersion();

    /**
     * @return the quorum system information
     */
    String getQuorumSystemInfo();

    /**
     * @return true if quorum peer is part of the ensemble, false otherwise
     */
    boolean isPartOfEnsemble();

    /**
     * @return true if the peer is the current leader
     */
    boolean isLeader();

    /**
     * @return Current maxCnxns allowed to a single ZooKeeper server
     */
    int getMaxCnxns();
}
