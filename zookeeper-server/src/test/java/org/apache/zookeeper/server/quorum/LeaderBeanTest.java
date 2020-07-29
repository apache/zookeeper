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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import org.apache.jute.OutputArchive;
import org.apache.jute.Record;
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
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

public class LeaderBeanTest {

    private Leader leader;
    private LeaderBean leaderBean;
    private FileTxnSnapLog fileTxnSnapLog;
    private LeaderZooKeeperServer zks;
    private QuorumPeer qp;
    private QuorumVerifier quorumVerifierMock;

    public static Map<Long, QuorumServer> getMockedPeerViews(long myId) {
        int clientPort = PortAssignment.unique();
        Map<Long, QuorumServer> peersView = new HashMap<Long, QuorumServer>();
        InetAddress clientIP = InetAddress.getLoopbackAddress();

        peersView.put(Long.valueOf(myId),
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
    public void testGetProposalSize() throws IOException, Leader.XidRolloverException {
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
    public void testResetProposalStats() throws IOException, Leader.XidRolloverException {
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

    private Request createMockRequest() throws IOException {
        TxnHeader header = mock(TxnHeader.class);
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                OutputArchive oa = (OutputArchive) args[0];
                oa.writeString("header", "test");
                return null;
            }
        }).when(header).serialize(any(OutputArchive.class), anyString());
        Record txn = mock(Record.class);
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                OutputArchive oa = (OutputArchive) args[0];
                oa.writeString("record", "test");
                return null;
            }
        }).when(txn).serialize(any(OutputArchive.class), anyString());
        return new Request(1, 2, 3, header, txn, 4);
    }

    @Test
    public void testFollowerInfo() throws IOException {
        Map<Long, QuorumServer> votingMembers = new HashMap<Long, QuorumServer>();
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
