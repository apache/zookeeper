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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.junit.jupiter.api.Test;

public class LocalPeerBeanTest {

    /**
     * Test case for https://issues.apache.org/jira/browse/ZOOKEEPER-2299
     */
    @Test
    public void testClientAddress() throws Exception {
        QuorumPeer quorumPeer = new QuorumPeer();
        LocalPeerBean remotePeerBean = new LocalPeerBean(quorumPeer);

        /**
         * Case 1: When cnxnFactory is null
         */
        String result = remotePeerBean.getClientAddress();
        assertNotNull(result);
        assertEquals(0, result.length());

        /**
         * Case 2: When only client port is configured
         */
        ServerCnxnFactory cnxnFactory = ServerCnxnFactory.createFactory();
        int clientPort = PortAssignment.unique();
        InetSocketAddress address = new InetSocketAddress(clientPort);
        cnxnFactory.configure(address, 5, -1, false);
        quorumPeer.setCnxnFactory(cnxnFactory);

        result = remotePeerBean.getClientAddress();
        String ipv4 = "0.0.0.0:" + clientPort;
        String ipv6 = "[0:0:0:0:0:0:0:0]:" + clientPort;
        assertTrue(result.equals(ipv4) || result.equals(ipv6));
        // cleanup
        cnxnFactory.shutdown();

        /**
         * Case 3: When both client port and client address is configured
         */
        clientPort = PortAssignment.unique();
        InetAddress clientIP = InetAddress.getLoopbackAddress();
        address = new InetSocketAddress(clientIP, clientPort);
        cnxnFactory = ServerCnxnFactory.createFactory();
        cnxnFactory.configure(address, 5, -1, false);
        quorumPeer.setCnxnFactory(cnxnFactory);

        result = remotePeerBean.getClientAddress();
        String expectedResult = clientIP.getHostAddress() + ":" + clientPort;
        assertEquals(expectedResult, result);
        // cleanup
        cnxnFactory.shutdown();
    }

    @Test
    public void testLocalPeerIsLeader() throws Exception {
        long localPeerId = 7;
        QuorumPeer peer = mock(QuorumPeer.class);
        when(peer.getId()).thenReturn(localPeerId);
        when(peer.isLeader(eq(localPeerId))).thenReturn(true);
        LocalPeerBean localPeerBean = new LocalPeerBean(peer);
        assertTrue(localPeerBean.isLeader());
    }

    @Test
    public void testLocalPeerIsNotLeader() throws Exception {
        long localPeerId = 7;
        QuorumPeer peer = mock(QuorumPeer.class);
        when(peer.getId()).thenReturn(localPeerId);
        when(peer.isLeader(eq(localPeerId))).thenReturn(false);
        LocalPeerBean localPeerBean = new LocalPeerBean(peer);
        assertFalse(localPeerBean.isLeader());
    }

}
