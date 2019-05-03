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

package org.apache.zookeeper.test;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.junit.Test;

import java.net.InetSocketAddress;

public class ServerDuplicateTest extends ZKTestCase {
/**
 * You don't necessarily write an integration test like this via Reconfig functionality.
 * Just create a mocked QuorumServer and call excludedSpecialAddresses() via the constructor
 * or via checkAddressDuplicate(). It would be more unit-test like and stable.
 */

    private String v6addr1 = "[2500:0:0:0:0:0:1:0]";
    private String v6addr2 = "[2600:0:0:0:0:0:1:0]";

    @Test
    public void testWildcard() throws KeeperException.BadArgumentsException {
        String[] addrs = new String[]{"127.0.0.1", "[0:0:0:0:0:0:0:1]", "0.0.0.0", "[::]"};
        for (int i = 0; i < addrs.length; i++) {
            for (int j = i; j < addrs.length; j++) {
                QuorumPeer.QuorumServer server1 =
                        new QuorumPeer.QuorumServer(1,
                                new InetSocketAddress(v6addr1, 2), // peer
                                new InetSocketAddress(v6addr1, 3), // election
                                new InetSocketAddress(addrs[i], 4)  // client
                        );
                QuorumPeer.QuorumServer server2 =
                        new QuorumPeer.QuorumServer(2,
                                new InetSocketAddress(v6addr2, 2), // peer
                                new InetSocketAddress(v6addr2, 3), // election
                                new InetSocketAddress(addrs[j], 4)  // client
                        );
                server1.checkAddressDuplicate(server2);
            }
        }
    }

    @Test(expected = KeeperException.BadArgumentsException.class)
    public void testDuplicate() throws KeeperException.BadArgumentsException {
        QuorumPeer.QuorumServer server1 =
                new QuorumPeer.QuorumServer(1,
                        new InetSocketAddress(v6addr1, 2), // peer
                        new InetSocketAddress(v6addr1, 3), // election
                        new InetSocketAddress(v6addr1, 4)  // client
                );
        QuorumPeer.QuorumServer server2 =
                new QuorumPeer.QuorumServer(2,
                        new InetSocketAddress(v6addr2, 2), // peer
                        new InetSocketAddress(v6addr2, 3), // election
                        new InetSocketAddress(v6addr1, 4)  // client
                );
        server1.checkAddressDuplicate(server2);
    }
}
