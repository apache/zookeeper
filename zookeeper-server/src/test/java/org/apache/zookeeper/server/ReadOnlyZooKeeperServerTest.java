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

package org.apache.zookeeper.server;

import java.io.File;
import java.io.IOException;
import java.util.Vector;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.common.X509Exception.SSLContextException;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.QuorumBase;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import static org.apache.zookeeper.client.FourLetterWordMain.send4LetterWord;

public class ReadOnlyZooKeeperServerTest extends QuorumPeerTestBase {
    private static final org.slf4j.Logger LOG = LoggerFactory
            .getLogger(ReadOnlyZooKeeperServerTest.class);

    private static int CONNECTION_TIMEOUT = QuorumBase.CONNECTION_TIMEOUT;
    public static int WAIT_TIMEOUT = 60000;

    Vector<MainThread> servers = new Vector<MainThread>();
    Vector<ServerConfig> configs = new Vector<ServerConfig>();

    int[] SERVERS = { 0, 1, 2, 3 };
    int[] PARTICIPANTS = { 0, 1, 2 };
    int[] OBSERVER = { 3 };

    int OBSERVER_ID = 3;
    int OBSERVER_PORT;

    /**
     * Make sure the response is returned in order in RO mode.
     */
    @Test
    public void testResponseOrder() throws Exception {
        System.setProperty("readonlymode.enabled", "true");

        ClientBase.setupTestEnv();
        setupServers(true);

        startup(OBSERVER, false);
        waitForISRO("ro", OBSERVER_PORT, 60);

        final ZooKeeper rozk = new ZooKeeper("127.0.0.1:" +
            OBSERVER_PORT, ClientBase.CONNECTION_TIMEOUT, this, true);
        waitForOne(rozk, States.CONNECTEDREADONLY);

        final int requestCount = 1000;
        final AtomicInteger successGetResponseCount = new AtomicInteger();
        final CountDownLatch getResponseLatch = new CountDownLatch(requestCount);
        Thread getDataThread = new Thread(new Runnable() {
            @Override
            public void run() {
                int requestIssued = 0;
                while (requestIssued++ < requestCount) {
                    rozk.getData("/", null, new DataCallback() {
                        @Override
                        public void processResult(int rc, String path,
                                Object ctx, byte data[], Stat stat) {
                              if (rc == 0) {
                                  successGetResponseCount.incrementAndGet();
                              }
                              getResponseLatch.countDown();
                        }
                    }, null);
                }
            }
        });
        final CountDownLatch setResponseLatch = new CountDownLatch(requestCount);
        Thread setDataThread = new Thread(new Runnable() {
            @Override
            public void run() {
                int requestIssued = 0;
                while (requestIssued++ < requestCount) {
                    rozk.setData("/test", new byte[0], -1, new StatCallback() {
                        @Override
                        public void processResult(int rc, String path,
                                Object ctx, Stat stat) {
                            setResponseLatch.countDown();
                        }
                    }, null);
                }
            }
        });

        getDataThread.start();
        setDataThread.start();

        Assert.assertTrue(getResponseLatch.await(2, TimeUnit.SECONDS));
        Assert.assertTrue(setResponseLatch.await(2, TimeUnit.SECONDS));
        Assert.assertEquals(requestCount, successGetResponseCount.get());

        rozk.close();
        shutdown(OBSERVER);
    }

    /**
     * Setup servers (3 participants + 1 observer)
     */
    void setupServers(boolean localSessionsEnabled) throws IOException {
        configs.clear();
        servers.clear();
        Vector<ServerConfig> ensembleConfigs = new Vector<ServerConfig>();

        for (int i = 0; i < 4; i++) {
            ServerConfig config = new ServerConfig(i);
            configs.add(config);
            if (i != OBSERVER_ID) {
                ensembleConfigs.add(config);
            }
        }

        StringBuilder hostList = new StringBuilder();
        for (ServerConfig config : configs) {
            hostList.append("server." + config.id + "=127.0.0.1:"
                    + config.qpPort + ":" + config.lePort);
            if (config.id == OBSERVER_ID) {
                hostList.append(":observer");
            }
            hostList.append("\n");
        }

        String quorumCfgSection = hostList.toString();

        for (ServerConfig config : configs) {
            MainThread server = new MainThread(config.id, config.clientPort,
                        quorumCfgSection, "localSessionsEnabled=" +
                        Boolean.toString(localSessionsEnabled) + "\n" +
                        "localSessionsUpgradingEnabled=" +
                        Boolean.toString(localSessionsEnabled) + "\n");
            if (config.id != OBSERVER_ID) {
                config.confFile = server.getConfFile();
            }
            servers.add(server);
        }

        OBSERVER_PORT = configs.get(OBSERVER_ID).clientPort;
    }

    void startup(int[] serverIds) {
        startup(serverIds, true);
    }

    void startup(int[] serverIds, boolean verify) {
        for (int i : serverIds) {
            servers.get(i).start();
        }
        if (verify) {
            for (int i : serverIds) {
                if (!ClientBase.waitForServerUp("127.0.0.1:"
                        + configs.get(i).clientPort, WAIT_TIMEOUT)) {
                    throw new RuntimeException("Server " + i
                            + " did not start up");
                }
            }
        }
    }

    void shutdown(int[] serverIds) throws InterruptedException {
        for (int i : serverIds) {
            servers.get(i).shutdown();
        }
        for (int i : serverIds) {
            if (!ClientBase.waitForServerDown("127.0.0.1:"
                    + configs.get(i).clientPort, WAIT_TIMEOUT)) {
                throw new RuntimeException("Server " + i + " did not shutdown");
            }
        }
    }

    /**
     * Wait for server rw/ro state change
     *
     * @param mode
     *            "ro" or "rw"
     * @param port
     * @param timeout
     * @return
     * @throws InterruptedException
     * @throws IOException
     */
    boolean waitForISRO(String mode, int port, int timeout)
            throws InterruptedException, IOException, SSLContextException {
        for (int i = 0; i < timeout; ++i) {
            Thread.sleep(1000);
            String resp = send4LetterWord("127.0.0.1", port, "isro");
            LOG.info("ISRO response for [" + port + "] ==" + resp + "==" + mode);
            if (resp.startsWith(mode)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Store server configuration information. Backup ports are used to simulate
     * partitioning between observer and participants.
     */
    class ServerConfig {

        int qpPort;
        int lePort;
        int clientPort;
        int qpBackupPort;
        int leBackupPort;

        int id;
        File confFile;

        ServerConfig(int id) {
            qpPort = PortAssignment.unique();
            lePort = PortAssignment.unique();
            clientPort = PortAssignment.unique();
            this.id = id;
            if (id != OBSERVER_ID) {
                qpBackupPort = PortAssignment.unique();
                leBackupPort = PortAssignment.unique();
            }
        }
    }

}
