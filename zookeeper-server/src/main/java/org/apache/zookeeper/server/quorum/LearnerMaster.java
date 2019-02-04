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

import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.quorum.auth.QuorumAuthServer;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;

/**
 * interface for keeping Observers in sync
 */
public interface LearnerMaster {
    /**
     * start tracking a learner handler
     * @param learnerHandler to track
     */
    void addLearnerHandler(LearnerHandler learnerHandler);

    /**
     * stop tracking a learner handler
     * @param learnerHandler to drop
     */
    void removeLearnerHandler(LearnerHandler learnerHandler);

    /**
     * wait for the leader of the new epoch to be confirmed by followers
     * @param sid learner id
     * @param ss
     * @throws IOException
     * @throws InterruptedException
     */
    void waitForEpochAck(long sid, StateSummary ss) throws IOException, InterruptedException;

    /**
     * snapshot throttler
     * @return snapshot throttler
     */
    LearnerSnapshotThrottler getLearnerSnapshotThrottler();

    /**
     * wait for server to start
     * @throws InterruptedException
     */
    void waitForStartup() throws InterruptedException;

    /**
     * get the first zxid of the next epoch
     * @param sid learner id
     * @param lastAcceptedEpoch
     * @return
     * @throws InterruptedException
     * @throws IOException
     */
    long getEpochToPropose(long sid, long lastAcceptedEpoch) throws InterruptedException, IOException;

    /**
     * ZKDatabase
     * @return ZKDatabase
     */
    ZKDatabase getZKDatabase();

    /**
     * wait for new leader to settle
     * @param sid id of learner
     * @param zxid zxid at learner
     * @throws InterruptedException
     */
    void waitForNewLeaderAck(long sid, long zxid) throws InterruptedException;

    /**
     * last proposed zxid
     * @return last proposed zxid
     */
    long getLastProposed();

    /**
     * the current tick
     * @return the current tick
     */
    int getCurrentTick();

    /**
     * time allowed for sync response
     * @return time allowed for sync response
     */
    int syncTimeout();

    /**
     * deadline tick marking observer sync (initial)
     * @return deadline tick marking observer sync (initial)
     */
    int getTickOfNextAckDeadline();

    /**
     * next deadline tick marking observer sync (steady state)
     * @return next deadline tick marking observer sync (steady state)
     */
    int getTickOfInitialAckDeadline();

    /**
     * decrement follower count
     * @return previous follower count
     */
    long getAndDecrementFollowerCounter();

    /**
     * handle ack packet
     * @param sid leader id
     * @param zxid packet zxid
     * @param localSocketAddress forwarder's address
     */
    void processAck(long sid, long zxid, SocketAddress localSocketAddress);

    /**
     * mark session as alive
     * @param sess session id
     * @param to timeout
     */
    void touch(long sess, int to);

    /**
     * handle revalidate packet
     * @param qp session packet
     * @param learnerHandler learner
     * @throws IOException
     */
    void revalidateSession(QuorumPacket qp, LearnerHandler learnerHandler) throws IOException;

    /**
     * proxy request from learner to server
     * @param si request
     */
    void submitLearnerRequest(Request si);

    /**
     * begin forwarding packets to learner handler
     * @param learnerHandler learner
     * @param lastSeenZxid zxid of learner
     * @return last zxid forwarded
     */
    long startForwarding(LearnerHandler learnerHandler, long lastSeenZxid);

    /**
     * version of current quorum verifier
     * @return version of current quorum verifier
     */
    long getQuorumVerifierVersion();

    /**
     *
     * @param sid server id
     * @return server information in the view
     */
    String getPeerInfo(long sid);

    /**
     * identifier of current quorum verifier for new leader
     * @return identifier of current quorum verifier for new leader
     */
    byte[] getQuorumVerifierBytes();

    QuorumAuthServer getQuorumAuthServer();

    /**
     * registers the handler's bean
     * @param learnerHandler handler
     * @param socket connection to learner
     */
    void registerLearnerHandlerBean(final LearnerHandler learnerHandler, Socket socket);

    /**
     * unregisters the handler's bean
     * @param learnerHandler handler
     */
    void unregisterLearnerHandlerBean(final LearnerHandler learnerHandler);
}
