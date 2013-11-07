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
import java.io.RandomAccessFile;

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.junit.Before;


public class ZkDatabaseCorruptionTest extends QuorumBase {
    protected static final Logger LOG = Logger.getLogger(ZkDatabaseCorruptionTest.class);
    public static final long CONNECTION_TIMEOUT = ClientTest.CONNECTION_TIMEOUT;
    
    private final QuorumBase qb = new QuorumBase();
    
    @Before
    @Override
    protected void setUp() throws Exception {
    	LOG.info("STARTING " + getClass().getName());
        qb.setUp();
    }
        
    protected void tearDown() throws Exception {
    	LOG.info("STOPPING " + getClass().getName());
    }
    
    private void corruptFile(File f) throws IOException {
        RandomAccessFile outFile = new RandomAccessFile(f, "rw");
        outFile.write("fail servers".getBytes());
        outFile.close();
    }
    
    private void corruptAllSnapshots(File snapDir) throws IOException {
        File[] listFiles = snapDir.listFiles();
        for (File f: listFiles) {
            if (f.getName().startsWith("snapshot")) {
                corruptFile(f);
            }
        }
    }
    
    public void testCorruption() throws Exception {
        ClientBase.waitForServerUp(qb.hostPort, 10000);
        ClientBase.waitForServerUp(qb.hostPort, 10000);
        ZooKeeper zk = new ZooKeeper(qb.hostPort, 10000, new Watcher() {
            public void process(WatchedEvent event) {
            }});
        SyncRequestProcessor.setSnapCount(100);
        for (int i = 0; i < 2000; i++) {
            zk.create("/0-" + i, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
        zk.close();
        QuorumPeer leader;
        //find out who is the leader and kill it
        if ( qb.s5.getPeerState() != ServerState.LEADING) {
            throw new Exception("the last server is not the leader");
        }
        leader = qb.s5;
        // now corrupt the qurompeer database
        FileTxnSnapLog snapLog = leader.getTxnFactory();
        File snapDir= snapLog.getSnapDir();
        //corrupt all the snapshot in the snapshot directory
        corruptAllSnapshots(snapDir);
        qb.shutdownServers();
        qb.setupServers();
        qb.s1.start();
        qb.s2.start();
        qb.s3.start();
        qb.s4.start();
        try {
            qb.s5.start();
            assertTrue(false);
        } catch(RuntimeException re) {
            LOG.info("Got an error: expected", re);
        }
        //waut for servers to be up
        String[] list = qb.hostPort.split(",");
        for (int i =0; i < 4; i++) {
            String hp = list[i];
          assertTrue("waiting for server up",
                       ClientBase.waitForServerUp(hp,
                                    CONNECTION_TIMEOUT));
            LOG.info(hp + " is accepting client connections");
        }
        
        zk = qb.createClient();
        SyncRequestProcessor.setSnapCount(100);
        for (int i = 2000; i < 4000; i++) {
            zk.create("/0-" + i, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
        zk.close();
        QuorumBase.shutdown(qb.s1);
        QuorumBase.shutdown(qb.s2);
        QuorumBase.shutdown(qb.s3);
        QuorumBase.shutdown(qb.s4);
    } 

    
}