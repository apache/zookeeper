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

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.util.PortForwarder;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ObserverMasterTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class FollowerRequestProcessorTest extends ObserverMasterTestBase {

    private PortForwarder forwarder;

    @Test
    public void testFollowerRequestProcessorSkipsLearnerRequestToNextProcessor() throws Exception {
        setupTestObserverServer("true");

        zk.create("/testFollowerSkipNextAProcessor", "test".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        assertEquals("test", new String(zk.getData("/testFollowerSkipNextAProcessor", null, null)));
        assertEquals(1L, ServerMetrics.getMetrics().SKIP_LEARNER_REQUEST_TO_NEXT_PROCESSOR_COUNT.get());
    }

    @Test
    public void testFollowerRequestProcessorSendsLearnerRequestToNextProcessor() throws Exception {
        setupTestObserverServer("false");

        zk.create("/testFollowerSkipNextAProcessor", "test".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        assertEquals("test", new String(zk.getData("/testFollowerSkipNextAProcessor", null, null)));
        assertEquals(0L, ServerMetrics.getMetrics().SKIP_LEARNER_REQUEST_TO_NEXT_PROCESSOR_COUNT.get());
    }

    private void setupTestObserverServer(String skipLearnerRequestToNextProcessor) throws Exception {
        System.setProperty(FollowerRequestProcessor.SKIP_LEARNER_REQUEST_TO_NEXT_PROCESSOR, skipLearnerRequestToNextProcessor);

        // Setup Ensemble with observer master port so that observer connects with Observer master and not the leader
        final int OM_PROXY_PORT = PortAssignment.unique();
        forwarder = setUp(OM_PROXY_PORT, true);

        q3.start();
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_OBS, CONNECTION_TIMEOUT),
                "waiting for server 3 being up");

        // Connect with observer zookeeper
        zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT_OBS, ClientBase.CONNECTION_TIMEOUT, this);
        waitForOne(zk, States.CONNECTED);

        // Clear all service metrics collected so far
        ServerMetrics.getMetrics().resetAll();
    }

    @AfterEach
    public void cleanup() throws Exception {
        System.setProperty(FollowerRequestProcessor.SKIP_LEARNER_REQUEST_TO_NEXT_PROCESSOR, "false");

        shutdown();
        if (forwarder != null) {
            forwarder.shutdown();
        }
    }
}
