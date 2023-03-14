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

import static java.nio.charset.StandardCharsets.UTF_8;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import org.apache.jute.Record;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.TxnLogEntry;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;
import org.apache.zookeeper.server.util.SerializeUtils;
import org.apache.zookeeper.server.util.ZxidUtils;
import org.apache.zookeeper.txn.SetDataTxn;
import org.apache.zookeeper.txn.TxnDigest;
import org.apache.zookeeper.txn.TxnHeader;

/**
 * This class has the control logic for the Follower.
 */
public class Follower extends Learner {

    private long lastQueued;
    // This is the same object as this.zk, but we cache the downcast op
    final FollowerZooKeeperServer fzk;

    ObserverMaster om;

    Follower(final QuorumPeer self, final FollowerZooKeeperServer zk) {
        this.self = Objects.requireNonNull(self);
        this.fzk = Objects.requireNonNull(zk);
        this.zk = zk;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Follower ").append(sock);
        sb.append(" lastQueuedZxid:").append(lastQueued);
        sb.append(" pendingRevalidationCount:").append(pendingRevalidations.size());
        return sb.toString();
    }

    /**
     * the main method called by the follower to follow the leader
     *
     * @throws InterruptedException
     */
    void followLeader() throws InterruptedException {
        self.end_fle = Time.currentElapsedTime();
        long electionTimeTaken = self.end_fle - self.start_fle;
        self.setElectionTimeTaken(electionTimeTaken);
        ServerMetrics.getMetrics().ELECTION_TIME.add(electionTimeTaken);
        LOG.info("FOLLOWING - LEADER ELECTION TOOK - {} {}", electionTimeTaken, QuorumPeer.FLE_TIME_UNIT);
        self.start_fle = 0;
        self.end_fle = 0;
        fzk.registerJMX(new FollowerBean(this, zk), self.jmxLocalPeerBean);

        long connectionTime = 0;
        boolean completedSync = false;

        try {
            self.setZabState(QuorumPeer.ZabState.DISCOVERY);
            QuorumServer leaderServer = findLeader();
            try {
                connectToLeader(leaderServer.addr, leaderServer.hostname);
                connectionTime = System.currentTimeMillis();
                long newEpochZxid = registerWithLeader(Leader.FOLLOWERINFO);
                if (self.isReconfigStateChange()) {
                    throw new Exception("learned about role change");
                }
                //check to see if the leader zxid is lower than ours
                //this should never happen but is just a safety check
                long newEpoch = ZxidUtils.getEpochFromZxid(newEpochZxid);
                if (newEpoch < self.getAcceptedEpoch()) {
                    LOG.error("Proposed leader epoch "
                              + ZxidUtils.zxidToString(newEpochZxid)
                              + " is less than our accepted epoch "
                              + ZxidUtils.zxidToString(self.getAcceptedEpoch()));
                    throw new IOException("Error: Epoch of leader is lower");
                }
                long startTime = Time.currentElapsedTime();
                self.setLeaderAddressAndId(leaderServer.addr, leaderServer.getId());
                self.setZabState(QuorumPeer.ZabState.SYNCHRONIZATION);
                syncWithLeader(newEpochZxid);
                self.setZabState(QuorumPeer.ZabState.BROADCAST);
                completedSync = true;
                long syncTime = Time.currentElapsedTime() - startTime;
                ServerMetrics.getMetrics().FOLLOWER_SYNC_TIME.add(syncTime);
                if (self.getObserverMasterPort() > 0) {
                    LOG.info("Starting ObserverMaster");

                    om = new ObserverMaster(self, fzk, self.getObserverMasterPort());
                    om.start();
                } else {
                    om = null;
                }
                // create a reusable packet to reduce gc impact
                QuorumPacket qp = new QuorumPacket();
                while (this.isRunning()) {
                    readPacket(qp);
                    processPacket(qp);
                }
            } catch (Exception e) {
                LOG.warn("Exception when following the leader", e);
                closeSocket();

                // clear pending revalidations
                pendingRevalidations.clear();
            }
        } finally {
            if (om != null) {
                om.stop();
            }
            zk.unregisterJMX(this);

            if (connectionTime != 0) {
                long connectionDuration = System.currentTimeMillis() - connectionTime;
                LOG.info(
                    "Disconnected from leader (with address: {}). Was connected for {}ms. Sync state: {}",
                    leaderAddr,
                    connectionDuration,
                    completedSync);
                messageTracker.dumpToLog(leaderAddr.toString());
            }
        }
    }

    /**
     * Examine the packet received in qp and dispatch based on its contents.
     * @param qp
     * @throws IOException
     */
    protected void processPacket(QuorumPacket qp) throws Exception {
        switch (qp.getType()) {
        case Leader.PING:
            ping(qp);
            break;
        case Leader.PROPOSAL:
            ServerMetrics.getMetrics().LEARNER_PROPOSAL_RECEIVED_COUNT.add(1);
            TxnLogEntry logEntry = SerializeUtils.deserializeTxn(qp.getData());
            TxnHeader hdr = logEntry.getHeader();
            Record txn = logEntry.getTxn();
            TxnDigest digest = logEntry.getDigest();
            if (hdr.getZxid() != lastQueued + 1) {
                LOG.warn(
                    "Got zxid 0x{} expected 0x{}",
                    Long.toHexString(hdr.getZxid()),
                    Long.toHexString(lastQueued + 1));
            }
            lastQueued = hdr.getZxid();

            if (hdr.getType() == OpCode.reconfig) {
                SetDataTxn setDataTxn = (SetDataTxn) txn;
                QuorumVerifier qv = self.configFromString(new String(setDataTxn.getData(), UTF_8));
                self.setLastSeenQuorumVerifier(qv, true);
            }

            fzk.logRequest(hdr, txn, digest);
            if (hdr != null) {
                /*
                 * Request header is created only by the leader, so this is only set
                 * for quorum packets. If there is a clock drift, the latency may be
                 * negative. Headers use wall time, not CLOCK_MONOTONIC.
                 */
                long now = Time.currentWallTime();
                long latency = now - hdr.getTime();
                if (latency >= 0) {
                    ServerMetrics.getMetrics().PROPOSAL_LATENCY.add(latency);
                }
            }
            if (om != null) {
                final long startTime = Time.currentElapsedTime();
                om.proposalReceived(qp);
                ServerMetrics.getMetrics().OM_PROPOSAL_PROCESS_TIME.add(Time.currentElapsedTime() - startTime);
            }
            break;
        case Leader.COMMIT:
            ServerMetrics.getMetrics().LEARNER_COMMIT_RECEIVED_COUNT.add(1);
            fzk.commit(qp.getZxid());
            if (om != null) {
                final long startTime = Time.currentElapsedTime();
                om.proposalCommitted(qp.getZxid());
                ServerMetrics.getMetrics().OM_COMMIT_PROCESS_TIME.add(Time.currentElapsedTime() - startTime);
            }
            break;

        case Leader.COMMITANDACTIVATE:
            // get the new configuration from the request
            Request request = fzk.pendingTxns.element();
            SetDataTxn setDataTxn = (SetDataTxn) request.getTxn();
            QuorumVerifier qv = self.configFromString(new String(setDataTxn.getData(), UTF_8));

            // get new designated leader from (current) leader's message
            ByteBuffer buffer = ByteBuffer.wrap(qp.getData());
            long suggestedLeaderId = buffer.getLong();
            final long zxid = qp.getZxid();
            boolean majorChange = self.processReconfig(qv, suggestedLeaderId, zxid, true);
            // commit (writes the new config to ZK tree (/zookeeper/config)
            fzk.commit(zxid);

            if (om != null) {
                om.informAndActivate(zxid, suggestedLeaderId);
            }
            if (majorChange) {
                throw new Exception("changes proposed in reconfig");
            }
            break;
        case Leader.UPTODATE:
            LOG.error("Received an UPTODATE message after Follower started");
            break;
        case Leader.REVALIDATE:
            if (om == null || !om.revalidateLearnerSession(qp)) {
                revalidate(qp);
            }
            break;
        case Leader.SYNC:
            fzk.sync();
            break;
        default:
            LOG.warn("Unknown packet type: {}", LearnerHandler.packetToString(qp));
            break;
        }
    }

    /**
     * The zxid of the last operation seen
     * @return zxid
     */
    public long getZxid() {
        synchronized (fzk) {
            return fzk.getZxid();
        }
    }

    /**
     * The zxid of the last operation queued
     * @return zxid
     */
    protected long getLastQueued() {
        return lastQueued;
    }

    public Integer getSyncedObserverSize() {
        return om == null ? null : om.getNumActiveObservers();
    }

    public Iterable<Map<String, Object>> getSyncedObserversInfo() {
        if (om != null && om.getNumActiveObservers() > 0) {
            return om.getActiveObservers();
        }
        return Collections.emptySet();
    }

    public void resetObserverConnectionStats() {
        if (om != null && om.getNumActiveObservers() > 0) {
            om.resetObserverConnectionStats();
        }
    }

    @Override
    public void shutdown() {
        LOG.info("shutdown Follower");
        super.shutdown();
    }

}
