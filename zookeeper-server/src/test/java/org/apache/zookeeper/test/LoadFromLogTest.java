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

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnLog;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.TxnLog.TxnIterator;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class LoadFromLogTest extends ClientBase {
    private static final int NUM_MESSAGES = 300;
    protected static final Logger LOG = LoggerFactory.getLogger(LoadFromLogTest.class);

    // setting up the quorum has a transaction overhead for creating and closing the session
    private static final int TRANSACTION_OVERHEAD = 2;	
    private static final int TOTAL_TRANSACTIONS = NUM_MESSAGES + TRANSACTION_OVERHEAD;

    @Before
     public void setUp() throws Exception {
                SyncRequestProcessor.setSnapCount(50);
                super.setUp();
            }

    /**
     * test that all transactions from the Log are loaded, and only once
     * @throws Exception an exception might be thrown here
     */
    @Test
    public void testLoad() throws Exception {
        ZooKeeper zk = createZKClient(hostPort);

        // generate some transactions that will get logged
        try {
            for (int i = 0; i< NUM_MESSAGES; i++) {
                zk.create("/invalidsnap-" + i, new byte[0], Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT);
            }
        } finally {
            zk.close();
        }
        stopServer();

        // now verify that the FileTxnLog reads every transaction only once
        File logDir = new File(tmpDir, FileTxnSnapLog.version + FileTxnSnapLog.VERSION);
        FileTxnLog txnLog = new FileTxnLog(logDir);

        TxnIterator itr = txnLog.read(0);
        long expectedZxid = 0;
        long lastZxid = 0;
        TxnHeader hdr;
        do {
            hdr = itr.getHeader();
            expectedZxid++;
            Assert.assertTrue("not the same transaction. lastZxid=" + lastZxid + ", zxid=" + hdr.getZxid(), lastZxid != hdr.getZxid());
            Assert.assertTrue("excepting next transaction. expected=" + expectedZxid + ", retreived=" + hdr.getZxid(), (hdr.getZxid() == expectedZxid));
            lastZxid = hdr.getZxid();
        }while(itr.next());
	
        Assert.assertTrue("processed all transactions. " + expectedZxid + " == " + TOTAL_TRANSACTIONS, (expectedZxid == TOTAL_TRANSACTIONS));
    }

    /**
     * Test we can restore the snapshot that has data ahead of the zxid
     * of the snapshot file. 
     */
    @Test
    public void testRestore() throws Exception {
        ZooKeeper zk = createZKClient(hostPort);

		// generate some transactions
		String lastPath = null;
		try {
			zk.create("/invalidsnap", new byte[0], Ids.OPEN_ACL_UNSAFE,
					CreateMode.PERSISTENT);
			for (int i = 0; i < NUM_MESSAGES; i++) {
				lastPath = zk.create("/invalidsnap/test-", new byte[0],
						Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
			}
		} finally {
			zk.close();
		}
		String[] tokens = lastPath.split("-");
		String expectedPath = "/invalidsnap/test-"
				+ String.format("%010d",
                Integer.parseInt(tokens[1]) + 1);
        ZooKeeperServer zks = getServer(serverFactory);
		long eZxid = zks.getZKDatabase().getDataTreeLastProcessedZxid();
		// force the zxid to be behind the content
		zks.getZKDatabase().setlastProcessedZxid(
				zks.getZKDatabase().getDataTreeLastProcessedZxid() - 10);
		LOG.info("Set lastProcessedZxid to "
				+ zks.getZKDatabase().getDataTreeLastProcessedZxid());
		// Force snapshot and restore
		zks.takeSnapshot();
		zks.shutdown();
		stopServer();

		startServer();
		zks = getServer(serverFactory);
		long fZxid = zks.getZKDatabase().getDataTreeLastProcessedZxid();

		// Verify lastProcessedZxid is set correctly
		Assert.assertTrue("Restore failed expected zxid=" + eZxid + " found="
				+ fZxid, fZxid == eZxid);
        zk = createZKClient(hostPort);

		// Verify correctness of data and whether sequential znode creation 
		// proceeds correctly after this point
		String[] children;
		String path;
		try {
			children = zk.getChildren("/invalidsnap", false).toArray(
					new String[0]);
			path = zk.create("/invalidsnap/test-", new byte[0],
					Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
		} finally {
			zk.close();
		}
		LOG.info("Expected " + expectedPath + " found " + path);
		Assert.assertTrue("Error in sequential znode creation expected "
				+ expectedPath + " found " + path, path.equals(expectedPath));
		Assert.assertTrue("Unexpected number of children " + children.length
				+ " expected " + NUM_MESSAGES,
				(children.length == NUM_MESSAGES));
	}
    
    /**
     * Test we can restore a snapshot that has errors and data ahead of the zxid
     * of the snapshot file. 
     */
    @Test
    public void testRestoreWithTransactionErrors() throws Exception {
        ZooKeeper zk = createZKClient(hostPort);

        // generate some transactions
        try {
            for (int i = 0; i < NUM_MESSAGES; i++) {
                try {
                    zk.create("/invaliddir/test-", new byte[0],
                            Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
                } catch(NoNodeException e) {
                    //Expected
                }
            }
        } finally {
            zk.close();
        }

        // force the zxid to be behind the content
        ZooKeeperServer zks = getServer(serverFactory);
        zks.getZKDatabase().setlastProcessedZxid(
                zks.getZKDatabase().getDataTreeLastProcessedZxid() - 10);
        LOG.info("Set lastProcessedZxid to "
                + zks.getZKDatabase().getDataTreeLastProcessedZxid());
        
        // Force snapshot and restore
        zks.takeSnapshot();
        zks.shutdown();
        stopServer();

        zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        startServer();
   }

    /**
     * ZOOKEEPER-1573: test restoring a snapshot with deleted txns ahead of the
     * snapshot file's zxid.
     */
    @Test
    public void testReloadSnapshotWithMissingParent() throws Exception {
        ZooKeeper zk = createZKClient(hostPort);

        // create transactions to create the snapshot with create/delete pattern
        zk.create("/a", "".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        Stat stat = zk.exists("/a", false);
        long createZxId = stat.getMzxid();
        zk.create("/a/b", "".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        zk.delete("/a/b", -1);
        zk.delete("/a", -1);
        // force the zxid to be behind the content
        ZooKeeperServer zks = getServer(serverFactory);
        zks.getZKDatabase().setlastProcessedZxid(createZxId);
        LOG.info("Set lastProcessedZxid to {}", zks.getZKDatabase()
                .getDataTreeLastProcessedZxid());
        // Force snapshot and restore
        zks.takeSnapshot();
        zks.shutdown();
        stopServer();
        startServer();
    }
}
