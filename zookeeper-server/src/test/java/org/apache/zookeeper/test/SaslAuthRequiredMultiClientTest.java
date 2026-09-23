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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.security.auth.login.Configuration;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class SaslAuthRequiredMultiClientTest extends SaslAuthDigestTestBase {

    @BeforeAll
    public static void setUpBeforeClass() {
        System.setProperty(SaslTestUtil.requireSASLAuthProperty, "true");
        System.setProperty(SaslTestUtil.authProviderProperty, SaslTestUtil.authProvider);
        System.setProperty(SaslTestUtil.jaasConfig, SaslTestUtil.createJAASConfigFile("jaas.conf", "test"));
    }

    @AfterAll
    public static void tearDownAfterClass() {
        System.clearProperty(SaslTestUtil.requireSASLAuthProperty);
        System.clearProperty(SaslTestUtil.authProviderProperty);
        System.clearProperty(SaslTestUtil.jaasConfig);
    }

    @Test
    public void testClientOpWithInvalidSASLUserAuthAfterSuccessLogin() throws Exception {
        resetJaasConfiguration("jaas.conf", "super", "test");
        try  (ZooKeeper zk = createClient()) {
            zk.create("/foobar", null, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
        } catch (KeeperException e) {
            fail("Client operation should succeed with valid SASL configuration.");
        }

        resetJaasConfiguration("jaas.conf", "super_wrong", "test");
        assertClientAuthFailed();
    }

    @Test
    public void testClientOpWithInvalidSASLPasswordAuthAfterSuccessLogin() throws Exception {
        resetJaasConfiguration("jaas.conf", "super", "test");
        try (ZooKeeper zk = createClient()) {
            zk.create("/foobar", null, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
        } catch (KeeperException e) {
            fail("Client operation should succeed with valid SASL configuration.");
        }

        resetJaasConfiguration("jaas.conf", "super", "test_wrongong");
        assertClientAuthFailed();
    }

    private void assertClientAuthFailed() throws Exception {
        CountDownLatch authFailed = new CountDownLatch(1);
        // A rejected connection may disappear before createClient's JMX check.
        try (ZooKeeper zk = new ZooKeeper(hostPort, CONNECTION_TIMEOUT, event -> {
            if (event.getState() == KeeperState.AuthFailed) {
                authFailed.countDown();
            }
        })) {
            assertTrue(authFailed.await(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS));
            assertEquals(ZooKeeper.States.AUTH_FAILED, zk.getState());
            assertThrows(KeeperException.AuthFailedException.class,
                         () -> zk.create("/bar", null, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT));
        }
    }

    protected static void resetJaasConfiguration(String fileName, String userName, String password) {
        Configuration.setConfiguration(null);
        System.setProperty(SaslTestUtil.jaasConfig, SaslTestUtil.createJAASConfigFile(fileName, userName, password));
    }
}
