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

package org.apache.zookeeper.server.util;

import org.apache.zookeeper.server.quorum.Follower;
import org.apache.zookeeper.server.quorum.Leader;
import org.apache.zookeeper.server.quorum.QuorumPeer;

/**
* Application must implement this interface and register its instance with
* the {@link ObserverManager}.
*/
public interface QuorumPeerObserver {
    /**
     * The local quorum peer qp has started.
     * @param qp the quorum peer.
     */
    public void onStartup(QuorumPeer qp);
    /**
     * The local quorum peer is about to shutdown.
     * @param qp the quorum peer.
     */
    public void onShutdown(QuorumPeer qp);
    /**
     * Leader election protocol has started
     * @param qp quorum peer running the protocol
     */
    public void onLeaderElectionStarted(QuorumPeer qp);
    /**
     * A new leader has been elected.
     * @param qp quorum peer hosting the leader
     * @param newLeader the new leader
     */
    public void onLeaderStarted(QuorumPeer qp, Leader newLeader);
    /**
     * The leader instance is shutting down.
     * @param qp the quorum peer hosting the leader.
     * @param leader the leader instance
     */
    public void onLeaderShutdown(QuorumPeer qp, Leader leader);
    /**
     * A new follower has started.
     * @param qp the quorum peer instance
     * @param newFollower the new follower instance
     */
    public void onFollowerStarted(QuorumPeer qp, Follower newFollower);
    /**
     * The follower is shutting down.
     * @param qp the quorum peer instance
     * @param follower the follower instance
     */
    public void onFollowerShutdown(QuorumPeer qp, Follower follower);
}
