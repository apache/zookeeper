/**
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

import org.apache.jute.OutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;
import org.apache.zookeeper.server.util.SerializeUtils;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class LeaderBeanTest {
    private Leader leader;
    private LeaderBean leaderBean;
    private FileTxnSnapLog fileTxnSnapLog;
    private LeaderZooKeeperServer zks;
    private QuorumPeer qp;

    @Before
    public void setUp() throws IOException {
        qp = mock(QuorumPeer.class);
        QuorumVerifier quorumVerifierMock = mock(QuorumVerifier.class);
        when(qp.getQuorumVerifier()).thenReturn(quorumVerifierMock);
        when(qp.getQuorumAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 2138));
        ServerCnxnFactory serverCnxnFactoryMock = mock(ServerCnxnFactory.class);
        when(qp.getCnxnFactory()).thenReturn(serverCnxnFactoryMock);
        File tmpDir = ClientBase.createTmpDir();
        fileTxnSnapLog = new FileTxnSnapLog(new File(tmpDir, "data"),
                new File(tmpDir, "data_txnlog"));
        ZKDatabase zkDb = new ZKDatabase(fileTxnSnapLog);
        qp.setZKDatabase(zkDb);

        zks = new LeaderZooKeeperServer(fileTxnSnapLog, qp, null, zkDb);
        leader = new Leader(qp, zks);
        leaderBean = new LeaderBean(leader, zks);    }

    @After
    public void tearDown() throws IOException {
        fileTxnSnapLog.close();
        leader.shutdown("tearDown");
        zks.shutdown();
        qp.shutdown();
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
}