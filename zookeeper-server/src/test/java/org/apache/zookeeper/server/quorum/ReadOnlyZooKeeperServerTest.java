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
import java.nio.ByteBuffer;
import org.apache.zookeeper.server.NIOServerCnxn;
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
     * test method {@link ZooKeeperServer#processConnectRequest(org.apache.zookeeper.server.ServerCnxn, java.nio.ByteBuffer)}
     */
    @Test
    public void testReadOnlyZookeeperServer() {
        ReadOnlyZooKeeperServer readOnlyZooKeeperServer = new ReadOnlyZooKeeperServer(
                mock(FileTxnSnapLog.class), mock(QuorumPeer.class), mock(ZKDatabase.class));

        final ByteBuffer output = ByteBuffer.allocate(30);
        // serialize a connReq
        output.putInt(1);
        output.putLong(1L);
        output.putInt(500);
        output.putLong(123L);
        output.putInt(1);
        output.put((byte) 1);
        // set readOnly false
        output.put((byte) 0);
        output.flip();

        ServerCnxn.CloseRequestException e = assertThrows(ServerCnxn.CloseRequestException.class, () -> {
            final NIOServerCnxn nioServerCnxn = mock(NIOServerCnxn.class);
            readOnlyZooKeeperServer.processConnectRequest(nioServerCnxn, output);
        });
        assertEquals(e.getReason(), ServerCnxn.DisconnectReason.NOT_READ_ONLY_CLIENT);
    }


}
