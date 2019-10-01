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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class SaslAuthRequiredFailNoSASLTest extends ClientBase {

    @BeforeClass
    public static void setUpBeforeClass() {
        System.setProperty(ZooKeeperServer.ENFORCE_AUTH_ENABLED, "true");
        System.setProperty(ZooKeeperServer.ENFORCE_AUTH_SCHEME, "sasl");
        System.setProperty(SaslTestUtil.authProviderProperty, SaslTestUtil.authProvider);
        System.setProperty(SaslTestUtil.jaasConfig, SaslTestUtil.createJAASConfigFileOnlyServer(
            "jaas_only_server.conf", "test"));
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        System.clearProperty(ZooKeeperServer.ENFORCE_AUTH_ENABLED);
        System.clearProperty(ZooKeeperServer.ENFORCE_AUTH_SCHEME);
        System.clearProperty(SaslTestUtil.authProviderProperty);
        System.clearProperty(SaslTestUtil.jaasConfig);
    }

    @Test
    public void testClientOpWithoutSASLConfigured() throws Exception {
        ZooKeeper zk = null;
        CountdownWatcher watcher = new CountdownWatcher();
        try {
            zk = createClient(watcher);
            zk.create("/foo", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            fail("Client is not configured with SASL authentication, so zk.create operation should fail.");
        } catch (KeeperException e) {
            assertTrue(e.code() == KeeperException.Code.UNAUTHENTICATEDCLIENT);
            // Verify that "eventually" (within the bound of timeouts)
            // this client closes the connection between itself and the server.
            watcher.waitForDisconnected(SaslTestUtil.CLIENT_DISCONNECT_TIMEOUT);
        } finally {
            if (zk != null) {
                zk.close();
            }
        }
    }

}

