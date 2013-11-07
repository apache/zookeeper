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

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.server.NIOServerCnxn;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZooKeeperServer;

public class ACLTest extends TestCase implements Watcher {
    private static final Logger LOG = Logger.getLogger(ACLTest.class);
    private static final String HOSTPORT =
        "127.0.0.1:" + PortAssignment.unique();
    private volatile CountDownLatch startSignal;

    @Override
    protected void setUp() throws Exception {
        LOG.info("STARTING " + getName());
    }
    @Override
    protected void tearDown() throws Exception {
        LOG.info("FINISHED " + getName());
    }

    public void testDisconnectedAddAuth() throws Exception {
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        SyncRequestProcessor.setSnapCount(1000);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        NIOServerCnxn.Factory f = new NIOServerCnxn.Factory(
                new InetSocketAddress(PORT));
        f.startup(zks);
        LOG.info("starting up the zookeeper server .. waiting");
        assertTrue("waiting for server being up",
                ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT));
        ZooKeeper zk = new ZooKeeper(HOSTPORT, CONNECTION_TIMEOUT, this);
        try {
            zk.addAuthInfo("digest", "pat:test".getBytes());
            zk.setACL("/", Ids.CREATOR_ALL_ACL, -1);
        } finally {
            zk.close();
        }

        f.shutdown();

        assertTrue("waiting for server down",
                   ClientBase.waitForServerDown(HOSTPORT,
                           ClientBase.CONNECTION_TIMEOUT));
    }

    /**
     * Verify that acl optimization of storing just
     * a few acls and there references in the data
     * node is actually working.
     */
    public void testAcls() throws Exception {
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        SyncRequestProcessor.setSnapCount(1000);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        NIOServerCnxn.Factory f = new NIOServerCnxn.Factory(
                new InetSocketAddress(PORT));
        f.startup(zks);
        LOG.info("starting up the zookeeper server .. waiting");
        assertTrue("waiting for server being up",
                ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT));
        ZooKeeper zk = new ZooKeeper(HOSTPORT, CONNECTION_TIMEOUT, this);
        String path;
        LOG.info("starting creating acls");
        for (int i = 0; i < 100; i++) {
            path = "/" + i;
            zk.create(path, path.getBytes(), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);
        }
        assertTrue("size of the acl map ", (1 == zks.getZKDatabase().getAclSize()));
        for (int j = 100; j < 200; j++) {
            path = "/" + j;
            ACL acl = new ACL();
            acl.setPerms(0);
            Id id = new Id();
            id.setId("1.1.1."+j);
            id.setScheme("ip");
            acl.setId(id);
            ArrayList<ACL> list = new ArrayList<ACL>();
            list.add(acl);
            zk.create(path, path.getBytes(), list, CreateMode.PERSISTENT);
        }
        assertTrue("size of the acl map ", (101 == zks.getZKDatabase().getAclSize()));
        // now shutdown the server and restart it
        f.shutdown();
        assertTrue("waiting for server down",
                ClientBase.waitForServerDown(HOSTPORT, CONNECTION_TIMEOUT));
        startSignal = new CountDownLatch(1);

        zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        f = new NIOServerCnxn.Factory(new InetSocketAddress(PORT));

        f.startup(zks);

        assertTrue("waiting for server up",
                   ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT));

        startSignal.await(CONNECTION_TIMEOUT,
                TimeUnit.MILLISECONDS);
        assertTrue("count == 0", startSignal.getCount() == 0);

        assertTrue("acl map ", (101 == zks.getZKDatabase().getAclSize()));
        for (int j = 200; j < 205; j++) {
            path = "/" + j;
            ACL acl = new ACL();
            acl.setPerms(0);
            Id id = new Id();
            id.setId("1.1.1."+j);
            id.setScheme("ip");
            acl.setId(id);
            ArrayList<ACL> list = new ArrayList<ACL>();
            list.add(acl);
            zk.create(path, path.getBytes(), list, CreateMode.PERSISTENT);
        }
        assertTrue("acl map ", (106 == zks.getZKDatabase().getAclSize()));

        zk.close();

        f.shutdown();

        assertTrue("waiting for server down",
                   ClientBase.waitForServerDown(HOSTPORT,
                           ClientBase.CONNECTION_TIMEOUT));

    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.Watcher#process(org.apache.zookeeper.WatcherEvent)
     */
    public void process(WatchedEvent event) {
        LOG.info("Event:" + event.getState() + " " + event.getType() + " "
                 + event.getPath());
        if (event.getState() == KeeperState.SyncConnected) {
            if (startSignal != null && startSignal.getCount() > 0) {
                LOG.info("startsignal.countDown()");
                startSignal.countDown();
            } else {
                LOG.warn("startsignal " + startSignal);
            }
        }
    }
}
