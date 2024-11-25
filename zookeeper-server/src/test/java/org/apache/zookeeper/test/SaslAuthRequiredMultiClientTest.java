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
import static org.junit.jupiter.api.Assertions.fail;
import javax.security.auth.login.Configuration;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
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
        try  (ZooKeeper wrongUserZk = createClient()) {
            wrongUserZk.create("/bar", null, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
            fail("Client with wrong SASL config should not pass SASL authentication.");
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.AUTHFAILED, e.code());
        }
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
        try (ZooKeeper wrongPasswordZk = createClient()) {
            wrongPasswordZk.create("/bar", null, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
            fail("Client with wrong SASL config should not pass SASL authentication.");
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.AUTHFAILED, e.code());
        }
    }

    protected static void resetJaasConfiguration(String fileName, String userName, String password) {
        Configuration.setConfiguration(null);
        System.setProperty(SaslTestUtil.jaasConfig, SaslTestUtil.createJAASConfigFile(fileName, userName, password));
    }
}
