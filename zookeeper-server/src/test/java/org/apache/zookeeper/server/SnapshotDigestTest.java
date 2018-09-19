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

package org.apache.zookeeper.server;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.QuorumPeerMainTest;
import org.apache.zookeeper.server.util.DigestCalculator;
import org.apache.zookeeper.test.ClientBase;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import org.mockito.Mockito;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SnapshotDigestTest extends ClientBase {

    private static final Logger LOG = LoggerFactory.getLogger(
            SnapshotDigestTest.class);

    private ZooKeeper zk;
    private ZooKeeperServer server;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        server = getServer(serverFactory);
        zk = createClient();
    }

    @After
    public void tearDown() throws Exception {
        // server will be closed in super.tearDown
        super.tearDown();

        if (zk != null) {
            zk.close();
        }
    }

    @Override
    public void setupCustomizedEnv() {
        DigestCalculator.setDigestEnabled(true);
        System.setProperty(ZooKeeperServer.SNAP_COUNT, "100");
    }

    @Override
    public void cleanUpCustomizedEnv() {
        DigestCalculator.setDigestEnabled(false);
        System.clearProperty(ZooKeeperServer.SNAP_COUNT);
    }

    /**
     * Check snapshot digests when loading a fuzzy or non-fuzzy snapshot.
     */
    @Test
    public void testSnapshotDigest() throws Exception {
        // take a empty snapshot without creating any txn and make sure
        // there is no digest mismatch issue
        server.takeSnapshot(); 
        reloadSnapshotAndCheckDigest();
        
        // trigger various write requests
        String pathPrefix = "/testSnapshotDigest";
        for (int i = 0; i < 1000; i++) {
            String path = pathPrefix + i;
            zk.create(path, path.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, 
                    CreateMode.PERSISTENT);
        }

        // update the data of first node
        String firstNode = pathPrefix + 0;
        zk.setData(firstNode, "new_setdata".getBytes(), -1);

        // delete the first node
        zk.delete(firstNode, -1);

        // trigger multi op
        List<Op> subTxns = new ArrayList<Op>();
        for (int i = 0; i < 3; i++) {
            String path = pathPrefix + "-m" + i;
            subTxns.add(Op.create(path, path.getBytes(), 
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        }
        zk.multi(subTxns);

        reloadSnapshotAndCheckDigest();

        // Take a snapshot and test the logic when loading a non-fuzzy snapshot 
        server = getServer(serverFactory);
        server.takeSnapshot(); 

        reloadSnapshotAndCheckDigest();
    }

    /**
     * Make sure the code will skip digest check when it's comparing 
     * digest with different version. 
     *
     * This enables us to smoonthly add new fields into digest or using 
     * new digest calculation.
     */
    @Test
    public void testDifferentDigestVersion() throws Exception {
        // check the current digest version
        int currentVersion = DigestCalculator.DIGEST_VERSION;

        // create a node
        String path = "/testDifferentDigestVersion";
        zk.create(path, path.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, 
                CreateMode.PERSISTENT);

        // take a full snapshot
        server.takeSnapshot(); 

        // using reflection to change the final static DIGEST_VERSION
        int newVersion = currentVersion + 1;
        Field field = DigestCalculator.class.getDeclaredField("DIGEST_VERSION");
        field.setAccessible(true);
        Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
        field.set(null, newVersion);

        Assert.assertEquals(newVersion, (int) DigestCalculator.DIGEST_VERSION);

        // using mock to return different digest value when the way we 
        // calculate digest changed
        FileTxnSnapLog txnSnapLog = new FileTxnSnapLog(tmpDir, tmpDir);
        DataTree dataTree = Mockito.spy(new DataTree());
        Mockito.when(dataTree.getTreeDigest()).thenReturn(0L); 
        txnSnapLog.restore(dataTree, new ConcurrentHashMap<Long, Integer>(), 
                Mockito.mock(FileTxnSnapLog.PlayBackListener.class));

        // make sure the reportDigestMismatch function is never called
        Mockito.verify(dataTree, Mockito.never())
               .reportDigestMismatch(Mockito.anyLong()); 
    }

    /**
     * Make sure it's backward compatible, and also we can rollback this 
     * feature without corrupt the database.
     */
    @Test
    public void testBackwardCompatible() throws Exception {
        testCompatibleHelper(false, true);

        testCompatibleHelper(true, false);
    }

    private void testCompatibleHelper(
            boolean enabledBefore, boolean enabledAfter) throws Exception {

        DigestCalculator.setDigestEnabled(enabledBefore);

        // restart the server to cache the option change
        reloadSnapshotAndCheckDigest();
   
         // create a node
        String path = "/testCompatible" + "-" + enabledBefore + "-" + enabledAfter;
        zk.create(path, path.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, 
                CreateMode.PERSISTENT);

        // take a full snapshot
        server.takeSnapshot(); 

        DigestCalculator.setDigestEnabled(enabledAfter);
      
        reloadSnapshotAndCheckDigest();

        Assert.assertEquals(path, new String(zk.getData(path, false, null)));
    }

    private void reloadSnapshotAndCheckDigest() throws Exception {
        stopServer();
        QuorumPeerMainTest.waitForOne(zk, States.CONNECTING);

        ServerMetrics.DIGEST_MISMATCHES_COUNT.reset();

        startServer();
        QuorumPeerMainTest.waitForOne(zk, States.CONNECTED);

        // Snapshot digests always match
        Assert.assertEquals(0L, (long) ServerMetrics.DIGEST_MISMATCHES_COUNT
                .getValues().get("digest_mismatches_count"));

        // reset the digestFromLoadedSnapshot after comparing
        Assert.assertNull(server.getZKDatabase().getDataTree()
                .getDigestFromLoadedSnapshot());
    }
}
