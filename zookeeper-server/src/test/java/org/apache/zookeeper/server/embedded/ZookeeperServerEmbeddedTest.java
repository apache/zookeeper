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
package org.apache.zookeeper.server.embedded;

import static org.junit.Assert.assertTrue;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ZookeeperServerEmbeddedTest {

    @BeforeAll
    public static void setUpEnvironment() {
        System.setProperty("zookeeper.admin.enableServer", "false");
        System.setProperty("zookeeper.4lw.commands.whitelist", "*");
    }

    @AfterAll
    public static void cleanUpEnvironment() throws InterruptedException, IOException {
        System.clearProperty("zookeeper.admin.enableServer");
        System.clearProperty("zookeeper.4lw.commands.whitelist");
    }

    @TempDir
    public Path baseDir;

    @Test
    public void testStart() throws Exception {
        int clientPort = PortAssignment.unique();
        final Properties configZookeeper = new Properties();
        configZookeeper.put("clientPort", clientPort + "");
        configZookeeper.put("host", "localhost");
        configZookeeper.put("ticktime", "4000");
        try (ZooKeeperServerEmbedded zkServer = ZooKeeperServerEmbedded
                .builder()
                .baseDir(baseDir)
                .configuration(configZookeeper)
                .exitHandler(ExitHandler.LOG_ONLY)
                .build()) {
            zkServer.start();
            assertTrue(ClientBase.waitForServerUp(zkServer.getConnectionString(), 60000));
            for (int i = 0; i < 100; i++) {
                ZookeeperServeInfo.ServerInfo status = ZookeeperServeInfo.getStatus("StandaloneServer*");
                if (status.isLeader() && status.isStandaloneMode()) {
                    break;
                }
                Thread.sleep(100);
            }
            ZookeeperServeInfo.ServerInfo status = ZookeeperServeInfo.getStatus("StandaloneServer*");
            assertTrue(status.isLeader());
            assertTrue(status.isStandaloneMode());
        }

        // restart (all ports should be closed and the restart should always work)
        try (ZooKeeperServerEmbedded zkServer = ZooKeeperServerEmbedded
                .builder()
                .baseDir(baseDir)
                .configuration(configZookeeper)
                .exitHandler(ExitHandler.LOG_ONLY)
                .build()) {
            zkServer.start();
            assertTrue(ClientBase.waitForServerUp(zkServer.getConnectionString(), 60000));
            for (int i = 0; i < 100; i++) {
                ZookeeperServeInfo.ServerInfo status = ZookeeperServeInfo.getStatus("StandaloneServer*");
                if (status.isLeader() && status.isStandaloneMode()) {
                    break;
                }
                Thread.sleep(100);
            }
            ZookeeperServeInfo.ServerInfo status = ZookeeperServeInfo.getStatus("StandaloneServer*");
            assertTrue(status.isLeader());
            assertTrue(status.isStandaloneMode());
        }

    }

}
