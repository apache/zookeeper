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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A notification received on the election port may carry a QuorumVerifier
 * (version >= 0x2). The election port accepts a connection from any sid, so
 * the config section must only be adopted when the sender is a voting member
 * of the receiver's current configuration. Otherwise an unauthenticated peer
 * could inject an arbitrary quorum configuration into a LOOKING server.
 */
public class FLEConfigFromNonVoterTest extends ZKTestCase {

    protected static final Logger LOG = LoggerFactory.getLogger(FLEConfigFromNonVoterTest.class);

    private static final int COUNT = 3;
    private static final long ROGUE_SID = 99L;

    private Map<Long, QuorumServer> peers;
    private File[] tmpdir;
    private int[] port;
    private QuorumPeer victim;
    private QuorumCnxManager[] cnxManagers;
    private boolean savedReconfigEnabled;

    @BeforeEach
    public void setUp() throws Exception {
        savedReconfigEnabled = QuorumPeerConfig.isReconfigEnabled();
        QuorumPeerConfig.setReconfigEnabled(true);

        peers = new HashMap<>();
        tmpdir = new File[COUNT + 1];
        port = new int[COUNT + 1];
        cnxManagers = new QuorumCnxManager[2];

        for (int i = 0; i < COUNT; i++) {
            int clientport = PortAssignment.unique();
            peers.put((long) i, new QuorumServer(i,
                    new InetSocketAddress("127.0.0.1", PortAssignment.unique()),
                    new InetSocketAddress("127.0.0.1", PortAssignment.unique()),
                    new InetSocketAddress("127.0.0.1", clientport)));
            tmpdir[i] = ClientBase.createTmpDir();
            port[i] = clientport;
        }
        tmpdir[COUNT] = ClientBase.createTmpDir();
        port[COUNT] = PortAssignment.unique();
    }

    @AfterEach
    public void tearDown() throws Exception {
        for (QuorumCnxManager m : cnxManagers) {
            if (m != null) {
                m.halt();
            }
        }
        if (victim != null) {
            victim.shutdown();
        }
        QuorumPeerConfig.setReconfigEnabled(savedReconfigEnabled);
    }

    @Test
    public void testConfigFromNonVoterIsIgnored() throws Exception {
        victim = new QuorumPeer(peers, tmpdir[0], tmpdir[0], port[0], 3, 0, 1000, 2, 2, 2);
        victim.startLeaderElection();
        QuorumVerifier originalQV = victim.getQuorumVerifier();
        long originalVersion = originalQV.getVersion();

        FLETestUtils.LEThread thread = new FLETestUtils.LEThread(victim, 0);
        thread.start();

        // Rogue peer: not a member of the victim's view, but knows the
        // victim's election address. Its sid is larger than the victim's so
        // the victim's QuorumCnxManager keeps the inbound connection.
        Map<Long, QuorumServer> rogueView = new HashMap<>(peers);
        rogueView.put(ROGUE_SID, new QuorumServer(ROGUE_SID,
                new InetSocketAddress("127.0.0.1", PortAssignment.unique()),
                new InetSocketAddress("127.0.0.1", PortAssignment.unique()),
                new InetSocketAddress("127.0.0.1", port[COUNT])));
        QuorumPeer rogue = new QuorumPeer(rogueView, tmpdir[COUNT], tmpdir[COUNT], port[COUNT], 3, ROGUE_SID, 1000, 2, 2, 2);
        cnxManagers[0] = rogue.createCnxnManager();
        cnxManagers[0].listener.start();

        String injected = originalQV.toString().replaceAll("version=.*", "")
                + "server." + ROGUE_SID + "=127.0.0.1:12345:12346:participant;12347\n"
                + "version=" + Long.toHexString(originalVersion + 1);
        cnxManagers[0].toSend(0L, FastLeaderElection.buildMsg(
                ServerState.LOOKING.ordinal(), 0, 0, 1, 1, injected.getBytes(UTF_8)));

        // Give the victim's WorkerReceiver time to process the message.
        Thread.sleep(2000);

        assertEquals(originalVersion, victim.getQuorumVerifier().getVersion(),
                "config from non-voter must not be adopted");
        assertFalse(victim.getQuorumVerifier().getAllMembers().containsKey(ROGUE_SID),
                "non-voter must not be able to add itself to the quorum");
        assertTrue(thread.isAlive(), "leader election must not have been restarted by a non-voter");

        // Positive control: the same config from a valid voter (sid 1) is adopted,
        // which proves the delivery path in this test actually works.
        QuorumPeer voter = new QuorumPeer(peers, tmpdir[1], tmpdir[1], port[1], 3, 1, 1000, 2, 2, 2);
        cnxManagers[1] = voter.createCnxnManager();
        cnxManagers[1].listener.start();
        cnxManagers[1].toSend(0L, FastLeaderElection.buildMsg(
                ServerState.LOOKING.ordinal(), 0, 0, 1, 1, injected.getBytes(UTF_8)));

        long deadline = System.currentTimeMillis() + 10000;
        while (victim.getQuorumVerifier().getVersion() == originalVersion && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertEquals(originalVersion + 1, victim.getQuorumVerifier().getVersion(),
                "config from a valid voter should still be adopted");
    }

}
