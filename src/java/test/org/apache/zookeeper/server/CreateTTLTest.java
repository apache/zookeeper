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

package org.apache.zookeeper.server;

import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class CreateTTLTest extends ClientBase {
    private ZooKeeper zk;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        zk = createClient();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        zk.close();
    }

    @Test
    public void testCreate()
            throws IOException, KeeperException, InterruptedException {
        Stat stat = new Stat();
        zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_WITH_TTL, stat, 100);
        Assert.assertEquals(0, stat.getEphemeralOwner());

        final AtomicLong fakeElapsed = new AtomicLong(0);
        ContainerManager containerManager = newContainerManager(fakeElapsed);
        containerManager.checkContainers();
        Assert.assertNotNull("Ttl node should not have been deleted yet", zk.exists("/foo", false));

        fakeElapsed.set(1000);
        containerManager.checkContainers();
        Assert.assertNull("Ttl node should have been deleted", zk.exists("/foo", false));
    }

    @Test
    public void testCreateSequential()
            throws IOException, KeeperException, InterruptedException {
        Stat stat = new Stat();
        String path = zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL_WITH_TTL, stat, 100);
        Assert.assertEquals(0, stat.getEphemeralOwner());

        final AtomicLong fakeElapsed = new AtomicLong(0);
        ContainerManager containerManager = newContainerManager(fakeElapsed);
        containerManager.checkContainers();
        Assert.assertNotNull("Ttl node should not have been deleted yet", zk.exists(path, false));

        fakeElapsed.set(1000);
        containerManager.checkContainers();
        Assert.assertNull("Ttl node should have been deleted", zk.exists(path, false));
    }

    @Test
    public void testCreateAsync()
            throws IOException, KeeperException, InterruptedException {
        AsyncCallback.Create2Callback callback = new AsyncCallback.Create2Callback() {
            @Override
            public void processResult(int rc, String path, Object ctx, String name, Stat stat) {
                // NOP
            }
        };
        zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_WITH_TTL, callback, null, 100);

        final AtomicLong fakeElapsed = new AtomicLong(0);
        ContainerManager containerManager = newContainerManager(fakeElapsed);
        containerManager.checkContainers();
        Assert.assertNotNull("Ttl node should not have been deleted yet", zk.exists("/foo", false));

        fakeElapsed.set(1000);
        containerManager.checkContainers();
        Assert.assertNull("Ttl node should have been deleted", zk.exists("/foo", false));
    }

    @Test
    public void testModifying()
            throws IOException, KeeperException, InterruptedException {
        Stat stat = new Stat();
        zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_WITH_TTL, stat, 100);
        Assert.assertEquals(0, stat.getEphemeralOwner());

        final AtomicLong fakeElapsed = new AtomicLong(0);
        ContainerManager containerManager = newContainerManager(fakeElapsed);
        containerManager.checkContainers();
        Assert.assertNotNull("Ttl node should not have been deleted yet", zk.exists("/foo", false));

        for ( int i = 0; i < 10; ++i ) {
            fakeElapsed.set(50);
            zk.setData("/foo", new byte[i + 1], -1);
            containerManager.checkContainers();
            Assert.assertNotNull("Ttl node should not have been deleted yet", zk.exists("/foo", false));
        }

        fakeElapsed.set(200);
        containerManager.checkContainers();
        Assert.assertNull("Ttl node should have been deleted", zk.exists("/foo", false));
    }

    @Test
    public void testMulti()
            throws IOException, KeeperException, InterruptedException {
        Op createTtl = Op.create("/a", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_WITH_TTL, 100);
        Op createTtlSequential = Op.create("/b", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL_WITH_TTL, 200);
        Op createNonTtl = Op.create("/c", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        List<OpResult> results = zk.multi(Arrays.asList(createTtl, createTtlSequential, createNonTtl));
        String sequentialPath = ((OpResult.CreateResult)results.get(1)).getPath();

        final AtomicLong fakeElapsed = new AtomicLong(0);
        ContainerManager containerManager = newContainerManager(fakeElapsed);
        containerManager.checkContainers();
        Assert.assertNotNull("node should not have been deleted yet", zk.exists("/a", false));
        Assert.assertNotNull("node should not have been deleted yet", zk.exists(sequentialPath, false));
        Assert.assertNotNull("node should never be deleted", zk.exists("/c", false));

        fakeElapsed.set(110);
        containerManager.checkContainers();
        Assert.assertNull("node should have been deleted", zk.exists("/a", false));
        Assert.assertNotNull("node should not have been deleted yet", zk.exists(sequentialPath, false));
        Assert.assertNotNull("node should never be deleted", zk.exists("/c", false));

        fakeElapsed.set(210);
        containerManager.checkContainers();
        Assert.assertNull("node should have been deleted", zk.exists("/a", false));
        Assert.assertNull("node should have been deleted", zk.exists(sequentialPath, false));
        Assert.assertNotNull("node should never be deleted", zk.exists("/c", false));
    }

    @Test
    public void testBadUsage() throws KeeperException, InterruptedException {
        for ( CreateMode createMode : CreateMode.values() ) {
            try {
                zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, createMode, new Stat(), createMode.isTTL() ? 0 : 100);
                Assert.fail("should have thrown IllegalArgumentException");
            } catch (IllegalArgumentException dummy) {
                // correct
            }
        }

        for ( CreateMode createMode : CreateMode.values() ) {
            AsyncCallback.Create2Callback callback = new AsyncCallback.Create2Callback() {
                @Override
                public void processResult(int rc, String path, Object ctx, String name, Stat stat) {
                    // NOP
                }
            };
            try {
                zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, createMode, callback, null, createMode.isTTL() ? 0 : 100);
                Assert.fail("should have thrown IllegalArgumentException");
            } catch (IllegalArgumentException dummy) {
                // correct
            }
        }

        try {
            Op op = Op.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_WITH_TTL, 0);
            zk.multi(Collections.singleton(op));
            Assert.fail("should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException dummy) {
            // correct
        }
        try {
            Op op = Op.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL_WITH_TTL, 0);
            zk.multi(Collections.singleton(op));
            Assert.fail("should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException dummy) {
            // correct
        }
    }

    private ContainerManager newContainerManager(final AtomicLong fakeElapsed) {
        return new ContainerManager(serverFactory.getZooKeeperServer()
                .getZKDatabase(), serverFactory.getZooKeeperServer().firstProcessor, 1, 100) {
            @Override
            protected long getElapsed(DataNode node) {
                return fakeElapsed.get();
            }
        };
    }
}
