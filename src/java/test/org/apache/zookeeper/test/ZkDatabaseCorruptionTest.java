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
import java.util.Arrays;

import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZkDatabaseCorruptionTest extends ZKTestCase {
    protected static final Logger LOG = LoggerFactory.getLogger(ZkDatabaseCorruptionTest.class);
    public static final long CONNECTION_TIMEOUT = ClientTest.CONNECTION_TIMEOUT;

    private final QuorumBase qb = new QuorumBase();

    @Before
    public void setUp() throws Exception {
        LOG.info("STARTING quorum " + getClass().getName());
        qb.setUp();
    }

    @After
    public void tearDown() throws Exception {
        LOG.info("STOPPING quorum " + getClass().getName());
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

    private class NoopStringCallback implements AsyncCallback.StringCallback {
        @Override
        public void processResult(int rc, String path, Object ctx,
                                  String name) {
        }
    }

    @Test
    public void testCorruption() throws Exception {
        ClientBase.waitForServerUp(qb.hostPort, 10000);
        ClientBase.waitForServerUp(qb.hostPort, 10000);
        ZooKeeper zk = new ZooKeeper(qb.hostPort, 10000, new Watcher() {
            public void process(WatchedEvent event) {
            }});
        SyncRequestProcessor.setSnapCount(100);
        for (int i = 0; i < 2000; i++) {
            zk.create("/0-" + i, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE,
                      CreateMode.PERSISTENT, new NoopStringCallback(), null);
        }
        zk.close();

        long leaderSid = 1;
        QuorumPeer leader = null;
        //find out who is the leader and kill it
        for (QuorumPeer quorumPeer : Arrays.asList(qb.s1, qb.s2, qb.s3, qb.s4, qb.s5)) {
            if (quorumPeer.getPeerState() == ServerState.LEADING) {
                leader = quorumPeer;
                break;
            }
            ++leaderSid;
        }

        Assert.assertNotNull("Cannot find the leader.", leader);
        leader.shutdown();

        // now corrupt the leader's database
        FileTxnSnapLog snapLog = leader.getTxnFactory();
        File snapDir= snapLog.getSnapDir();
        //corrupt all the snapshot in the snapshot directory
        corruptAllSnapshots(snapDir);
        qb.shutdownServers();
        qb.setupServers();

        if (leaderSid != 1)qb.s1.start(); else leader = qb.s1;
        if (leaderSid != 2)qb.s2.start(); else leader = qb.s2;
        if (leaderSid != 3)qb.s3.start(); else leader = qb.s3;
        if (leaderSid != 4)qb.s4.start(); else leader = qb.s4;
        if (leaderSid != 5)qb.s5.start(); else leader = qb.s5;

        try {
            leader.start();
            Assert.assertTrue(false);
        } catch(RuntimeException re) {
            LOG.info("Got an error: expected", re);
        }
        //wait for servers to be up
        String[] list = qb.hostPort.split(",");
        for (int i = 0; i < 5; i++) {
            if(leaderSid != (i + 1)) {
                String hp = list[i];
                Assert.assertTrue("waiting for server up",
                        ClientBase.waitForServerUp(hp,
                                CONNECTION_TIMEOUT));
                LOG.info("{} is accepting client connections", hp);
            } else {
                LOG.info("Skipping the leader");
            }
        }

        zk = qb.createClient();
        SyncRequestProcessor.setSnapCount(100);
        for (int i = 2000; i < 4000; i++) {
            zk.create("/0-" + i, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE,
                      CreateMode.PERSISTENT, new NoopStringCallback(), null);
        }
        zk.close();

        if (leaderSid != 1)QuorumBase.shutdown(qb.s1);
        if (leaderSid != 2)QuorumBase.shutdown(qb.s2);
        if (leaderSid != 3)QuorumBase.shutdown(qb.s3);
        if (leaderSid != 4)QuorumBase.shutdown(qb.s4);
        if (leaderSid != 5)QuorumBase.shutdown(qb.s5);
    }


}
