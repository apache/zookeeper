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

package org.apache.zookeeper.test;

import java.io.File;
import java.util.HashSet;
import java.util.Random;

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.proto.CreateRequest;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnLog;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test loading committed proposal from txnlog. Learner uses these proposals to
 * catch-up with leader
 */
public class TxnLogSizeLimitTest extends ZKTestCase implements Watcher {
    private static final Logger LOG = Logger
            .getLogger(TxnLogSizeLimitTest.class);
    private static String HOSTPORT = "127.0.0.1:" + PortAssignment.unique();
    private static final int CONNECTION_TIMEOUT = 3000;

    // Overhead is about 150 bytes for txn created in this test
    private static final int NODE_SIZE = 1024;
    private final long PREALLOCATE = 512;
    private final long LOG_SIZE_LIMIT = 1024 * 4;

    /**
     * Tested that log size get update correctly
     */
    @Test
    public void testGetCurrentLogSize() throws Exception {
        FileTxnLog.setLogSizeLimit(-1);
        File tmpDir = ClientBase.createTmpDir();
        FileTxnLog log = new FileTxnLog(tmpDir);
        FileTxnLog.setPreallocSize(PREALLOCATE);
        CreateRequest record = new CreateRequest(null, new byte[NODE_SIZE],
                Ids.OPEN_ACL_UNSAFE, 0);
        int zxid = 1;
        for (int i = 0; i < 4; i++) {
            log.append(new TxnHeader(0, 0, zxid++, 0, 0), record);
            LOG.debug("Current log size: " + log.getCurrentLogSize());
        }
        log.commit();
        LOG.info("Current log size: " + log.getCurrentLogSize());
        Assert.assertTrue(log.getCurrentLogSize() > (zxid - 1) * NODE_SIZE);
        for (int i = 0; i < 4; i++) {
            log.append(new TxnHeader(0, 0, zxid++, 0, 0), record);
            LOG.debug("Current log size: " + log.getCurrentLogSize());
        }
        log.commit();
        LOG.info("Current log size: " + log.getCurrentLogSize());
        Assert.assertTrue(log.getCurrentLogSize() > (zxid - 1) * NODE_SIZE);
    }

    /**
     * Test that the server can correctly load the data when there are multiple
     * txnlogs per snapshot
     */
    @Test
    public void testLogSizeLimit() throws Exception {
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();

        // Need to override preallocate set by setupTestEnv()
        // We don't need to unset these values since each unit test run in
        // a separate JVM instance
        FileTxnLog.setPreallocSize(PREALLOCATE);
        FileTxnLog.setLogSizeLimit(LOG_SIZE_LIMIT);

        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        Assert.assertTrue("waiting for server being up ",
                ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT));
        ZooKeeper zk = new ZooKeeper(HOSTPORT, CONNECTION_TIMEOUT, this);

        // Generate transactions
        HashSet<Long> zxids = new HashSet<Long>();
        byte[] bytes = new byte[NODE_SIZE];
        Random random = new Random();
        random.nextBytes(bytes);

        // We will create enough txn to generate 3 logs
        long txnCount = LOG_SIZE_LIMIT / NODE_SIZE / 2 * 5;

        LOG.info("Creating " + txnCount + " txns");

        try {
            for (long i = 0; i < txnCount; i++) {
                Stat stat = new Stat();
                zk.create("/node-" + i, bytes, Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT);
                zk.getData("/node-" + i, null, stat);
                zxids.add(stat.getCzxid());
            }

        } finally {
            zk.close();
        }

        // shutdown
        f.shutdown();
        Assert.assertTrue("waiting for server to shutdown",
                ClientBase.waitForServerDown(HOSTPORT, CONNECTION_TIMEOUT));

        File logDir = new File(tmpDir, FileTxnSnapLog.version + FileTxnSnapLog.VERSION);
        File[] txnLogs = FileTxnLog.getLogFiles(logDir.listFiles(), 0);

        Assert.assertEquals("Unexpected number of logs", 3, txnLogs.length);

        // Log size should not exceed limit by more than one node size;
        long threshold = LOG_SIZE_LIMIT + NODE_SIZE;
        LOG.info(txnLogs[0].getAbsolutePath());
        Assert.assertTrue(
                "Exceed log size limit: " + txnLogs[0].length(),
                threshold > txnLogs[0].length());
        LOG.info(txnLogs[1].getAbsolutePath());
        Assert.assertTrue(
                "Exceed log size limit " + txnLogs[1].length(),
                threshold > txnLogs[1].length());

        // Start database only
        zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        zks.startdata();

        ZKDatabase db = zks.getZKDatabase();

        for (long i = 0; i < txnCount; i++) {
            Stat stat = new Stat();
            byte[] data = db.getData("/node-" + i, stat, null);
            Assert.assertArrayEquals("Missmatch data", bytes, data);
            Assert.assertTrue("Unknown zxid ", zxids.contains(stat.getMzxid()));
        }
    }

    public void process(WatchedEvent event) {
        // do nothing
    }

}
