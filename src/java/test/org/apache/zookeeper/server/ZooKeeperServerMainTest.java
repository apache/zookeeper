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
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.jute.Record;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.Util;
import org.apache.zookeeper.server.util.ZxidUtils;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.txn.SetDataTxn;
import org.apache.zookeeper.txn.TxnHeader;
import org.jboss.netty.channel.Channel;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test stand-alone server.
 *
 */
public class ZooKeeperServerMainTest extends ZKTestCase implements Watcher {
    protected static final Logger LOG =
        LoggerFactory.getLogger(ZooKeeperServerMainTest.class);

    public static class MainThread extends Thread {
        final File confFile;
        final TestZKSMain main;
        final File tmpDir;
        final File dataDir;
        final File logDir;

        public MainThread(int clientPort, boolean preCreateDirs) throws IOException {
            this(clientPort, preCreateDirs, ClientBase.createTmpDir());
        }

        public MainThread(int clientPort, boolean preCreateDirs, File tmpDir) throws IOException {
            super("Standalone server with clientPort:" + clientPort);
            this.tmpDir = tmpDir;
            confFile = new File(this.tmpDir, "zoo.cfg");

            FileWriter fwriter = new FileWriter(confFile);
            fwriter.write("tickTime=2000\n");
            fwriter.write("initLimit=10\n");
            fwriter.write("syncLimit=5\n");

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

            String dataDirPath = dataDir.toString();
            String logDirPath = logDir.toString();

            // Convert windows path to UNIX to avoid problems with "\"
            String osname = java.lang.System.getProperty("os.name");
            if (osname.toLowerCase().contains("windows")) {
                dataDirPath = dataDirPath.replace('\\', '/');
                logDirPath = logDirPath.replace('\\', '/');
            }
            fwriter.write("dataDir=" + dataDirPath + "\n");
            fwriter.write("dataLogDir=" + logDirPath + "\n");
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
     * writing transaction log) on ZooKeeper service will be available
     */
    @Test(timeout = 30000)
    public void testNonRecoverableError() throws Exception {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT = PortAssignment.unique();

        MainThread main = new MainThread(CLIENT_PORT, true);
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
        MainThread main = new MainThread(CLIENT_PORT, true);
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
        main = new MainThread(CLIENT_PORT, false, tmpDir);
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
        MainThread main = new MainThread(CLIENT_PORT, true);
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
        main = new MainThread(CLIENT_PORT, false, tmpDir);
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

        MainThread main = new MainThread(CLIENT_PORT, true);
        main.start();

        Assert.assertTrue("waiting for server being up",
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT,
                        CONNECTION_TIMEOUT));


        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT,
                ClientBase.CONNECTION_TIMEOUT, this);

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
     * Test verifies the auto creation of data dir and data log dir.
     */
    @Test(timeout = 30000)
    public void testAutoCreateDataLogDir() throws Exception {
        ClientBase.setupTestEnv();
        final int CLIENT_PORT = PortAssignment.unique();

        MainThread main = new MainThread(CLIENT_PORT, false);
        String args[] = new String[1];
        args[0] = main.confFile.toString();
        main.start();

        Assert.assertTrue("waiting for server being up",
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT,
                        CONNECTION_TIMEOUT));

        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT,
                ClientBase.CONNECTION_TIMEOUT, this);

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

    /**
     * Test case to verify that ZooKeeper server is able to shutdown properly
     * when there are pending request(s) in the RequestProcessor chain.
     *
     * {@link https://issues.apache.org/jira/browse/ZOOKEEPER-2347}
     */
    @Test(timeout = 30000)
    public void testRaceBetweenSyncFlushAndZKShutdown() throws Exception {
        File tmpDir = ClientBase.createTmpDir();
        File testDir = File.createTempFile("test", ".dir", tmpDir);
        testDir.delete();

        // Following are the sequence of steps to simulate the deadlock
        // situation - SyncRequestProcessor#shutdown holds a lock and waits on
        // FinalRequestProcessor to complete a pending operation, which in turn
        // also needs the ZooKeeperServer lock

        // 1. start zk server
        FileTxnSnapLog ftsl = new FileTxnSnapLog(testDir, testDir);
        final SimpleZooKeeperServer zkServer = new SimpleZooKeeperServer(ftsl);
        zkServer.startup();
        // 2. Wait for setting up request processor chain. At the end of setup,
        // it will add a mock request into the chain
        // 3. Also, waiting for FinalRequestProcessor to start processing request
        zkServer.waitForFinalProcessRequest();
        // 4. Above step ensures that there is a request in the processor chain.
        // Now invoke shutdown, which will acquire zks lock
        Thread shutdownThread = new Thread() {
            public void run() {
                zkServer.shutdown();
            };
        };
        shutdownThread.start();
        // 5. Wait for SyncRequestProcessor to trigger shutdown function.
        // This is to ensure that zks lock is acquired
        zkServer.waitForSyncReqProcessorShutdown();
        // 6. Now resume FinalRequestProcessor which in turn call
        // zks#decInProcess() function and tries to acquire zks lock.
        // This results in deadlock
        zkServer.resumeFinalProcessRequest();
        // 7. Waiting to finish server shutdown. Testing that
        // SyncRequestProcessor#shutdown holds a lock and waits on
        // FinalRequestProcessor to complete a pending operation, which in turn
        // also needs the ZooKeeperServer lock
        shutdownThread.join();
    }

    private class SimpleZooKeeperServer extends ZooKeeperServer {
        private SimpleSyncRequestProcessor syncProcessor;
        private SimpleFinalRequestProcessor finalProcessor;

        SimpleZooKeeperServer(FileTxnSnapLog ftsl) throws IOException {
            super(ftsl, 2000, 2000, 4000, null, new ZKDatabase(ftsl));
        }

        @Override
        protected void setupRequestProcessors() {
            finalProcessor = new SimpleFinalRequestProcessor(this);
            syncProcessor = new SimpleSyncRequestProcessor(this,
                    finalProcessor);
            syncProcessor.start();
            firstProcessor = new PrepRequestProcessor(this, syncProcessor);
            ((PrepRequestProcessor) firstProcessor).start();

            // add request to the chain
            addRequestToSyncProcessor();
        }

        private void addRequestToSyncProcessor() {
            long zxid = ZxidUtils.makeZxid(3, 7);
            TxnHeader hdr = new TxnHeader(1, 1, zxid, 1,
                    ZooDefs.OpCode.setData);
            Record txn = new SetDataTxn("/foo" + zxid, new byte[0], 1);
            byte[] buf;
            try {
                buf = Util.marshallTxnEntry(hdr, txn);
            } catch (IOException e) {
                LOG.error("IOException while adding request to SyncRequestProcessor", e);
                Assert.fail("IOException while adding request to SyncRequestProcessor!");
                return;
            }
            NettyServerCnxnFactory factory = new NettyServerCnxnFactory();
            final MockNettyServerCnxn nettyCnxn = new MockNettyServerCnxn(null,
                    this, factory);
            Request req = new Request(nettyCnxn, 1, 1, ZooDefs.OpCode.setData,
                    ByteBuffer.wrap(buf), null);
            req.hdr = hdr;
            req.txn = txn;
            syncProcessor.processRequest(req);
        }

        void waitForFinalProcessRequest() throws InterruptedException {
            Assert.assertTrue("Waiting for FinalRequestProcessor to start processing request",
                    finalProcessor.waitForProcessRequestToBeCalled());
        }

        void waitForSyncReqProcessorShutdown() throws InterruptedException {
            Assert.assertTrue("Waiting for SyncRequestProcessor to shut down",
                    syncProcessor.waitForShutdownToBeCalled());
        }

        void resumeFinalProcessRequest() throws InterruptedException {
            finalProcessor.resumeProcessRequest();
        }
    }

    private class MockNettyServerCnxn extends NettyServerCnxn {
        public MockNettyServerCnxn(Channel channel, ZooKeeperServer zks,
                NettyServerCnxnFactory factory) {
            super(null, null, factory);
        }

        @Override
        protected synchronized void updateStatsForResponse(long cxid, long zxid,
                String op, long start, long end) {
            return;
        }

        @Override
        public synchronized void sendResponse(ReplyHeader h, Record r,
                String tag) {
            return;
        }
    }

    private class SimpleFinalRequestProcessor extends FinalRequestProcessor {
        private CountDownLatch finalReqProcessCalled = new CountDownLatch(1);
        private CountDownLatch resumeFinalReqProcess = new CountDownLatch(1);
        private volatile boolean interrupted = false;
        public SimpleFinalRequestProcessor(ZooKeeperServer zks) {
            super(zks);
        }

        @Override
        public void processRequest(Request request) {
            finalReqProcessCalled.countDown();
            try {
                resumeFinalReqProcess.await(ClientBase.CONNECTION_TIMEOUT,
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                LOG.error("Interrupted while waiting to process request", e);
                interrupted = true; // Marked as interrupted
                resumeFinalReqProcess.countDown();
                return;
            }
            super.processRequest(request);
        }

        boolean waitForProcessRequestToBeCalled() throws InterruptedException {
            return finalReqProcessCalled.await(ClientBase.CONNECTION_TIMEOUT,
                    TimeUnit.MILLISECONDS);
        }

        void resumeProcessRequest() throws InterruptedException {
            resumeFinalReqProcess.countDown();
            resumeFinalReqProcess.await(ClientBase.CONNECTION_TIMEOUT,
                    TimeUnit.MILLISECONDS);
            Assert.assertFalse("Interrupted while waiting to process request",
                    interrupted);
        }
    }

    private class SimpleSyncRequestProcessor extends SyncRequestProcessor {
        private final CountDownLatch shutdownCalled = new CountDownLatch(1);

        public SimpleSyncRequestProcessor(ZooKeeperServer zks,
                RequestProcessor nextProcessor) {
            super(zks, nextProcessor);
        }

        @Override
        public void shutdown() {
            shutdownCalled.countDown();
            super.shutdown();
        }

        boolean waitForShutdownToBeCalled() throws InterruptedException {
            return shutdownCalled.await(ClientBase.CONNECTION_TIMEOUT / 3,
                    TimeUnit.MILLISECONDS);
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
        // ignore for this test
    }
}
