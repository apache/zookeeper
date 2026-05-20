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
package org.apache.zookeeper.server.admin;

import static org.apache.zookeeper.server.ZooKeeperServer.ZOOKEEPER_SERIALIZE_LAST_PROCESSED_ZXID_ENABLED;
import static org.apache.zookeeper.server.admin.Commands.ADMIN_RATE_LIMITER_INTERVAL;
import static org.apache.zookeeper.server.admin.Commands.RestoreCommand.ADMIN_RESTORE_ENABLED;
import static org.apache.zookeeper.server.admin.Commands.SnapshotCommand.ADMIN_SNAPSHOT_ENABLED;
import static org.apache.zookeeper.server.admin.SnapshotAndRestoreCommandTest.performRestoreAndValidate;
import static org.apache.zookeeper.server.admin.SnapshotAndRestoreCommandTest.takeSnapshotAndValidate;
import java.io.File;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class RestoreQuorumTest extends QuorumPeerTestBase {
    @Test
    public void testRestoreAfterQuorumLost() throws Exception {
        setupAdminServerProperties();

        int SERVER_COUNT = 3;
        final int NODE_COUNT = 10;
        final String PATH = "/testRestoreAfterQuorumLost";

        try {
            // start up servers
            servers = LaunchServers(SERVER_COUNT);
            int leaderId = servers.findLeader();

            // create data
            servers.zk[leaderId].create(PATH, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            for (int i = 0; i < NODE_COUNT; i++) {
                servers.zk[leaderId].create(PATH + "/" + i, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }

            // take snapshot
            final File snapshotFile = takeSnapshotAndValidate(servers.adminPorts[leaderId], ClientBase.testBaseDir);

            // create more data
            for (int i = NODE_COUNT; i < NODE_COUNT * 2; i++) {
                servers.zk[leaderId].create(PATH + "/" + i, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }

            // shutdown all servers to simulate quorum lost
            servers.shutDownAllServers();
            waitForAll(servers, ZooKeeper.States.CONNECTING);

            // restart the servers
            for (int i = 0; i < SERVER_COUNT; i++) {
                System.setProperty("zookeeper.admin.serverPort", String.valueOf(servers.adminPorts[i]));
                servers.mt[i].start();
                servers.restartClient(i, this);
            }
            waitForAll(servers, ZooKeeper.States.CONNECTED);

            // restore servers
            for (int i = 0; i < SERVER_COUNT; i++) {
                performRestoreAndValidate(servers.adminPorts[i], snapshotFile);
            }

            // validate all servers are restored
            for (int i = 0; i < SERVER_COUNT; i++) {
                servers.restartClient(i, this);
                Assertions.assertEquals(NODE_COUNT, servers.zk[i].getAllChildrenNumber(PATH));
            }

            // create more data after restore
            for (int i = NODE_COUNT * 2; i < NODE_COUNT * 3; i++) {
                servers.zk[leaderId].create(PATH + "/" + i, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }

            // validate all servers have expected data
            for (int i = 0; i < SERVER_COUNT; i++) {
                Assertions.assertEquals(NODE_COUNT * 2, servers.zk[i].getAllChildrenNumber(PATH));
            }
        } finally {
            clearAdminServerProperties();
        }
    }

    private void setupAdminServerProperties() {
        System.setProperty("zookeeper.admin.enableServer", "true");
        System.setProperty(ADMIN_RATE_LIMITER_INTERVAL, "0");
        System.setProperty(ADMIN_SNAPSHOT_ENABLED, "true");
        System.setProperty(ZOOKEEPER_SERIALIZE_LAST_PROCESSED_ZXID_ENABLED, "true");
        System.setProperty(ADMIN_RESTORE_ENABLED, "true");
    }

    private void clearAdminServerProperties() {
        System.clearProperty("zookeeper.admin.enableServer");
        System.clearProperty("zookeeper.admin.serverPort");
        System.clearProperty(ADMIN_RATE_LIMITER_INTERVAL);
        System.clearProperty(ADMIN_SNAPSHOT_ENABLED);
        System.clearProperty(ZOOKEEPER_SERIALIZE_LAST_PROCESSED_ZXID_ENABLED);
        System.clearProperty(ADMIN_RESTORE_ENABLED);
    }
}
