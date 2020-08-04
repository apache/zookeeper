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

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.common.PathUtils;
import org.apache.zookeeper.metrics.BaseTestMetricsProvider;
import org.apache.zookeeper.metrics.BaseTestMetricsProvider.MetricsProviderCapturingLifecycle;
import org.apache.zookeeper.metrics.BaseTestMetricsProvider.MetricsProviderWithConfiguration;
import org.apache.zookeeper.metrics.BaseTestMetricsProvider.MetricsProviderWithErrorInConfigure;
import org.apache.zookeeper.metrics.BaseTestMetricsProvider.MetricsProviderWithErrorInStart;
import org.apache.zookeeper.metrics.BaseTestMetricsProvider.MetricsProviderWithErrorInStop;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test stand-alone server.
 *
 */
public class ZooKeeperServerMainTest extends ZKTestCase implements Watcher {

    protected static final Logger LOG = LoggerFactory.getLogger(ZooKeeperServerMainTest.class);

    private CountDownLatch clientConnected = new CountDownLatch(1);

    public static class MainThread extends Thread {

        final File confFile;
        final TestZKSMain main;
        final File tmpDir;
        final File dataDir;
        final File logDir;

        public MainThread(int clientPort, boolean preCreateDirs, String configs) throws IOException {
            this(clientPort, preCreateDirs, ClientBase.createTmpDir(), configs);
        }

        public MainThread(int clientPort, boolean preCreateDirs, File tmpDir, String configs) throws IOException {
            super("Standalone server with clientPort:" + clientPort);
            this.tmpDir = tmpDir;
            confFile = new File(tmpDir, "zoo.cfg");

            FileWriter fwriter = new FileWriter(confFile);
            fwriter.write("tickTime=2000\n");
            fwriter.write("initLimit=10\n");
            fwriter.write("syncLimit=5\n");
            if (configs != null) {
                fwriter.write(configs);
            }

            dataDir = new File(this.tmpDir, "data");
            logDir = new File(dataDir.toString() + "_txnlog");
            if (preCreateDirs) {
                if (!dataDir.mkdir()) {
                    throw new IOException("unable to mkdir " + dataDir);
                }
                if (!logDir.mkdir()) {
                    throw new IOException("unable to mkdir " + logDir);
                }
                ClientBase.createInitializeFile(logDir);
            }

            String normalizedDataDir = PathUtils.normalizeFileSystemPath(dataDir.toString());
            String normalizedLogDir = PathUtils.normalizeFileSystemPath(logDir.toString());
            fwriter.write("dataDir=" + normalizedDataDir + "\n");
            fwriter.write("dataLogDir=" + normalizedLogDir + "\n");
            fwriter.write("clientPort=" + clientPort + "\n");
            fwriter.flush();
            fwriter.close();

            main = new TestZKSMain();
        }

        public void run() {
            String[] args = new String[1];
            args[0] = confFile.toString();
            try {
                main.initializeAndRun(args);
            } catch (Exception e) {
                // test will still fail even though we just log/ignore
                LOG.error("unexpected exception in run", e);
            }
        }

        public void shutdown() throws IOException {
            main.shutdown();
        }

        void deleteDirs() throws IOException {
            delete(tmpDir);
        }

        void delete(File f) throws IOException {
            if (f.isDirectory()) {
                for (File c : f.listFiles()) {
                    delete(c);
                }
            }
            if (!f.delete()) {
                // double check for the file existence
                if (f.exists()) {
                    throw new IOException("Failed to delete file: " + f);
                }
            }
        }

        ServerCnxnFactory getCnxnFactory() {
            return main.getCnxnFactory();
        }

    }

    public static class TestZKSMain extends ZooKeeperServerMain {

        public void shutdown() {
            super.shutdown();
        }

    }

    /**
     * Test case for https://issues.apache.org/jira/browse/ZOOKEEPER-2247.
     * Test to verify that even after non recoverable error (error while
     * writing transaction log), ZooKeeper is still available.
     */
    @Test
    @Timeout(value = 30)
    public void testNonRecoverableError() throws Exception {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT = PortAssignment.unique();

        MainThread main = new MainThread(CLIENT_PORT, true, null);
        main.start();

        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT, CONNECTION_TIMEOUT),
                "waiting for server being up");

        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT, ClientBase.CONNECTION_TIMEOUT, this);

        zk.create("/foo1", "foobar".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertEquals(new String(zk.getData("/foo1", null, null)), "foobar");

        // inject problem in server
        ZooKeeperServer zooKeeperServer = main.getCnxnFactory().getZooKeeperServer();
        FileTxnSnapLog snapLog = zooKeeperServer.getTxnLogFactory();
        FileTxnSnapLog fileTxnSnapLogWithError = new FileTxnSnapLog(snapLog.getDataDir(), snapLog.getSnapDir()) {
            @Override
            public void commit() throws IOException {
                throw new IOException("Input/output error");
            }
        };
        ZKDatabase newDB = new ZKDatabase(fileTxnSnapLogWithError);
        zooKeeperServer.setZKDatabase(newDB);

        try {
            // do create operation, so that injected IOException is thrown
            zk.create("/foo2", "foobar".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            fail("IOException is expected as error is injected in transaction log commit funtionality");
        } catch (Exception e) {
            // do nothing
        }
        zk.close();
        assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT, ClientBase.CONNECTION_TIMEOUT),
                "waiting for server down");
        fileTxnSnapLogWithError.close();
        main.shutdown();
        main.deleteDirs();
    }

    /**
     * Tests that the ZooKeeper server will fail to start if the
     * snapshot directory is read only.
     *
     * This test will fail if it is executed as root user.
     */
    @Test
    @Timeout(value = 30)
    public void testReadOnlySnapshotDir() throws Exception {
        ClientBase.setupTestEnv();
        final int CLIENT_PORT = PortAssignment.unique();

        // Start up the ZK server to automatically create the necessary directories
        // and capture the directory where data is stored
        MainThread main = new MainThread(CLIENT_PORT, true, null);
        File tmpDir = main.tmpDir;
        main.start();
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT, CONNECTION_TIMEOUT / 2),
                "waiting for server being up");
        main.shutdown();

        // Make the snapshot directory read only
        File snapDir = new File(main.dataDir, FileTxnSnapLog.version + FileTxnSnapLog.VERSION);
        snapDir.setWritable(false);

        // Restart ZK and observe a failure
        main = new MainThread(CLIENT_PORT, false, tmpDir, null);
        main.start();

        assertFalse(ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT, CONNECTION_TIMEOUT / 2),
                "waiting for server being up");

        main.shutdown();

        snapDir.setWritable(true);

        main.deleteDirs();
    }

    /**
     * Tests that the ZooKeeper server will fail to start if the
     * transaction log directory is read only.
     *
     * This test will fail if it is executed as root user.
     */
    @Test
    @Timeout(value = 30)
    public void testReadOnlyTxnLogDir() throws Exception {
        ClientBase.setupTestEnv();
        final int CLIENT_PORT = PortAssignment.unique();

        // Start up the ZK server to automatically create the necessary directories
        // and capture the directory where data is stored
        MainThread main = new MainThread(CLIENT_PORT, true, null);
        File tmpDir = main.tmpDir;
        main.start();
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT, CONNECTION_TIMEOUT / 2),
                "waiting for server being up");
        main.shutdown();

        // Make the transaction log directory read only
        File logDir = new File(main.logDir, FileTxnSnapLog.version + FileTxnSnapLog.VERSION);
        logDir.setWritable(false);

        // Restart ZK and observe a failure
        main = new MainThread(CLIENT_PORT, false, tmpDir, null);
        main.start();

        assertFalse(ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT, CONNECTION_TIMEOUT / 2),
                "waiting for server being up");

        main.shutdown();

        logDir.setWritable(true);

        main.deleteDirs();
    }

    /**
     * Verify the ability to start a standalone server instance.
     */
    @Test
    public void testStandalone() throws Exception {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT = PortAssignment.unique();

        MainThread main = new MainThread(CLIENT_PORT, true, null);
        main.start();

        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT, CONNECTION_TIMEOUT),
                "waiting for server being up");

        clientConnected = new CountDownLatch(1);
        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT, ClientBase.CONNECTION_TIMEOUT, this);
        assertTrue(clientConnected.await(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS), "Failed to establish zkclient connection!");

        zk.create("/foo", "foobar".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertEquals(new String(zk.getData("/foo", null, null)), "foobar");
        zk.close();

        main.shutdown();
        main.join();
        main.deleteDirs();

        assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT, ClientBase.CONNECTION_TIMEOUT),
                "waiting for server down");
    }

    /**
     * Test verifies that the server shouldn't allow minsessiontimeout greater than
     * maxsessiontimeout
     */
    @Test
    public void testWithMinSessionTimeoutGreaterThanMaxSessionTimeout() throws Exception {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT = PortAssignment.unique();
        final int tickTime = 2000;
        final int minSessionTimeout = 20 * tickTime + 1000; // min is higher
        final int maxSessionTimeout = tickTime * 2 - 100; // max is lower
        final String configs = "maxSessionTimeout="
                                       + maxSessionTimeout
                                       + "\n"
                                       + "minSessionTimeout="
                                       + minSessionTimeout
                                       + "\n";
        MainThread main = new MainThread(CLIENT_PORT, true, configs);
        String[] args = new String[1];
        args[0] = main.confFile.toString();
        try {
            main.main.initializeAndRun(args);
            fail("Must throw exception as " + "minsessiontimeout > maxsessiontimeout");
        } catch (ConfigException iae) {
            // expected
        }
    }

    /**
     * Test verifies that the server shouldn't boot with an invalid metrics provider
     */
    @Test
    public void testInvalidMetricsProvider() throws Exception {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT = PortAssignment.unique();
        final String configs = "metricsProvider.className=BadClass\n";
        MainThread main = new MainThread(CLIENT_PORT, true, configs);
        String[] args = new String[1];
        args[0] = main.confFile.toString();
        try {
            main.main.initializeAndRun(args);
            fail("Must throw exception as metrics provider is not " + "well configured");
        } catch (ConfigException iae) {
            // expected
        }
    }

    /**
     * Test verifies that the server shouldn't boot with a faulty metrics provider
     */
    @Test
    public void testFaultyMetricsProviderOnStart() throws Exception {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT = PortAssignment.unique();
        final String configs = "metricsProvider.className=" + MetricsProviderWithErrorInStart.class.getName() + "\n";
        MainThread main = new MainThread(CLIENT_PORT, true, configs);
        String[] args = new String[1];
        args[0] = main.confFile.toString();
        try {
            main.main.initializeAndRun(args);
            fail("Must throw exception as metrics provider cannot boot");
        } catch (IOException iae) {
            // expected
        }
    }

    /**
     * Test verifies that the server shouldn't boot with a faulty metrics provider
     */
    @Test
    public void testFaultyMetricsProviderOnConfigure() throws Exception {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT = PortAssignment.unique();
        final String configs = "metricsProvider.className="
                                       + MetricsProviderWithErrorInConfigure.class.getName()
                                       + "\n";
        MainThread main = new MainThread(CLIENT_PORT, true, configs);
        String[] args = new String[1];
        args[0] = main.confFile.toString();
        try {
            main.main.initializeAndRun(args);
            fail("Must throw exception as metrics provider is cannot boot");
        } catch (IOException iae) {
            // expected
        }
    }

    /**
     * Test verifies that the server shouldn't be affected but runtime errors on stop()
     */
    @Test
    public void testFaultyMetricsProviderOnStop() throws Exception {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT = PortAssignment.unique();
        MetricsProviderWithErrorInStop.stopCalled.set(false);
        final String configs = "metricsProvider.className=" + MetricsProviderWithErrorInStop.class.getName() + "\n";
        MainThread main = new MainThread(CLIENT_PORT, true, configs);
        main.start();

        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT, CONNECTION_TIMEOUT),
                "waiting for server being up");

        clientConnected = new CountDownLatch(1);
        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT, ClientBase.CONNECTION_TIMEOUT, this);
        assertTrue(clientConnected.await(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS), "Failed to establish zkclient connection!");

        zk.create("/foo", "foobar".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertEquals(new String(zk.getData("/foo", null, null)), "foobar");
        zk.close();

        main.shutdown();
        main.join();
        main.deleteDirs();

        assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT, ClientBase.CONNECTION_TIMEOUT),
                "waiting for server down");
        assertTrue(MetricsProviderWithErrorInStop.stopCalled.get());
    }

    /**
     * Test verifies that configuration is passed to the MetricsProvider.
     */
    @Test
    public void testMetricsProviderConfiguration() throws Exception {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT = PortAssignment.unique();
        MetricsProviderWithConfiguration.httpPort.set(0);
        final String configs = "metricsProvider.className="
                                       + MetricsProviderWithConfiguration.class.getName()
                                       + "\n"
                                       + "metricsProvider.httpPort=1234\n";
        MainThread main = new MainThread(CLIENT_PORT, true, configs);
        main.start();

        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT, CONNECTION_TIMEOUT),
                "waiting for server being up");

        clientConnected = new CountDownLatch(1);
        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT, ClientBase.CONNECTION_TIMEOUT, this);
        assertTrue(clientConnected.await(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS), "Failed to establish zkclient connection!");

        zk.create("/foo", "foobar".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertEquals(new String(zk.getData("/foo", null, null)), "foobar");
        zk.close();

        main.shutdown();
        main.join();
        main.deleteDirs();

        assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT, ClientBase.CONNECTION_TIMEOUT),
                "waiting for server down");
        assertEquals(1234, MetricsProviderWithConfiguration.httpPort.get());
    }

    /**
     * Test verifies that all of the lifecycle methods of the MetricsProvider are called.
     */
    @Test
    public void testMetricsProviderLifecycle() throws Exception {
        ClientBase.setupTestEnv();
        MetricsProviderCapturingLifecycle.reset();

        final int CLIENT_PORT = PortAssignment.unique();
        final String configs = "metricsProvider.className="
                                       + MetricsProviderCapturingLifecycle.class.getName()
                                       + "\n"
                                       + "metricsProvider.httpPort=1234\n";
        MainThread main = new MainThread(CLIENT_PORT, true, configs);
        main.start();

        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT, CONNECTION_TIMEOUT),
                "waiting for server being up");

        clientConnected = new CountDownLatch(1);
        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT, ClientBase.CONNECTION_TIMEOUT, this);
        assertTrue(clientConnected.await(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS), "Failed to establish zkclient connection!");

        zk.create("/foo", "foobar".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertEquals(new String(zk.getData("/foo", null, null)), "foobar");
        zk.close();

        main.shutdown();
        main.join();
        main.deleteDirs();

        assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT, ClientBase.CONNECTION_TIMEOUT), "waiting for server down");
        assertTrue(BaseTestMetricsProvider.MetricsProviderCapturingLifecycle.configureCalled.get(), "metrics provider lifecycle error");
        assertTrue(BaseTestMetricsProvider.MetricsProviderCapturingLifecycle.startCalled.get(), "metrics provider lifecycle error");
        assertTrue(BaseTestMetricsProvider.MetricsProviderCapturingLifecycle.getRootContextCalled.get(), "metrics provider lifecycle error");
        assertTrue(BaseTestMetricsProvider.MetricsProviderCapturingLifecycle.stopCalled.get(), "metrics provider lifecycle error");
    }

    /**
     * Test verifies that the server is able to redefine if user configured only
     * minSessionTimeout limit
     */
    @Test
    public void testWithOnlyMinSessionTimeout() throws Exception {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT = PortAssignment.unique();
        final int tickTime = 2000;
        final int minSessionTimeout = tickTime * 2 - 100;
        int maxSessionTimeout = 20 * tickTime;
        final String configs = "minSessionTimeout=" + minSessionTimeout + "\n";
        MainThread main = new MainThread(CLIENT_PORT, true, configs);
        main.start();

        String HOSTPORT = "127.0.0.1:" + CLIENT_PORT;
        assertTrue(ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server being up");
        // create session with min value
        verifySessionTimeOut(minSessionTimeout, minSessionTimeout, HOSTPORT);
        verifySessionTimeOut(minSessionTimeout - 2000, minSessionTimeout, HOSTPORT);
        // create session with max value
        verifySessionTimeOut(maxSessionTimeout, maxSessionTimeout, HOSTPORT);
        verifySessionTimeOut(maxSessionTimeout + 2000, maxSessionTimeout, HOSTPORT);
        main.shutdown();
        assertTrue(ClientBase.waitForServerDown(HOSTPORT, ClientBase.CONNECTION_TIMEOUT), "waiting for server down");
    }

    /**
     * Test verifies that the server is able to redefine the min/max session
     * timeouts
     */
    @Test
    public void testMinMaxSessionTimeOut() throws Exception {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT = PortAssignment.unique();
        final int tickTime = 2000;
        final int minSessionTimeout = tickTime * 2 - 100;
        final int maxSessionTimeout = 20 * tickTime + 1000;
        final String configs = "maxSessionTimeout="
                                       + maxSessionTimeout
                                       + "\n"
                                       + "minSessionTimeout="
                                       + minSessionTimeout
                                       + "\n";
        MainThread main = new MainThread(CLIENT_PORT, true, configs);
        main.start();

        String HOSTPORT = "127.0.0.1:" + CLIENT_PORT;
        assertTrue(ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server being up");
        // create session with min value
        verifySessionTimeOut(minSessionTimeout, minSessionTimeout, HOSTPORT);
        verifySessionTimeOut(minSessionTimeout - 2000, minSessionTimeout, HOSTPORT);
        // create session with max value
        verifySessionTimeOut(maxSessionTimeout, maxSessionTimeout, HOSTPORT);
        verifySessionTimeOut(maxSessionTimeout + 2000, maxSessionTimeout, HOSTPORT);
        main.shutdown();

        assertTrue(ClientBase.waitForServerDown(HOSTPORT, ClientBase.CONNECTION_TIMEOUT), "waiting for server down");
    }

    private void verifySessionTimeOut(int sessionTimeout, int expectedSessionTimeout, String HOSTPORT) throws IOException, KeeperException, InterruptedException {
        clientConnected = new CountDownLatch(1);
        ZooKeeper zk = new ZooKeeper(HOSTPORT, sessionTimeout, this);
        assertTrue(clientConnected.await(sessionTimeout, TimeUnit.MILLISECONDS), "Failed to establish zkclient connection!");
        assertEquals(expectedSessionTimeout, zk.getSessionTimeout(), "Not able to configure the sessionTimeout values");
        zk.close();
    }

    @Test
    public void testJMXRegistrationWithNIO() throws Exception {
        ClientBase.setupTestEnv();
        File tmpDir_1 = ClientBase.createTmpDir();
        ServerCnxnFactory server_1 = startServer(tmpDir_1);
        File tmpDir_2 = ClientBase.createTmpDir();
        ServerCnxnFactory server_2 = startServer(tmpDir_2);

        server_1.shutdown();
        server_2.shutdown();

        deleteFile(tmpDir_1);
        deleteFile(tmpDir_2);
    }

    @Test
    public void testJMXRegistrationWithNetty() throws Exception {
        String originalServerCnxnFactory = System.getProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, NettyServerCnxnFactory.class.getName());
        try {
            ClientBase.setupTestEnv();
            File tmpDir_1 = ClientBase.createTmpDir();
            ServerCnxnFactory server_1 = startServer(tmpDir_1);
            File tmpDir_2 = ClientBase.createTmpDir();
            ServerCnxnFactory server_2 = startServer(tmpDir_2);

            server_1.shutdown();
            server_2.shutdown();

            deleteFile(tmpDir_1);
            deleteFile(tmpDir_2);
        } finally {
            // setting back
            if (originalServerCnxnFactory == null || originalServerCnxnFactory.isEmpty()) {
                System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
            } else {
                System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, originalServerCnxnFactory);
            }
        }
    }

    private void deleteFile(File f) throws IOException {
        if (f.isDirectory()) {
            for (File c : f.listFiles()) {
                deleteFile(c);
            }
        }
        if (!f.delete()) {
        // double check for the file existence

            if (f.exists()) {
                throw new IOException("Failed to delete file: " + f);
            }
        }
    }

    private ServerCnxnFactory startServer(File tmpDir) throws IOException, InterruptedException {
        final int CLIENT_PORT = PortAssignment.unique();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(CLIENT_PORT, -1);
        f.startup(zks);
        assertNotNull(zks.jmxServerBean, "JMX initialization failed!");
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT, CONNECTION_TIMEOUT),
                "waiting for server being up");
        return f;
    }

    public void process(WatchedEvent event) {
        if (event.getState() == KeeperState.SyncConnected) {
            clientConnected.countDown();
        }
    }

}
