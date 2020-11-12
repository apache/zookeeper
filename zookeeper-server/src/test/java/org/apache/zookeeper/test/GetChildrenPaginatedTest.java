/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.test;

import static org.junit.Assert.fail;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.UUID;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.RemoteIterator;
import org.apache.zookeeper.Transaction;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.PathWithStat;
import org.apache.zookeeper.data.Stat;
import org.junit.Assert;
import org.junit.Test;

public class GetChildrenPaginatedTest extends ClientBase {
    private ZooKeeper zk;
    private final Random random = new Random();


    @Override
    public void setUp() throws Exception {
        super.setUp();

        zk = createClient();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();

        zk.close();
    }

    @Test(timeout = 30000)
    public void testPagination() throws Exception {

        final String testId = UUID.randomUUID().toString();
        final String basePath = "/testPagination-" + testId;

        Map<String, Stat> createdChildrenMetadata = createChildren(basePath, 10, 1);

        // Create child 0 out of order (to make sure paths are not ordered lexicographically).
        {
            String childPath = basePath + "/" + 0;
            zk.create(childPath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            final Stat stat = zk.exists(childPath, null);

            createdChildrenMetadata.put(String.valueOf(0), stat);

            LOG.info("Created: " + childPath + " czxId: " + stat.getCzxid());
        }

        long minCzxId = -1;
        Map<String, Stat> readChildrenMetadata = new HashMap<String, Stat>();
        final int pageSize = 3;

        RemoteIterator<PathWithStat> it = zk.getChildrenIterator(basePath, null, pageSize, minCzxId);

        while (it.hasNext()) {
            PathWithStat pathWithStat = it.next();

            final String nodePath = pathWithStat.getPath();
            final Stat nodeStat = pathWithStat.getStat();

            LOG.info("Read: " + nodePath + " czxid: " + nodeStat.getCzxid());
            readChildrenMetadata.put(nodePath, nodeStat);

            Assert.assertTrue(nodeStat.getCzxid() > minCzxId);
            minCzxId = nodeStat.getCzxid();

        }

        Assert.assertEquals(createdChildrenMetadata.keySet(), readChildrenMetadata.keySet());

        for (String child : createdChildrenMetadata.keySet()) {
            Assert.assertEquals(createdChildrenMetadata.get(child), readChildrenMetadata.get(child));
        }
    }

    @Test(timeout = 30000)
    public void testPaginationIterator() throws Exception {

        final String testId = UUID.randomUUID().toString();
        final String basePath = "/testPagination-" + testId;

        Map<String, Stat> createdChildrenMetadata = createChildren(basePath, random.nextInt(50) + 1, 0);

        Map<String, Stat> readChildrenMetadata = new HashMap<String, Stat>();

        final int batchSize = random.nextInt(3) + 1;

        RemoteIterator<PathWithStat> childrenIterator = zk.getChildrenIterator(basePath, null, batchSize, -1);


        while (childrenIterator.hasNext()) {
            PathWithStat child = childrenIterator.next();

            final String nodePath = child.getPath();
            final Stat nodeStat = child.getStat();

            LOG.info("Read: " + nodePath + " czxid: " + nodeStat.getCzxid());
            readChildrenMetadata.put(nodePath, nodeStat);
        }

        Assert.assertEquals(createdChildrenMetadata.keySet(), readChildrenMetadata.keySet());

        for (String child : createdChildrenMetadata.keySet()) {
            Assert.assertEquals(createdChildrenMetadata.get(child), readChildrenMetadata.get(child));
        }
    }

    /*
     * This test validates a known list of children is returned by the iterator despite server failures.
     * After the iterator is created successfully, the following logic drives the rest of the test:
     * <ul>
     *     <li>Randomly change the server state (down to up or up to down)</li>
     *     <li>Try to fetch the next element, swallowing exception produced by the server being down</li>
     * </ul>
     * Eventually, all children should be returned regardless of the number of times the server was unavailable.
     */
    @Test(timeout = 60000)
    public void testPaginationWithServerDown() throws Exception {

        final String testId = UUID.randomUUID().toString();
        final String basePath = "/testPagination-" + testId;

        Map<String, Stat> createdChildrenMetadata = createChildren(basePath, random.nextInt(15) + 10, 0);

        Map<String, Stat> readChildrenMetadata = new HashMap<String, Stat>();

        final int batchSize = random.nextInt(3) + 1;

        RemoteIterator<PathWithStat> childrenIterator = zk.getChildrenIterator(basePath, null, batchSize, -1);

        boolean serverDown = false;

        while (childrenIterator.hasNext()) {

            // Randomly change the up/down state of the server
            if (random.nextBoolean()) {
                if (serverDown) {
                    LOG.info("Bringing server UP");
                    startServer();
                    waitForServerUp(hostPort, 5000);
                    serverDown = false;
                } else {
                    LOG.info("Taking server DOWN");
                    stopServer();
                    serverDown = true;
                }
            }

            PathWithStat child = null;

            boolean exception = false;
            try {
                child = childrenIterator.next();
            } catch (InterruptedException | KeeperException e) {
                LOG.info("Exception in #next(): " + e.getMessage());
                exception = true;
            }

            if (!exception) {
                // next() returned (either more elements in current batch or server is up)
                Assert.assertNotNull(child);

                final String nodePath = child.getPath();
                final Stat nodeStat = child.getStat();

                LOG.info("Read: " + nodePath + " czxid: " + nodeStat.getCzxid());
                readChildrenMetadata.put(nodePath, nodeStat);
            }
        }

        Assert.assertEquals(createdChildrenMetadata.keySet(), readChildrenMetadata.keySet());

        for (String child : createdChildrenMetadata.keySet()) {
            Assert.assertEquals(createdChildrenMetadata.get(child), readChildrenMetadata.get(child));
        }
    }


    class FireOnlyOnceWatcher implements Watcher {
        int watchFiredCount = 0;

        @Override
        public void process(WatchedEvent event) {

            synchronized (this) {
                watchFiredCount += 1;
                this.notify();
            }
        }
    }

    @Test(timeout = 30000)
    public void testPaginationWatch() throws Exception {

        final String testId = UUID.randomUUID().toString();
        final String basePath = "/testPaginationWatch-" + testId;

        createChildren(basePath, 10, 0);

        long minCzxId = -1;
        final int pageSize = 3;

        FireOnlyOnceWatcher fireOnlyOnceWatcher = new FireOnlyOnceWatcher();

        RemoteIterator<PathWithStat> it = zk.getChildrenIterator(basePath, fireOnlyOnceWatcher, pageSize, minCzxId);

        int childrenIndex = 0;

        while (it.hasNext()) {

            ++childrenIndex;

            PathWithStat pathWithStat = it.next();

            final String nodePath = pathWithStat.getPath();
            LOG.info("Read: " + nodePath);

            final Stat nodeStat = pathWithStat.getStat();

            Assert.assertTrue(nodeStat.getCzxid() > minCzxId);
            minCzxId = nodeStat.getCzxid();

            // Create more children before pagination is completed -- should NOT trigger watch
            if (childrenIndex < 6) {
                String childPath = basePath + "/" + "before-pagination-" + childrenIndex;
                zk.create(childPath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }

            // Modify the first child of each page.
            // This should not trigger additional watches or create duplicates in the set of children returned
            if (childrenIndex % pageSize == 0) {
                zk.setData(basePath + "/" + pathWithStat.getPath(), new byte[3], -1);
            }

            synchronized (fireOnlyOnceWatcher) {
                Assert.assertEquals("Watch should not have fired yet", 0, fireOnlyOnceWatcher.watchFiredCount);
            }
        }

        // Create another children after pagination is completed -- should trigger watch
        {
            String childPath = basePath + "/" + "after-pagination";
            zk.create(childPath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }


        // Test eventually times out and fails if watches does not fire
        while (true) {
            synchronized (fireOnlyOnceWatcher) {
                if (fireOnlyOnceWatcher.watchFiredCount > 0) {
                    Assert.assertEquals("Watch should have fired once", 1, fireOnlyOnceWatcher.watchFiredCount);
                    break;
                }
                fireOnlyOnceWatcher.wait(1000);
            }
        }

        // Watch fired once.

        // Give it another chance to fire (i.e. a duplicate) which would make the test fail
        synchronized (fireOnlyOnceWatcher) {
            fireOnlyOnceWatcher.wait(1000);
            Assert.assertEquals("Watch should have fired once", 1, fireOnlyOnceWatcher.watchFiredCount);
        }
    }

    @Test(timeout = 60000, expected = NoSuchElementException.class)
    public void testPaginationWithNoChildren() throws Exception {

        final String testId = UUID.randomUUID().toString();
        final String basePath = "/testPagination-" + testId;

        Map<String, Stat> createdChildrenMetadata = createChildren(basePath, 0, 0);

        final int batchSize = 10;

        RemoteIterator<PathWithStat> childrenIterator = zk.getChildrenIterator(basePath, null, batchSize, -1);

        Assert.assertFalse(childrenIterator.hasNext());

        childrenIterator.next();
        fail("NoSuchElementException is expected");
    }

    private Map<String, Stat> createChildren(String basePath, int numChildren, int firstChildrenNameOffset) throws KeeperException, InterruptedException {
        zk.create(basePath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        Map<String, Stat> createdChildrenMetadata = new HashMap<String, Stat>();

        for (int i = firstChildrenNameOffset; i < (firstChildrenNameOffset + numChildren); i++) {
            String childPath = basePath + "/" + i;
            zk.create(childPath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            final Stat stat = zk.exists(childPath, null);

            createdChildrenMetadata.put(String.valueOf(i), stat);

            LOG.info("Created: " + childPath + " czxid: " + stat.getCzxid());
        }
        return createdChildrenMetadata;
    }

    @Test(timeout = 60000)
    public void testPaginationWithMulti() throws Exception {

        final String testId = UUID.randomUUID().toString();
        final String basePath = "/testPagination-" + testId;

        final int numChildren = 10;
        final int batchSize = 3;

        zk.create(basePath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        Transaction transaction = zk.transaction();
        for (int i = 0; i < numChildren; i++) {
            String childPath = basePath + "/" + i;
            transaction.create(childPath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }

        List<OpResult> transactionOpResults = transaction.commit();

        Assert.assertEquals(numChildren, transactionOpResults.size());

        Map<String, Stat> createdChildrenMetadata = new HashMap<>();

        for (int i = 0; i < numChildren; i++) {
            String childPath = basePath + "/" + i;
            final Stat stat = zk.exists(childPath, null);
            createdChildrenMetadata.put(String.valueOf(i), stat);
            LOG.info("Created: " + childPath + " zkId: " + stat.getCzxid());
        }

        Map<String, Stat> readChildrenMetadata = new HashMap<String, Stat>();

        RemoteIterator<PathWithStat> childrenIterator = zk.getChildrenIterator(basePath, null, batchSize, -1);

        while (childrenIterator.hasNext()) {

            PathWithStat children = childrenIterator.next();

            LOG.info("Read: " + children.getPath() + " zkId: " + children.getStat().getCzxid());
            readChildrenMetadata.put(children.getPath(), children.getStat());
        }

        Assert.assertEquals(numChildren, readChildrenMetadata.size());

        Assert.assertEquals(createdChildrenMetadata.keySet(), readChildrenMetadata.keySet());

        for (String child : createdChildrenMetadata.keySet()) {
            Assert.assertEquals(createdChildrenMetadata.get(child), readChildrenMetadata.get(child));
        }
    }
}