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

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs.SnapPingCode;
import org.apache.zookeeper.server.SnapshotGenerator;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.SnapPingManager.SnapPingData;
import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SnapPingTest extends ZKTestCase {

    private ZKDatabase zkDb;
    private Leader leader;
    private SnapshotGenerator leaderSnapGenerator;
    private static final long TXNS_SIZE_THRESHOLD = 4000 * 1024 * 1024L;

    @Before
    public void setup() throws Exception {
        System.setProperty(
                SnapPingManager.SnapPingHandler.SNAP_TXNS_SIZE_THRESHOLD_KB,
                "" + (TXNS_SIZE_THRESHOLD / 1024));
        // create a 5 members voting quorum
        Map<Long, QuorumServer> votingMembers = new HashMap<Long, QuorumServer>();
        for (long i = 1L; i <= 5L; i++) {
            votingMembers.put(i, null);
        }
        QuorumPeer qp = new QuorumPeer();
        QuorumVerifier quorumVerifierMock = mock(QuorumVerifier.class);
        when(quorumVerifierMock.getVotingMembers()).thenReturn(votingMembers);
        when(quorumVerifierMock.getAllMembers()).thenReturn(LeaderBeanTest.getMockedPeerViews(qp.getId()));
        qp.setQuorumVerifier(quorumVerifierMock, false);
        qp.setId(5L);

        zkDb = mock(ZKDatabase.class);
        File tmpDir = ClientBase.createEmptyTestDir();
        LeaderZooKeeperServer lzs = new LeaderZooKeeperServer(
                new FileTxnSnapLog(tmpDir, tmpDir), qp, zkDb);
        leaderSnapGenerator = mock(SnapshotGenerator.class);
        lzs.setSnapshotGenerator(leaderSnapGenerator);
        leader = new Leader(qp, lzs);

        leader.addForwardingFollower(genLearnerHandler(1L));
        leader.addForwardingFollower(genLearnerHandler(2L));
        leader.addForwardingFollower(genLearnerHandler(3L));
        leader.addForwardingFollower(genLearnerHandler(4L));
    }

    /**
     * Verify when leader has the maximum txns since last snapshot, it will
     * take snapshot. Non voting followers are being ignored.
     */
    @Test
    public void testLeaderSnapSchedule() throws Exception {
        // add a non-voting follower
        leader.addForwardingFollower(genLearnerHandler(8L));

        leader.processSnapPing(new SnapPingData(1L, 1L, false, 1000000, 0, 0, 2000000, 0));
        leader.processSnapPing(new SnapPingData(2L, 1L, false, 2000000, 0, 0, 4000000, 0));
        leader.processSnapPing(new SnapPingData(3L, 1L, false, 3000000, 0, 0, 6000000, 0));
        leader.processSnapPing(new SnapPingData(4L, 1L, false, 4000000, 0, 0, 8000000, 0));
        leader.processSnapPing(new SnapPingData(5L, 1L, false, 5000000, 0, 0, 10000000, 0));

        // take snapshot on leader since it has the most txns since last snap
        verify(leaderSnapGenerator, times(1)).takeSnapshot(true);

        for (SnapPingListener listener : leader.getForwardingFollowers()) {
            long sid = listener.getSid();
            if (sid == 8L) {
                verify(listener, never()).snapPing(anyLong(), any());
            } else {
                verify(listener, times(1)).snapPing(
                        SnapPingListener.SNAP_PING_ID_DONT_CARE, SnapPingCode.SKIP);
            }
        }
    }

    /**
     * Verify on a 5 members ensemble, there could be 2 concurrent snapshot.
     */
    @Test
    public void testSnapScheduleWithMultipleSnap() throws Exception {
        leader.processSnapPing(new SnapPingData(1L, 1L, false, 1000000, 0, 0, 2000000, 0));
        leader.processSnapPing(new SnapPingData(2L, 1L, false, 2000000, 0, 0, 4000000, 0));
        leader.processSnapPing(new SnapPingData(3L, 1L, false, 3000000, 0, 0, 6000000, 0));
        leader.processSnapPing(new SnapPingData(4L, 1L, false, 4000000, 0, 0, 8000000, 0));
        leader.processSnapPing(new SnapPingData(5L, 1L, true, 5000000, 0, 0, 10000000, 0));

        // won't take snapshot on leader since it's already in snapshot
        verify(leaderSnapGenerator, never()).takeSnapshot(anyBoolean());

        for (LearnerHandler lh : leader.getForwardingFollowers()) {
            if (lh.getSid() != 4L) {
                verify(lh, times(1)).snapPing(
                        SnapPingListener.SNAP_PING_ID_DONT_CARE, SnapPingCode.SKIP);
            } else {
                verify(lh, times(1)).snapPing(
                        SnapPingListener.SNAP_PING_ID_DONT_CARE, SnapPingCode.SNAP);
            }
        }
    }

    /**
     * When there are already 2 concurrent snapshot in 5 members ensemble,
     * no more snapshot could be scheduled.
     */
    @Test
    public void testMaxConcurrentSnapSchedule() throws Exception {
        leader.processSnapPing(new SnapPingData(1L, 1L, false, 1000000, 0, 0, 2000000, 0));
        leader.processSnapPing(new SnapPingData(2L, 1L, false, 2000000, 0, 0, 4000000, 0));
        leader.processSnapPing(new SnapPingData(3L, 1L, false, 3000000, 0, 0, 6000000, 0));
        leader.processSnapPing(new SnapPingData(4L, 1L, true, 4000000, 0, 0, 8000000, 0));
        leader.processSnapPing(new SnapPingData(5L, 1L, true, 5000000, 0, 0, 10000000, 0));

        verify(leaderSnapGenerator, never()).takeSnapshot(anyBoolean());

        // skip snap on all servers
        for (LearnerHandler lh : leader.getForwardingFollowers()) {
            verify(lh, times(1)).snapPing(
                    SnapPingListener.SNAP_PING_ID_DONT_CARE, SnapPingCode.SKIP);
        }
    }

    /**
     * With 2 out of 5 servers have high sync latency, we'll skip SNAP on
     * all servers.
     */
    @Test
    public void testSnapScheduleWithHighFlushLatency() throws Exception {
        leader.processSnapPing(new SnapPingData(1L, 1L, false, 1000000, 1000, 0, 2000000, 0));
        leader.processSnapPing(new SnapPingData(2L, 1L, false, 2000000, 1000, 0, 4000000, 0));
        leader.processSnapPing(new SnapPingData(3L, 1L, false, 3000000, 0, 0, 6000000, 0));
        leader.processSnapPing(new SnapPingData(4L, 1L, false, 4000000, 0, 0, 8000000, 0));
        leader.processSnapPing(new SnapPingData(5L, 1L, false, 5000000, 0, 0, 10000000, 0));

        // won't take snapshot on leader since it's already in snapshot
        verify(leaderSnapGenerator, never()).takeSnapshot(anyBoolean());

        for (LearnerHandler lh : leader.getForwardingFollowers()) {
            verify(lh, times(1)).snapPing(
                    SnapPingListener.SNAP_PING_ID_DONT_CARE, SnapPingCode.SKIP);
        }
    }

    /**
     * With 2 out of 5 servers have large sync request queue size, we'll skip
     * SNAP on all servers.
     */
    @Test
    public void testSnapScheduleWithLargeLearnerQueueSize() throws Exception {
        leader.processSnapPing(new SnapPingData(1L, 1L, false, 1000000, 0, 100000, 2000000, 0));
        leader.processSnapPing(new SnapPingData(2L, 1L, false, 2000000, 0, 100000, 4000000, 0));
        leader.processSnapPing(new SnapPingData(3L, 1L, false, 3000000, 0, 0, 6000000, 0));
        leader.processSnapPing(new SnapPingData(4L, 1L, false, 4000000, 0, 0, 8000000, 0));
        leader.processSnapPing(new SnapPingData(5L, 1L, false, 5000000, 0, 0, 10000000, 0));

        // won't take snapshot on leader since it's already in snapshot
        verify(leaderSnapGenerator, never()).takeSnapshot(anyBoolean());

        for (LearnerHandler lh : leader.getForwardingFollowers()) {
            verify(lh, times(1)).snapPing(
                    SnapPingListener.SNAP_PING_ID_DONT_CARE, SnapPingCode.SKIP);
        }
    }

    /**
     * With 2 out of 5 servers have large learner queue size, we'll skip SNAP on
     * all servers.
     */
    @Test
    public void testSnapScheduleWithLargeSyncRequestQueueSize() throws Exception {
        leader.processSnapPing(new SnapPingData(1L, 1L, false, 1000000, 0, 0, 2000000, 1000000));
        leader.processSnapPing(new SnapPingData(2L, 1L, false, 2000000, 0, 0, 4000000, 1000000));
        leader.processSnapPing(new SnapPingData(3L, 1L, false, 3000000, 0, 0, 6000000, 0));
        leader.processSnapPing(new SnapPingData(4L, 1L, false, 4000000, 0, 0, 8000000, 0));
        leader.processSnapPing(new SnapPingData(5L, 1L, false, 5000000, 0, 0, 10000000, 0));

        // won't take snapshot on leader since it's already in snapshot
        verify(leaderSnapGenerator, never()).takeSnapshot(anyBoolean());

        for (LearnerHandler lh : leader.getForwardingFollowers()) {
            verify(lh, times(1)).snapPing(
                    SnapPingListener.SNAP_PING_ID_DONT_CARE, SnapPingCode.SKIP);
        }
    }

    /**
     * Test the logic of processing snapPing on follower.
     */
    @Test
    public void testSnapPingPacketOnFollower() throws Exception {
        QuorumPeer qp = new QuorumPeer();
        QuorumVerifier quorumVerifierMock = mock(QuorumVerifier.class);
        qp.setQuorumVerifier(quorumVerifierMock, false);
        File tmpDir = ClientBase.createEmptyTestDir();
        FileTxnSnapLog fileTxnSnapLog = new FileTxnSnapLog(tmpDir, tmpDir);
        ZKDatabase zkDb = new ZKDatabase(fileTxnSnapLog);

        final AtomicInteger snapshotDurationInSeconds = new AtomicInteger(0);
        FollowerZooKeeperServer fzk = new FollowerZooKeeperServer(
                fileTxnSnapLog, qp, zkDb) {
            @Override
            public void takeSnapshot(boolean syncSnap) {
                try {
                    Thread.sleep(snapshotDurationInSeconds.get() * 1000);
                } catch (InterruptedException e) {}
            }
        };
        fzk.setupRequestProcessors();
        Assert.assertFalse(fzk.syncProcessor.isOnlySnapWhenSafetyIsThreatened());

        LinkedBlockingQueue<QuorumPacket> snapPacketsSent =
              new LinkedBlockingQueue<QuorumPacket>();

        Follower follower = new Follower(qp, fzk) {
            @Override
            public void writePacket(QuorumPacket pp, boolean flush) {
                if (pp.getType() == Leader.SNAPPING) {
                    try {
                        snapPacketsSent.put(pp);
                    } catch (InterruptedException e) { /* ignore */ }
                }
            }
        };

        snapshotDurationInSeconds.set(1);

        // Send a SNAP message
        follower.snapPing(buildSnapPingPacket(SnapPingCode.SNAP));
        Assert.assertTrue(fzk.syncProcessor.isOnlySnapWhenSafetyIsThreatened());

        // Send a CHECK message
        follower.snapPing(buildSnapPingPacket(SnapPingCode.CHECK));
        QuorumPacket packetSent = snapPacketsSent.poll(1, TimeUnit.SECONDS);
        Assert.assertNotNull(packetSent);

        ByteArrayInputStream bais = new ByteArrayInputStream(packetSent.getData());
        DataInputStream dataIS = new DataInputStream(bais);
        long snapPingId = dataIS.readLong();
        boolean snapInProgress = dataIS.readBoolean();
        int txnsSinceLastSnap = dataIS.readInt();
        long lastRequestFlushLatency = dataIS.readLong();
        long syncProcessorQueueSize = dataIS.readInt();
        Assert.assertEquals(snapPingId, 1);
        Assert.assertTrue(snapInProgress);
        Assert.assertEquals(0, txnsSinceLastSnap);
        Assert.assertEquals(0, lastRequestFlushLatency);
        Assert.assertEquals(0, syncProcessorQueueSize);

        // Send a CANCEL message and make sure we enabled self SNAP again
        follower.snapPing(buildSnapPingPacket(SnapPingCode.CANCEL));
        Assert.assertFalse(fzk.syncProcessor.isOnlySnapWhenSafetyIsThreatened());
    }

    /**
     * Make sure the packet will be ignored if the version is different.
     */
    @Test
    public void testDifferentVersion() throws Exception {
        QuorumPeer qp = new QuorumPeer();
        QuorumVerifier quorumVerifierMock = mock(QuorumVerifier.class);
        qp.setQuorumVerifier(quorumVerifierMock, false);
        File tmpDir = ClientBase.createEmptyTestDir();
        FileTxnSnapLog fileTxnSnapLog = new FileTxnSnapLog(tmpDir, tmpDir);
        ZKDatabase zkDb = new ZKDatabase(fileTxnSnapLog);
        FollowerZooKeeperServer fzk = new FollowerZooKeeperServer(
                fileTxnSnapLog, qp, zkDb);
        fzk.setupRequestProcessors();
        Follower follower = new Follower(qp, fzk);

        Assert.assertFalse(fzk.syncProcessor.isOnlySnapWhenSafetyIsThreatened());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream oa = new DataOutputStream(baos);
        oa.writeInt(SnapPingManager.SNAP_PING_VERSION + 1);
        oa.writeLong(1L);
        oa.writeInt(SnapPingCode.CHECK.ordinal());
        oa.close();

        follower.snapPing(new QuorumPacket(Leader.SNAPPING, -1, baos.toByteArray(), null));
        // Make sure we don't enable safety snapshot when snap ping version
        // is different
        Assert.assertFalse(fzk.syncProcessor.isOnlySnapWhenSafetyIsThreatened());
    }

    @Test
    public void testCancelSnapPingSchedule() throws Exception {
        // Disable the snapPing manager via set the interval to -1
        leader.snapPingManager.setSnapPingIntervalInSeconds(-1);

        for (LearnerHandler lh : leader.getForwardingFollowers()) {
            verify(lh, times(1)).snapPing(
                    SnapPingListener.SNAP_PING_ID_DONT_CARE, SnapPingCode.CANCEL);
        }
    }

    /**
     * Expect to trigger snap schedule even we didn't receive enough responses
     * if we received a higher SnapPing id message.
     */
    @Test
    public void testNotReceiveAllSnapPingResponse() throws Exception {
        leader.processSnapPing(new SnapPingData(1L, 1L, false, 1000000, 0, 0, 2000000, 0));
        leader.processSnapPing(new SnapPingData(2L, 1L, false, 2000000, 0, 0, 4000000, 0));
        leader.processSnapPing(new SnapPingData(3L, 1L, false, 3000000, 0, 0, 6000000, 0));
        leader.processSnapPing(new SnapPingData(5L, 1L, false, 5000000, 0, 0, 10000000, 0));

        // Received a new SnapPing with higher SnapPing Id before we received
        // the all responses for the previous PING.
        leader.processSnapPing(new SnapPingData(1L, 2L, false, 3000000, 0, 0, 6000000, 0));

        // won't take snapshot on leader since it's already in snapshot
        verify(leaderSnapGenerator, times(1)).takeSnapshot(true);

        for (LearnerHandler lh : leader.getForwardingFollowers()) {
            verify(lh, times(1)).snapPing(
                    SnapPingListener.SNAP_PING_ID_DONT_CARE, SnapPingCode.SKIP);
        }
    }

    @Test
    public void testScheduleBasedOnTxnSize() throws Exception {
        leader.processSnapPing(new SnapPingData(1L, 1L, false, 1000, 0, 0, TXNS_SIZE_THRESHOLD / 4, 0));
        leader.processSnapPing(new SnapPingData(2L, 1L, false, 2000, 0, 0, TXNS_SIZE_THRESHOLD / 2, 0));
        leader.processSnapPing(new SnapPingData(3L, 1L, false, 3000, 0, 0, TXNS_SIZE_THRESHOLD * 3 / 4, 0));
        leader.processSnapPing(new SnapPingData(4L, 1L, false, 4000, 0, 0, TXNS_SIZE_THRESHOLD, 0));
        leader.processSnapPing(new SnapPingData(5L, 1L, false, 5000, 0, 0, TXNS_SIZE_THRESHOLD * 5 / 4, 0));

        // take snapshot on leader since it has the most txns size since last snap
        verify(leaderSnapGenerator, times(1)).takeSnapshot(true);

        for (SnapPingListener listener : leader.getForwardingFollowers()) {
            long sid = listener.getSid();
            verify(listener, times(1)).snapPing(
                    SnapPingListener.SNAP_PING_ID_DONT_CARE, SnapPingCode.SKIP);
        }
    }

    @Test
    public void testScheduleBasedOnTxnSizeSmallerThanThreshold() throws Exception {
        leader.processSnapPing(new SnapPingData(1L, 1L, false, 100, 0, 0, TXNS_SIZE_THRESHOLD / 40, 0));
        leader.processSnapPing(new SnapPingData(2L, 1L, false, 200, 0, 0, TXNS_SIZE_THRESHOLD / 20, 0));
        leader.processSnapPing(new SnapPingData(3L, 1L, false, 300, 0, 0, TXNS_SIZE_THRESHOLD * 3 / 40, 0));
        leader.processSnapPing(new SnapPingData(4L, 1L, false, 400, 0, 0, TXNS_SIZE_THRESHOLD / 10, 0));
        leader.processSnapPing(new SnapPingData(5L, 1L, false, 500, 0, 0, TXNS_SIZE_THRESHOLD * 5 / 40, 0));

        verify(leaderSnapGenerator, never()).takeSnapshot(anyBoolean());

        for (SnapPingListener listener : leader.getForwardingFollowers()) {
            long sid = listener.getSid();
            verify(listener, times(1)).snapPing(
                    SnapPingListener.SNAP_PING_ID_DONT_CARE, SnapPingCode.SKIP);
        }
    }

    /**
     * Schedule snapshot after several idle snap pings even if two followers are down.
     */
    @Test
    public void testScheduleBasedOnIdle() throws Exception {
        leader.processSnapPing(new SnapPingData(1L, 1L, false, 200000, 0, 0, TXNS_SIZE_THRESHOLD / 40, 0));
        leader.processSnapPing(new SnapPingData(2L, 1L, false, 200000, 0, 0, TXNS_SIZE_THRESHOLD / 20, 0));
        leader.processSnapPing(new SnapPingData(5L, 1L, false, 500000, 0, 0, TXNS_SIZE_THRESHOLD * 3, 0));
        leader.processSnapPing(new SnapPingData(1L, 2L, false, 200000, 0, 0, TXNS_SIZE_THRESHOLD / 40, 0));
        leader.processSnapPing(new SnapPingData(2L, 2L, false, 200000, 0, 0, TXNS_SIZE_THRESHOLD / 20, 0));
        leader.processSnapPing(new SnapPingData(5L, 2L, false, 500000, 0, 0, TXNS_SIZE_THRESHOLD * 3, 0));
        leader.processSnapPing(new SnapPingData(1L, 3L, false, 200000, 0, 0, TXNS_SIZE_THRESHOLD / 40, 0));
        leader.processSnapPing(new SnapPingData(2L, 3L, false, 200000, 0, 0, TXNS_SIZE_THRESHOLD / 20, 0));
        leader.processSnapPing(new SnapPingData(5L, 3L, false, 500000, 0, 0, TXNS_SIZE_THRESHOLD * 3, 0));

        verify(leaderSnapGenerator, never()).takeSnapshot(anyBoolean());

        // At this point, the number of idle pings should be equal to the threshold of 3, so the next ping should
        // result in a snapshot being scheduled.
        leader.processSnapPing(new SnapPingData(1L, 4L, false, 200000, 0, 0, TXNS_SIZE_THRESHOLD / 40, 0));

        verify(leaderSnapGenerator, times(1)).takeSnapshot(true);
        for (SnapPingListener listener : leader.getForwardingFollowers()) {
            long sid = listener.getSid();
            verify(listener, times(3)).snapPing(
                    SnapPingListener.SNAP_PING_ID_DONT_CARE, SnapPingCode.SKIP);
        }
    }

    private QuorumPacket buildSnapPingPacket(SnapPingCode code) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream oa = new DataOutputStream(baos);
        oa.writeInt(SnapPingManager.SNAP_PING_VERSION);
        oa.writeLong(1L);
        oa.writeInt(code.ordinal());
        oa.close();
        return new QuorumPacket(Leader.SNAPPING, -1, baos.toByteArray(), null);
    }

    private LearnerHandler genLearnerHandler(long sid) {
        LearnerHandler lh = mock(LearnerHandler.class);
        when(lh.getSid()).thenReturn(sid);
        return lh;
    }
}
