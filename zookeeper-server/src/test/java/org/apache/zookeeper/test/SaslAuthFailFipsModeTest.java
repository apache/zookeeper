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
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.common.X509Util;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests that DIGEST-MD5 SASL authentication is rejected when FIPS mode is enabled.
 * Does NOT extend SaslAuthDigestTestBase because that base class disables FIPS mode.
 */
public class SaslAuthFailFipsModeTest extends ClientBase {

    @BeforeAll
    public static void setUpBeforeClass() {
        System.setProperty(X509Util.FIPS_MODE_PROPERTY, "true");
        System.setProperty(SaslTestUtil.requireSASLAuthProperty, "true");
        System.setProperty(SaslTestUtil.authProviderProperty, SaslTestUtil.authProvider);
        System.setProperty(SaslTestUtil.jaasConfig, SaslTestUtil.createJAASConfigFile("jaas_fips.conf", "test"));
    }

    @AfterAll
    public static void tearDownAfterClass() {
        System.clearProperty(X509Util.FIPS_MODE_PROPERTY);
        System.clearProperty(SaslTestUtil.requireSASLAuthProperty);
        System.clearProperty(SaslTestUtil.authProviderProperty);
        System.clearProperty(SaslTestUtil.jaasConfig);
    }

    @Test
    public void testDigestMd5RejectedInFipsMode() throws Exception {
        ZooKeeper zk = null;
        CountdownWatcher watcher = new CountdownWatcher();
        try {
            zk = createClient(watcher);
            zk.create("/fips-test", null, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
            fail("DIGEST-MD5 SASL authentication should be rejected when FIPS mode is enabled.");
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.AUTHFAILED, e.code());
            watcher.waitForDisconnected(SaslTestUtil.CLIENT_DISCONNECT_TIMEOUT);
        } finally {
            if (zk != null) {
                zk.close();
            }
        }
    }

}
