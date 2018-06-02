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

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

public class Emulate353TTLTest extends ClientBase {
    private TestableZooKeeper zk;

    @Override
    public void setUp() throws Exception {
        System.setProperty(EphemeralType.EXTENDED_TYPES_ENABLED_PROPERTY, "true");
        System.setProperty(EphemeralType.TTL_3_5_3_EMULATION_PROPERTY, "true");
        super.setUp();
        zk = createClient();
    }

    @Override
    public void tearDown() throws Exception {
        System.clearProperty(EphemeralType.EXTENDED_TYPES_ENABLED_PROPERTY);
        System.clearProperty(EphemeralType.TTL_3_5_3_EMULATION_PROPERTY);
        super.tearDown();
        zk.close();
    }

    @Test
    public void testCreate()
            throws KeeperException, InterruptedException {
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
    public void test353TTL()
            throws KeeperException, InterruptedException {
        DataTree dataTree = serverFactory.zkServer.getZKDatabase().dataTree;
        long ephemeralOwner = EphemeralTypeEmulate353.ttlToEphemeralOwner(100);
        dataTree.createNode("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, ephemeralOwner,
                dataTree.getNode("/").stat.getCversion()+1, 1, 1);

        final AtomicLong fakeElapsed = new AtomicLong(0);
        ContainerManager containerManager = newContainerManager(fakeElapsed);
        containerManager.checkContainers();
        Assert.assertNotNull("Ttl node should not have been deleted yet", zk.exists("/foo", false));

        fakeElapsed.set(1000);
        containerManager.checkContainers();
        Assert.assertNull("Ttl node should have been deleted", zk.exists("/foo", false));
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
