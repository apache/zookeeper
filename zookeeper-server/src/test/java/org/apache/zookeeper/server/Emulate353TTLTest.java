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

package org.apache.zookeeper.server;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class Emulate353TTLTest extends ClientBase {

    private TestableZooKeeper zk;

    @BeforeEach
    @Override
    public void setUp() throws Exception {
        System.setProperty(EphemeralType.EXTENDED_TYPES_ENABLED_PROPERTY, "true");
        System.setProperty(EphemeralType.TTL_3_5_3_EMULATION_PROPERTY, "true");
        super.setUp();
        zk = createClient();
    }

    @AfterEach
    @Override
    public void tearDown() throws Exception {
        System.clearProperty(EphemeralType.EXTENDED_TYPES_ENABLED_PROPERTY);
        System.clearProperty(EphemeralType.TTL_3_5_3_EMULATION_PROPERTY);
        super.tearDown();
        zk.close();
    }

    @Test
    public void testCreate() throws KeeperException, InterruptedException {
        Stat stat = new Stat();
        zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_WITH_TTL, stat, 100);
        assertEquals(0, stat.getEphemeralOwner());

        final AtomicLong fakeElapsed = new AtomicLong(0);
        ContainerManager containerManager = newContainerManager(fakeElapsed);
        containerManager.checkContainers();
        assertNotNull(zk.exists("/foo", false), "Ttl node should not have been deleted yet");

        fakeElapsed.set(1000);
        containerManager.checkContainers();
        assertNull(zk.exists("/foo", false), "Ttl node should have been deleted");
    }

    @Test
    public void test353TTL() throws KeeperException, InterruptedException {
        DataTree dataTree = serverFactory.zkServer.getZKDatabase().dataTree;
        long ephemeralOwner = EphemeralTypeEmulate353.ttlToEphemeralOwner(100);
        dataTree.createNode("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, ephemeralOwner, dataTree.getNode("/").stat.getCversion()
                                                                                                      + 1, 1, 1);

        final AtomicLong fakeElapsed = new AtomicLong(0);
        ContainerManager containerManager = newContainerManager(fakeElapsed);
        containerManager.checkContainers();
        assertNotNull(zk.exists("/foo", false), "Ttl node should not have been deleted yet");

        fakeElapsed.set(1000);
        containerManager.checkContainers();
        assertNull(zk.exists("/foo", false), "Ttl node should have been deleted");
    }

    @Test
    public void testEphemeralOwner_emulationTTL() {
        assertThat(EphemeralType.get(-1), equalTo(EphemeralType.TTL));
    }

    @Test
    public void testEphemeralOwner_emulationContainer() {
        assertThat(EphemeralType.get(EphemeralType.CONTAINER_EPHEMERAL_OWNER), equalTo(EphemeralType.CONTAINER));
    }

    private ContainerManager newContainerManager(final AtomicLong fakeElapsed) {
        return new ContainerManager(serverFactory.getZooKeeperServer().getZKDatabase(), serverFactory.getZooKeeperServer().firstProcessor, 1, 100) {
            @Override
            protected long getElapsed(DataNode node) {
                return fakeElapsed.get();
            }
        };
    }

}
