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

package org.apache.zookeeper.server.persistence;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.number.OrderingComparison.greaterThan;
import static org.hamcrest.number.OrderingComparison.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.QuorumUtil;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileTxnSnapLogMetricsTest extends ZKTestCase {

    private static final Logger LOG = LoggerFactory.getLogger(FileTxnSnapLogMetricsTest.class);

    CountDownLatch allCreatedLatch;

    private class MockWatcher implements Watcher {

        @Override
        public void process(WatchedEvent e) {
            LOG.info("all nodes created");
            allCreatedLatch.countDown();
        }

    }

    @Test
    public void testFileTxnSnapLogMetrics() throws Exception {
        SyncRequestProcessor.setSnapCount(100);

        QuorumUtil util = new QuorumUtil(1);
        util.startAll();

        allCreatedLatch = new CountDownLatch(1);

        byte[] data = new byte[500];
        // make sure a snapshot is taken and some txns are not in a snapshot
        ZooKeeper zk = ClientBase.createZKClient(util.getConnString());
        for (int i = 0; i < 150; i++) {
            zk.create("/path" + i, data, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }

        if (null == zk.exists("/path149", new MockWatcher())) {
            allCreatedLatch.await();
        }

        ServerMetrics.getMetrics().resetAll();
        int leader = util.getLeaderServer();
        // restart a server so it will read the snapshot and the txn logs
        util.shutdown(leader);
        util.start(leader);

        Map<String, Object> values = MetricsUtils.currentServerMetrics();
        LOG.info("txn loaded during start up {}", values.get("max_startup_txns_loaded"));
        assertEquals(1L, values.get("cnt_startup_txns_loaded"));
        assertThat((long) values.get("max_startup_txns_loaded"), greaterThan(0L));
        assertEquals(1L, values.get("cnt_startup_txns_load_time"));
        assertThat((long) values.get("max_startup_txns_load_time"), greaterThanOrEqualTo(0L));
        assertEquals(1L, values.get("cnt_startup_snap_load_time"));
        assertThat((long) values.get("max_startup_snap_load_time"), greaterThan(0L));

        util.shutdownAll();
    }

}
