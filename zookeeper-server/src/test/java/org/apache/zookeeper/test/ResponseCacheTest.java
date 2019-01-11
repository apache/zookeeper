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

import java.util.Map;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.ServerMetrics;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResponseCacheTest extends ClientBase {
    protected static final Logger LOG =
            LoggerFactory.getLogger(ResponseCacheTest.class);

    @Test
    public void testResponseCache() throws Exception {
        ZooKeeper zk = createClient();

        try {
            performCacheTest(zk, "/cache", true);
            performCacheTest(zk, "/nocache", false);
        }
        finally {
            zk.close();
        }
    }

    private void checkCacheStatus(long expectedHits, long expectedMisses) {
        Map<String, Object> metrics = ServerMetrics.getAllValues();
        Assert.assertEquals(expectedHits, metrics.get("response_packet_cache_hits"));
        Assert.assertEquals(expectedMisses, metrics.get("response_packet_cache_misses"));
    }

    public void performCacheTest(ZooKeeper zk, String path, boolean useCache) throws Exception {
        ServerMetrics.resetAll();
        Stat writeStat = new Stat();
        Stat readStat = new Stat();
        byte[] readData = null;
        int reads = 10;
        long expectedHits = 0;
        long expectedMisses = 0;

        getServer(serverFactory).setResponseCachingEnabled(useCache);
        LOG.info("caching: {}", useCache);

        byte[] writeData = "test1".getBytes();
        zk.create(path, writeData, ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT, writeStat);
        for (int i = 0; i < reads; ++i) {
            readData = zk.getData(path, false, readStat);
            Assert.assertArrayEquals(writeData, readData);
            Assert.assertEquals(writeStat, readStat);
        }
        if (useCache) {
            expectedMisses += 1;
            expectedHits += reads - 1;
        }
        checkCacheStatus(expectedHits, expectedMisses);

        writeData = "test2".getBytes();
        writeStat = zk.setData(path, writeData, -1);
        for (int i = 0; i < 10; ++i) {
            readData = zk.getData(path, false, readStat);
            Assert.assertArrayEquals(writeData, readData);
            Assert.assertEquals(writeStat, readStat);
        }
        if (useCache) {
            expectedMisses += 1;
            expectedHits += reads - 1;
        }
        checkCacheStatus(expectedHits, expectedMisses);

        // Create a child beneath the tested node. This won't change the data of
        // the tested node, but will change it's pzxid. The next read of the tested
        // node should miss in the cache. The data should still match what was written
        // before, but the stat information should not.
        zk.create(path + "/child", "child".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT, null);
        readData = zk.getData(path, false, readStat);
        if (useCache) {
            expectedMisses++;
        }
        Assert.assertArrayEquals(writeData, readData);
        Assert.assertNotSame(writeStat, readStat);
        checkCacheStatus(expectedHits, expectedMisses);
    }
}
