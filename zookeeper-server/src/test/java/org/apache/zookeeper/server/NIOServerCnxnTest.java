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

package org.apache.zookeeper.server;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.IOException;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.quorum.BufferStats;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NIOServerCnxnTest extends ClientBase {

    private static final Logger LOG = LoggerFactory.getLogger(NIOServerCnxnTest.class);

    /**
     * Test operations on ServerCnxn after socket closure.
     */
    @Test
    @Timeout(value = 60)
    public void testOperationsAfterCnxnClose() throws IOException, InterruptedException, KeeperException {
        final ZooKeeper zk = createClient();

        final String path = "/a";
        try {
            // make sure zkclient works
            zk.create(path, "test".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            assertNotNull(zk.exists(path, false), "Didn't create znode:" + path);
            // Defaults ServerCnxnFactory would be instantiated with
            // NIOServerCnxnFactory
            assertTrue(serverFactory instanceof NIOServerCnxnFactory,
                "Didn't instantiate ServerCnxnFactory with NIOServerCnxnFactory!");
            Iterable<ServerCnxn> connections = serverFactory.getConnections();
            for (ServerCnxn serverCnxn : connections) {
                serverCnxn.close(ServerCnxn.DisconnectReason.CHANNEL_CLOSED_EXCEPTION);
                try {
                    serverCnxn.toString();
                } catch (Exception e) {
                    LOG.error("Exception while getting connection details!", e);
                    fail("Shouldn't throw exception while " + "getting connection details!");
                }
            }
        } finally {
            zk.close();
        }
    }

    @Test
    public void testClientResponseStatsUpdate() throws IOException, InterruptedException, KeeperException {
        try (ZooKeeper zk = createClient()) {
            BufferStats clientResponseStats = serverFactory.getZooKeeperServer().serverStats().getClientResponseStats();
            assertThat("Last client response size should be initialized with INIT_VALUE", clientResponseStats.getLastBufferSize(), equalTo(BufferStats.INIT_VALUE));

            zk.create("/a", "test".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            assertThat("Last client response size should be greater then zero after client request was performed", clientResponseStats.getLastBufferSize(), greaterThan(0));
        }
    }

}
