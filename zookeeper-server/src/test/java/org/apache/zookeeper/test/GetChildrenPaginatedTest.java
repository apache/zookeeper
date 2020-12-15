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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.apache.jute.BinaryInputArchive;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.PaginationNextPage;
import org.apache.zookeeper.RemoteIterator;
import org.apache.zookeeper.Transaction;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.quorum.BufferStats;
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
        Set<String> readChildrenMetadata = new HashSet<String>();
        final int pageSize = 3;

        RemoteIterator<String> it = zk.getChildrenIterator(basePath, null, pageSize, minCzxId);

        while (it.hasNext()) {
            final String nodePath = it.next();

            LOG.info("Read: " + nodePath);
            readChildrenMetadata.add(nodePath);

            Assert.assertTrue(createdChildrenMetadata.get(nodePath).getCzxid() > minCzxId);
            minCzxId = createdChildrenMetadata.get(nodePath).getCzxid();
        }

        Assert.assertEquals(createdChildrenMetadata.keySet(), readChildrenMetadata);
    }

    @Test(timeout = 30000)
    public void testPaginationIterator() throws Exception {

        final String testId = UUID.randomUUID().toString();
        final String basePath = "/testPagination-" + testId;

        Map<String, Stat> createdChildrenMetadata = createChildren(basePath, random.nextInt(50) + 1, 0);

        Set<String> readChildrenMetadata = new HashSet<String>();

        final int batchSize = random.nextInt(3) + 1;

        RemoteIterator<String> childrenIterator = zk.getChildrenIterator(basePath, null, batchSize, -1);


        while (childrenIterator.hasNext()) {
            String nodePath = childrenIterator.next();

            LOG.info("Read: " + nodePath);
            readChildrenMetadata.add(nodePath);
        }

        Assert.assertEquals(createdChildrenMetadata.keySet(), readChildrenMetadata);
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

        Set<String> readChildrenMetadata = new HashSet<String>();

        final int batchSize = random.nextInt(3) + 1;

        RemoteIterator<String> childrenIterator = zk.getChildrenIterator(basePath, null, batchSize, -1);

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

            String child = null;

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

                LOG.info("Read: " + child);
                readChildrenMetadata.add(child);
            }
        }

        Assert.assertEquals(createdChildrenMetadata.keySet(), readChildrenMetadata);
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

        RemoteIterator<String> it = zk.getChildrenIterator(basePath, fireOnlyOnceWatcher, pageSize, minCzxId);

        int childrenIndex = 0;

        while (it.hasNext()) {

            ++childrenIndex;

            final String nodePath = it.next();
            LOG.info("Read: " + nodePath);

            // Create more children before pagination is completed -- should NOT trigger watch
            if (childrenIndex < 6) {
                String childPath = basePath + "/" + "before-pagination-" + childrenIndex;
                zk.create(childPath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }

            // Modify the first child of each page.
            // This should not trigger additional watches or create duplicates in the set of children returned
            if (childrenIndex % pageSize == 0) {
                zk.setData(basePath + "/" + nodePath, new byte[3], -1);
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

        RemoteIterator<String> childrenIterator = zk.getChildrenIterator(basePath, null, batchSize, -1);

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

        Set<String> readChildrenMetadata = new HashSet<String>();

        RemoteIterator<String> childrenIterator = zk.getChildrenIterator(basePath, null, batchSize, -1);

        while (childrenIterator.hasNext()) {

            String children = childrenIterator.next();

            LOG.info("Read: " + children);
            readChildrenMetadata.add(children);
        }

        Assert.assertEquals(numChildren, readChildrenMetadata.size());

        Assert.assertEquals(createdChildrenMetadata.keySet(), readChildrenMetadata);
    }

    /*
     * Tests if all children can be fetched in one page, the children are
     * in the same order(no sorting by czxid) as the non-paginated result.
     */
    @Test(timeout = 60000)
    public void testGetAllChildrenPaginatedOnePage() throws KeeperException, InterruptedException {
        final String basePath = "/testPagination-" + UUID.randomUUID().toString();
        createChildren(basePath, 100, 0);

        List<String> expected = zk.getChildren(basePath, false);
        List<String> actual = zk.getAllChildrenPaginated(basePath, false);

        Assert.assertEquals(expected, actual);
    }

    /*
     * Tests if all children's packet exceeds jute.maxbuffer, the paginated API can still successfully fetch them.
     */
    @Test(timeout = 60000)
    public void testGetAllChildrenPaginatedMultiPages() throws InterruptedException, KeeperException {
        // Get the number of children that would definitely exceed 1 MB.
        int numChildren = BinaryInputArchive.maxBuffer / UUID.randomUUID().toString().length() + 1;
        final String basePath = "/testPagination-" + UUID.randomUUID().toString();

        zk.create(basePath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        Set<String> expectedChildren = new HashSet<>();

        for (int i = 0; i < numChildren; i += 1000) {
            Transaction transaction = zk.transaction();
            for (int j = i; j < i + 1000 && j < numChildren; j++) {
                String child = UUID.randomUUID().toString();
                String childPath = basePath + "/" + child;
                transaction.create(childPath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                expectedChildren.add(child);
            }
            transaction.commit();
        }

        try {
            zk.getChildren(basePath, false);
            Assert.fail("Should not succeed to get children because packet length is out of range");
        } catch (KeeperException.ConnectionLossException expected) {
            // ConnectionLossException is expected because packet length exceeds jute.maxbuffer
        }

        // Paginated API can successfully fetch all the children with pagination.
        // If ConnectionLossException is thrown from this method, it possibly means
        // the packet length computing formula in DataTree#getPaginatedChildren needs modification.
        List<String> actualChildren = zk.getAllChildrenPaginated(basePath, false);

        Assert.assertEquals(numChildren, actualChildren.size());
        Assert.assertEquals(expectedChildren, new HashSet<>(actualChildren));
    }

    /*
     * Tests the packet length formula in DataTree as expected. The packet length calculated
     * is equal to the actual buffer size of the client response.
     */
    @Test(timeout = 60000)
    public void testPacketLengthFormula() throws KeeperException, InterruptedException {
        final String basePath = "/testPagination-" + UUID.randomUUID().toString();
        zk.create(basePath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        Set<String> expectedChildren = new HashSet<>();
        int numChildren = 50 + random.nextInt(50);

        for (int i = 0; i < numChildren; i++) {
            String child = UUID.randomUUID().toString();
            String childPath = basePath + "/" + child;
            zk.create(childPath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            expectedChildren.add(child);
        }

        // Fetch max number of children that can fit into one page. The client response stat should record the same
        // buffer size as the packet length calculated by the formula in DataTree.
        PaginationNextPage nextPage = new PaginationNextPage();

        // First page
        List<String> firstPage = zk.getChildren(basePath, null, numChildren, 0, 0, null, nextPage);
        BufferStats clientResponseStats = serverFactory.getZooKeeperServer().serverStats().getClientResponseStats();
        int expectedPacketLength =
                (DataTree.PAGINATION_PACKET_CHILD_EXTRA_BYTES + UUID.randomUUID().toString().length())
                        * firstPage.size() + DataTree.PAGINATION_PACKET_BASE_BYTES;
        int actualBufferSize = clientResponseStats.getLastBufferSize();

        Assert.assertEquals("The page should be the last page.",
                ZooDefs.GetChildrenPaginated.lastPageMinCzxid, nextPage.getMinCzxid());
        Assert.assertEquals("The client response buffer size should be equal to the packet length calculated by "
                + "pagination formula in DataTree.", expectedPacketLength, actualBufferSize);
        Assert.assertTrue(actualBufferSize <= BinaryInputArchive.maxBuffer);

        Assert.assertEquals(expectedChildren, new HashSet<>(firstPage));
    }

    /*
     * Tests below logic:
     * 1. the packet length formula is as expected. The calculated length is equal to the actual
     * client response's buffer size.
     * 2. logic of adding children on server side is correct. It does not miss the last child.
     * 3. watch is only added in the last page.
     */
    @Test(timeout = 60000)
    public void testMaxNumChildrenPageWatch() throws KeeperException, InterruptedException {
        // Get the max number of UUID children that can return in a page.
        int numChildren = (BinaryInputArchive.maxBuffer - DataTree.PAGINATION_PACKET_BASE_BYTES)
                / (UUID.randomUUID().toString().length() + DataTree.PAGINATION_PACKET_CHILD_EXTRA_BYTES);
        // The packet will exceed 1 MB.
        numChildren++;

        final String basePath = "/testPagination-" + UUID.randomUUID().toString();

        zk.create(basePath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        Set<String> expectedChildren = new HashSet<>();

        for (int i = 0; i < numChildren; i += 1000) {
            Transaction transaction = zk.transaction();
            for (int j = i; j < i + 1000 && j < numChildren; j++) {
                String child = UUID.randomUUID().toString();
                String childPath = basePath + "/" + child;
                transaction.create(childPath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                expectedChildren.add(child);
            }
            transaction.commit();
        }

        // Fetch max number of children that can fit into one page. The client response stat should record the same
        // buffer size as the packet length calculated by the formula in DataTree.
        PaginationNextPage nextPage = new PaginationNextPage();
        FireOnlyOnceWatcher fireOnlyOnceWatcher = new FireOnlyOnceWatcher();

        // First page
        List<String> firstPage = zk.getChildren(basePath, fireOnlyOnceWatcher, numChildren, 0, 0, null, nextPage);
        BufferStats clientResponseStats = serverFactory.getZooKeeperServer().serverStats().getClientResponseStats();
        int expectedPacketLength =
                (DataTree.PAGINATION_PACKET_CHILD_EXTRA_BYTES + UUID.randomUUID().toString().length())
                        * firstPage.size() + DataTree.PAGINATION_PACKET_BASE_BYTES;
        int actualBufferSize = clientResponseStats.getLastBufferSize();

        Assert.assertNotEquals("The page should not be the last page.",
                ZooDefs.GetChildrenPaginated.lastPageMinCzxid, nextPage.getMinCzxid());
        Assert.assertEquals("The client response buffer size should be equal to the packet length calculated by "
                + "pagination formula in DataTree.", expectedPacketLength, actualBufferSize);
        Assert.assertTrue(actualBufferSize <= BinaryInputArchive.maxBuffer);

        // Verify the index on server is correct: not adding the last child in the first page.
        Assert.assertEquals(numChildren - 1, firstPage.size());

        // Modify the first child of each page.
        // This should not trigger additional watches or create duplicates in the set of children returned
        zk.setData(basePath + "/" + firstPage.get(0), new byte[3], -1);

        synchronized (fireOnlyOnceWatcher) {
            Assert.assertEquals("Watch should not have fired yet", 0, fireOnlyOnceWatcher.watchFiredCount);
        }

        // Second page
        List<String> secondPage = zk.getChildren(basePath, fireOnlyOnceWatcher, numChildren, nextPage.getMinCzxid(),
                nextPage.getMinCzxidOffset(), null, nextPage);

        // Verify the logic of adding children to page in DataTree is correct.
        Assert.assertEquals(ZooDefs.GetChildrenPaginated.lastPageMinCzxid, nextPage.getMinCzxid());
        Assert.assertEquals("Should expect only 1 child left in the second page", 1, secondPage.size());

        zk.setData(basePath + "/" + secondPage.get(0), new byte[3], -1);

        synchronized (fireOnlyOnceWatcher) {
            Assert.assertEquals("Watch has fired", 0, fireOnlyOnceWatcher.watchFiredCount);
        }

        Set<String> actualSet = new HashSet<>(firstPage);
        actualSet.addAll(secondPage);
        Assert.assertEquals(expectedChildren, actualSet);
    }
}