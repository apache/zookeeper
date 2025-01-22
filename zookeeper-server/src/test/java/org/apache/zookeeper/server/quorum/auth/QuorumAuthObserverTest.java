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

package org.apache.zookeeper.server.quorum.auth;

import java.util.HashMap;
import java.util.Map;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * ZOOKEEPER-4886：myid small observer can't join quorum, so need use getView()
 * SASL Quorum:
 * server.11=localhost:11223:11224:participant
 * server.21=localhost:11226:11227:participant
 * server.1=localhost:11229:11230:observer
 *
 * The server.1 can't join quorum.
 */
public class QuorumAuthObserverTest extends QuorumAuthTestBase {

    static {
        String jaasEntries = "QuorumServer {\n"
                             + "       org.apache.zookeeper.server.auth.DigestLoginModule required\n"
                             + "       user_test=\"mypassword\";\n"
                             + "};\n"
                             + "QuorumLearner {\n"
                             + "       org.apache.zookeeper.server.auth.DigestLoginModule required\n"
                             + "       username=\"test\"\n"
                             + "       password=\"mypassword\";\n"
                             + "};\n";
        setupJaasConfig(jaasEntries);
    }

    @AfterEach
    @Override
    public void tearDown() throws Exception {
        shutdownAll();
        super.tearDown();
    }

    @AfterAll
    public static void cleanup() {
        cleanupJaasConfig();
    }

    /**
     * Test to ensure observer with small myid can join SASL quorum.
     * peer0 myid：11 participant
     * peer1 myid：21 participant
     * peer2 myid：1 observer
     */
    @Test
    @Timeout(value = 30)
    public void testSmallObserverJoinSASLQuorum() throws Exception {
        Map<String, String> authConfigs = new HashMap<>();
        authConfigs.put(QuorumAuth.QUORUM_SASL_AUTH_ENABLED, "true");
        authConfigs.put(QuorumAuth.QUORUM_SERVER_SASL_AUTH_REQUIRED, "true");
        authConfigs.put(QuorumAuth.QUORUM_LEARNER_SASL_AUTH_REQUIRED, "true");

        // create quorum
        StringBuilder connectStringBuilder = new StringBuilder();
        int[] myidList = {11, 21, 1};
        String[] roleList = {"participant", "participant", "observer"};
        int[] clientPorts = startQuorum(3, connectStringBuilder, authConfigs, 3, false, myidList, roleList);

        // small observer can't join quorum
        String connectStr = "127.0.0.1:" + clientPorts[2];
        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(connectStr, ClientBase.CONNECTION_TIMEOUT, watcher);
        watcher.waitForConnected(ClientBase.CONNECTION_TIMEOUT);

        zk.create("/foo", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.close();
    }
}
