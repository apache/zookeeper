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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
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
import org.apache.zookeeper.server.quorum.flexible.QuorumMaj;
import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

public class LeaderBeanTest {

    private Leader leader;
    private LeaderBean leaderBean;
    private FileTxnSnapLog fileTxnSnapLog;
    private LeaderZooKeeperServer zks;
    private QuorumPeer qp;
    private QuorumVerifier quorumVerifierMock;
    @TempDir
    File tmpDir;

    public static Map<Long, QuorumServer> getMockedPeerViews(long myId) {
        int clientPort = PortAssignment.unique();
        Map<Long, QuorumServer> peersView = new HashMap<>();
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
        when(quorumVerifierMock.getAllMembers()).thenReturn(getMockedPeerViews(qp.getMyId()));

        qp.setQuorumVerifier(quorumVerifierMock, false);
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
    public void testCreateServerSocketWillRecreateInetSocketAddr() {
        Leader spyLeader = Mockito.spy(leader);
        InetSocketAddress addr = new InetSocketAddress("localhost", PortAssignment.unique());
        spyLeader.createServerSocket(addr, false, false);
        // make sure the address to be bound will be recreated with expected hostString and port
        Mockito.verify(spyLeader, times(1)).recreateInetSocketAddr(addr.getHostString(), addr.getPort());
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
        byte[] data = req.getSerializeData();
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

    @Test
    public void testGetDesignatedLeaderShouldRecreateSocketAddresses() {
        Leader.Proposal p = new Leader.Proposal();
        Map<Long, QuorumServer> peersView = new HashMap<>();
        QuorumServer qs = Mockito.mock(QuorumServer.class);
        MultipleAddresses multipleAddresses = new MultipleAddresses();
        qs.type = LearnerType.PARTICIPANT;
        qs.addr = multipleAddresses;
        peersView.put(0L, qs);
        QuorumVerifier qv = new QuorumMaj(peersView);
        HashSet<Long> ackset = new HashSet<>();
        ackset.add(0L);
        ArrayList<Leader.Proposal.QuorumVerifierAcksetPair> qvAcksetPairs = new ArrayList<>();
        qvAcksetPairs.add(new Leader.Proposal.QuorumVerifierAcksetPair(qv, ackset));
        p.qvAcksetPairs = qvAcksetPairs;
        Leader spyLeader = spy(leader);
        doReturn(multipleAddresses, multipleAddresses).when(spyLeader).recreateSocketAddresses(any(MultipleAddresses.class));

        spyLeader.getDesignatedLeader(p, 0L);
        // Verify `recreateSocketAddresses` method should be invoked twice for the address in proposal and self one
        verify(spyLeader, times(2)).recreateSocketAddresses(any(MultipleAddresses.class));
    }

    @Test
    public void testRecreateSocketAddresses() {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        String oldIP = loopback.getHostAddress();
        String newIP = "1.1.1.1";

        // test case 1: empty MultipleAddresses instance will still be empty after recreateSocketAddresses
        MultipleAddresses multipleAddresses = new MultipleAddresses();
        assertEquals(multipleAddresses, leader.recreateSocketAddresses(multipleAddresses));

        // test case 2: The content of MultipleAddresses instance will still be the same after recreateSocketAddresses if address no change
        InetSocketAddress addr1 = new InetSocketAddress(loopback, PortAssignment.unique());
        InetSocketAddress addr2 = new InetSocketAddress(loopback, PortAssignment.unique());
        multipleAddresses = new MultipleAddresses(Arrays.asList(addr1, addr2));
        // Verify after recreateSocketAddresses, the multipleAddresses should be the same (i.e. under no DNS's interaction)
        assertEquals(multipleAddresses, leader.recreateSocketAddresses(multipleAddresses));

        // test case 3: Simulating the DNS returning different IP address for the same hostname during recreation.
        // After recreateSocketAddresses, the MultipleAddresses should contain the updated IP address instance while other fields unchanged.
        InetAddress spyInetAddr = Mockito.spy(loopback);
        InetSocketAddress addr3 = new InetSocketAddress(spyInetAddr, PortAssignment.unique());
        // Verify the address is the old IP before recreateSocketAddresses.
        assertEquals(oldIP, addr3.getAddress().getHostAddress());
        multipleAddresses = new MultipleAddresses(Arrays.asList(addr3));
        // simulating the DNS returning different IP address
        when(spyInetAddr.getHostAddress()).thenReturn(newIP);

        // Verify after recreateSocketAddresses, the multipleAddresses should have different IP address result
        MultipleAddresses newMultipleAddress = leader.recreateSocketAddresses(multipleAddresses);
        assertNotEquals(multipleAddresses, newMultipleAddress);
        assertEquals(1, multipleAddresses.getAllAddresses().size());
        InetSocketAddress newAddr = multipleAddresses.getAllAddresses().iterator().next();
        // Verify the hostName should still be the same
        assertEquals(loopback.getHostName(), newAddr.getAddress().getHostName());
        // Verify the IP address has changed.
        assertEquals(newIP, newAddr.getAddress().getHostAddress());
    }
}
