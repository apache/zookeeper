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

import static org.apache.zookeeper.client.FourLetterWordMain.send4LetterWord;
import static org.apache.zookeeper.server.command.AbstractFourLetterCommand.ZK_NOT_SERVING;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.common.X509Exception.SSLContextException;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class tests the startup behavior of ZooKeeper server.
 */
public class ZooKeeperServerStartupTest extends ZKTestCase {

    private static final Logger LOG = LoggerFactory.getLogger(ZooKeeperServerStartupTest.class);
    private static int PORT = PortAssignment.unique();
    private static String HOST = "127.0.0.1";
    private static String HOSTPORT = HOST + ":" + PORT;

    private ServerCnxnFactory servcnxnf;
    private ZooKeeperServer zks;
    private File tmpDir;
    private CountDownLatch startupDelayLatch = new CountDownLatch(1);

    @AfterEach
    public void teardown() throws Exception {
        // count down to avoid infinite blocking call due to this latch, if
        // any.
        startupDelayLatch.countDown();

        if (servcnxnf != null) {
            servcnxnf.shutdown();
        }
        if (zks != null) {
            zks.shutdown();
        }
        if (zks.getZKDatabase() != null) {
            zks.getZKDatabase().close();
        }
        ClientBase.recursiveDelete(tmpDir);
    }

    /**
     * Test case for
     * https://issues.apache.org/jira/browse/ZOOKEEPER-2383
     */
    @Test
    @Timeout(value = 30)
    public void testClientConnectionRequestDuringStartupWithNIOServerCnxn() throws Exception {
        tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();

        startSimpleZKServer(startupDelayLatch);
        SimpleZooKeeperServer simplezks = (SimpleZooKeeperServer) zks;
        assertTrue(simplezks.waitForStartupInvocation(10), "Failed to invoke zks#startup() method during server startup");

        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zkClient = new ZooKeeper(HOSTPORT, ClientBase.CONNECTION_TIMEOUT, watcher);

        assertFalse(simplezks.waitForSessionCreation(5),
            "Since server is not fully started, zks#createSession() shouldn't be invoked");

        LOG.info("Decrements the count of the latch, so that server will proceed with startup");
        startupDelayLatch.countDown();

        assertTrue(ClientBase.waitForServerUp(HOSTPORT, ClientBase.CONNECTION_TIMEOUT), "waiting for server being up ");

        assertTrue(simplezks.waitForSessionCreation(5),
            "Failed to invoke zks#createSession() method during client session creation");
        watcher.waitForConnected(ClientBase.CONNECTION_TIMEOUT);
        zkClient.close();
    }

    /**
     * Test case for
     * https://issues.apache.org/jira/browse/ZOOKEEPER-2383
     */
    @Test
    @Timeout(value = 30)
    public void testClientConnectionRequestDuringStartupWithNettyServerCnxn() throws Exception {
        tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();

        String originalServerCnxnFactory = System.getProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
        try {
            System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, NettyServerCnxnFactory.class.getName());
            startSimpleZKServer(startupDelayLatch);
            SimpleZooKeeperServer simplezks = (SimpleZooKeeperServer) zks;
            assertTrue(simplezks.waitForStartupInvocation(10), "Failed to invoke zks#startup() method during server startup");

            CountdownWatcher watcher = new CountdownWatcher();
            ZooKeeper zkClient = new ZooKeeper(HOSTPORT, ClientBase.CONNECTION_TIMEOUT, watcher);

            assertFalse(simplezks.waitForSessionCreation(5), "Since server is not fully started, zks#createSession() shouldn't be invoked");

            LOG.info("Decrements the count of the latch, so that server will proceed with startup");
            startupDelayLatch.countDown();

            assertTrue(ClientBase.waitForServerUp(HOSTPORT, ClientBase.CONNECTION_TIMEOUT), "waiting for server being up ");

            assertTrue(simplezks.waitForSessionCreation(5), "Failed to invoke zks#createSession() method during client session creation");
            watcher.waitForConnected(ClientBase.CONNECTION_TIMEOUT);
            zkClient.close();
        } finally {
            // reset cnxn factory
            if (originalServerCnxnFactory == null) {
                System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
                return;
            }
            System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, originalServerCnxnFactory);
        }
    }

    /**
     * Test case for
     * https://issues.apache.org/jira/browse/ZOOKEEPER-2383
     */
    @Test
    @Timeout(value = 30)
    public void testFourLetterWords() throws Exception {
        startSimpleZKServer(startupDelayLatch);
        verify("conf", ZK_NOT_SERVING);
        verify("crst", ZK_NOT_SERVING);
        verify("cons", ZK_NOT_SERVING);
        verify("dirs", ZK_NOT_SERVING);
        verify("dump", ZK_NOT_SERVING);
        verify("mntr", ZK_NOT_SERVING);
        verify("stat", ZK_NOT_SERVING);
        verify("srst", ZK_NOT_SERVING);
        verify("wchp", ZK_NOT_SERVING);
        verify("wchc", ZK_NOT_SERVING);
        verify("wchs", ZK_NOT_SERVING);
        verify("isro", "null");
    }

    private void verify(String cmd, String expected) throws IOException, SSLContextException {
        String resp = sendRequest(cmd);
        LOG.info("cmd {} expected {} got {}", cmd, expected, resp);
        assertTrue(resp.contains(expected), "Unexpected response");
    }

    private String sendRequest(String cmd) throws IOException, SSLContextException {
        return send4LetterWord(HOST, PORT, cmd);
    }

    private void startSimpleZKServer(CountDownLatch startupDelayLatch) throws IOException {
        zks = new SimpleZooKeeperServer(tmpDir, tmpDir, 3000, startupDelayLatch);
        SyncRequestProcessor.setSnapCount(100);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);

        servcnxnf = ServerCnxnFactory.createFactory(PORT, -1);
        Thread startupThread = new Thread() {
            public void run() {
                try {
                    servcnxnf.startup(zks);
                } catch (IOException e) {
                    LOG.error("Unexcepted exception during server startup", e);
                    // Ignoring exception. If there is an ioexception
                    // then one of the following assertion will fail
                } catch (InterruptedException e) {
                    LOG.error("Unexcepted exception during server startup", e);
                    // Ignoring exception. If there is an interrupted exception
                    // then one of the following assertion will fail
                }
            }
        };
        LOG.info("Starting zk server {}", HOSTPORT);
        startupThread.start();
    }

    private static class SimpleZooKeeperServer extends ZooKeeperServer {

        private CountDownLatch startupDelayLatch;
        private CountDownLatch startupInvokedLatch = new CountDownLatch(1);
        private CountDownLatch createSessionInvokedLatch = new CountDownLatch(1);

        public SimpleZooKeeperServer(File snapDir, File logDir, int tickTime, CountDownLatch startupDelayLatch) throws IOException {
            super(snapDir, logDir, tickTime);
            this.startupDelayLatch = startupDelayLatch;
        }

        @Override
        public synchronized void startup() {
            try {
                startupInvokedLatch.countDown();
                // Delaying the zk server startup so that
                // ZooKeeperServer#sessionTracker reference won't be
                // initialized. In the defect scenario, while processing the
                // connection request zkServer needs sessionTracker reference,
                // but this is not yet initialized and the server is still in
                // the startup phase, resulting in NPE.
                startupDelayLatch.await();
            } catch (InterruptedException e) {
                fail("Unexpected InterruptedException while startinng up!");
            }
            super.startup();
        }

        @Override
        long createSession(ServerCnxn cnxn, byte[] passwd, int timeout) {
            createSessionInvokedLatch.countDown();
            return super.createSession(cnxn, passwd, timeout);
        }

        boolean waitForStartupInvocation(long timeout) throws InterruptedException {
            return startupInvokedLatch.await(timeout, TimeUnit.SECONDS);
        }

        boolean waitForSessionCreation(long timeout) throws InterruptedException {
            return createSessionInvokedLatch.await(timeout, TimeUnit.SECONDS);
        }

    }

}
