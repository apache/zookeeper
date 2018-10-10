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

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.common.PathUtils;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test stand-alone server.
 *
 */
public class ZooKeeperServerMainTest extends ZKTestCase implements Watcher {
    protected static final Logger LOG =
        LoggerFactory.getLogger(ZooKeeperServerMainTest.class);

    private CountDownLatch clientConnected = new CountDownLatch(1);

    public static class MainThread extends Thread {
        final File confFile;
        final TestZKSMain main;
        final File tmpDir;
        final File dataDir;
        final File logDir;

        public MainThread(int clientPort, boolean preCreateDirs, String configs)
                throws IOException {
            this(clientPort, preCreateDirs, ClientBase.createTmpDir(), configs);
        }

        public MainThread(int clientPort, boolean preCreateDirs, File tmpDir, String configs)
                throws IOException {
            super("Standalone server with clientPort:" + clientPort);
            this.tmpDir = tmpDir;
            confFile = new File(tmpDir, "zoo.cfg");

            FileWriter fwriter = new FileWriter(confFile);
            fwriter.write("tickTime=2000\n");
            fwriter.write("initLimit=10\n");
            fwriter.write("syncLimit=5\n");
            if(configs != null){
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
            String args[] = new String[1];
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

        void deleteDirs() throws IOException{
            delete(tmpDir);
        }

        void delete(File f) throws IOException {
            if (f.isDirectory()) {
                for (File c : f.listFiles())
                    delete(c);
            }
            if (!f.delete())
                // double check for the file existence
                if (f.exists()) {
                    throw new IOException("Failed to delete file: " + f);
                }
        }

        ServerCnxnFactory getCnxnFactory() {
            return main.getCnxnFactory();
        }
    }

    public static  class TestZKSMain extends ZooKeeperServerMain {
        public void shutdown() {
            super.shutdown();
        }
    }

    /**
     * Test case for https://issues.apache.org/jira/browse/ZOOKEEPER-2247.
     * Test to verify that even after non recoverable error (error while
     * writing transaction log), ZooKeeper is still available.
     */
    @Test(timeout = 30000)
    public void testNonRecoverableError() throws Exception {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT = PortAssignment.unique();

        MainThread main = new MainThread(CLIENT_PORT, true, null);
        main.start();

        Assert.assertTrue("waiting for server being up",
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT,
                        CONNECTION_TIMEOUT));


        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT,
                ClientBase.CONNECTION_TIMEOUT, this);

        zk.create("/foo1", "foobar".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        Assert.assertEquals(new String(zk.getData("/foo1", null, null)), "foobar");

        // inject problem in server
        ZooKeeperServer zooKeeperServer = main.getCnxnFactory()
                .getZooKeeperServer();
        FileTxnSnapLog snapLog = zooKeeperServer.getTxnLogFactory();
        FileTxnSnapLog fileTxnSnapLogWithError = new FileTxnSnapLog(
                snapLog.getDataDir(), snapLog.getSnapDir()) {
            @Override
            public void commit() throws IOException {
                throw new IOException("Input/output error");
            }
        };
        ZKDatabase newDB = new ZKDatabase(fileTxnSnapLogWithError);
        zooKeeperServer.setZKDatabase(newDB);

        try {
            // do create operation, so that injected IOException is thrown
            zk.create("/foo2", "foobar".getBytes(), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);
            fail("IOException is expected as error is injected in transaction log commit funtionality");
        } catch (Exception e) {
            // do nothing
        }
        zk.close();
        Assert.assertTrue("waiting for server down",
                ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT,
                        ClientBase.CONNECTION_TIMEOUT));
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
    @Test(timeout = 30000)
    public void testReadOnlySnapshotDir() throws Exception {
        ClientBase.setupTestEnv();
        final int CLIENT_PORT = PortAssignment.unique();

        // Start up the ZK server to automatically create the necessary directories
        // and capture the directory where data is stored
        MainThread main = new MainThread(CLIENT_PORT, true, null);
        File tmpDir = main.tmpDir;
        main.start();
        Assert.assertTrue("waiting for server being up", ClientBase
                .waitForServerUp("127.0.0.1:" + CLIENT_PORT,
                        CONNECTION_TIMEOUT / 2));
        main.shutdown();

        // Make the snapshot directory read only
        File snapDir = new File(main.dataDir, FileTxnSnapLog.version + FileTxnSnapLog.VERSION);
        snapDir.setWritable(false);

        // Restart ZK and observe a failure
        main = new MainThread(CLIENT_PORT, false, tmpDir, null);
        main.start();

        Assert.assertFalse("waiting for server being up", ClientBase
                .waitForServerUp("127.0.0.1:" + CLIENT_PORT,
                        CONNECTION_TIMEOUT / 2));

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
    @Test(timeout = 30000)
    public void testReadOnlyTxnLogDir() throws Exception {
        ClientBase.setupTestEnv();
        final int CLIENT_PORT = PortAssignment.unique();

        // Start up the ZK server to automatically create the necessary directories
        // and capture the directory where data is stored
        MainThread main = new MainThread(CLIENT_PORT, true, null);
        File tmpDir = main.tmpDir;
        main.start();
        Assert.assertTrue("waiting for server being up", ClientBase
                .waitForServerUp("127.0.0.1:" + CLIENT_PORT,
                        CONNECTION_TIMEOUT / 2));
        main.shutdown();

        // Make the transaction log directory read only
        File logDir = new File(main.logDir, FileTxnSnapLog.version + FileTxnSnapLog.VERSION);
        logDir.setWritable(false);

        // Restart ZK and observe a failure
        main = new MainThread(CLIENT_PORT, false, tmpDir, null);
        main.start();

        Assert.assertFalse("waiting for server being up", ClientBase
                .waitForServerUp("127.0.0.1:" + CLIENT_PORT,
                        CONNECTION_TIMEOUT / 2));

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

        Assert.assertTrue("waiting for server being up",
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT,
                        CONNECTION_TIMEOUT));

        clientConnected = new CountDownLatch(1);
        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT,
                ClientBase.CONNECTION_TIMEOUT, this);
        Assert.assertTrue("Failed to establish zkclient connection!",
                clientConnected.await(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS));

        zk.create("/foo", "foobar".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        Assert.assertEquals(new String(zk.getData("/foo", null, null)), "foobar");
        zk.close();

        main.shutdown();
        main.join();
        main.deleteDirs();

        Assert.assertTrue("waiting for server down",
                ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT,
                        ClientBase.CONNECTION_TIMEOUT));
    }

    /**
     * Test verifies server should fail when data dir or data log dir doesn't
     * exists. Sets "zookeeper.datadir.autocreate" to false.
     */
    @Test(timeout = 30000)
    public void testWithoutAutoCreateDataLogDir() throws Exception {
        ClientBase.setupTestEnv();
        System.setProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE, "false");
        try {
            final int CLIENT_PORT = PortAssignment.unique();

            MainThread main = new MainThread(CLIENT_PORT, false, null);
            String args[] = new String[1];
            args[0] = main.confFile.toString();
            main.start();

            Assert.assertFalse("waiting for server being up", ClientBase
                    .waitForServerUp("127.0.0.1:" + CLIENT_PORT,
                            CONNECTION_TIMEOUT / 2));
        } finally {
            // resets "zookeeper.datadir.autocreate" flag
            System.setProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE,
                    FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE_DEFAULT);
        }
    }

    /**
     * Test verifies the auto creation of data dir and data log dir.
     * Sets "zookeeper.datadir.autocreate" to true.
     */
    @Test(timeout = 30000)
    public void testWithAutoCreateDataLogDir() throws Exception {
        ClientBase.setupTestEnv();
        System.setProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE, "true");
        final int CLIENT_PORT = PortAssignment.unique();

        MainThread main = new MainThread(CLIENT_PORT, false, null);
        String args[] = new String[1];
        args[0] = main.confFile.toString();
        main.start();

        Assert.assertTrue("waiting for server being up",
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT,
                        CONNECTION_TIMEOUT));
        clientConnected = new CountDownLatch(1);
        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT,
                ClientBase.CONNECTION_TIMEOUT, this);
        Assert.assertTrue("Failed to establish zkclient connection!",
                clientConnected.await(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS));

        zk.create("/foo", "foobar".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        Assert.assertEquals(new String(zk.getData("/foo", null, null)),
                "foobar");
        zk.close();

        main.shutdown();
        main.join();
        main.deleteDirs();

        Assert.assertTrue("waiting for server down", ClientBase
                .waitForServerDown("127.0.0.1:" + CLIENT_PORT,
                        ClientBase.CONNECTION_TIMEOUT));
    }

    /**
     * Test verifies that the server shouldn't allow minsessiontimeout >
     * maxsessiontimeout
     */
    @Test
    public void testWithMinSessionTimeoutGreaterThanMaxSessionTimeout()
            throws Exception {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT = PortAssignment.unique();
        final int tickTime = 2000;
        final int minSessionTimeout = 20 * tickTime + 1000; // min is higher
        final int maxSessionTimeout = tickTime * 2 - 100; // max is lower
        final String configs = "maxSessionTimeout=" + maxSessionTimeout + "\n"
                + "minSessionTimeout=" + minSessionTimeout + "\n";
        MainThread main = new MainThread(CLIENT_PORT, false, configs);
        String args[] = new String[1];
        args[0] = main.confFile.toString();
        try {
            main.main.initializeAndRun(args);
            Assert.fail("Must throw exception as "
                    + "minsessiontimeout > maxsessiontimeout");
        } catch (ConfigException iae) {
            // expected
        }
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
        MainThread main = new MainThread(CLIENT_PORT, false, configs);
        main.start();

        String HOSTPORT = "127.0.0.1:" + CLIENT_PORT;
        Assert.assertTrue("waiting for server being up",
                ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT));
        // create session with min value
        verifySessionTimeOut(minSessionTimeout, minSessionTimeout, HOSTPORT);
        verifySessionTimeOut(minSessionTimeout - 2000, minSessionTimeout,
                HOSTPORT);
        // create session with max value
        verifySessionTimeOut(maxSessionTimeout, maxSessionTimeout, HOSTPORT);
        verifySessionTimeOut(maxSessionTimeout + 2000, maxSessionTimeout,
                HOSTPORT);
        main.shutdown();
        Assert.assertTrue("waiting for server down", ClientBase
                .waitForServerDown(HOSTPORT, ClientBase.CONNECTION_TIMEOUT));
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
        final String configs = "maxSessionTimeout=" + maxSessionTimeout + "\n"
                + "minSessionTimeout=" + minSessionTimeout + "\n";
        MainThread main = new MainThread(CLIENT_PORT, false, configs);
        main.start();

        String HOSTPORT = "127.0.0.1:" + CLIENT_PORT;
        Assert.assertTrue("waiting for server being up",
                ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT));
        // create session with min value
        verifySessionTimeOut(minSessionTimeout, minSessionTimeout, HOSTPORT);
        verifySessionTimeOut(minSessionTimeout - 2000, minSessionTimeout,
                HOSTPORT);
        // create session with max value
        verifySessionTimeOut(maxSessionTimeout, maxSessionTimeout, HOSTPORT);
        verifySessionTimeOut(maxSessionTimeout + 2000, maxSessionTimeout,
                HOSTPORT);
        main.shutdown();

        Assert.assertTrue("waiting for server down", ClientBase
                .waitForServerDown(HOSTPORT, ClientBase.CONNECTION_TIMEOUT));
    }

    private void verifySessionTimeOut(int sessionTimeout,
            int expectedSessionTimeout, String HOSTPORT) throws IOException,
            KeeperException, InterruptedException {
        clientConnected = new CountDownLatch(1);
        ZooKeeper zk = new ZooKeeper(HOSTPORT, sessionTimeout, this);
        Assert.assertTrue("Failed to establish zkclient connection!",
                clientConnected.await(sessionTimeout, TimeUnit.MILLISECONDS));
        Assert.assertEquals("Not able to configure the sessionTimeout values",
                expectedSessionTimeout, zk.getSessionTimeout());
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
        String originalServerCnxnFactory = System
                .getProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY,
                NettyServerCnxnFactory.class.getName());
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
            if (originalServerCnxnFactory == null
                    || originalServerCnxnFactory.isEmpty()) {
                System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
            } else {
                System.setProperty(
                        ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY,
                        originalServerCnxnFactory);
            }
        }
    }

    private void deleteFile(File f) throws IOException {
        if (f.isDirectory()) {
            for (File c : f.listFiles())
                deleteFile(c);
        }
        if (!f.delete())
            // double check for the file existence
            if (f.exists()) {
                throw new IOException("Failed to delete file: " + f);
            }
    }

    private ServerCnxnFactory startServer(File tmpDir) throws IOException,
            InterruptedException {
        final int CLIENT_PORT = PortAssignment.unique();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(CLIENT_PORT, -1);
        f.startup(zks);
        Assert.assertNotNull("JMX initialization failed!", zks.jmxServerBean);
        Assert.assertTrue("waiting for server being up",
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT,
                        CONNECTION_TIMEOUT));
        return f;
    }

    public void process(WatchedEvent event) {
        if (event.getState() == KeeperState.SyncConnected) {
            clientConnected.countDown();
        }
    }
}
