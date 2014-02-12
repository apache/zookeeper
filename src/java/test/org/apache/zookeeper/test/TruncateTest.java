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
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;

import junit.framework.Assert;

import org.apache.jute.Record;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnLog;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.TxnLog.TxnIterator;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.txn.SetDataTxn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TruncateTest extends ZKTestCase {
	private static final Logger LOG = LoggerFactory.getLogger(TruncateTest.class);
    File dataDir1, dataDir2, dataDir3;
    final int baseHostPort = PortAssignment.unique();
    
    @Before
    public void setUp() throws IOException {
        dataDir1 = ClientBase.createTmpDir();
        dataDir2 = ClientBase.createTmpDir();
        dataDir3 = ClientBase.createTmpDir();
    }
    
    @After
    public void tearDown() {
        ClientBase.recursiveDelete(dataDir1);
        ClientBase.recursiveDelete(dataDir2);
        ClientBase.recursiveDelete(dataDir3);
    }
    
    volatile boolean connected;
    Watcher nullWatcher = new Watcher() {
        @Override
        public void process(WatchedEvent event) {
            connected = event.getState() == Watcher.Event.KeeperState.SyncConnected;
        }
    };

    @Test
    public void testTruncationStreamReset() throws Exception {
        File tmpdir = ClientBase.createTmpDir();
        FileTxnSnapLog snaplog = new FileTxnSnapLog(tmpdir, tmpdir);
        ZKDatabase zkdb = new ZKDatabase(snaplog);

        for (int i = 1; i <= 100; i++) {
            append(zkdb, i);
        }

        zkdb.truncateLog(1);

        append(zkdb, 200);

        zkdb.close();

        // verify that the truncation and subsequent append were processed
        // correctly
        FileTxnLog txnlog = new FileTxnLog(new File(tmpdir, "version-2"));
        TxnIterator iter = txnlog.read(1);

        TxnHeader hdr = iter.getHeader();
        Record txn = iter.getTxn();
        Assert.assertEquals(1, hdr.getZxid());
        Assert.assertTrue(txn instanceof SetDataTxn);

        iter.next();

        hdr = iter.getHeader();
        txn = iter.getTxn();
        Assert.assertEquals(200, hdr.getZxid());
        Assert.assertTrue(txn instanceof SetDataTxn);
        iter.close();
        ClientBase.recursiveDelete(tmpdir);
    }

    private void append(ZKDatabase zkdb, int i) throws IOException {
        TxnHeader hdr = new TxnHeader(1, 1, i, 1, ZooDefs.OpCode.setData);
        Record txn = new SetDataTxn("/foo" + i, new byte[0], 1);
        Request req = new Request(null, 0, 0, 0, null, null);
        req.hdr = hdr;
        req.txn = txn;

        zkdb.append(req);
        zkdb.commit();
    }

    @Test
    public void testTruncate() throws IOException, InterruptedException, KeeperException {
        // Prime the server that is going to come in late with 50 txns
        String hostPort = "127.0.0.1:" + baseHostPort;
        int maxCnxns = 100;
        ServerCnxnFactory factory = ClientBase.createNewServerInstance(null,
                hostPort, maxCnxns);
        ClientBase.startServerInstance(dataDir1, factory, hostPort);
        ClientBase.shutdownServerInstance(factory, hostPort);

        // standalone starts with 0 epoch while quorum starts with 1
        File origfile = new File(new File(dataDir1, "version-2"), "snapshot.0");
        File newfile = new File(new File(dataDir1, "version-2"), "snapshot.100000000");
        origfile.renameTo(newfile);

        factory = ClientBase.createNewServerInstance(null, hostPort, maxCnxns);
        ClientBase.startServerInstance(dataDir1, factory, hostPort);

        ZooKeeper zk = new ZooKeeper(hostPort, 15000, nullWatcher);
        for(int i = 0; i < 50; i++) {
            zk.create("/" + i, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
        zk.close();
        
        ZKDatabase zkDb;
        {
            ZooKeeperServer zs = ClientBase.getServer(factory);
    
            zkDb = zs.getZKDatabase();
        }
        factory.shutdown();
        try {
            zkDb.close();
        } catch (IOException ie) {
            LOG.warn("Error closing logs ", ie);
        }
        int tickTime = 2000;
        int initLimit = 3;
        int syncLimit = 3;
        int port1 = baseHostPort+1;
        int port2 = baseHostPort+2;
        int port3 = baseHostPort+3;
        
        // Start up two of the quorum and add 10 txns
        HashMap<Long,QuorumServer> peers = new HashMap<Long,QuorumServer>();
        peers.put(Long.valueOf(1), new QuorumServer(1, new InetSocketAddress("127.0.0.1", port1 + 1000)));
        peers.put(Long.valueOf(2), new QuorumServer(2, new InetSocketAddress("127.0.0.1", port2 + 1000)));
        peers.put(Long.valueOf(3), new QuorumServer(3, new InetSocketAddress("127.0.0.1", port3 + 1000)));

        QuorumPeer s2 = new QuorumPeer(peers, dataDir2, dataDir2, port2, 0, 2, tickTime, initLimit, syncLimit);
        s2.start();
        QuorumPeer s3 = new QuorumPeer(peers, dataDir3, dataDir3, port3, 0, 3, tickTime, initLimit, syncLimit);
        s3.start();
        connected = false;
        zk = new ZooKeeper("127.0.0.1:" + port2, 15000, nullWatcher);
        while(!connected) {
            Thread.sleep(1000);
        }
        for(int i = 0; i < 10; i++) {
            zk.create("/" + i, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
        zk.close();
        
        final ZooKeeper zk2 = new ZooKeeper("127.0.0.1:" + port2, 15000, nullWatcher);
        zk2.getData("/9", false, new Stat());
        try {
            zk2.getData("/10", false, new Stat());
            Assert.fail("Should have gotten an error");
        } catch(KeeperException.NoNodeException e) {
            // this is what we want
        }
        QuorumPeer s1 = new QuorumPeer(peers, dataDir1, dataDir1, port1, 0, 1, tickTime, initLimit, syncLimit);
        s1.start();

        connected = false;
        ZooKeeper zk1 = new ZooKeeper("127.0.0.1:" + port1, 15000, nullWatcher);
        while(!connected) {
            Thread.sleep(1000);
        }
        zk1.getData("/9", false, new Stat());
        try {
            // /10 wont work because the session expiration
            // will match the zxid for /10 and so we wont
            // actually truncate the zxid for /10 creation
            // due to an artifact of switching the xid of the standalone
            // /11 is the last entry in the log for the xid
            // as a result /12 is the first of the truncated znodes to check for
            zk1.getData("/12", false, new Stat());
            Assert.fail("Should have gotten an error");
        } catch(KeeperException.NoNodeException e) {
            // this is what we want
        }
        zk1.close();
        QuorumBase.shutdown(s1);
        QuorumBase.shutdown(s2);
        QuorumBase.shutdown(s3);
    }
}
