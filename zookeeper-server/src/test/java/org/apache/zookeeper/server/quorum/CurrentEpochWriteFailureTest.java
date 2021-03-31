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

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.io.IOException;
import org.apache.commons.io.FileUtils;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.common.AtomicFileOutputStream;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CurrentEpochWriteFailureTest extends QuorumPeerTestBase {
    protected static final Logger LOG = LoggerFactory.getLogger(CurrentEpochWriteFailureTest.class);
    private Servers servers;
    private int clientPort;

    @AfterEach
    public void tearDown() throws InterruptedException {
        if (servers != null) {
            servers.shutDownAllServers();
        }
    }

    /*
     * ZOOKEEPER-4269:
     * accepted epoch is first written to temporary file acceptedEpoch.tmp then this file is
     * renamed to acceptedEpoch.
     * Failure, either because of exception or power-off, in renaming the acceptedEpoch.tmp file
     * will cause server startup error with message "The current epoch, x, is older than the last
     * zxid y"
     * To handle this scenario we should read accepted epoch from this temp file as well.
     */
    @Test
    public void testReadCurrentEpochFromAcceptedEpochTmpFile() throws Exception {
        startServers();
        writeSomeData();

        restartServers();
        writeSomeData();

        MainThread firstServer = servers.mt[0];

        // As started servers two times, current epoch must be two
        long currentEpoch = firstServer.getQuorumPeer().getCurrentEpoch();
        assertEquals(2, currentEpoch);

        // Initialize files for later use
        File snapDir = firstServer.getQuorumPeer().getTxnFactory().getSnapDir();
        File currentEpochFile = new File(snapDir, QuorumPeer.CURRENT_EPOCH_FILENAME);
        File currentEpochTempFile = new File(snapDir,
            QuorumPeer.CURRENT_EPOCH_FILENAME + AtomicFileOutputStream.TMP_EXTENSION);

        // Shutdown servers
        servers.shutDownAllServers();
        waitForAll(servers, ZooKeeper.States.CONNECTING);

        // Create scenario of file currentEpoch.tmp rename to currentEpoch failure.
        // In this case currentEpoch file will have old epoch and currentEpoch.tmp will have the latest epoch
        FileUtils.write(currentEpochFile, Long.toString(currentEpoch - 1), "UTF-8");
        FileUtils.write(currentEpochTempFile, Long.toString(currentEpoch), "UTF-8");

        // Restart the serves, all serves should restart successfully.
        servers.restartAllServersAndClients(this);

        // Check the first server where problem was injected.
        assertTrue(ClientBase
                .waitForServerUp("127.0.0.1:" + firstServer.getClientPort(), CONNECTION_TIMEOUT),
            "server " + firstServer.getMyid()
                + " is not up as file currentEpoch.tmp rename to currentEpoch file was failed"
                + " which lead current epoch inconsistent state.");
    }

    private void restartServers() throws InterruptedException, IOException {
        servers.shutDownAllServers();
        waitForAll(servers, ZooKeeper.States.CONNECTING);
        servers.restartAllServersAndClients(this);
        waitForAll(servers, ZooKeeper.States.CONNECTED);
    }

    private void writeSomeData() throws Exception {
        ZooKeeper client = ClientBase.createZKClient("127.0.0.1:" + clientPort);
        String path = "/somePath" + System.currentTimeMillis();
        String data = "someData";
        client.create(path, data.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        byte[] data1 = client.getData(path, false, null);
        assertEquals(data, new String(data1));
        client.close();
    }

    private void startServers() throws Exception {
        servers = LaunchServers(3);
        clientPort = servers.clientPorts[0];
    }
}
