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

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.QuotaExceededException;
import org.apache.zookeeper.Quotas;
import org.apache.zookeeper.StatsTrack;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeperMain;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.cli.SetQuotaCommand;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.Assert;
import org.junit.Test;

public class ZooKeeperQuotaTest extends ClientBase {

    public static class OldStatsTrack {
        private int count;
        private long bytes;

        public OldStatsTrack(String stats) {
            if (stats == null) {
                stats = "count=-1,bytes=-1";
            }
            String[] split = stats.split(",");
            if (split.length != 2) {
                throw new IllegalArgumentException("invalid string " + stats);
            }
            count = Integer.parseInt(split[0].split("=")[1]);
            bytes = Long.parseLong(split[1].split("=")[1]);
        }

        public int getCount() {
            return count;
        }

        public long getBytes() {
            return bytes;
        }
    }

    @Test
    public void testQuota() throws IOException,
        InterruptedException, KeeperException, Exception {
        final ZooKeeper zk = createClient();
        final String path = "/a/b/v";
        // making sure setdata works on /
        zk.setData("/", "some".getBytes(), -1);
        zk.create("/a", "some".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);

        zk.create("/a/b", "some".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);

        zk.create("/a/b/v", "some".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);

        zk.create("/a/b/v/d", "some".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        ZooKeeperMain.createQuota(zk, path, 5L, 10);

        // see if its set
        String absolutePath = Quotas.quotaZookeeper + path + "/" + Quotas.limitNode;
        byte[] data = zk.getData(absolutePath, false, new Stat());
        StatsTrack st = new StatsTrack(new String(data));
        Assert.assertTrue("bytes are set", st.getBytes() == 5L);
        Assert.assertTrue("num count is set", st.getCount() == 10);

        String statPath = Quotas.quotaZookeeper + path + "/" + Quotas.statNode;
        byte[] qdata = zk.getData(statPath, false, new Stat());
        StatsTrack qst = new StatsTrack(new String(qdata));
        Assert.assertTrue("bytes are set", qst.getBytes() == 8L);
        Assert.assertTrue("count is set", qst.getCount() == 2);

        //force server to restart and load from snapshot, not txn log
        stopServer();
        startServer();
        stopServer();
        startServer();
        ZooKeeperServer server = getServer(serverFactory);
        Assert.assertNotNull("Quota is still set",
            server.getZKDatabase().getDataTree().getMaxPrefixWithQuota(path) != null);
    }

    @Test
    public void testRateQuota() throws IOException, InterruptedException, KeeperException, Exception {
        final ZooKeeper zk = createClient();

        zk.create("/a2", "some".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        StatsTrack quota = new StatsTrack(null);
        // Default BPS window duration is 10s, so a limit of 10 allows 100
        // bytes through over the course of the 10s window.
        quota.setBytesPerSecHardLimit(10L);
        SetQuotaCommand.createQuota(zk, "/a2", quota);

        // see if its set
        String absolutePath = Quotas.quotaZookeeper + "/a2/" + Quotas.limitNode;
        byte[] data = zk.getData(absolutePath, false, new Stat());
        StatsTrack st = new StatsTrack(new String(data));
        Assert.assertTrue("bytes-per-sec hard limit is set",
                st.getBytesPerSecHardLimit() == 10L);
        Assert.assertTrue("bytes-per-sec soft limit is not set",
                st.getBytesPerSec() == -1L);
        // check quota node readable by old servers
        OldStatsTrack ost = new OldStatsTrack(new String(data));

        String statPath = Quotas.quotaZookeeper + "/a2/" + Quotas.statNode;
        data = zk.getData(statPath, false, new Stat());
        st = new StatsTrack(new String(data));
        Assert.assertTrue("bytes-per-sec bytes is unset",
                st.getBytesPerSecBytes() == 0L);
        // check stats node readable by old servers
        ost = new OldStatsTrack(new String(data));

        // Overhead per transaction is modeled at 40 bytes, so these two
        // creates send 404=44 bytes each.
        zk.create("/a2/b", "some".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        zk.create("/a2/c", "some".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        try {
            zk.create("/a2/d", "some".getBytes(), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);
            Assert.fail("Should get quota exceeded error (bytes-per-sec hard limit)");
        } catch (KeeperException.QuotaExceededException e) {
            // expected
        }
        
        data = zk.getData(statPath, false, new Stat());
        st = new StatsTrack(new String(data));
        Assert.assertTrue("bytes-per-sec bytes is set",
                st.getBytesPerSecBytes() == 88L);
        Assert.assertTrue("bytes-per-sec start time is set",
                st.getBytesPerSecStartTime() != -1L);
        // check stats node readable by old servers
        ost = new OldStatsTrack(new String(data));
    }

    @Test(expected = QuotaExceededException.class)
    public void testSetExceedBytesQuota() throws Exception {

        final ZooKeeper zk = createClient();
        final String path = "/test/quota";
        zk.create("/test", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/test/quota", "data".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        ZooKeeperMain.createQuota(zk, path, 5L, 10);
        zk.setData("/test/quota", "newdata".getBytes(), -1);
    }

    @Test//(expected = QuotaExceededException.class)
    public void testSetOnChildExceedBytesQuota() throws Exception {

        final ZooKeeper zk = createClient();
        final String path = "/test/quota";
        zk.create("/test", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/test/quota", "data".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        zk.create("/test/quota/data", "data".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        ZooKeeperMain.createQuota(zk, path, 5L, 10);
        try {
            zk.setData("/test/quota/data", "newdata".getBytes(), -1);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test(expected = QuotaExceededException.class)
    public void testCreateExceedBytesQuota() throws Exception {

        final ZooKeeper zk = createClient();
        final String path = "/test/quota";
        zk.create("/test", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/test/quota", "data".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        ZooKeeperMain.createQuota(zk, path, 5L, 10);
        zk.create("/test/quota/data", "data".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
    }

    @Test(expected = QuotaExceededException.class)
    public void testCreateExceedCountQuota() throws Exception {

        final ZooKeeper zk = createClient();
        final String path = "/test/quota";
        zk.create("/test", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/test/quota", "data".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        ZooKeeperMain.createQuota(zk, path, 100L, 1);
        zk.create("/test/quota/data", "data".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
    }
}
