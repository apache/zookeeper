/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
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

import org.apache.log4j.Logger;
import org.apache.zookeeper.*;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.OpResult.ErrorResult;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;

import org.apache.zookeeper.data.Stat;

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;

public class MultiTransactionTest extends ZKTestCase implements Watcher {
    private static final Logger LOG = Logger.getLogger(MultiTransactionTest.class);

    private static final String HOSTPORT = "127.0.0.1:" + PortAssignment.unique();

    private ZooKeeper zk;
    private ServerCnxnFactory serverFactory;

    @Override
    public void process(WatchedEvent event) {
        // ignore
    }

    @Before
    public void setupZk() throws Exception {
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        SyncRequestProcessor.setSnapCount(150);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        serverFactory = ServerCnxnFactory.createFactory(PORT, -1);
        serverFactory.startup(zks);
        LOG.info("starting up the zookeeper server .. waiting");
        Assert.assertTrue("waiting for server being up",
                ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT));
        zk = new ZooKeeper(HOSTPORT, CONNECTION_TIMEOUT, this);
    }

    @After
    public void shutdownServer() throws Exception {
        zk.close();
        serverFactory.shutdown();
    }

    @Test
    public void testCreate() throws Exception {
        List<OpResult> results = new ArrayList<OpResult>();

        results = zk.multi(Arrays.asList(
                Op.create("/multi0", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create("/multi1", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create("/multi2", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
                ));
        zk.getData("/multi0", false, null);
        zk.getData("/multi1", false, null);
        zk.getData("/multi2", false, null);
    }
    
    @Test
    public void testCreateDelete() throws Exception {

        zk.multi(Arrays.asList(
                Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.delete("/multi", 0)
                ));

        // '/multi' should have been deleted
        Assert.assertNull(zk.exists("/multi", null));
    }

    @Test
    public void testInvalidVersion() throws Exception {

        try {
            zk.multi(Arrays.asList(
                    Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.delete("/multi", 1)
            ));
            Assert.fail("delete /multi should have failed");
        } catch (KeeperException e) {
            /* PASS */
        }
    }

    @Test
    public void testNestedCreate() throws Exception {

        zk.multi(Arrays.asList(
                /* Create */
                Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create("/multi/a", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create("/multi/a/1", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),

                /* Delete */
                Op.delete("/multi/a/1", 0),
                Op.delete("/multi/a", 0),
                Op.delete("/multi", 0)
                ));

        //Verify tree deleted
        Assert.assertNull(zk.exists("/multi/a/1", null));
        Assert.assertNull(zk.exists("/multi/a", null));
        Assert.assertNull(zk.exists("/multi", null));
    }

    @Test
    public void testSetData() throws Exception {

        String[] names = {"/multi0", "/multi1", "/multi2"};
        List<Op> ops = new ArrayList<Op>();

        for (int i = 0; i < names.length; i++) {
            ops.add(Op.create(names[i], new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
            ops.add(Op.setData(names[i], names[i].getBytes(), 0));
        }

        zk.multi(ops) ;

        for (int i = 0; i < names.length; i++) {
            Assert.assertArrayEquals(names[i].getBytes(), zk.getData(names[i], false, null));
        }
    }

    @Test
    public void testUpdateConflict() throws Exception {
    
        Assert.assertNull(zk.exists("/multi", null));
        
        try {
            zk.multi(Arrays.asList(
                    Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.setData("/multi", "X".getBytes(), 0),
                    Op.setData("/multi", "Y".getBytes(), 0)
                    ));
            Assert.fail("Should have thrown a KeeperException for invalid version");
        } catch (KeeperException e) {
            //PASS
            LOG.error("STACKTRACE: " + e);
        }

        Assert.assertNull(zk.exists("/multi", null));

        //Updating version solves conflict -- order matters
        zk.multi(Arrays.asList(
                Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.setData("/multi", "X".getBytes(), 0),
                Op.setData("/multi", "Y".getBytes(), 1)
                ));

        Assert.assertArrayEquals(zk.getData("/multi", false, null), "Y".getBytes());
    }

    @Test
    public void TestDeleteUpdateConflict() throws Exception {

        /* Delete of a node folowed by an update of the (now) deleted node */
        try {
            zk.multi(Arrays.asList(
                Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.delete("/multi", 0),
                Op.setData("/multi", "Y".getBytes(), 0)
                ));
            Assert.fail("/multi should have been deleted so setData should have failed");
        } catch (KeeperException e) {
            /* PASS */
        }

        // '/multi' should never have been created as entire op should fail
        Assert.assertNull(zk.exists("/multi", null)) ;
    }

    @Test
    public void TestGetResults() throws Exception {
        /* Delete of a node folowed by an update of the (now) deleted node */
        try {
            zk.multi(Arrays.asList(
                    Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.delete("/multi", 0),
                    Op.setData("/multi", "Y".getBytes(), 0),
                    Op.create("/foo", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
            ));
            Assert.fail("/multi should have been deleted so setData should have failed");
        } catch (KeeperException e) {
            // '/multi' should never have been created as entire op should fail
            Assert.assertNull(zk.exists("/multi", null));

            for (OpResult r : e.getResults()) {
                LOG.info("RESULT==> " + r);
                if (r instanceof ErrorResult) {
                    ErrorResult er = (ErrorResult) r;
                    LOG.info("ERROR RESULT: " + er + " ERR=>" + KeeperException.Code.get(er.getErr()));
                }
            }
        }
    }



}
