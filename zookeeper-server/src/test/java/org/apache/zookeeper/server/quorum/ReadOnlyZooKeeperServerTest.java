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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import org.apache.zookeeper.proto.ConnectRequest;
import org.apache.zookeeper.server.MockServerCnxn;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.junit.jupiter.api.Test;

/**
 * test ReadOnlyZooKeeperServer
 */
public class ReadOnlyZooKeeperServerTest {

    /**
     * test method {@link ZooKeeperServer#processConnectRequest(ServerCnxn, ConnectRequest)}
     */
    @Test
    public void testReadOnlyZookeeperServer() {
        ReadOnlyZooKeeperServer readOnlyZooKeeperServer = new ReadOnlyZooKeeperServer(
                mock(FileTxnSnapLog.class),
                mock(QuorumPeer.class),
                mock(ZKDatabase.class));

        final ConnectRequest request = new ConnectRequest();
        request.setProtocolVersion(1);
        request.setLastZxidSeen(99L);
        request.setTimeOut(500);
        request.setSessionId(123L);
        request.setPasswd(new byte[]{ 1 });
        request.setReadOnly(false);

        ServerCnxn.CloseRequestException e = assertThrows(
                ServerCnxn.CloseRequestException.class,
                () -> readOnlyZooKeeperServer.processConnectRequest(new MockServerCnxn(), request));
        assertEquals(e.getReason(), ServerCnxn.DisconnectReason.NOT_READ_ONLY_CLIENT);
    }


}
