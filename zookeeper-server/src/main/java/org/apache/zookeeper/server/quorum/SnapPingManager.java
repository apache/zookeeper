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

import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.ZooDefs.SnapPingCode;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the snap ping check, collect information, and based on the
 * status and strategy to decide who should take snapshot.
 */
public class SnapPingManager {
    private static final Logger LOG = LoggerFactory.getLogger(SnapPingManager.class);

    public static final int SNAP_PING_VERSION = 2;
    public static final String SNAP_PING_INTERVAL_IN_SECONDS = "zookeeper.leader.snapPingIntervalInSeconds";

    private int snapPingIntervalInSeconds;

    private ScheduledExecutorService snapPinger;
    private final SnapPingHandler spHandler;
    private final Leader leader;

    public SnapPingManager(Leader leader) {
        this.leader = leader;
        this.spHandler = new SnapPingHandler(leader);
        this.snapPingIntervalInSeconds = Integer.getInteger(SNAP_PING_INTERVAL_IN_SECONDS, -1);
        LOG.info("{} = {}", SNAP_PING_INTERVAL_IN_SECONDS, snapPingIntervalInSeconds);

        if (snapPingIntervalInSeconds > 0) {
            restartSnapPinger();
        }
    }

    public void processSnapPing(SnapPingData spData) {
        spHandler.processSnapPing(spData);
    }

    public void setSnapPingIntervalInSeconds(int seconds) {
        if (seconds != snapPingIntervalInSeconds) {
            snapPingIntervalInSeconds = seconds;
            LOG.info("Updated snapPingIntervalInSeconds to {}", snapPingIntervalInSeconds);
        }

        if (snapPingIntervalInSeconds < 0) {
            cancelSnapSchedule();
            LOG.info("snapPingIntervalInSeconds is less than 0, going to "
                    + "disable snapshot schedule");
        } else {
            restartSnapPinger();
        }
    }

    public int getSnapPingIntervalInSeconds() {
        return snapPingIntervalInSeconds;
    }

    final Runnable snapPingRunnable = new Runnable() {
        long snapPingId = 1;

        @Override
        public void run() {

            // Don't need to have a strong guarantee of the current view of
            // voting members when doing snap schedule.
            Map<Long, QuorumServer> currentVotingMembers =
                    leader.getQuorumVerifier().getVotingMembers();
            List<SnapPingListener> listeners = leader.getSnapPingListeners();

            // Trigger a SnapPing to check the status
            for (SnapPingListener listener : listeners) {
                long sid = listener.getSid();
                if (currentVotingMembers.containsKey(sid)) {
                    try {
                        listener.snapPing(snapPingId, SnapPingCode.CHECK);
                    } catch (IOException e) {
                        LOG.error("Exception when sending SNAPPING "
                                + "to {}, {}", sid, e);
                    }
                }
            }

            if (++snapPingId < 0) {
                snapPingId = 1;
            }
        }
    };

    public void shutdown() {
        if (snapPinger != null) {
            snapPinger.shutdownNow();
            snapPinger = null;
        }
    }

    private void restartSnapPinger() {
        shutdown();
        snapPinger = Executors.newSingleThreadScheduledExecutor();
        snapPinger.scheduleAtFixedRate(snapPingRunnable, 0,
                snapPingIntervalInSeconds, TimeUnit.SECONDS);
        LOG.info("Started snapPinger with interval {}s", snapPingIntervalInSeconds);
    }

    private void cancelSnapSchedule() {
        boolean snapPinerTerminated = true;
        if (snapPinger != null) {
            snapPinger.shutdownNow();
            try {
                snapPinerTerminated = snapPinger.awaitTermination(
                        60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                LOG.warn("Got InterruptedException while waiting for "
                    + "snapPinger to terminate");
            } finally {
                snapPinger = null;
            }
        }

        // This is unlikely to happen
        if (!snapPinerTerminated) {
            LOG.warn("SnapPinger is not terminated yet, learner may "
                + "not enable self snapshot.");
        }

        Map<Long, QuorumServer> currentVotingMembers =
                leader.getQuorumVerifier().getVotingMembers();
        // Resume self snapshot behavior on learner
        List<SnapPingListener> listeners = leader.getSnapPingListeners();
        for (SnapPingListener  listener : listeners) {
            long sid = listener.getSid();
            if (currentVotingMembers.containsKey(sid)) {
                try {
                    listener.snapPing(SnapPingListener.SNAP_PING_ID_DONT_CARE,
                            SnapPingCode.CANCEL);
                } catch (IOException e) {
                    LOG.error("Exception when sending SNAPPING "
                            + "to {}, {}", sid, e);
                }
           }
        }
    }

    public static class SnapPingHandler {

        public static final String SNAP_TXNS_THRESHOLD = "zookeeper.leader.snapTxnsThreshold";
        public static final String SNAP_TXNS_SIZE_THRESHOLD_KB = "zookeeper.leader.snapTxnsSizeThresholdKB";
        public static final String FLUSH_LATENCY_DRAIN_THRESHOLD = "zookeeper.flushLatencyDrainThreshold";
        public static final String LEARNER_QUEUE_SIZE_DRAIN_THRESHOLD = "zookeeper.learner.queueSizeDrainThreshold";
        public static final String UNSCHEDULED_SNAP_THRESHOLD = "zookeeper.snapsync.unscheduledSnapshotThreshold";

        private int snapTxnsThreshold;
        private long snapTxnsSizeThresholdBytes;
        private int flushLatencyDrainThreshold;
        private int learnerQueueSizeDrainThreshold;
        private int unscheduledSnapThreshold;
        private int idleSnapPings = 0;

        private final Leader leader;
        private long currentSnapPingId = -1;
        private Set<SnapPingData> data = new HashSet<SnapPingData>();

        private int votingMemberSize = 0;
        private Map<Long, QuorumServer> currentVotingMembers;

        public SnapPingHandler(Leader leader) {
            this.leader = leader;

            this.snapTxnsThreshold = Integer.getInteger(SNAP_TXNS_THRESHOLD, 100000);
            // by default using 4GB
            this.snapTxnsSizeThresholdBytes = Long.getLong(SNAP_TXNS_SIZE_THRESHOLD_KB, 4 * 1024 * 1024L) * 1024;
            this.flushLatencyDrainThreshold = Integer.getInteger(
                    FLUSH_LATENCY_DRAIN_THRESHOLD, 200);
            this.learnerQueueSizeDrainThreshold = Integer.getInteger(
                    LEARNER_QUEUE_SIZE_DRAIN_THRESHOLD, 10000);
            this.unscheduledSnapThreshold = Integer.getInteger(UNSCHEDULED_SNAP_THRESHOLD, 3);

            LOG.info("{} = {}, {} = {} KB, {} = {}, {} = {}, {} = {}",
                SNAP_TXNS_THRESHOLD, snapTxnsThreshold,
                SNAP_TXNS_SIZE_THRESHOLD_KB, snapTxnsSizeThresholdBytes,
                FLUSH_LATENCY_DRAIN_THRESHOLD, flushLatencyDrainThreshold,
                LEARNER_QUEUE_SIZE_DRAIN_THRESHOLD, learnerQueueSizeDrainThreshold,
                UNSCHEDULED_SNAP_THRESHOLD, unscheduledSnapThreshold);
        }

        private boolean snapsThresholdExceeded(int maxLearnerTxnsSinceLastSnap, long maxLearnerTxnsSizeSinceLastSnap) {
            return maxLearnerTxnsSinceLastSnap > snapTxnsThreshold || ((snapTxnsSizeThresholdBytes > 0)
                    && maxLearnerTxnsSizeSinceLastSnap > snapTxnsSizeThresholdBytes);
        }

        public void processData() {
            // Find out the total number of learners not taking snapshot.
            int numOfMembersNotInSnap = 0;
            int numOfMembersDraining = 0;
            int maxLearnerTxnsSinceLastSnap = 0;
            int numSnapsInProgress = 0;
            long maxLearnerTxnsSizeSinceLastSnap = 0;
            long learnerWithMaxTxnsNotInSnap = -1;
            for (SnapPingData f : data) {
                // Given the order of snapInProgress and txnsSinceLastSnap
                // being assigned in LearnerHandler with received SNAPPING,
                // and given how we're using it here, there is no need to
                // do the atomic update and check for these.
                long sid = f.getSid();
                if (!currentVotingMembers.containsKey(sid)) {
                    continue;
                }
                if (f.isSnapInProgress()) {
                    numSnapsInProgress++;
                    continue;
                }
                numOfMembersNotInSnap++;
                int txns = f.getTxnsSinceLastSnap();
                long txnsSize = f.getTxnsSizeSinceLastSnap();
                if (txns > maxLearnerTxnsSinceLastSnap
                        && txnsSize > maxLearnerTxnsSizeSinceLastSnap) {
                    maxLearnerTxnsSinceLastSnap = txns;
                    maxLearnerTxnsSizeSinceLastSnap = txnsSize;
                    learnerWithMaxTxnsNotInSnap = sid;
                }
                long lastRequestFlushLatency = f.getLastRequestFlushLatency();
                if (lastRequestFlushLatency > flushLatencyDrainThreshold) {
                    numOfMembersDraining++;
                    LOG.info("{} has long flush delay {}, mark it as draining",
                            sid, lastRequestFlushLatency);
                } else if (f.getQueuedRequestsSize() > learnerQueueSizeDrainThreshold) {
                    numOfMembersDraining++;
                    LOG.info("{} has long queue {}, mark it as draining",
                            sid, f.getQueuedRequestsSize());
                } else if (f.getLearnerHandlerQueueSize() > learnerQueueSizeDrainThreshold) {
                    numOfMembersDraining++;
                    LOG.info("{} has long learner queue {}, mark it as draining",
                            sid, f.getLearnerHandlerQueueSize());
                }
            }

            // Doesn't distinguish the server weight in the quorum hierarchical model
            // for now.
            int majority = currentVotingMembers.size() / 2 + 1;
            int numOfCandidates = numOfMembersNotInSnap - majority - numOfMembersDraining;

            long learnerSnapCandidate = -1;
            if (snapsThresholdExceeded(maxLearnerTxnsSinceLastSnap, maxLearnerTxnsSizeSinceLastSnap)) {
                // Schedule a snapshot when there is an available candidate OR when we have a majority running, but
                // haven't been able to schedule a snapshot in a while due to a lack of candidates. This second
                // condition should protect against all workers scheduling safety snapshots when running in a degraded
                // state.
                if (numOfCandidates > 0 || (numOfMembersNotInSnap >= majority && numSnapsInProgress == 0
                        && unscheduledSnapThreshold > -1 && ++idleSnapPings >= unscheduledSnapThreshold)) {
                    learnerSnapCandidate = learnerWithMaxTxnsNotInSnap;
                    idleSnapPings = 0;
                    LOG.info("Going to tell {} to take snapshot, txns since " + "last snapshot is {}, {}",
                            learnerSnapCandidate, maxLearnerTxnsSinceLastSnap, maxLearnerTxnsSizeSinceLastSnap);
                    if (numOfCandidates <= 0) {
                        ServerMetrics.getMetrics().MANAGER_INITIATED_SAFE_SNAPSHOT.add(1);
                    }
                }
            }

            // Send SNAPPING to all current participants, non-voting
            // members are not considered here, those non-voting ones
            // will take snapshot periodically as before.
            List<SnapPingListener> listeners = leader.getSnapPingListeners();
            for (SnapPingListener listener : listeners) {
                long sid = listener.getSid();
                if (currentVotingMembers.containsKey(sid)) {
                    try {
                        listener.snapPing(SnapPingListener.SNAP_PING_ID_DONT_CARE,
                                sid == learnerSnapCandidate
                                ? SnapPingCode.SNAP : SnapPingCode.SKIP);
                    } catch (IOException e) {
                        LOG.error("Exception when sending SNAPPING "
                                + "to {}, {}", sid, e);
                    }
                }
            }

            // clear the processed data points
            data.clear();
        }

        public synchronized void processSnapPing(SnapPingData spData) {
            if (spData.getSnapPingId() > currentSnapPingId) {
                if (!data.isEmpty()) {
                    processData();
                }
                currentSnapPingId = spData.snapPingId;
            }
            data.add(spData);

            currentVotingMembers = leader.getQuorumVerifier().getVotingMembers();
            votingMemberSize = currentVotingMembers.size();

            if (data.size() == votingMemberSize) {
                processData();
            }
        }
    }

    public static class SnapPingData {

        private long sid;
        private long snapPingId;
        private boolean snapInProgress;
        private int txnsSinceLastSnap;
        private long lastRequestFlushLatency;
        private int syncProcessorQueuedRequests;
        private long txnsSizeSinceLastSnap;
        private int learnerHandlerQueueSize;

        public SnapPingData(long sid, DataInputStream dataIS,
                int learnerHandlerQueueSize) throws IOException {
            this(sid, dataIS.readLong(), dataIS.readBoolean(),
                    dataIS.readInt(), dataIS.readLong(), dataIS.readInt(),
                    dataIS.readLong(), learnerHandlerQueueSize);
        }

        public SnapPingData(long sid, long snapPingId, boolean snapInProgress,
                int txnsSinceLastSnap, long lastRequestFlushLatency,
                int syncProcessorQueuedRequests, long txnsSizeSinceLastSnap,
                int learnerHandlerQueueSize) {
            this.sid = sid;
            this.snapPingId = snapPingId;
            this.snapInProgress = snapInProgress;
            this.txnsSinceLastSnap = txnsSinceLastSnap;
            this.lastRequestFlushLatency = lastRequestFlushLatency;
            this.syncProcessorQueuedRequests = syncProcessorQueuedRequests;
            this.txnsSizeSinceLastSnap = txnsSizeSinceLastSnap;
            this.learnerHandlerQueueSize = learnerHandlerQueueSize;
            LOG.info("{}: {}, snap in progress: {}, txns since last "
                    + "snap: {}, last request flush delay: {}, "
                    + "sync processor queued requests: {}, "
                    + "txn size in bytes since last snap: {}, "
                    + "learner handler queue size: {}", snapPingId, sid,
                    snapInProgress, txnsSinceLastSnap,
                    lastRequestFlushLatency, syncProcessorQueuedRequests,
                    txnsSizeSinceLastSnap, learnerHandlerQueueSize);
        }

        public long getSid() {
            return sid;
        }

        public long getSnapPingId() {
            return snapPingId;
        }

        public boolean isSnapInProgress() {
            return snapInProgress;
        }

        public int getTxnsSinceLastSnap() {
            return txnsSinceLastSnap;
        }

        public long getLastRequestFlushLatency() {
            return lastRequestFlushLatency;
        }

        public int getQueuedRequestsSize() {
            return syncProcessorQueuedRequests;
        }

        public long getTxnsSizeSinceLastSnap() {
            return txnsSizeSinceLastSnap;
        }

        public int getLearnerHandlerQueueSize() {
            return learnerHandlerQueueSize;
        }
    }
}
