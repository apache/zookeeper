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
import java.io.IOException;
import java.util.ArrayList;

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.junit.Before;
import org.junit.Test;

public class QuorumTest extends QuorumBase {
    private static final Logger LOG = Logger.getLogger(QuorumTest.class);
    public static final long CONNECTION_TIMEOUT = ClientTest.CONNECTION_TIMEOUT;

    private final QuorumBase qb = new QuorumBase();
    private final ClientTest ct = new ClientTest();

    @Before
    @Override
    protected void setUp() throws Exception {
        qb.setUp();
        ct.hostPort = qb.hostPort;
        ct.setUpAll();
    }

    protected void tearDown() throws Exception {
        ct.tearDownAll();
        qb.tearDown();
    }

    @Test
    public void testDeleteWithChildren() throws Exception {
        ct.testDeleteWithChildren();
    }

    @Test
    public void testHammerBasic() throws Throwable {
        ct.testHammerBasic();
    }

    @Test
    public void testPing() throws Exception {
        ct.testPing();
    }

    @Test
    public void testSequentialNodeNames()
        throws IOException, InterruptedException, KeeperException
    {
        ct.testSequentialNodeNames();
    }

    @Test
    public void testACLs() throws Exception {
        ct.testACLs();
    }

    @Test
    public void testClientwithoutWatcherObj() throws IOException,
            InterruptedException, KeeperException
    {
        ct.testClientwithoutWatcherObj();
    }

    @Test
    public void testClientWithWatcherObj() throws IOException,
            InterruptedException, KeeperException
    {
        ct.testClientWithWatcherObj();
    }
    @Test
    public void testMultipleWatcherObjs() throws IOException,
            InterruptedException, KeeperException
    {
        ct.testMutipleWatcherObjs();
    }

    @Test
    /**
     * Connect to two different servers with two different handles using the same session and
     * make sure we cannot do any changes.
     */
    public void testSessionMove() throws IOException, InterruptedException, KeeperException {
        String hps[] = qb.hostPort.split(",");
        ZooKeeper zk = new DisconnectableZooKeeper(hps[0], ClientBase.CONNECTION_TIMEOUT, new Watcher() {
            public void process(WatchedEvent event) {
        }});
        zk.create("/t1", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        // This should stomp the zk handle
        ZooKeeper zknew = new DisconnectableZooKeeper(hps[1], ClientBase.CONNECTION_TIMEOUT, new Watcher() {
            public void process(WatchedEvent event) {
            }}, zk.getSessionId(), zk.getSessionPasswd());
        zknew.create("/t2", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        try {
            zk.create("/t3", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
            fail("Should have lost the connection");
        } catch(KeeperException.SessionMovedException e) {
        }

        ArrayList<ZooKeeper> toClose = new ArrayList<ZooKeeper>();
        toClose.add(zknew);
        // Let's just make sure it can still move
        for(int i = 0; i < 10; i++) {
            zknew = new DisconnectableZooKeeper(hps[1], ClientBase.CONNECTION_TIMEOUT, new Watcher() {
                public void process(WatchedEvent event) {
                }}, zk.getSessionId(), zk.getSessionPasswd());
            toClose.add(zknew);
            zknew.create("/t-"+i, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        }
    for(ZooKeeper z: toClose) {
            z.close();
        }
        zk.close();
    }

    // skip superhammer and clientcleanup as they are too expensive for quorum
}
