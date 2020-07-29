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

package org.apache.zookeeper.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Quotas;
import org.apache.zookeeper.StatsTrack;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.cli.MalformedPathException;
import org.apache.zookeeper.cli.SetQuotaCommand;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.jupiter.api.Test;

public class ZooKeeperQuotaTest extends ClientBase {

    @Test
    public void testQuota() throws Exception {
        final ZooKeeper zk = createClient();
        final String path = "/a/b/v";
        // making sure setdata works on /
        zk.setData("/", "some".getBytes(), -1);
        zk.create("/a", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        zk.create("/a/b", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        zk.create("/a/b/v", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        zk.create("/a/b/v/d", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        SetQuotaCommand.createQuota(zk, path, 5L, 10);

        // see if its set
        String absolutePath = Quotas.quotaZookeeper + path + "/" + Quotas.limitNode;
        byte[] data = zk.getData(absolutePath, false, new Stat());
        StatsTrack st = new StatsTrack(new String(data));
        assertTrue(st.getBytes() == 5L, "bytes are set");
        assertTrue(st.getCount() == 10, "num count is set");

        String statPath = Quotas.quotaZookeeper + path + "/" + Quotas.statNode;
        byte[] qdata = zk.getData(statPath, false, new Stat());
        StatsTrack qst = new StatsTrack(new String(qdata));
        assertTrue(qst.getBytes() == 8L, "bytes are set");
        assertTrue(qst.getCount() == 2, "count is set");

        //force server to restart and load from snapshot, not txn log
        stopServer();
        startServer();
        stopServer();
        startServer();
        ZooKeeperServer server = serverFactory.getZooKeeperServer();
        assertNotNull(server.getZKDatabase().getDataTree().getMaxPrefixWithQuota(path) != null, "Quota is still set");
    }

    @Test
    public void testSetQuota() throws IOException, InterruptedException, KeeperException, MalformedPathException {
        final ZooKeeper zk = createClient();

        String path = "/c1";
        String nodeData = "foo";
        zk.create(path, nodeData.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        int count = 10;
        long bytes = 5L;
        SetQuotaCommand.createQuota(zk, path, bytes, count);

        //check the limit
        String absoluteLimitPath = Quotas.quotaZookeeper + path + "/" + Quotas.limitNode;
        byte[] data = zk.getData(absoluteLimitPath, false, null);
        StatsTrack st = new StatsTrack(new String(data));
        assertEquals(bytes, st.getBytes());
        assertEquals(count, st.getCount());
        //check the stats
        String absoluteStatPath = Quotas.quotaZookeeper + path + "/" + Quotas.statNode;
        data = zk.getData(absoluteStatPath, false, null);
        st = new StatsTrack(new String(data));
        assertEquals(nodeData.length(), st.getBytes());
        assertEquals(1, st.getCount());

        //create another node
        String path2 = "/c1/c2";
        String nodeData2 = "bar";
        zk.create(path2, nodeData2.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        absoluteStatPath = Quotas.quotaZookeeper + path + "/" + Quotas.statNode;
        data = zk.getData(absoluteStatPath, false, null);
        st = new StatsTrack(new String(data));
        //check the stats
        assertEquals(nodeData.length() + nodeData2.length(), st.getBytes());
        assertEquals(2, st.getCount());
    }

    @Test
    public void testSetQuotaWhenSetQuotaOnParentOrChildPath() throws IOException, InterruptedException, KeeperException, MalformedPathException {
        final ZooKeeper zk = createClient();

        zk.create("/c1", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/c1/c2", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/c1/c2/c3", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/c1/c2/c3/c4", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/c1/c2/c3/c4/c5", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        //set the quota on the path:/c1/c2/c3
        SetQuotaCommand.createQuota(zk, "/c1/c2/c3", 5L, 10);

        try {
            SetQuotaCommand.createQuota(zk, "/c1", 5L, 10);
        } catch (IllegalArgumentException e) {
            assertEquals("/c1 has a child /c1/c2/c3 which has a quota", e.getMessage());
        }

        try {
            SetQuotaCommand.createQuota(zk, "/c1/c2/c3/c4/c5", 5L, 10);
        } catch (IllegalArgumentException e) {
            assertEquals("/c1/c2/c3/c4/c5 has a parent /c1/c2/c3 which has a quota", e.getMessage());
        }
    }

}
