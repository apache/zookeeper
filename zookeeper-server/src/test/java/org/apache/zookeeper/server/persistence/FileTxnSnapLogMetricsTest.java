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
import java.io.File;
import java.util.Map;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileTxnSnapLogMetricsTest extends ZKTestCase {

    private static final Logger LOG = LoggerFactory.getLogger(FileTxnSnapLogMetricsTest.class);

    @TempDir
    File logDir;

    @TempDir
    File snapDir;

    private ServerCnxnFactory startServer() throws Exception {
        ZooKeeperServer zkServer = new ZooKeeperServer(snapDir, logDir, 3000);
        ServerCnxnFactory cnxnFactory = ServerCnxnFactory.createFactory(0, -1);
        cnxnFactory.startup(zkServer);
        return cnxnFactory;
    }

    @AfterEach
    public void cleanup() throws Exception {
        SyncRequestProcessor.setSnapCount(ZooKeeperServer.getSnapCount());
    }

    @Test
    public void testFileTxnSnapLogMetrics() throws Exception {
        SyncRequestProcessor.setSnapCount(100);

        ServerCnxnFactory cnxnFactory = startServer();
        String connectString = "127.0.0.1:" + cnxnFactory.getLocalPort();

        // Snapshot in load data.
        assertEquals(1L, MetricsUtils.currentServerMetrics().get("cnt_snapshottime"));

        byte[] data = new byte[500];
        ZooKeeper zk = ClientBase.createZKClient(connectString);
        for (int i = 0; i < 150; i++) {
            zk.create("/path" + i, data, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }

        // It is possible that above writes will trigger more than one snapshot due to randomization.
        waitForMetric("cnt_snapshottime", greaterThanOrEqualTo(2L), 10);

        // Pauses snapshot and logs more txns.
        cnxnFactory.getZooKeeperServer().getTxnLogFactory().snapLog.close();
        zk.create("/" + 1000, data, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/" + 1001, data, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        // Restart server to count startup metrics.
        cnxnFactory.shutdown();
        ServerMetrics.getMetrics().resetAll();
        cnxnFactory = startServer();

        Map<String, Object> values = MetricsUtils.currentServerMetrics();
        LOG.info("txn loaded during start up {}", values.get("max_startup_txns_loaded"));
        assertEquals(1L, values.get("cnt_startup_txns_loaded"));
        assertThat((long) values.get("max_startup_txns_loaded"), greaterThan(0L));
        assertEquals(1L, values.get("cnt_startup_txns_load_time"));
        assertThat((long) values.get("max_startup_txns_load_time"), greaterThanOrEqualTo(0L));
        assertEquals(1L, values.get("cnt_startup_snap_load_time"));
        assertThat((long) values.get("max_startup_snap_load_time"), greaterThan(0L));

        cnxnFactory.shutdown();
    }

}
