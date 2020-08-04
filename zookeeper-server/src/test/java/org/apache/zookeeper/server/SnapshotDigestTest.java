/*
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.server.metric.SimpleCounter;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.QuorumPeerMainTest;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SnapshotDigestTest extends ClientBase {

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotDigestTest.class);

    private ZooKeeper zk;
    private ZooKeeperServer server;

    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        server = serverFactory.getZooKeeperServer();
        zk = createClient();
    }

    @AfterEach
    public void tearDown() throws Exception {
        // server will be closed in super.tearDown
        super.tearDown();

        if (zk != null) {
            zk.close();
        }
    }

    @Override
    public void setupCustomizedEnv() {
        ZooKeeperServer.setDigestEnabled(true);
        System.setProperty(ZooKeeperServer.SNAP_COUNT, "100");
    }

    @Override
    public void cleanUpCustomizedEnv() {
        ZooKeeperServer.setDigestEnabled(false);
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
            zk.create(path, path.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
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
            subTxns.add(Op.create(path, path.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        }
        zk.multi(subTxns);

        reloadSnapshotAndCheckDigest();

        // Take a snapshot and test the logic when loading a non-fuzzy snapshot
        server = serverFactory.getZooKeeperServer();
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
        int currentVersion = new DigestCalculator().getDigestVersion();

        // create a node
        String path = "/testDifferentDigestVersion";
        zk.create(path, path.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        // take a full snapshot
        server.takeSnapshot();

        //increment the digest version
        int newVersion = currentVersion + 1;
        DigestCalculator newVersionDigestCalculator = Mockito.spy(DigestCalculator.class);
        Mockito.when(newVersionDigestCalculator.getDigestVersion()).thenReturn(newVersion);
        assertEquals(newVersion, newVersionDigestCalculator.getDigestVersion());

        // using mock to return different digest value when the way we
        // calculate digest changed
        FileTxnSnapLog txnSnapLog = new FileTxnSnapLog(tmpDir, tmpDir);
        DataTree dataTree = Mockito.spy(new DataTree(newVersionDigestCalculator));
        Mockito.when(dataTree.getTreeDigest()).thenReturn(0L);
        txnSnapLog.restore(dataTree, new ConcurrentHashMap<>(), Mockito.mock(FileTxnSnapLog.PlayBackListener.class));

        // make sure the reportDigestMismatch function is never called
        Mockito.verify(dataTree, Mockito.never()).reportDigestMismatch(Mockito.anyLong());
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

    private void testCompatibleHelper(Boolean enabledBefore, Boolean enabledAfter) throws Exception {

        ZooKeeperServer.setDigestEnabled(enabledBefore);


        // restart the server to cache the option change
        reloadSnapshotAndCheckDigest();

        // create a node
        String path = "/testCompatible" + "-" + enabledBefore + "-" + enabledAfter;
        zk.create(path, path.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        // take a full snapshot
        server.takeSnapshot();

        ZooKeeperServer.setDigestEnabled(enabledAfter);

        reloadSnapshotAndCheckDigest();

        assertEquals(path, new String(zk.getData(path, false, null)));
    }

    private void reloadSnapshotAndCheckDigest() throws Exception {
        stopServer();
        QuorumPeerMainTest.waitForOne(zk, States.CONNECTING);

        ((SimpleCounter) ServerMetrics.getMetrics().DIGEST_MISMATCHES_COUNT).reset();

        startServer();
        QuorumPeerMainTest.waitForOne(zk, States.CONNECTED);

        server = serverFactory.getZooKeeperServer();

        // Snapshot digests always match
        assertEquals(0L, ServerMetrics.getMetrics().DIGEST_MISMATCHES_COUNT.get());

        // reset the digestFromLoadedSnapshot after comparing
        assertNull(server.getZKDatabase().getDataTree().getDigestFromLoadedSnapshot());
    }

}
