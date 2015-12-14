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

import org.apache.zookeeper.*;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.PathWithStat;
import org.apache.zookeeper.data.Stat;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class GetChildrenPaginatedTest extends ClientBase {
    private ZooKeeper zk;

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

    @Test
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

            LOG.info("Created: " + childPath + " zkId: " + stat.getCzxid());
        }

        long minZkId = -1;
        Map<String, Stat> readChildrenMetadata = new HashMap<String, Stat>();
        final int pageSize = 3;

        while (true) {
            final List<PathWithStat> page = zk.getChildrenPaginated(basePath, null, pageSize, minZkId);

            if(page.isEmpty()) {
                break;
            }

            for (PathWithStat pathWithStat : page) {

                final String nodePath = pathWithStat.getPath();
                final Stat nodeStat = pathWithStat.getStat();

                LOG.info("Read: " + nodePath + " zkId: " + nodeStat.getCzxid());
                readChildrenMetadata.put(nodePath, nodeStat);

                Assert.assertTrue(nodeStat.getCzxid() > minZkId);
                minZkId = nodeStat.getCzxid();
            }
        }

        Assert.assertEquals(createdChildrenMetadata.keySet(), readChildrenMetadata.keySet());

        for (String child : createdChildrenMetadata.keySet()) {
            Assert.assertEquals(createdChildrenMetadata.get(child), readChildrenMetadata.get(child));
        }
    }

    class MyWatcher implements Watcher {
        boolean watchFired = false;

        @Override
        public void process(WatchedEvent event) {
            Assert.assertFalse("Watch fired multiple times " + event.toString(), watchFired);
            watchFired = true;
        }
    };

    @Test(timeout = 30000)
    public void testPaginationWatch() throws Exception {

        final String testId = UUID.randomUUID().toString();
        final String basePath = "/testPaginationWatch-" + testId;

        createChildren(basePath, 10, 0);

        long minZkId = -1;
        final int pageSize = 3;
        int pageCount = 0;

        MyWatcher myWatcher = new MyWatcher();

        while (true) {
            final List<PathWithStat> page = zk.getChildrenPaginated(basePath, myWatcher, pageSize, minZkId);

            if(page.isEmpty()) {
                break;
            }

            // Create another children before pagination is completed -- should NOT trigger watch
            if(pageCount < 3) {
                String childPath = basePath + "/" + "before-pagination-" + pageCount;
                zk.create(childPath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }

            for (PathWithStat pathWithStat : page) {

                final String nodePath = pathWithStat.getPath();
                LOG.info("Read: " + nodePath);

                final Stat nodeStat = pathWithStat.getStat();

                Assert.assertTrue(nodeStat.getCzxid() > minZkId);
                minZkId = nodeStat.getCzxid();
            }

            pageCount += 1;
        }

        // Create another children after pagination is completed -- should trigger watch
        {
            String childPath = basePath + "/" + "after-pagination";
            zk.create(childPath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }

        while (true) {
            // Test times-out if this doesn't become true in a reasonable amount of time
            if(!myWatcher.watchFired) {
                LOG.info("Watch did not fire yet");
                Thread.sleep(1000);
            } else {
                LOG.info("Watch fired");
                break;
            }
        }

        //Sleep a bit more, to allow any duplicated watch to fire again
        Thread.sleep(1000);
        Assert.assertTrue(myWatcher.watchFired);
    }

    private Map<String, Stat> createChildren(String basePath, int numChildren, int firstChildrenNameOffset) throws KeeperException, InterruptedException {
        zk.create(basePath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        Map<String, Stat> createdChildrenMetadata = new HashMap<String, Stat>();

        for (int i = firstChildrenNameOffset; i < (firstChildrenNameOffset+numChildren); i++) {
            String childPath = basePath + "/" + i;
            zk.create(childPath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            final Stat stat = zk.exists(childPath, null);

            createdChildrenMetadata.put(String.valueOf(i), stat);

            LOG.info("Created: " + childPath + " zkId: " + stat.getCzxid());
        }
        return createdChildrenMetadata;
    }
}
