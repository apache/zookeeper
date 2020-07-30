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

import static org.junit.jupiter.api.Assertions.fail;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
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

public class FLEMalformedNotificationMessageTest extends ZKTestCase {
    private static final Logger LOG = LoggerFactory.getLogger(FLEMalformedNotificationMessageTest.class);
    private static final byte[] CONFIG_BYTES = "my very invalid config string".getBytes();
    private static final int CONFIG_BYTES_LENGTH = CONFIG_BYTES.length;

    int count;
    HashMap<Long, QuorumServer> peers;
    File tmpdir[];
    int port[];

    QuorumCnxManager mockCnxManager;
    FLETestUtils.LEThread leaderElectionThread;
    QuorumPeer peerRunningLeaderElection;


    @BeforeEach
    public void setUp() throws Exception {
        count = 3;

        peers = new HashMap<>(count);
        tmpdir = new File[count];
        port = new int[count];

        LOG.info("FLEMalformedNotificationMessageTest: {}, {}", getTestName(), count);
        for (int i = 0; i < count; i++) {
            int clientport = PortAssignment.unique();
            peers.put((long) i,
                      new QuorumServer(i,
                                       new InetSocketAddress(clientport),
                                       new InetSocketAddress(PortAssignment.unique())));
            tmpdir[i] = ClientBase.createTmpDir();
            port[i] = clientport;
        }

        /*
         * Start server 0
         */
        peerRunningLeaderElection = new QuorumPeer(peers, tmpdir[0], tmpdir[0], port[0], 3, 0, 1000, 2, 2, 2);
        peerRunningLeaderElection.startLeaderElection();
        leaderElectionThread = new FLETestUtils.LEThread(peerRunningLeaderElection, 0);
        leaderElectionThread.start();
    }


    @AfterEach
    public void tearDown() throws Exception {
        peerRunningLeaderElection.shutdown();
        mockCnxManager.halt();
    }


    @Test
    public void testTooShortPartialNotificationMessage() throws Exception {

        /*
         * Start mock server 1, send a message too short to be compatible with any protocol version
         * This simulates the case when only some parts of the whole message is received.
         */
        startMockServer(1);
        byte requestBytes[] = new byte[12];
        ByteBuffer requestBuffer = ByteBuffer.wrap(requestBytes);
        requestBuffer.clear();
        requestBuffer.putInt(ServerState.LOOKING.ordinal());   // state
        requestBuffer.putLong(0);                              // leader
        mockCnxManager.toSend(0L, requestBuffer);

        /*
         * Assert that the message receiver thread in leader election is still healthy:
         * we are sending valid votes and waiting for the leader election to be finished.
         */
        sendValidNotifications(1, 0);
        leaderElectionThread.join(5000);
        if (leaderElectionThread.isAlive()) {
            fail("Leader election thread didn't join, something went wrong.");
        }
    }


    @Test
    public void testNotificationMessageWithNegativeConfigLength() throws Exception {

        /*
         * Start mock server 1, send a message with negative configLength field
         */
        startMockServer(1);
        byte requestBytes[] = new byte[48];
        ByteBuffer requestBuffer = ByteBuffer.wrap(requestBytes);
        requestBuffer.clear();
        requestBuffer.putInt(ServerState.LOOKING.ordinal());   // state
        requestBuffer.putLong(0);                              // leader
        requestBuffer.putLong(0);                              // zxid
        requestBuffer.putLong(0);                              // electionEpoch
        requestBuffer.putLong(0);                              // epoch
        requestBuffer.putInt(FastLeaderElection.Notification.CURRENTVERSION);   // version
        requestBuffer.putInt(-123);                            // configData.length
        mockCnxManager.toSend(0L, requestBuffer);

        /*
         * Assert that the message receiver thread in leader election is still healthy:
         * we are sending valid votes and waiting for the leader election to be finished.
         */
        sendValidNotifications(1, 0);
        leaderElectionThread.join(5000);
        if (leaderElectionThread.isAlive()) {
            fail("Leader election thread didn't join, something went wrong.");
        }
    }


    @Test
    public void testNotificationMessageWithInvalidConfigLength() throws Exception {

        /*
         * Start mock server 1, send a message with an invalid configLength field
         * (instead of sending CONFIG_BYTES_LENGTH, we send 10000)
         */
        startMockServer(1);
        byte requestBytes[] = new byte[48 + CONFIG_BYTES_LENGTH];
        ByteBuffer requestBuffer = ByteBuffer.wrap(requestBytes);
        requestBuffer.clear();
        requestBuffer.putInt(ServerState.LOOKING.ordinal());   // state
        requestBuffer.putLong(0);                              // leader
        requestBuffer.putLong(0);                              // zxid
        requestBuffer.putLong(0);                              // electionEpoch
        requestBuffer.putLong(0);                              // epoch
        requestBuffer.putInt(FastLeaderElection.Notification.CURRENTVERSION);   // version
        requestBuffer.putInt(10000);                           // configData.length
        requestBuffer.put(CONFIG_BYTES);                       // configData
        mockCnxManager.toSend(0L, requestBuffer);

        /*
         * Assert that the message receiver thread in leader election is still healthy:
         * we are sending valid votes and waiting for the leader election to be finished.
         */
        sendValidNotifications(1, 0);
        leaderElectionThread.join(5000);
        if (leaderElectionThread.isAlive()) {
            fail("Leader election thread didn't join, something went wrong.");
        }
    }


    @Test
    public void testNotificationMessageWithInvalidConfig() throws Exception {

        /*
         * Start mock server 1, send a message with an invalid config field
         * (the receiver should not be able to parse the config part of the message)
         */
        startMockServer(1);
        ByteBuffer requestBuffer = FastLeaderElection.buildMsg(ServerState.LOOKING.ordinal(), 1, 0, 0, 0, CONFIG_BYTES);
        mockCnxManager.toSend(0L, requestBuffer);

        /*
         * Assert that the message receiver thread in leader election is still healthy:
         * we are sending valid votes and waiting for the leader election to be finished.
         */
        sendValidNotifications(1, 0);
        leaderElectionThread.join(5000);
        if (leaderElectionThread.isAlive()) {
            fail("Leader election thread didn't join, something went wrong.");
        }
    }


    @Test
    public void testNotificationMessageWithBadProtocol() throws Exception {

        /*
         * Start mock server 1, send an invalid 30 bytes long message
         * (the receiver should not be able to parse the message and should skip it)
         * This simulates the case when only some parts of the whole message is received.
         */
        startMockServer(1);
        byte requestBytes[] = new byte[30];
        ByteBuffer requestBuffer = ByteBuffer.wrap(requestBytes);
        requestBuffer.clear();
        requestBuffer.putInt(ServerState.LOOKING.ordinal());   // state
        requestBuffer.putLong(1);                              // leader
        requestBuffer.putLong(0);                              // zxid
        requestBuffer.putLong(0);                              // electionEpoch
        requestBuffer.putShort((short) 0);                      // this is the first two bytes of a proper
                                                               // 8 bytes Long we should send here
        mockCnxManager.toSend(0L, requestBuffer);

        /*
         * Assert that the message receiver thread in leader election is still healthy:
         * we are sending valid votes and waiting for the leader election to be finished.
         */
        sendValidNotifications(1, 0);
        leaderElectionThread.join(5000);
        if (leaderElectionThread.isAlive()) {
            fail("Leader election thread didn't join, something went wrong.");
        }
    }


    void startMockServer(int sid) throws IOException {
        QuorumPeer peer = new QuorumPeer(peers, tmpdir[sid], tmpdir[sid], port[sid], 3, sid, 1000, 2, 2, 2);
        mockCnxManager = peer.createCnxnManager();
        mockCnxManager.listener.start();
    }


    void sendValidNotifications(int fromSid, int toSid) throws InterruptedException {
        mockCnxManager.toSend((long) toSid, FLETestUtils.createMsg(ServerState.LOOKING.ordinal(), fromSid, 0, 0));
        mockCnxManager.recvQueue.take();
        mockCnxManager.toSend((long) toSid, FLETestUtils.createMsg(ServerState.FOLLOWING.ordinal(), toSid, 0, 0));
    }

}
