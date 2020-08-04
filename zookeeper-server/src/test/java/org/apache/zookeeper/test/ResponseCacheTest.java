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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.fail;
import java.util.List;
import java.util.Map;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResponseCacheTest extends ClientBase {

    protected static final Logger LOG = LoggerFactory.getLogger(ResponseCacheTest.class);

    @BeforeEach
    public void setup() throws Exception {
        System.setProperty(ZooKeeperServer.GET_DATA_RESPONSE_CACHE_SIZE, "32");
        System.setProperty(ZooKeeperServer.GET_CHILDREN_RESPONSE_CACHE_SIZE, "64");
        super.setUp();
    }

    @AfterEach
    public void tearDown() throws Exception {
        System.clearProperty(ZooKeeperServer.GET_DATA_RESPONSE_CACHE_SIZE);
        System.clearProperty(ZooKeeperServer.GET_CHILDREN_RESPONSE_CACHE_SIZE);
    }

    @Test
    public void testResponseCache() throws Exception {
        ZooKeeper zk = createClient();

        try {
            performCacheTest(zk, "/cache", true);
            performCacheTest(zk, "/nocache", false);
        } finally {
            zk.close();
        }
    }

    private void checkCacheStatus(long expectedHits, long expectedMisses,
                                  String cacheHitMetricsName, String cacheMissMetricsName) {

        Map<String, Object> metrics = MetricsUtils.currentServerMetrics();
        assertEquals(expectedHits, metrics.get(cacheHitMetricsName));
        assertEquals(expectedMisses, metrics.get(cacheMissMetricsName));
    }

    public void performCacheTest(ZooKeeper zk, String path, boolean useCache) throws Exception {
        ServerMetrics.getMetrics().resetAll();
        Stat writeStat = new Stat();
        Stat readStat = new Stat();
        byte[] readData = null;
        int reads = 10;
        long expectedHits = 0;
        long expectedMisses = 0;

        ZooKeeperServer zks = serverFactory.getZooKeeperServer();
        zks.setResponseCachingEnabled(useCache);
        LOG.info("caching: {}", useCache);

        if (useCache) {
            assertEquals(zks.getReadResponseCache().getCacheSize(), 32);
            assertEquals(zks.getGetChildrenResponseCache().getCacheSize(), 64);
        }

        byte[] writeData = "test1".getBytes();
        zk.create(path, writeData, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, writeStat);
        for (int i = 0; i < reads; ++i) {
            readData = zk.getData(path, false, readStat);
            assertArrayEquals(writeData, readData);
            assertEquals(writeStat, readStat);
        }
        if (useCache) {
            expectedMisses += 1;
            expectedHits += reads - 1;
        }
        checkCacheStatus(expectedHits, expectedMisses, "response_packet_cache_hits",
                "response_packet_cache_misses");

        writeData = "test2".getBytes();
        writeStat = zk.setData(path, writeData, -1);
        for (int i = 0; i < 10; ++i) {
            readData = zk.getData(path, false, readStat);
            assertArrayEquals(writeData, readData);
            assertEquals(writeStat, readStat);
        }
        if (useCache) {
            expectedMisses += 1;
            expectedHits += reads - 1;
        }
        checkCacheStatus(expectedHits, expectedMisses, "response_packet_cache_hits",
                "response_packet_cache_misses");

        // Create a child beneath the tested node. This won't change the data of
        // the tested node, but will change it's pzxid. The next read of the tested
        // node should miss in the cache. The data should still match what was written
        // before, but the stat information should not.
        zk.create(path + "/child", "child".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, null);
        readData = zk.getData(path, false, readStat);
        if (useCache) {
            expectedMisses++;
        }
        assertArrayEquals(writeData, readData);
        assertNotSame(writeStat, readStat);
        checkCacheStatus(expectedHits, expectedMisses, "response_packet_cache_hits",
                "response_packet_cache_misses");

        ServerMetrics.getMetrics().resetAll();
        expectedHits = 0;
        expectedMisses = 0;
        createPath(path + "/a", zk);
        createPath(path + "/a/b", zk);
        createPath(path + "/a/c", zk);
        createPath(path + "/a/b/d", zk);
        createPath(path + "/a/b/e", zk);
        createPath(path + "/a/b/e/f", zk);
        createPath(path + "/a/b/e/g", zk);
        createPath(path + "/a/b/e/h", zk);

        checkPath(path + "/a", zk, 2);
        checkPath(path + "/a/b", zk, 2);
        checkPath(path + "/a/c", zk, 0);
        checkPath(path + "/a/b/d", zk, 0);
        checkPath(path + "/a/b/e", zk, 3);
        checkPath(path + "/a/b/e/h", zk, 0);

        if (useCache) {
            expectedMisses += 6;
        }

        checkCacheStatus(expectedHits, expectedMisses, "response_packet_get_children_cache_hits",
                "response_packet_get_children_cache_misses");

        checkPath(path + "/a", zk, 2);
        checkPath(path + "/a/b", zk, 2);
        checkPath(path + "/a/c", zk, 0);

        if (useCache) {
            expectedHits += 3;
        }

        checkCacheStatus(expectedHits, expectedMisses, "response_packet_get_children_cache_hits",
                "response_packet_get_children_cache_misses");
    }

    private void createPath(String path, ZooKeeper zk) throws Exception {
        zk.create(path, "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, null);
    }

    private void checkPath(String path, ZooKeeper zk, int expectedNumberOfChildren) throws Exception {
        Stat stat = zk.exists(path, false);

        List<String> c1 = zk.getChildren(path, false);
        List<String> c2 = zk.getChildren(path, false, stat);

        if (!c1.equals(c2)) {
            fail("children lists from getChildren()/getChildren2() do not match");
        }

        assertEquals(c1.size(), expectedNumberOfChildren);

        if (!stat.equals(stat)) {
            fail("stats from exists()/getChildren2() do not match");
        }
    }

}
