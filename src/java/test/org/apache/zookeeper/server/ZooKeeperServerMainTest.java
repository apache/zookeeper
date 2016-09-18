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

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;

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
    }

    public static  class TestZKSMain extends ZooKeeperServerMain {
        public void shutdown() {
            super.shutdown();
        }
    }

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

        snapDir.setWritable(true);

        main.deleteDirs();
    }

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

        logDir.setWritable(true);

        main.deleteDirs();
    }

    /**
     * Verify the ability to start a standalone server instance.
     */
    @Test
    public void testStandalone() throws Exception {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT = 3181;

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

    public void process(WatchedEvent event) {
        // ignore for this test
    }
}
