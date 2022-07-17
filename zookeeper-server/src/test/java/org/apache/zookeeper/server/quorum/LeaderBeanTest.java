/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.quorum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.common.X509Exception;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.QuorumPeer.LearnerType;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;
import org.apache.zookeeper.server.util.SerializeUtils;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.txn.Txn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class LeaderBeanTest {

    private Leader leader;
    private LeaderBean leaderBean;
    private FileTxnSnapLog fileTxnSnapLog;
    private LeaderZooKeeperServer zks;
    private QuorumPeer qp;
    private QuorumVerifier quorumVerifierMock;

    public static Map<Long, QuorumServer> getMockedPeerViews(long myId) {
        int clientPort = PortAssignment.unique();
        Map<Long, QuorumServer> peersView = new HashMap<>();
        InetAddress clientIP = InetAddress.getLoopbackAddress();

        peersView.put(myId,
                new QuorumServer(myId, new InetSocketAddress(clientIP, PortAssignment.unique()),
                        new InetSocketAddress(clientIP, PortAssignment.unique()),
                        new InetSocketAddress(clientIP, clientPort), LearnerType.PARTICIPANT));
        return peersView;
    }

    @BeforeEach
    public void setUp() throws IOException, X509Exception {
        qp = new QuorumPeer();
        quorumVerifierMock = mock(QuorumVerifier.class);
        when(quorumVerifierMock.getAllMembers()).thenReturn(getMockedPeerViews(qp.getId()));

        qp.setQuorumVerifier(quorumVerifierMock, false);
        File tmpDir = ClientBase.createEmptyTestDir();
        fileTxnSnapLog = new FileTxnSnapLog(new File(tmpDir, "data"), new File(tmpDir, "data_txnlog"));
        ZKDatabase zkDb = new ZKDatabase(fileTxnSnapLog);

        zks = new LeaderZooKeeperServer(fileTxnSnapLog, qp, zkDb);
        leader = new Leader(qp, zks);
        leaderBean = new LeaderBean(leader, zks);
    }

    @AfterEach
    public void tearDown() throws IOException {
        fileTxnSnapLog.close();
    }

    @Test
    public void testGetName() {
        assertEquals("Leader", leaderBean.getName());
    }

    @Test
    public void testGetCurrentZxid() {
        // Arrange
        zks.setZxid(1);

        // Assert
        assertEquals("0x1", leaderBean.getCurrentZxid());
    }

    @Test
    public void testGetElectionTimeTaken() {
        // Arrange
        qp.setElectionTimeTaken(1);

        // Assert
        assertEquals(1, leaderBean.getElectionTimeTaken());
    }

    @Test
    public void testGetProposalSize() throws Leader.XidRolloverException {
        // Arrange
        Request req = createMockRequest();

        // Act
        leader.propose(req);

        // Assert
        byte[] data = SerializeUtils.serializeRequest(req);
        assertEquals(data.length, leaderBean.getLastProposalSize());
        assertEquals(data.length, leaderBean.getMinProposalSize());
        assertEquals(data.length, leaderBean.getMaxProposalSize());
    }

    @Test
    public void testResetProposalStats() throws Leader.XidRolloverException {
        // Arrange
        int initialProposalSize = leaderBean.getLastProposalSize();
        Request req = createMockRequest();

        // Act
        leader.propose(req);

        // Assert
        assertNotEquals(initialProposalSize, leaderBean.getLastProposalSize());
        leaderBean.resetProposalStatistics();
        assertEquals(initialProposalSize, leaderBean.getLastProposalSize());
        assertEquals(initialProposalSize, leaderBean.getMinProposalSize());
        assertEquals(initialProposalSize, leaderBean.getMaxProposalSize());
    }

    private Request createMockRequest() {
        final TxnHeader hdr = new TxnHeader();
        hdr.setClientId(1);
        hdr.setCxid(2);
        hdr.setType(3);
        hdr.setZxid(4);
        final Txn txn = new Txn();
        return new Request(hdr.getClientId(), hdr, txn, hdr.getZxid());
    }

    @Test
    public void testFollowerInfo() {
        Map<Long, QuorumServer> votingMembers = new HashMap<>();
        votingMembers.put(1L, null);
        votingMembers.put(2L, null);
        votingMembers.put(3L, null);
        when(quorumVerifierMock.getVotingMembers()).thenReturn(votingMembers);

        LearnerHandler follower = mock(LearnerHandler.class);
        when(follower.getLearnerType()).thenReturn(LearnerType.PARTICIPANT);
        when(follower.toString()).thenReturn("1");
        when(follower.getSid()).thenReturn(1L);
        leader.addLearnerHandler(follower);
        leader.addForwardingFollower(follower);

        assertEquals("1\n", leaderBean.followerInfo());
        assertEquals("", leaderBean.nonVotingFollowerInfo());

        LearnerHandler observer = mock(LearnerHandler.class);
        when(observer.getLearnerType()).thenReturn(LearnerType.OBSERVER);
        when(observer.toString()).thenReturn("2");
        leader.addLearnerHandler(observer);

        assertEquals("1\n", leaderBean.followerInfo());
        assertEquals("", leaderBean.nonVotingFollowerInfo());

        LearnerHandler nonVotingFollower = mock(LearnerHandler.class);
        when(nonVotingFollower.getLearnerType()).thenReturn(LearnerType.PARTICIPANT);
        when(nonVotingFollower.toString()).thenReturn("5");
        when(nonVotingFollower.getSid()).thenReturn(5L);
        leader.addLearnerHandler(nonVotingFollower);
        leader.addForwardingFollower(nonVotingFollower);

        String followerInfo = leaderBean.followerInfo();
        assertTrue(followerInfo.contains("1"));
        assertTrue(followerInfo.contains("5"));
        assertEquals("5\n", leaderBean.nonVotingFollowerInfo());
    }

}
