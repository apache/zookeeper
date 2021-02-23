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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.QuotaExceededException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.Quotas;
import org.apache.zookeeper.StatsTrack;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.cli.DelQuotaCommand;
import org.apache.zookeeper.cli.ListQuotaCommand;
import org.apache.zookeeper.cli.MalformedPathException;
import org.apache.zookeeper.cli.SetQuotaCommand;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.test.StatsTrackTest.OldStatsTrack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ZooKeeperQuotaTest extends ClientBase {
    private ZooKeeper zk = null;

    @BeforeEach
    @Override
    public void setUp() throws Exception {
        System.setProperty(ZooKeeperServer.ENFORCE_QUOTA, "true");
        super.setUp();
        zk = createClient();
    }

    @AfterEach
    @Override
    public void tearDown() throws Exception {
        System.clearProperty(ZooKeeperServer.ENFORCE_QUOTA);
        super.tearDown();
        zk.close();
    }

    @Test
    public void testQuota() throws Exception {

        final String path = "/a/b/v";
        // making sure setdata works on /
        zk.setData("/", "some".getBytes(), -1);
        zk.create("/a", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        zk.create("/a/b", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        zk.create("/a/b/v", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        zk.create("/a/b/v/d", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        StatsTrack quota = new StatsTrack();
        quota.setCount(4);
        quota.setCountHardLimit(4);
        quota.setBytes(9L);
        quota.setByteHardLimit(15L);
        SetQuotaCommand.createQuota(zk, path, quota);

        // see if its set
        String absolutePath = Quotas.limitPath(path);
        byte[] data = zk.getData(absolutePath, false, new Stat());
        StatsTrack st = new StatsTrack(data);
        assertTrue(st.getBytes() == 9L, "bytes are set");
        assertTrue(st.getByteHardLimit() == 15L, "byte hard limit is set");
        assertTrue(st.getCount() == 4, "num count is set");
        assertTrue(st.getCountHardLimit() == 4, "count hard limit is set");

        // check quota node readable by old servers
        OldStatsTrack ost = new OldStatsTrack(new String(data));
        assertTrue(ost.getBytes() == 9L, "bytes are set");
        assertTrue(ost.getCount() == 4, "num count is set");

        String statPath = Quotas.statPath(path);
        byte[] qdata = zk.getData(statPath, false, new Stat());
        StatsTrack qst = new StatsTrack(qdata);
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

        String path = "/c1";
        String nodeData = "foo";
        zk.create(path, nodeData.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        int count = 10;
        long bytes = 5L;
        StatsTrack quota = new StatsTrack();
        quota.setCount(count);
        quota.setBytes(bytes);
        SetQuotaCommand.createQuota(zk, path, quota);

        //check the limit
        String absoluteLimitPath = Quotas.limitPath(path);
        byte[] data = zk.getData(absoluteLimitPath, false, null);
        StatsTrack st = new StatsTrack(data);
        assertEquals(bytes, st.getBytes());
        assertEquals(count, st.getCount());
        //check the stats
        String absoluteStatPath = Quotas.statPath(path);
        data = zk.getData(absoluteStatPath, false, null);
        st = new StatsTrack(data);
        assertEquals(nodeData.length(), st.getBytes());
        assertEquals(1, st.getCount());

        //create another node
        String path2 = "/c1/c2";
        String nodeData2 = "bar";
        zk.create(path2, nodeData2.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        absoluteStatPath = Quotas.statPath(path);
        data = zk.getData(absoluteStatPath, false, null);
        st = new StatsTrack(data);
        //check the stats
        assertEquals(nodeData.length() + nodeData2.length(), st.getBytes());
        assertEquals(2, st.getCount());
    }

    @Test
    public void testSetQuotaWhenSetQuotaOnParentOrChildPath() throws IOException, InterruptedException, KeeperException, MalformedPathException {

        zk.create("/c1", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/c1/c2", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/c1/c2/c3", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/c1/c2/c3/c4", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/c1/c2/c3/c4/c5", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        //set the quota on the path:/c1/c2/c3
        StatsTrack quota = new StatsTrack();
        quota.setCount(5);
        quota.setBytes(10);
        SetQuotaCommand.createQuota(zk, "/c1/c2/c3", quota);

        try {
            SetQuotaCommand.createQuota(zk, "/c1", quota);
            fail("should not set quota when child has a quota");
        } catch (IllegalArgumentException e) {
            assertEquals("/c1 has a child /c1/c2/c3 which has a quota", e.getMessage());
        }

        try {
            SetQuotaCommand.createQuota(zk, "/c1/c2/c3/c4/c5", quota);
            fail("should not set quota when parent has a quota");
        } catch (IllegalArgumentException e) {
            assertEquals("/c1/c2/c3/c4/c5 has a parent /c1/c2/c3 which has a quota", e.getMessage());
        }
    }

    @Test
    public void testSetQuotaWhenExceedBytesSoftQuota() throws Exception {

        final String path = "/c1";
        zk.create(path, "data".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        StatsTrack st = new StatsTrack();
        st.setBytes(5L);
        SetQuotaCommand.createQuota(zk, path, st);

        zk.setData(path, "12345".getBytes(), -1);

        try {
            zk.setData(path, "123456".getBytes(), -1);
        } catch (Exception e) {
            fail("should set data which exceeds the soft byte quota");
        }
    }

    @Test
    public void testSetQuotaWhenExceedBytesHardQuota() throws Exception {

        final String path = "/c1";
        zk.create(path, "12345".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        StatsTrack st = new StatsTrack();
        st.setByteHardLimit(5L);
        SetQuotaCommand.createQuota(zk, path, st);

        try {
            zk.setData(path, "123456".getBytes(), -1);
            fail("should not set data which exceeds the hard byte quota");
        } catch (QuotaExceededException e) {
           //expected
        }
    }

    @Test
    public void testSetQuotaWhenExceedBytesHardQuotaExtend() throws Exception {

        String path = "/c0";
        zk.create(path, "1".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        int bytes = 100;
        StatsTrack st = new StatsTrack();
        st.setByteHardLimit(bytes);
        SetQuotaCommand.createQuota(zk, path, st);
        StringBuilder sb = new StringBuilder(path);
        for (int i = 1; i <= bytes; i++) {
            sb.append("/c" + i);
            if (i == bytes) {
                try {
                    zk.create(sb.toString(), "1".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                    fail("should not set quota when exceeds hard bytes quota");
                } catch (QuotaExceededException e) {
                    //expected
                }
            } else {
                zk.create(sb.toString(), "1".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
        }
    }

    @Test
    public void testSetQuotaWhenSetQuotaLessThanExistBytes() throws Exception {

        String path = "/c0";
        zk.create(path, "123456789".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        int bytes = 5;
        StatsTrack st = new StatsTrack();
        st.setByteHardLimit(bytes);
        SetQuotaCommand.createQuota(zk, path, st);
        try {
            zk.setData(path, "123456".getBytes(), -1);
            fail("should not set quota when exceeds hard bytes quota");
        } catch (QuotaExceededException e) {
            //expected
        }
    }

    @Test
    public void testSetQuotaWhenSetChildDataExceedBytesQuota() throws Exception {

        final String path = "/test/quota";
        zk.create("/test", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/test/quota", "01234".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/test/quota/data", "56789".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        StatsTrack quota = new StatsTrack();
        quota.setByteHardLimit(10);
        SetQuotaCommand.createQuota(zk, path, quota);
        try {
            zk.setData("/test/quota/data", "567891".getBytes(), -1);
            fail("should not set data when exceed hard byte quota");
        } catch (QuotaExceededException e) {
            //expected
        }
    }

    @Test
    public void testSetQuotaWhenCreateNodeExceedBytesQuota() throws Exception {

        final String path = "/test/quota";
        zk.create("/test", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/test/quota", "01234".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        StatsTrack quota = new StatsTrack();
        quota.setByteHardLimit(10);
        SetQuotaCommand.createQuota(zk, path, quota);
        try {
            zk.create("/test/quota/data", "567891".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            fail("should not set data when exceed hard byte quota");
        } catch (QuotaExceededException e) {
            //expected
        }
    }

    @Test
    public void testSetQuotaWhenExceedCountSoftQuota() throws Exception {

        final String path = "/c1";
        zk.create(path, "data".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        int count = 2;
        StatsTrack st = new StatsTrack();
        st.setCount(count);
        SetQuotaCommand.createQuota(zk, path, st);
        zk.create(path + "/c2", "data".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        try {
            zk.create(path + "/c2" + "/c3", "data".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch (QuotaExceededException e) {
            fail("should set quota when exceeds soft count quota");
        }
    }

    @Test
    public void testSetQuotaWhenExceedCountHardQuota() throws Exception {

        final String path = "/c1";
        zk.create(path, "data".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        int count = 2;
        StatsTrack st = new StatsTrack();
        st.setCountHardLimit(count);
        SetQuotaCommand.createQuota(zk, path, st);
        zk.create(path + "/c2", "data".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        try {
            zk.create(path + "/c2" + "/c3", "data".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            fail("should not set quota when exceeds hard count quota");
        } catch (QuotaExceededException e) {
            //expected
        }
    }

    @Test
    public void testSetQuotaWhenExceedCountHardQuotaExtend() throws Exception {

        String path = "/c0";
        zk.create(path, "data".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        int count = 100;
        StatsTrack st = new StatsTrack();
        st.setCountHardLimit(count);
        SetQuotaCommand.createQuota(zk, path, st);
        StringBuilder sb = new StringBuilder(path);
        for (int i = 1; i <= count; i++) {
            sb.append("/c" + i);
            if (i == count) {
                try {
                    zk.create(sb.toString() , "data".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                    fail("should not set quota when exceeds hard count quota");
                } catch (QuotaExceededException e) {
                    //expected
                }
            } else {
                zk.create(sb.toString(), "data".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
        }
    }

    @Test
    public void testSetQuotaWhenSetQuotaLessThanExistCount() throws Exception {

        String path = "/c0";
        zk.create(path, "1".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create(path + "/c1", "1".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create(path + "/c2", "1".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        int count = 2;
        StatsTrack st = new StatsTrack();
        st.setCountHardLimit(count);
        SetQuotaCommand.createQuota(zk, path, st);
        try {
            zk.create(path + "/c3", "1".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            fail("should not set quota when exceeds hard count quota");
        } catch (QuotaExceededException e) {
            //expected
        }
    }

    @Test
    public void testSetQuotaWhenExceedBothBytesAndCountHardQuota() throws Exception {

        final String path = "/c1";
        zk.create(path, "12345".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        StatsTrack st = new StatsTrack();
        st.setByteHardLimit(5L);
        st.setCountHardLimit(1);
        SetQuotaCommand.createQuota(zk, path, st);

        try {
            zk.create(path + "/c2", "1".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            fail("should give priority to CountQuotaExceededException when both meets the count and bytes quota");
        } catch (QuotaExceededException e) {
            //expected
        }
    }

    @Test
    public void testMultiCreateThenSetDataShouldWork() throws Exception {
        final String path = "/a";
        final String subPath = "/a/b";

        zk.create(path, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        final byte[] data13b = "Hello, World!".getBytes(StandardCharsets.UTF_8);

        final StatsTrack st = new StatsTrack();
        st.setByteHardLimit(data13b.length);
        SetQuotaCommand.createQuota(zk, path, st);

        final List<Op> ops = Arrays.asList(
            Op.create(subPath, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
            Op.setData(subPath, data13b, -1));

        zk.multi(ops);
    }

    @Test
    public void testMultiCreateThenSetDataShouldFail() throws Exception {
        final String path = "/a";
        final String subPath = "/a/b";

        zk.create(path, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        final byte[] data13b = "Hello, World!".getBytes(StandardCharsets.UTF_8);

        final StatsTrack st = new StatsTrack();
        st.setByteHardLimit(data13b.length - 1);
        SetQuotaCommand.createQuota(zk, path, st);

        final List<Op> ops = Arrays.asList(
            Op.create(subPath, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
            Op.setData(subPath, data13b, -1));

        try {
            zk.multi(ops);
            fail("should fail transaction when hard quota is exceeded");
        } catch (QuotaExceededException e) {
            //expected
        }

        assertNull(zk.exists(subPath, null));
    }

    @Test
    public void testDeleteBytesQuota() throws Exception {

        final String path = "/c1";
        zk.create(path, "12345".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        StatsTrack st = new StatsTrack();
        st.setByteHardLimit(5L);
        SetQuotaCommand.createQuota(zk, path, st);

        try {
            zk.setData(path, "123456".getBytes(), -1);
            fail("should not set data which exceeds the hard byte quota");
        } catch (QuotaExceededException e) {
            //expected
        }

        //delete the Byte Hard Quota
        st = new StatsTrack();
        st.setByteHardLimit(1);
        DelQuotaCommand.delQuota(zk, path, st);

        zk.setData(path, "123456".getBytes(), -1);
    }

    @Test
    public void testDeleteCountQuota() throws Exception {

        final String path = "/c1";
        zk.create(path, "data".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        int count = 2;
        StatsTrack st = new StatsTrack();
        st.setCountHardLimit(count);
        SetQuotaCommand.createQuota(zk, path, st);
        zk.create(path + "/c2", "data".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        try {
            zk.create(path + "/c2" + "/c3", "data".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            fail("should not set quota when exceeds hard count quota");
        } catch (QuotaExceededException e) {
            //expected
        }

        //delete the Count Hard Quota
        st = new StatsTrack();
        st.setCountHardLimit(1);
        DelQuotaCommand.delQuota(zk, path, st);

        zk.create(path + "/c2" + "/c3", "data".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    }

    @Test
    public void testListQuota() throws Exception {

        final String path = "/c1";
        zk.create(path, "12345".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        StatsTrack st = new StatsTrack();
        long bytes = 5L;
        int count = 10;
        long byteHardLimit = 6L;
        int countHardLimit = 12;
        st.setBytes(bytes);
        st.setCount(count);
        st.setByteHardLimit(byteHardLimit);
        st.setCountHardLimit(countHardLimit);
        SetQuotaCommand.createQuota(zk, path, st);

        List<StatsTrack> statsTracks = ListQuotaCommand.listQuota(zk, path);
        for (int i = 0; i < statsTracks.size(); i++) {
            st = statsTracks.get(i);
            if (i == 0) {
                assertEquals(count, st.getCount());
                assertEquals(countHardLimit, st.getCountHardLimit());
                assertEquals(bytes, st.getBytes());
                assertEquals(byteHardLimit, st.getByteHardLimit());
            } else {
                assertEquals(1, st.getCount());
                assertEquals(-1, st.getCountHardLimit());
                assertEquals(5, st.getBytes());
                assertEquals(-1, st.getByteHardLimit());
            }
        }
        //delete the Byte Hard Quota
        st = new StatsTrack();
        st.setByteHardLimit(1);
        st.setBytes(1);
        st.setCountHardLimit(1);
        st.setCount(1);
        DelQuotaCommand.delQuota(zk, path, st);

        statsTracks = ListQuotaCommand.listQuota(zk, path);
        for (int i = 0; i < statsTracks.size(); i++) {
            st = statsTracks.get(i);
            if (i == 0) {
                assertEquals(-1, st.getCount());
                assertEquals(-1, st.getCountHardLimit());
                assertEquals(-1, st.getBytes());
                assertEquals(-1, st.getByteHardLimit());
            } else {
                assertEquals(1, st.getCount());
                assertEquals(-1, st.getCountHardLimit());
                assertEquals(5, st.getBytes());
                assertEquals(-1, st.getByteHardLimit());
            }
        }
    }
}
