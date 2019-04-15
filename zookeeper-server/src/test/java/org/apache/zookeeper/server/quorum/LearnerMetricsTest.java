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
 *uuuuu
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "/RequuuAS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.quorum;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.number.OrderingComparison.greaterThan;

public class LearnerMetricsTest extends QuorumPeerTestBase {

    @Test
    public void testLearnerMetricsTest() throws Exception {
        ServerMetrics.getMetrics().resetAll();
        ClientBase.setupTestEnv();

        final int SERVER_COUNT = 6; // 5 participants, 1 observer
        final String path = "/zk-testLeanerMetrics";
        final byte[] data = new byte[512];
        final int clientPorts[] = new int[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        int observer = 0 ;
        clientPorts[observer] = PortAssignment.unique();
        sb.append("server."+observer+"=127.0.0.1:"+PortAssignment.unique()+":"+PortAssignment.unique()+":observer\n");
        for(int i = 1; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            sb.append("server."+i+"=127.0.0.1:"+PortAssignment.unique()+":"+PortAssignment.unique()+"\n");
        }

        // start the participants
        String quorumCfgSection = sb.toString();
        QuorumPeerTestBase.MainThread mt[] = new QuorumPeerTestBase.MainThread[SERVER_COUNT];
        for(int i = 1; i < SERVER_COUNT; i++) {
            mt[i] = new QuorumPeerTestBase.MainThread(i, clientPorts[i], quorumCfgSection);
            mt[i].start();
        }

        // start the observer
        Map<String, String> observerConfig = new HashMap<>();
        observerConfig.put("peerType", "observer");
        mt[observer] = new QuorumPeerTestBase.MainThread(observer, clientPorts[observer], quorumCfgSection, observerConfig);
        mt[observer].start();

        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + clientPorts[1], ClientBase.CONNECTION_TIMEOUT, this);

        waitForOne(zk, ZooKeeper.States.CONNECTED);

        // send one create request
        zk.create(path, data, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        Thread.sleep(200);

        Map<String, Object> values = MetricsUtils.currentServerMetrics();
        // there are 4 followers, each received two proposals, one for leader election, one for the create request
        Assert.assertEquals(8L, values.get("learner_proposal_received_count"));
        Assert.assertEquals(8L, values.get("cnt_proposal_latency"));
        Assert.assertThat((long)values.get("min_proposal_latency"), greaterThan(0L));
        Assert.assertEquals(8L, values.get("cnt_proposal_ack_creation_latency"));
        Assert.assertThat((long)values.get("min_proposal_ack_creation_latency"), greaterThan(0L));

        // there are five learners, each received two commits, one for leader election, one for the create request
        Assert.assertEquals(10L, values.get("learner_commit_received_count"));
        Assert.assertEquals(10L, values.get("cnt_commit_propagation_latency"));
        Assert.assertThat((long)values.get("min_commit_propagation_latency"), greaterThan(0L));
    }
}
