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

package org.apache.zookeeper.server.quorum;

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
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.QuorumUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class LeaderMetricsTest extends ZKTestCase {

    CountDownLatch createdLatch;
    int oldLoggingFeq;

    private class MyWatcher implements Watcher {

        @Override
        public void process(WatchedEvent e) {
            createdLatch.countDown();
        }

    }

    @BeforeEach
    public void setup() {
        oldLoggingFeq = Leader.getAckLoggingFrequency();
    }

    @AfterEach
    public void teardown() {
        Leader.setAckLoggingFrequency(oldLoggingFeq);
    }

    @Test
    public void testLeaderMetrics() throws Exception {
        // set the logging frequency to one so we log the ack latency for every ack
        Leader.setAckLoggingFrequency(1);

        ServerMetrics.getMetrics().resetAll();

        QuorumUtil util = new QuorumUtil(1); //creating a quorum of 3 servers
        util.startAll();

        ZooKeeper zk = ClientBase.createZKClient(util.getConnString());
        createdLatch = new CountDownLatch(1);
        zk.exists("/test", new MyWatcher());
        zk.create("/test", new byte[2], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        createdLatch.await();

        Map<String, Object> values = MetricsUtils.currentServerMetrics();

        assertEquals(2L, values.get("proposal_count"));
        // Quorum ack latency is per txn
        assertEquals(2L, values.get("cnt_quorum_ack_latency"));
        assertThat((long) values.get("min_quorum_ack_latency"), greaterThan(0L));

        int numberOfAckServers = 0;
        // ack latency is per server
        for (int sid = 1; sid <= 3; sid++) {
            String metricName = "min_" + sid + "_ack_latency";
            if (values.get(metricName) != null) {
                numberOfAckServers++;
                assertThat((long) values.get("min_" + sid + "_ack_latency"), greaterThanOrEqualTo(0L));
            }
        }

        // at least two servers should have send ACKs
        assertThat(numberOfAckServers, greaterThanOrEqualTo(2));

        zk.close();
        util.shutdownAll();
    }

}
