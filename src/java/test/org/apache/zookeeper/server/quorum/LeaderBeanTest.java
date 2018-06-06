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

import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;
import org.apache.zookeeper.test.ClientBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

public class LeaderBeanTest {
    private Leader leader;
    private LeaderBean leaderBean;
    private FileTxnSnapLog fileTxnSnapLog;
    private LeaderZooKeeperServer zks;
    private QuorumPeer qp;

    @Before
    public void setUp() throws IOException {
        qp = new QuorumPeer();
        QuorumVerifier quorumVerifierMock = mock(QuorumVerifier.class);
        qp.setQuorumVerifier(quorumVerifierMock, false);
        File tmpDir = ClientBase.createEmptyTestDir();
        fileTxnSnapLog = new FileTxnSnapLog(new File(tmpDir, "data"),
                new File(tmpDir, "data_txnlog"));
        ZKDatabase zkDb = new ZKDatabase(fileTxnSnapLog);

        zks = new LeaderZooKeeperServer(fileTxnSnapLog, qp, zkDb);
        leader = new Leader(qp, zks);
        leaderBean = new LeaderBean(leader, zks);
    }

    @After
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
}
