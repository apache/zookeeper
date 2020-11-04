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

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.quorum.auth.QuorumAuthServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * interface for keeping Observers in sync
 */
public abstract class LearnerMaster {

    private static final Logger LOG = LoggerFactory.getLogger(LearnerMaster.class);

    // Throttle when there are too many concurrent snapshots being sent to observers
    private static final String MAX_CONCURRENT_SNAPSYNCS = "zookeeper.leader.maxConcurrentSnapSyncs";
    private static final int DEFAULT_CONCURRENT_SNAPSYNCS;

    // Throttle when there are too many concurrent diff syncs being sent to observers
    private static final String MAX_CONCURRENT_DIFF_SYNCS = "zookeeper.leader.maxConcurrentDiffSyncs";
    private static final int DEFAULT_CONCURRENT_DIFF_SYNCS;

    static {
        DEFAULT_CONCURRENT_SNAPSYNCS = Integer.getInteger(MAX_CONCURRENT_SNAPSYNCS, 10);
        LOG.info("{} = {}", MAX_CONCURRENT_SNAPSYNCS, DEFAULT_CONCURRENT_SNAPSYNCS);

        DEFAULT_CONCURRENT_DIFF_SYNCS = Integer.getInteger(MAX_CONCURRENT_DIFF_SYNCS, 100);
        LOG.info("{} = {}", MAX_CONCURRENT_DIFF_SYNCS, DEFAULT_CONCURRENT_DIFF_SYNCS);
    }

    private volatile int maxConcurrentSnapSyncs = DEFAULT_CONCURRENT_SNAPSYNCS;
    private volatile int maxConcurrentDiffSyncs = DEFAULT_CONCURRENT_DIFF_SYNCS;

    private final LearnerSyncThrottler learnerSnapSyncThrottler = new LearnerSyncThrottler(maxConcurrentSnapSyncs, LearnerSyncThrottler.SyncType.SNAP);

    private final LearnerSyncThrottler learnerDiffSyncThrottler = new LearnerSyncThrottler(maxConcurrentDiffSyncs, LearnerSyncThrottler.SyncType.DIFF);

    public int getMaxConcurrentSnapSyncs() {
        return maxConcurrentSnapSyncs;
    }

    public void setMaxConcurrentSnapSyncs(int maxConcurrentSnapSyncs) {
        LOG.info("Set maxConcurrentSnapSyncs to {}", maxConcurrentSnapSyncs);
        this.maxConcurrentSnapSyncs = maxConcurrentSnapSyncs;
        learnerSnapSyncThrottler.setMaxConcurrentSyncs(maxConcurrentSnapSyncs);
    }

    public int getMaxConcurrentDiffSyncs() {
        return maxConcurrentDiffSyncs;
    }

    public void setMaxConcurrentDiffSyncs(int maxConcurrentDiffSyncs) {
        LOG.info("Set maxConcurrentDiffSyncs to {}", maxConcurrentDiffSyncs);
        this.maxConcurrentDiffSyncs = maxConcurrentDiffSyncs;
        learnerDiffSyncThrottler.setMaxConcurrentSyncs(maxConcurrentDiffSyncs);
    }

    /**
     * snap sync throttler
     * @return snapshot throttler
     */
    public LearnerSyncThrottler getLearnerSnapSyncThrottler() {
        return learnerSnapSyncThrottler;
    }

    /**
     * diff sync throttler
     * @return diff throttler
     */
    public LearnerSyncThrottler getLearnerDiffSyncThrottler() {
        return learnerDiffSyncThrottler;
    }

    /**
     * start tracking a learner handler
     * @param learnerHandler to track
     */
    abstract void addLearnerHandler(LearnerHandler learnerHandler);

    /**
     * stop tracking a learner handler
     * @param learnerHandler to drop
     */
    abstract void removeLearnerHandler(LearnerHandler learnerHandler);

    /**
     * wait for the leader of the new epoch to be confirmed by followers
     * @param sid learner id
     * @param ss
     * @throws IOException
     * @throws InterruptedException
     */
    abstract void waitForEpochAck(long sid, StateSummary ss) throws IOException, InterruptedException;

    /**
     * wait for server to start
     * @throws InterruptedException
     */
    abstract void waitForStartup() throws InterruptedException;

    /**
     * get the first zxid of the next epoch
     * @param sid learner id
     * @param lastAcceptedEpoch
     * @return the first zxid of the next epoch
     * @throws InterruptedException
     * @throws IOException
     */
    abstract long getEpochToPropose(long sid, long lastAcceptedEpoch) throws InterruptedException, IOException;

    /**
     * ZKDatabase
     * @return ZKDatabase
     */
    abstract ZKDatabase getZKDatabase();

    /**
     * wait for new leader to settle
     * @param sid id of learner
     * @param zxid zxid at learner
     * @throws InterruptedException
     */
    abstract void waitForNewLeaderAck(long sid, long zxid) throws InterruptedException;

    /**
     * last proposed zxid
     * @return last proposed zxid
     */
    abstract long getLastProposed();

    /**
     * the current tick
     * @return the current tick
     */
    abstract int getCurrentTick();

    /**
     * time allowed for sync response
     * @return time allowed for sync response
     */
    abstract int syncTimeout();

    /**
     * deadline tick marking observer sync (initial)
     * @return deadline tick marking observer sync (initial)
     */
    abstract int getTickOfNextAckDeadline();

    /**
     * next deadline tick marking observer sync (steady state)
     * @return next deadline tick marking observer sync (steady state)
     */
    abstract int getTickOfInitialAckDeadline();

    /**
     * decrement follower count
     * @return previous follower count
     */
    abstract long getAndDecrementFollowerCounter();

    /**
     * handle ack packet
     * @param sid leader id
     * @param zxid packet zxid
     * @param localSocketAddress forwarder's address
     */
    abstract void processAck(long sid, long zxid, SocketAddress localSocketAddress);

    /**
     * mark session as alive
     * @param sess session id
     * @param to timeout
     */
    abstract void touch(long sess, int to);

    /**
     * handle revalidate packet
     * @param qp session packet
     * @param learnerHandler learner
     * @throws IOException
     */
    abstract void revalidateSession(QuorumPacket qp, LearnerHandler learnerHandler) throws IOException;

    /**
     * proxy request from learner to server
     * @param si request
     */
    abstract void submitLearnerRequest(Request si);

    /**
     * begin forwarding packets to learner handler
     * @param learnerHandler learner
     * @param lastSeenZxid zxid of learner
     * @return last zxid forwarded
     */
    abstract long startForwarding(LearnerHandler learnerHandler, long lastSeenZxid);

    /**
     * version of current quorum verifier
     * @return version of current quorum verifier
     */
    abstract long getQuorumVerifierVersion();

    /**
     *
     * @param sid server id
     * @return server information in the view
     */
    abstract String getPeerInfo(long sid);

    /**
     * identifier of current quorum verifier for new leader
     * @return identifier of current quorum verifier for new leader
     */
    abstract byte[] getQuorumVerifierBytes();

    abstract QuorumAuthServer getQuorumAuthServer();

    /**
     * registers the handler's bean
     * @param learnerHandler handler
     * @param socket connection to learner
     */
    abstract void registerLearnerHandlerBean(LearnerHandler learnerHandler, Socket socket);

    /**
     * unregisters the handler's bean
     * @param learnerHandler handler
     */
    abstract void unregisterLearnerHandlerBean(LearnerHandler learnerHandler);

}
