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
import static org.junit.Assert.assertNotNull;

import java.net.InetSocketAddress;

import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.junit.Test;

public class RemotePeerBeanTest {

    /**
     * Test case for https://issues.apache.org/jira/browse/ZOOKEEPER-2269
     */
    @Test
    public void testGetClientAddressShouldReturnEmptyStringWhenClientAddressIsNull() {
        InetSocketAddress peerCommunicationAddress = null;
        // Here peerCommunicationAddress is null, also clientAddr is null
        QuorumServer peer = new QuorumServer(1, peerCommunicationAddress);
        RemotePeerBean remotePeerBean = new RemotePeerBean(peer);
        String clientAddress = remotePeerBean.getClientAddress();
        assertNotNull(clientAddress);
        assertEquals(0, clientAddress.length());
    }

}
