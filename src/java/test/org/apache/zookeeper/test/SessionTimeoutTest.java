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
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.test;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.DisconnectableZooKeeper;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class SessionTimeoutTest extends ClientBase {
    protected static final Logger LOG = LoggerFactory.getLogger(SessionTimeoutTest.class);

    private TestableZooKeeper zk;
    File tmpDir;

    @Before
    public void setUp() throws Exception {
        tickTime = 100;
        super.setUp();
        zk = createClient();
    }

    /**
     * Make sure ephemerals get cleaned up when a session times out.
     */
    @Test
    public void testSessionTimeout() throws Exception {
        zk.create("/stest", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL);

        // stop pinging
        zk.getSendThread().interrupt();

        zk.getEventThread().join(10000);
        Assert.assertFalse("EventThread is still running", zk.getEventThread().isAlive());

        zk = createClient();
        Stat stest = zk.exists("/stest", null);
        assertNull("Ephemeral node /stest should have been removed", stest);
    }

    /**
     * Make sure ephemerals get cleaned up when session disconnects.
     */
    @Test
    public void testSessionDisconnect() throws KeeperException, InterruptedException, IOException {
        zk.create("/sdisconnect", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL);
        assertNotNull("Ephemeral node has not been created", zk.exists("/sdisconnect", null));

        zk.close();

        zk = createClient();
        assertNull("Ephemeral node shouldn't exist after client disconnect", zk.exists("/sdisconnect", null));
    }

    /**
     * Make sure ephemerals are kept when session restores.
     */
    @Test
    public void testSessionRestore() throws KeeperException, InterruptedException, IOException {
        zk.create("/srestore", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL);
        assertNotNull("Ephemeral node has not been created", zk.exists("/srestore", null));

        zk.disconnect();
        zk.close();

        zk = createClient();
        assertNotNull("Ephemeral node should be present when session is restored", zk.exists("/srestore", null));
    }

    /**
     * Make sure ephemerals are kept when server restarts.
     */
    @Test
    public void testSessionSurviveServerRestart() throws Exception {
        zk.create("/sdeath", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL);
        assertNotNull("Ephemeral node has not been created", zk.exists("/sdeath", null));

        zk.disconnect();
        stopServer();
        startServer();
        zk = createClient();

        assertNotNull("Ephemeral node should be present when server restarted", zk.exists("/sdeath", null));
    }
}
