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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import javax.security.auth.login.Configuration;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test Quorum Mutual Auth with ZooKeeperEmbedded.
 */
public class ZookeeperServerClusterMutualAuthTest {

    @BeforeAll
    public static void setUpEnvironment() {
        System.setProperty("java.security.auth.login.config", new File("src/test/resources/embedded/test_jaas_server_auth.conf")
                .getAbsolutePath());
        Configuration.getConfiguration().refresh();
        System.setProperty("zookeeper.admin.enableServer", "false");
        System.setProperty("zookeeper.4lw.commands.whitelist", "*");
    }

    @AfterAll
    public static void cleanUpEnvironment() throws InterruptedException, IOException {
        System.clearProperty("zookeeper.admin.enableServer");
        System.clearProperty("zookeeper.4lw.commands.whitelist");
        System.clearProperty("java.security.auth.login.config");
        Configuration.getConfiguration().refresh();
    }

    @TempDir
    public Path baseDir;

    @Test
    public void testStart() throws Exception {
        Path baseDir1 = baseDir.resolve("server1");
        Path baseDir2 = baseDir.resolve("server2");
        Path baseDir3 = baseDir.resolve("server3");

        int clientport1 = PortAssignment.unique();
        int clientport2 = PortAssignment.unique();
        int clientport3 = PortAssignment.unique();

        int port4 = PortAssignment.unique();
        int port5 = PortAssignment.unique();
        int port6 = PortAssignment.unique();

        int port7 = PortAssignment.unique();
        int port8 = PortAssignment.unique();
        int port9 = PortAssignment.unique();

        Properties config = new Properties();
        config.put("host", "localhost");
        config.put("ticktime", "10");
        config.put("initLimit", "4000");
        config.put("syncLimit", "5");

        config.put("server.1", "localhost:" + port4 + ":" + port7);
        config.put("server.2", "localhost:" + port5 + ":" + port8);
        config.put("server.3", "localhost:" + port6 + ":" + port9);

        config.put("quorum.auth.enableSasl", "true");
        config.put("quorum.auth.learnerRequireSasl", "true");
        config.put("quorum.auth.serverRequireSasl", "true");
        config.put("quorum.auth.learner.loginContext", "QuorumLearner");
        config.put("quorum.auth.server.loginContext", "QuorumServer");
        config.put("quorum.auth.kerberos.servicePrincipal", "servicename/_HOST");
        config.put("quorum.cnxn.threads.size", "20");

        final Properties configZookeeper1 = new Properties();
        configZookeeper1.putAll(config);
        configZookeeper1.put("clientPort", clientport1 + "");

        final Properties configZookeeper2 = new Properties();
        configZookeeper2.putAll(config);
        configZookeeper2.put("clientPort", clientport2 + "");

        final Properties configZookeeper3 = new Properties();
        configZookeeper3.putAll(config);
        configZookeeper3.put("clientPort", clientport3 + "");

        Files.createDirectories(baseDir1.resolve("data"));
        Files.write(baseDir1.resolve("data").resolve("myid"), "1".getBytes("ASCII"));
        Files.createDirectories(baseDir2.resolve("data"));
        Files.write(baseDir2.resolve("data").resolve("myid"), "2".getBytes("ASCII"));
        Files.createDirectories(baseDir3.resolve("data"));
        Files.write(baseDir3.resolve("data").resolve("myid"), "3".getBytes("ASCII"));

        try (ZooKeeperServerEmbedded zkServer1 = ZooKeeperServerEmbedded.builder().configuration(configZookeeper1).baseDir(baseDir1).exitHandler(ExitHandler.LOG_ONLY).build();
                ZooKeeperServerEmbedded zkServer2 = ZooKeeperServerEmbedded.builder().configuration(configZookeeper2).baseDir(baseDir2).exitHandler(ExitHandler.LOG_ONLY).build();
                ZooKeeperServerEmbedded zkServer3 = ZooKeeperServerEmbedded.builder().configuration(configZookeeper3).baseDir(baseDir3).exitHandler(ExitHandler.LOG_ONLY).build();) {
            zkServer1.start();
            zkServer2.start();
            zkServer3.start();

            assertTrue(ClientBase.waitForServerUp(zkServer1.getConnectionString(), 60000));
            assertTrue(ClientBase.waitForServerUp(zkServer2.getConnectionString(), 60000));
            assertTrue(ClientBase.waitForServerUp(zkServer3.getConnectionString(), 60000));

            for (int i = 0; i < 100; i++) {
                ZookeeperServeInfo.ServerInfo status = ZookeeperServeInfo.getStatus("ReplicatedServer*");
                System.out.println("status:" + status);
                if (status.isLeader() && !status.isStandaloneMode() && status.getPeers().size() == 3) {
                    break;
                }
                Thread.sleep(100);
            }
            ZookeeperServeInfo.ServerInfo status = ZookeeperServeInfo.getStatus("ReplicatedServer*");
            assertTrue(status.isLeader());
            assertTrue(!status.isStandaloneMode());
            assertEquals(3, status.getPeers().size());
        }
    }

}
