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

import static org.hamcrest.core.Is.is;
import static org.hamcrest.number.OrderingComparison.greaterThanOrEqualTo;
import java.util.HashMap;
import java.util.Map;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.test.ClientBase;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class LearnerMetricsTest extends QuorumPeerTestBase {

    private static final int TIMEOUT_SECONDS = 30;
    private static final int SERVER_COUNT = 4; // 1 observer, 3 participants
    private final QuorumPeerTestBase.MainThread[] mt = new QuorumPeerTestBase.MainThread[SERVER_COUNT];
    private ZooKeeper zk_client;
    private static boolean bakAsyncSending;

    @BeforeAll
    public static void saveAsyncSendingFlag() {
        bakAsyncSending = Learner.getAsyncSending();
    }

    @AfterAll
    public static void resetAsyncSendingFlag() {
        Learner.setAsyncSending(bakAsyncSending);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testLearnerMetricsTest(boolean asyncSending) throws Exception {
        Learner.setAsyncSending(asyncSending);
        ServerMetrics.getMetrics().resetAll();
        ClientBase.setupTestEnv();

        final String path = "/zk-testLeanerMetrics";
        final byte[] data = new byte[512];
        final int[] clientPorts = new int[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        int observer = 0;
        clientPorts[observer] = PortAssignment.unique();
        sb.append("server." + observer + "=127.0.0.1:" + PortAssignment.unique() + ":" + PortAssignment.unique() + ":observer\n");
        for (int i = 1; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            sb.append("server." + i + "=127.0.0.1:" + PortAssignment.unique() + ":" + PortAssignment.unique() + "\n");
        }

        // start the three participants
        String quorumCfgSection = sb.toString();
        for (int i = 1; i < SERVER_COUNT; i++) {
            mt[i] = new QuorumPeerTestBase.MainThread(i, clientPorts[i], quorumCfgSection);
            mt[i].start();
        }

        // start the observer
        Map<String, String> observerConfig = new HashMap<>();
        observerConfig.put("peerType", "observer");
        mt[observer] = new QuorumPeerTestBase.MainThread(observer, clientPorts[observer], quorumCfgSection, observerConfig);
        mt[observer].start();

        // connect to the observer node and wait for CONNECTED state
        // (this way we make sure to wait until the leader election finished and the observer node joined as well)
        zk_client = new ZooKeeper("127.0.0.1:" + clientPorts[observer], ClientBase.CONNECTION_TIMEOUT, this);
        waitForOne(zk_client, ZooKeeper.States.CONNECTED);

        // creating a node
        zk_client.create(path, data, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);


        // there are two proposals by now, one for the global client session creation, one for the create request

        // there are two followers, each received two PROPOSALs
        waitForMetric("learner_proposal_received_count", is(4L));
        waitForMetric("cnt_proposal_latency", is(4L));
        waitForMetric("min_proposal_latency", greaterThanOrEqualTo(0L));

        // the two ACKs are processed by the leader and by each of the two followers
        waitForMetric("cnt_proposal_ack_creation_latency", is(6L));
        waitForMetric("min_proposal_ack_creation_latency", greaterThanOrEqualTo(0L));

        // two COMMITs are received by each of the two followers, and two INFORMs are received by the single observer
        // (the INFORM message is also counted into the "commit_received" metrics)
        waitForMetric("learner_commit_received_count", is(6L));
        waitForMetric("cnt_commit_propagation_latency", is(6L));
        waitForMetric("min_commit_propagation_latency", greaterThanOrEqualTo(0L));
    }

    private void waitForMetric(final String metricKey, final Matcher<Long> matcher) throws InterruptedException {
        final String errorMessage = String.format("unable to match on metric: %s", metricKey);
        waitFor(errorMessage, () -> {
            long actual = (long) MetricsUtils.currentServerMetrics().get(metricKey);
            if (!matcher.matches(actual)) {
                LOG.info("match failed on {}, actual value: {}", metricKey, actual);
                return false;
            }
            return true;
        }, TIMEOUT_SECONDS);
    }

    @AfterEach
    public void tearDown() throws Exception {
        zk_client.close();
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].shutdown();
        }
    }

}
