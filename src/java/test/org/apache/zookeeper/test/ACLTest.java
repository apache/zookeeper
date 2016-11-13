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
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.auth.IPAuthenticationProvider;
import org.junit.Assert;
import org.junit.Test;

public class ACLTest extends ZKTestCase implements Watcher {
    private static final Logger LOG = LoggerFactory.getLogger(ACLTest.class);
    private static final String HOSTPORT =
        "127.0.0.1:" + PortAssignment.unique();
    private volatile CountDownLatch startSignal;

    @Test
    public void testIPAuthenticationIsValidCIDR() throws Exception {
        IPAuthenticationProvider prov = new IPAuthenticationProvider();
        Assert.assertTrue("testing no netmask", prov.isValid("127.0.0.1"));
        Assert.assertTrue("testing single ip netmask", prov.isValid("127.0.0.1/32"));
        Assert.assertTrue("testing lowest netmask possible", prov.isValid("127.0.0.1/0"));
        Assert.assertFalse("testing netmask too high", prov.isValid("127.0.0.1/33"));
        Assert.assertFalse("testing netmask too low", prov.isValid("10.0.0.1/-1"));
    }

    @Test
    public void testDisconnectedAddAuth() throws Exception {
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        SyncRequestProcessor.setSnapCount(1000);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        try {
            LOG.info("starting up the zookeeper server .. waiting");
            Assert.assertTrue("waiting for server being up",
                    ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT));
            ZooKeeper zk = ClientBase.createZKClient(HOSTPORT);
            try {
                zk.addAuthInfo("digest", "pat:test".getBytes());
                zk.setACL("/", Ids.CREATOR_ALL_ACL, -1);
            } finally {
                zk.close();
            }
        } finally {
            f.shutdown();
            zks.shutdown();
            Assert.assertTrue("waiting for server down",
                    ClientBase.waitForServerDown(HOSTPORT,
                            ClientBase.CONNECTION_TIMEOUT));
        }
    }

    /**
     * Verify that acl optimization of storing just
     * a few acls and there references in the data
     * node is actually working.
     */
    @Test
    public void testAcls() throws Exception {
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        SyncRequestProcessor.setSnapCount(1000);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        ZooKeeper zk;
        String path;
        try {
            LOG.info("starting up the zookeeper server .. waiting");
            Assert.assertTrue("waiting for server being up",
                    ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT));
            zk = ClientBase.createZKClient(HOSTPORT);
            LOG.info("starting creating acls");
            for (int i = 0; i < 100; i++) {
                path = "/" + i;
                zk.create(path, path.getBytes(), Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT);
            }
            int size = zks.getZKDatabase().getAclSize();
            Assert.assertTrue("size of the acl map ", (2 == zks.getZKDatabase().getAclSize()));
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
            Assert.assertTrue("size of the acl map ", (102 == zks.getZKDatabase().getAclSize()));
        } finally {
            // now shutdown the server and restart it
            f.shutdown();
            zks.shutdown();
            Assert.assertTrue("waiting for server down",
                    ClientBase.waitForServerDown(HOSTPORT, CONNECTION_TIMEOUT));
        }

        zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        f = ServerCnxnFactory.createFactory(PORT, -1);

        f.startup(zks);
        try {
            Assert.assertTrue("waiting for server up",
                       ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT));
            zk = ClientBase.createZKClient(HOSTPORT);
            Assert.assertTrue("acl map ", (102 == zks.getZKDatabase().getAclSize()));
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
            Assert.assertTrue("acl map ", (107 == zks.getZKDatabase().getAclSize()));
    
            zk.close();
        } finally {
            f.shutdown();
            zks.shutdown();
            Assert.assertTrue("waiting for server down",
                       ClientBase.waitForServerDown(HOSTPORT,
                               ClientBase.CONNECTION_TIMEOUT));
        }

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
