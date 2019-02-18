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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.TxnLogProposalIterator;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.Leader.Proposal;
import org.apache.zookeeper.server.util.ZxidUtils;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Matchers;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LearnerHandlerTest extends ZKTestCase {
    protected static final Logger LOG = LoggerFactory
            .getLogger(LearnerHandlerTest.class);

    class MockLearnerHandler extends LearnerHandler {
        boolean threadStarted = false;

        MockLearnerHandler(Socket sock, Leader leader) throws IOException {
            super(sock, new BufferedInputStream(sock.getInputStream()), leader);
        }

        protected void startSendingPackets() {
            threadStarted = true;
        }
    }

    class MockZKDatabase extends ZKDatabase {
        long lastProcessedZxid;
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        LinkedList<Proposal> committedLog = new LinkedList<Leader.Proposal>();
        LinkedList<Proposal> txnLog = new LinkedList<Leader.Proposal>();

        public MockZKDatabase(FileTxnSnapLog snapLog) {
            super(snapLog);
        }

        public long getDataTreeLastProcessedZxid() {
            return lastProcessedZxid;
        }

        public long getmaxCommittedLog() {
            if (!committedLog.isEmpty()) {
                return committedLog.getLast().packet.getZxid();
            }
            return 0;
        }

        public long getminCommittedLog() {
            if (!committedLog.isEmpty()) {
                return committedLog.getFirst().packet.getZxid();
            }
            return 0;
        }

        public List<Proposal> getCommittedLog() {
            return committedLog;
        }

        public ReentrantReadWriteLock getLogLock() {
            return lock;
        }

        public Iterator<Proposal> getProposalsFromTxnLog(long peerZxid,
                long limit) {
            if (peerZxid >= txnLog.peekFirst().packet.getZxid()) {
                return txnLog.iterator();
            } else {
                return (new LinkedList<Proposal>()).iterator();
            }

        }

        public long calculateTxnLogSizeLimit() {
            return 1;
        }
    }

    private MockLearnerHandler learnerHandler;
    private Socket sock;

    // Member variables for mocking Leader
    private Leader leader;
    private long currentZxid;

    // Member variables for mocking ZkDatabase
    private MockZKDatabase db;

    @Before
    public void setUp() throws Exception {
        db = new MockZKDatabase(null);
        sock = mock(Socket.class);

        // Intercept when startForwarding is called
        leader = mock(Leader.class);
        when(
                leader.startForwarding(Matchers.any(LearnerHandler.class),
                        Matchers.anyLong())).thenAnswer(new Answer() {
            public Object answer(InvocationOnMock invocation) {
                currentZxid = (Long) invocation.getArguments()[1];
                return 0;
            }
        });
        when(leader.getZKDatabase()).thenReturn(db);

        learnerHandler = new MockLearnerHandler(sock, leader);
    }

    Proposal createProposal(long zxid) {
        Proposal p = new Proposal();
        p.packet = new QuorumPacket();
        p.packet.setZxid(zxid);
        p.packet.setType(Leader.PROPOSAL);
        return p;
    }

    /**
     * Validate that queued packets contains proposal in the following orders as
     * a given array of zxids
     *
     * @param zxids
     */
    public void queuedPacketMatches(long[] zxids) {
        int index = 0;
        for (QuorumPacket qp : learnerHandler.getQueuedPackets()) {
            if (qp.getType() == Leader.PROPOSAL) {
                assertZxidEquals(zxids[index++], qp.getZxid());
            }
        }
    }

    void reset() {
        learnerHandler.getQueuedPackets().clear();
        learnerHandler.threadStarted = false;
        learnerHandler.setFirstPacket(true);
    }

    /**
     * Check if op packet (first packet in the queue) match the expected value
     * @param type - type of packet
     * @param zxid - zxid in the op packet
     * @param currentZxid - last packet queued by syncFollower,
     *                      before invoking startForwarding()
     */
    public void assertOpType(int type, long zxid, long currentZxid) {
        Queue<QuorumPacket> packets = learnerHandler.getQueuedPackets();
        assertTrue(packets.size() > 0);
        assertEquals(type, packets.peek().getType());
        assertZxidEquals(zxid, packets.peek().getZxid());
        assertZxidEquals(currentZxid, this.currentZxid);
    }

    void assertZxidEquals(long expected, long value) {
        assertEquals("Expected 0x" + Long.toHexString(expected) + " but was 0x"
                + Long.toHexString(value), expected, value);
    }

    /**
     * Test cases when leader has empty commitedLog
     */
    @Test
    public void testEmptyCommittedLog() throws Exception {
        long peerZxid;

        // Peer has newer zxid
        peerZxid = 3;
        db.lastProcessedZxid = 1;
        db.committedLog.clear();
        assertFalse(learnerHandler.syncFollower(peerZxid, leader));
        // We send TRUNC and forward any packet starting lastProcessedZxid
        assertOpType(Leader.TRUNC, db.lastProcessedZxid, db.lastProcessedZxid);
        reset();

        // Peer is already sync
        peerZxid = 1;
        db.lastProcessedZxid = 1;
        db.committedLog.clear();
        assertFalse(learnerHandler.syncFollower(peerZxid, leader));
        // We send DIFF and forward any packet starting lastProcessedZxid
        assertOpType(Leader.DIFF, db.lastProcessedZxid, db.lastProcessedZxid);
        assertEquals(1, learnerHandler.getQueuedPackets().size());
        reset();

        // Peer has 0 zxid (new machine turn up), txnlog
        // is disabled
        peerZxid = 0;
        db.setSnapshotSizeFactor(-1);
        db.lastProcessedZxid = 1;
        db.committedLog.clear();
        // We send SNAP
        assertTrue(learnerHandler.syncFollower(peerZxid, leader));
        assertEquals(0, learnerHandler.getQueuedPackets().size());
        reset();

    }

    /**
     * Test cases when leader has committedLog
     */
    @Test
    public void testCommittedLog() throws Exception {
        long peerZxid;

        // Commit proposal may lag behind data tree, but it shouldn't affect
        // us in any case
        db.lastProcessedZxid = 6;
        db.committedLog.add(createProposal(2));
        db.committedLog.add(createProposal(3));
        db.committedLog.add(createProposal(5));

        // Peer has zxid that we have never seen
        peerZxid = 4;
        assertFalse(learnerHandler.syncFollower(peerZxid, leader));
        // We send TRUNC to 3 and forward any packet starting 5
        assertOpType(Leader.TRUNC, 3, 5);
        // DIFF + 1 proposals + 1 commit
        assertEquals(3, learnerHandler.getQueuedPackets().size());
        queuedPacketMatches(new long[] { 5 });
        reset();

        // Peer is within committedLog range
        peerZxid = 2;
        assertFalse(learnerHandler.syncFollower(peerZxid, leader));
        // We send DIFF and forward any packet starting lastProcessedZxid
        assertOpType(Leader.DIFF, db.getmaxCommittedLog(),
                db.getmaxCommittedLog());
        // DIFF + 2 proposals + 2 commit
        assertEquals(5, learnerHandler.getQueuedPackets().size());
        queuedPacketMatches(new long[] { 3, 5 });
        reset();

        // Peer miss the committedLog and txnlog is disabled
        peerZxid = 1;
        db.setSnapshotSizeFactor(-1);
        // We send SNAP
        assertTrue(learnerHandler.syncFollower(peerZxid, leader));
        assertEquals(0, learnerHandler.getQueuedPackets().size());
        reset();
    }

    /**
     * Test cases when txnlog is enabled
     */
    @Test
    public void testTxnLog() throws Exception {
        long peerZxid;
        db.txnLog.add(createProposal(2));
        db.txnLog.add(createProposal(3));
        db.txnLog.add(createProposal(5));
        db.txnLog.add(createProposal(6));
        db.txnLog.add(createProposal(7));
        db.txnLog.add(createProposal(8));
        db.txnLog.add(createProposal(9));

        db.lastProcessedZxid = 9;
        db.committedLog.add(createProposal(6));
        db.committedLog.add(createProposal(7));
        db.committedLog.add(createProposal(8));

        // Peer has zxid that we have never seen
        peerZxid = 4;
        assertFalse(learnerHandler.syncFollower(peerZxid, leader));
        // We send TRUNC to 3 and forward any packet starting at maxCommittedLog
        assertOpType(Leader.TRUNC, 3, db.getmaxCommittedLog());
        // DIFF + 4 proposals + 4 commit
        assertEquals(9, learnerHandler.getQueuedPackets().size());
        queuedPacketMatches(new long[] { 5, 6, 7, 8 });
        reset();

        // Peer zxid is in txnlog range
        peerZxid = 3;
        assertFalse(learnerHandler.syncFollower(peerZxid, leader));
        // We send DIFF and forward any packet starting at maxCommittedLog
        assertOpType(Leader.DIFF, db.getmaxCommittedLog(),
                db.getmaxCommittedLog());
        // DIFF + 4 proposals + 4 commit
        assertEquals(9, learnerHandler.getQueuedPackets().size());
        queuedPacketMatches(new long[] { 5, 6, 7, 8 });
        reset();

    }

    /**
     * Test case verifying TxnLogProposalIterator closure.
     */
    @Test
    public void testTxnLogProposalIteratorClosure() throws Exception {
        long peerZxid;

        // CommmitedLog is empty, we will use txnlog up to lastProcessZxid
        db = new MockZKDatabase(null) {
            @Override
            public Iterator<Proposal> getProposalsFromTxnLog(long peerZxid,
                    long limit) {
                return TxnLogProposalIterator.EMPTY_ITERATOR;
            }
        };
        db.lastProcessedZxid = 7;
        db.txnLog.add(createProposal(2));
        db.txnLog.add(createProposal(3));
        when(leader.getZKDatabase()).thenReturn(db);

        // Peer zxid
        peerZxid = 4;
        assertTrue("Couldn't identify snapshot transfer!",
                learnerHandler.syncFollower(peerZxid, leader));
        reset();
    }

    /**
     * Test cases when txnlog is enabled and commitedLog is empty
     */
    @Test
    public void testTxnLogOnly() throws Exception {
        long peerZxid;

        // CommmitedLog is empty, we will use txnlog up to lastProcessZxid
        db.lastProcessedZxid = 7;
        db.txnLog.add(createProposal(2));
        db.txnLog.add(createProposal(3));
        db.txnLog.add(createProposal(5));
        db.txnLog.add(createProposal(6));
        db.txnLog.add(createProposal(7));
        db.txnLog.add(createProposal(8));

        // Peer has zxid that we have never seen
        peerZxid = 4;
        assertFalse(learnerHandler.syncFollower(peerZxid, leader));
        // We send TRUNC to 3 and forward any packet starting at
        // lastProcessedZxid
        assertOpType(Leader.TRUNC, 3, db.lastProcessedZxid);
        // DIFF + 3 proposals + 3 commit
        assertEquals(7, learnerHandler.getQueuedPackets().size());
        queuedPacketMatches(new long[] { 5, 6, 7 });
        reset();

        // Peer has zxid in txnlog range
        peerZxid = 2;
        assertFalse(learnerHandler.syncFollower(peerZxid, leader));
        // We send DIFF and forward any packet starting at lastProcessedZxid
        assertOpType(Leader.DIFF, db.lastProcessedZxid, db.lastProcessedZxid);
        // DIFF + 4 proposals + 4 commit
        assertEquals(9, learnerHandler.getQueuedPackets().size());
        queuedPacketMatches(new long[] { 3, 5, 6, 7 });
        reset();

        // Peer miss the txnlog
        peerZxid = 1;
        assertTrue(learnerHandler.syncFollower(peerZxid, leader));
        // We send snap
        assertEquals(0, learnerHandler.getQueuedPackets().size());
        reset();
    }

    long getZxid(long epoch, long counter){
        return ZxidUtils.makeZxid(epoch, counter);
    }

    /**
     * Test cases with zxids that are negative long
     */
    @Test
    public void testTxnLogWithNegativeZxid() throws Exception {
        long peerZxid;
        db.txnLog.add(createProposal(getZxid(0xf, 2)));
        db.txnLog.add(createProposal(getZxid(0xf, 3)));
        db.txnLog.add(createProposal(getZxid(0xf, 5)));
        db.txnLog.add(createProposal(getZxid(0xf, 6)));
        db.txnLog.add(createProposal(getZxid(0xf, 7)));
        db.txnLog.add(createProposal(getZxid(0xf, 8)));
        db.txnLog.add(createProposal(getZxid(0xf, 9)));

        db.lastProcessedZxid = getZxid(0xf, 9);
        db.committedLog.add(createProposal(getZxid(0xf, 6)));
        db.committedLog.add(createProposal(getZxid(0xf, 7)));
        db.committedLog.add(createProposal(getZxid(0xf, 8)));

        // Peer has zxid that we have never seen
        peerZxid = getZxid(0xf, 4);
        assertFalse(learnerHandler.syncFollower(peerZxid, leader));
        // We send TRUNC to 3 and forward any packet starting at maxCommittedLog
        assertOpType(Leader.TRUNC, getZxid(0xf, 3), db.getmaxCommittedLog());
        // DIFF + 4 proposals + 4 commit
        assertEquals(9, learnerHandler.getQueuedPackets().size());
        queuedPacketMatches(new long[] { getZxid(0xf, 5),
                getZxid(0xf, 6), getZxid(0xf, 7), getZxid(0xf, 8) });
        reset();

        // Peer zxid is in txnlog range
        peerZxid = getZxid(0xf, 3);
        assertFalse(learnerHandler.syncFollower(peerZxid, leader));
        // We send DIFF and forward any packet starting at maxCommittedLog
        assertOpType(Leader.DIFF, db.getmaxCommittedLog(),
                db.getmaxCommittedLog());
        // DIFF + 4 proposals + 4 commit
        assertEquals(9, learnerHandler.getQueuedPackets().size());
        queuedPacketMatches(new long[] { getZxid(0xf, 5),
                getZxid(0xf, 6), getZxid(0xf, 7), getZxid(0xf, 8) });
        reset();
    }

    /**
     * Test cases when peer has new-epoch zxid
     */
    @Test
    public void testNewEpochZxid() throws Exception {
        long peerZxid;
        db.txnLog.add(createProposal(getZxid(0, 1)));
        db.txnLog.add(createProposal(getZxid(1, 1)));
        db.txnLog.add(createProposal(getZxid(1, 2)));

        // After leader election, lastProcessedZxid will point to new epoch
        db.lastProcessedZxid = getZxid(2, 0);
        db.committedLog.add(createProposal(getZxid(1, 1)));
        db.committedLog.add(createProposal(getZxid(1, 2)));

        // Peer has zxid of epoch 0
        peerZxid = getZxid(0, 0);
        // We should get snap, we can do better here, but the main logic is
        // that we should never send diff if we have never seen any txn older
        // than peer zxid
        assertTrue(learnerHandler.syncFollower(peerZxid, leader));
        assertEquals(0, learnerHandler.getQueuedPackets().size());
        reset();

        // Peer has zxid of epoch 1
        peerZxid = getZxid(1, 0);
        assertFalse(learnerHandler.syncFollower(peerZxid, leader));
        // We send DIFF to (1, 2) and forward any packet starting at (1, 2)
        assertOpType(Leader.DIFF, getZxid(1, 2), getZxid(1, 2));
        // DIFF + 2 proposals + 2 commit
        assertEquals(5, learnerHandler.getQueuedPackets().size());
        queuedPacketMatches(new long[] { getZxid(1, 1), getZxid(1, 2)});
        reset();

        // Peer has zxid of epoch 2, so it is already sync
        peerZxid = getZxid(2, 0);
        assertFalse(learnerHandler.syncFollower(peerZxid, leader));
        // We send DIFF to (2, 0) and forward any packet starting at (2, 0)
        assertOpType(Leader.DIFF, getZxid(2, 0), getZxid(2, 0));
        // DIFF only
        assertEquals(1, learnerHandler.getQueuedPackets().size());
        reset();

    }

    /**
     * Test cases when learner has new-epcoh zxid
     * (zxid & 0xffffffffL) == 0;
     */
    @Test
    public void testNewEpochZxidWithTxnlogOnly() throws Exception {
        long peerZxid;
        db.txnLog.add(createProposal(getZxid(1, 1)));
        db.txnLog.add(createProposal(getZxid(2, 1)));
        db.txnLog.add(createProposal(getZxid(2, 2)));
        db.txnLog.add(createProposal(getZxid(4, 1)));

        // After leader election, lastProcessedZxid will point to new epoch
        db.lastProcessedZxid = getZxid(6, 0);

        // Peer has zxid of epoch 3
        peerZxid = getZxid(3, 0);
        assertFalse(learnerHandler.syncFollower(peerZxid, leader));
        // We send DIFF to (6,0) and forward any packet starting at (4,1)
        assertOpType(Leader.DIFF, getZxid(6, 0), getZxid(4, 1));
        // DIFF + 1 proposals + 1 commit
        assertEquals(3, learnerHandler.getQueuedPackets().size());
        queuedPacketMatches(new long[] { getZxid(4, 1)});
        reset();

        // Peer has zxid of epoch 4
        peerZxid = getZxid(4, 0);
        assertFalse(learnerHandler.syncFollower(peerZxid, leader));
        // We send DIFF to (6,0) and forward any packet starting at (4,1)
        assertOpType(Leader.DIFF, getZxid(6, 0), getZxid(4, 1));
        // DIFF + 1 proposals + 1 commit
        assertEquals(3, learnerHandler.getQueuedPackets().size());
        queuedPacketMatches(new long[] { getZxid(4, 1)});
        reset();

        // Peer has zxid of epoch 5
        peerZxid = getZxid(5, 0);
        assertFalse(learnerHandler.syncFollower(peerZxid, leader));
        // We send DIFF to (6,0) and forward any packet starting at (5,0)
        assertOpType(Leader.DIFF, getZxid(6, 0), getZxid(5, 0));
        // DIFF only
        assertEquals(1, learnerHandler.getQueuedPackets().size());
        reset();

        // Peer has zxid of epoch 6
        peerZxid = getZxid(6, 0);
        assertFalse(learnerHandler.syncFollower(peerZxid, leader));
        // We send DIFF to (6,0) and forward any packet starting at (6, 0)
        assertOpType(Leader.DIFF, getZxid(6, 0), getZxid(6, 0));
        // DIFF only
        assertEquals(1, learnerHandler.getQueuedPackets().size());
        reset();
    }

    /**
     * Test cases when there is a duplicate txn in the committedLog. This
     * should never happen unless there is a bug in initialization code
     * but the learner should never see duplicate packets
     */
    @Test
    public void testDuplicatedTxn() throws Exception {
        long peerZxid;
        db.txnLog.add(createProposal(getZxid(0, 1)));
        db.txnLog.add(createProposal(getZxid(1, 1)));
        db.txnLog.add(createProposal(getZxid(1, 2)));
        db.txnLog.add(createProposal(getZxid(1, 1)));
        db.txnLog.add(createProposal(getZxid(1, 2)));

        // After leader election, lastProcessedZxid will point to new epoch
        db.lastProcessedZxid = getZxid(2, 0);
        db.committedLog.add(createProposal(getZxid(1, 1)));
        db.committedLog.add(createProposal(getZxid(1, 2)));
        db.committedLog.add(createProposal(getZxid(1, 1)));
        db.committedLog.add(createProposal(getZxid(1, 2)));

        // Peer has zxid of epoch 1
        peerZxid = getZxid(1, 0);
        assertFalse(learnerHandler.syncFollower(peerZxid, leader));
        // We send DIFF to (1, 2) and forward any packet starting at (1, 2)
        assertOpType(Leader.DIFF, getZxid(1, 2), getZxid(1, 2));
        // DIFF + 2 proposals + 2 commit
        assertEquals(5, learnerHandler.getQueuedPackets().size());
        queuedPacketMatches(new long[] { getZxid(1, 1), getZxid(1, 2)});
        reset();

    }

    /**
     * Test cases when we have to TRUNC learner, but it may cross epoch boundary
     * so we need to send snap instead
     */
    @Test
    public void testCrossEpochTrunc() throws Exception {
        long peerZxid;
        db.txnLog.add(createProposal(getZxid(1, 1)));
        db.txnLog.add(createProposal(getZxid(2, 1)));
        db.txnLog.add(createProposal(getZxid(2, 2)));
        db.txnLog.add(createProposal(getZxid(4, 1)));

        // After leader election, lastProcessedZxid will point to new epoch
        db.lastProcessedZxid = getZxid(6, 0);

        // Peer has zxid (3, 1)
        peerZxid = getZxid(3, 1);
        assertTrue(learnerHandler.syncFollower(peerZxid, leader));
        assertEquals(0, learnerHandler.getQueuedPackets().size());
        reset();
    }
}
