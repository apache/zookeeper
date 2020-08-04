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

import static org.junit.jupiter.api.Assertions.fail;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FLELostMessageTest extends ZKTestCase {

    protected static final Logger LOG = LoggerFactory.getLogger(FLELostMessageTest.class);

    int count;
    HashMap<Long, QuorumServer> peers;
    File[] tmpdir;
    int[] port;

    QuorumCnxManager cnxManager;

    @BeforeEach
    public void setUp() throws Exception {
        count = 3;

        peers = new HashMap<Long, QuorumServer>(count);
        tmpdir = new File[count];
        port = new int[count];
    }

    @AfterEach
    public void tearDown() throws Exception {
        cnxManager.halt();
    }

    @Test
    public void testLostMessage() throws Exception {
        LOG.info("TestLE: {}, {}", getTestName(), count);
        for (int i = 0; i < count; i++) {
            int clientport = PortAssignment.unique();
            peers.put(Long.valueOf(i), new QuorumServer(i, new InetSocketAddress(clientport), new InetSocketAddress(PortAssignment.unique())));
            tmpdir[i] = ClientBase.createTmpDir();
            port[i] = clientport;
        }

        /*
         * Start server 0
         */
        QuorumPeer peer = new QuorumPeer(peers, tmpdir[1], tmpdir[1], port[1], 3, 1, 1000, 2, 2, 2);
        peer.startLeaderElection();
        FLETestUtils.LEThread thread = new FLETestUtils.LEThread(peer, 1);
        thread.start();

        /*
         * Start mock server 1
         */
        mockServer();
        thread.join(5000);
        if (thread.isAlive()) {
            fail("Threads didn't join");
        }
    }

    void mockServer() throws InterruptedException, IOException {
        QuorumPeer peer = new QuorumPeer(peers, tmpdir[0], tmpdir[0], port[0], 3, 0, 1000, 2, 2, 2);
        cnxManager = peer.createCnxnManager();
        cnxManager.listener.start();

        cnxManager.toSend(1L, FLETestUtils.createMsg(ServerState.LOOKING.ordinal(), 0, 0, 0));
        cnxManager.recvQueue.take();
        cnxManager.toSend(1L, FLETestUtils.createMsg(ServerState.FOLLOWING.ordinal(), 1, 0, 0));
    }

}
