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

import junit.framework.TestCase;

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.NIOServerCnxn;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TruncateTest extends TestCase {
	private static final Logger LOG = Logger.getLogger(TruncateTest.class);
    File dataDir1, dataDir2, dataDir3;
    final int baseHostPort = 12233;
    
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
    public void testTruncate() throws IOException, InterruptedException, KeeperException {
        // Prime the server that is going to come in late with 50 txns
        NIOServerCnxn.Factory factory = ClientBase.createNewServerInstance(dataDir1, null, "127.0.0.1:" + baseHostPort, 100);
        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + baseHostPort, 15000, nullWatcher);
        for(int i = 0; i < 50; i++) {
            zk.create("/" + i, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
        zk.close();
        ZKDatabase zkDb = factory.getZooKeeperServer().getZKDatabase();
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
            fail("Should have gotten an error");
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
        	// 10 wont work because the session expiration
        	// will match the zxid for 10 and so we wont
        	// actually truncate the zxid for 10 creation
        	// but for 11 we will for sure
        	zk1.getData("/11", false, new Stat());
            fail("Should have gotten an error");
        } catch(KeeperException.NoNodeException e) {
            // this is what we want
        }
        zk1.close();
        QuorumBase.shutdown(s1);
        QuorumBase.shutdown(s2);
        QuorumBase.shutdown(s3);
    }
}
