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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.proto.CreateResponse;
import org.apache.zookeeper.proto.CreateTTLRequest;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.proto.RequestHeader;
import org.apache.zookeeper.server.TTLManager.TTLNode;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Assert;
import org.junit.Test;

public class CreateTTLTest extends ClientBase {

    private TestableZooKeeper zk;

    private static final Collection<String> disabledTests = Collections.singleton("testDisabled");

    @Override
    public void setUp() throws Exception {
        System.setProperty(
            EphemeralType.EXTENDED_TYPES_ENABLED_PROPERTY,
            disabledTests.contains(getTestName()) ? "false" : "true");
        super.setUpWithServerId(254);
        zk = createClient();
    }

    @Override
    public void tearDown() throws Exception {
        System.clearProperty(EphemeralType.EXTENDED_TYPES_ENABLED_PROPERTY);
        super.tearDown();
        zk.close();
    }

    @Test
    public void testTTLTreeSetCollection() {
        TTLManager ttlManager = new TTLManager();
        Set<TTLNode> ttls = ttlManager.getTTLs();

        //case 1:
        ttls.clear();
        ttls.add(new TTLNode("/a", 1234));
        ttls.add(new TTLNode("/b", 1235));
        ttls.add(new TTLNode("/c", 1234));
        ttls.add(new TTLNode("/d", 1237));
        ttls.add(new TTLNode("/e", 1236));
        //add a duplicated one
        ttls.add(new TTLNode("/b", 1235));
        Assert.assertEquals(5, ttls.size());
        Iterator<TTLNode> it = ttls.iterator();
        int index = 1;
        //the rank is /a,/c,/b,/e,/d
        while (it.hasNext()) {
            TTLNode ttlNode = it.next();
            if (index == 1) {
                Assert.assertEquals("/a", ttlNode.getPath());
            } else if (index == 2) {
                Assert.assertEquals("/c", ttlNode.getPath());
            } else if (index == 3) {
                Assert.assertEquals("/b", ttlNode.getPath());
            } else if (index == 4) {
                Assert.assertEquals("/e", ttlNode.getPath());
            } else if (index == 5) {
                Assert.assertEquals("/d", ttlNode.getPath());
            }
            index++;
        }
        ttls.remove(new TTLNode("/e", 9999));
        Assert.assertEquals(5, ttls.size());
        ttls.remove(new TTLNode("/e", 1236));
        Assert.assertEquals(4, ttls.size());

        //case 2:
        ttls.clear();
        ttls.add(new TTLNode("/f", 8888));
        ttls.add(new TTLNode("/f", 8888));
        Assert.assertEquals(1, ttls.size());
        ttls.add(new TTLNode("/f", 8889));
        Assert.assertEquals(2, ttls.size());

        //case 3:
        ttls.clear();
        ttls.add(new TTLNode("/g", 8888));
        ttls.add(new TTLNode("/h", 8888));
        ttls.add(new TTLNode("/i", 8888));
        Assert.assertEquals(3, ttls.size());
        //finally clean up
        ttls.clear();
    }

    @Test
    public void testTTLRankByMTimeAsc() throws KeeperException, InterruptedException {
        Stat stat = new Stat();
        String path = "/testTTLRankByMTimeAsc";
        int nodeCount = 10;
        for (int i = 1; i <= nodeCount; i++) {
            zk.create(path + i, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_WITH_TTL, stat, i * 100);
            assertEquals(0, stat.getEphemeralOwner());
        }

        final AtomicLong fakeElapsedTime = new AtomicLong(0);
        ContainerManager containerManager = newContainerManager(fakeElapsedTime);

        int targetTouchIndex = 5;
        int timeDiff = 1;
        for (int i = 1; i <= nodeCount; i++) {
            if (i == targetTouchIndex) {
                zk.setData(path + targetTouchIndex, new byte[i], -1);
            }
            fakeElapsedTime.set(i * 100 + timeDiff);
            containerManager.checkContainers();
            if (i == targetTouchIndex) {
                assertNotNull("Ttl node path:" + (path + i) + " should have been deleted", zk.exists(path + i, false));
                continue;
            } else {
                assertNull("Ttl node path:" + (path + i) + " should have been deleted", zk.exists(path + i, false));
                if ((i + 1) <= nodeCount) {
                    assertNotNull("Ttl node path:" + (path + (i + 1)) + " should not have been deleted", zk.exists(path + (i + 1), false));
                }
            }
        }

        fakeElapsedTime.set(targetTouchIndex * 100 + timeDiff);
        containerManager.checkContainers();
        assertNull("Ttl node should have been deleted", zk.exists(path + targetTouchIndex, false));
    }

    @Test
    public void testTTLWhenHasChildren() throws KeeperException, InterruptedException {
        Stat stat = new Stat();
        String path = "/testTTLWhenHasChildren";
        int ttl = 1000;
        zk.create(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_WITH_TTL, stat, ttl);
        String childPath = path + "/child";
        zk.create(childPath, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);

        final AtomicLong fakeElapsedTime = new AtomicLong(0);
        ContainerManager containerManager = newContainerManager(fakeElapsedTime);
        fakeElapsedTime.set(ttl + 1);
        containerManager.checkContainers();
        assertNotNull("Ttl node should not have been deleted", zk.exists(path, false));

        zk.delete(childPath, -1);
        assertNull(zk.exists(childPath, false));
        fakeElapsedTime.set(ttl + 1);
        containerManager.checkContainers();
        assertNull("Ttl node should have been deleted", zk.exists(path, false));

    }

    @Test
    public void testCreate() throws KeeperException, InterruptedException {
        Stat stat = new Stat();
        zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_WITH_TTL, stat, 100);
        assertEquals(0, stat.getEphemeralOwner());

        final AtomicLong fakeElapsed = new AtomicLong(0);
        ContainerManager containerManager = newContainerManager(fakeElapsed);
        containerManager.checkContainers();
        assertNotNull("Ttl node should not have been deleted yet", zk.exists("/foo", false));

        fakeElapsed.set(1000);
        containerManager.checkContainers();
        assertNull("Ttl node should have been deleted", zk.exists("/foo", false));
    }

    @Test
    public void testBadTTLs() throws InterruptedException, KeeperException {
        RequestHeader h = new RequestHeader(1, ZooDefs.OpCode.createTTL);

        String path = "/bad_ttl";
        CreateTTLRequest request = new CreateTTLRequest(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_WITH_TTL.toFlag(), -100);
        CreateResponse response = new CreateResponse();
        ReplyHeader r = zk.submitRequest(h, request, response, null);
        assertEquals("An invalid CreateTTLRequest should throw BadArguments", r.getErr(), Code.BADARGUMENTS.intValue());
        assertNull("An invalid CreateTTLRequest should not result in znode creation", zk.exists(path, false));

        request = new CreateTTLRequest(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_WITH_TTL.toFlag(),
                                       EphemeralType.TTL.maxValue()
                                               + 1);
        response = new CreateResponse();
        r = zk.submitRequest(h, request, response, null);
        assertEquals("An invalid CreateTTLRequest should throw BadArguments", r.getErr(), Code.BADARGUMENTS.intValue());
        assertNull("An invalid CreateTTLRequest should not result in znode creation", zk.exists(path, false));
    }

    @Test
    public void testMaxTTLs() throws InterruptedException, KeeperException {
        RequestHeader h = new RequestHeader(1, ZooDefs.OpCode.createTTL);

        String path = "/bad_ttl";
        CreateTTLRequest request = new CreateTTLRequest(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_WITH_TTL.toFlag(), EphemeralType.TTL.maxValue());
        CreateResponse response = new CreateResponse();
        ReplyHeader r = zk.submitRequest(h, request, response, null);
        assertEquals("EphemeralType.getMaxTTL() should succeed", r.getErr(), Code.OK.intValue());
        assertNotNull("Node should exist", zk.exists(path, false));
    }

    @Test
    public void testCreateSequential() throws KeeperException, InterruptedException {
        Stat stat = new Stat();
        String path = zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL_WITH_TTL, stat, 100);
        assertEquals(0, stat.getEphemeralOwner());

        final AtomicLong fakeElapsed = new AtomicLong(0);
        ContainerManager containerManager = newContainerManager(fakeElapsed);
        containerManager.checkContainers();
        assertNotNull("Ttl node should not have been deleted yet", zk.exists(path, false));

        fakeElapsed.set(1000);
        containerManager.checkContainers();
        assertNull("Ttl node should have been deleted", zk.exists(path, false));
    }

    @Test
    public void testCreateAsync() throws KeeperException, InterruptedException {
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
        assertNotNull("Ttl node should not have been deleted yet", zk.exists("/foo", false));

        fakeElapsed.set(1000);
        containerManager.checkContainers();
        assertNull("Ttl node should have been deleted", zk.exists("/foo", false));
    }

    @Test
    public void testModifying() throws KeeperException, InterruptedException {
        Stat stat = new Stat();
        zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_WITH_TTL, stat, 100);
        assertEquals(0, stat.getEphemeralOwner());

        final AtomicLong fakeElapsed = new AtomicLong(0);
        ContainerManager containerManager = newContainerManager(fakeElapsed);
        containerManager.checkContainers();
        assertNotNull("Ttl node should not have been deleted yet", zk.exists("/foo", false));

        for (int i = 0; i < 10; ++i) {
            fakeElapsed.set(50);
            zk.setData("/foo", new byte[i + 1], -1);
            containerManager.checkContainers();
            assertNotNull("Ttl node should not have been deleted yet", zk.exists("/foo", false));
        }

        fakeElapsed.set(200);
        containerManager.checkContainers();
        assertNull("Ttl node should have been deleted", zk.exists("/foo", false));
    }

    @Test
    public void testMulti() throws KeeperException, InterruptedException {
        Op createTtl = Op.create("/a", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_WITH_TTL, 100);
        Op createTtlSequential = Op.create("/b", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL_WITH_TTL, 200);
        Op createNonTtl = Op.create("/c", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        List<OpResult> results = zk.multi(Arrays.asList(createTtl, createTtlSequential, createNonTtl));
        String sequentialPath = ((OpResult.CreateResult) results.get(1)).getPath();

        final AtomicLong fakeElapsed = new AtomicLong(0);
        ContainerManager containerManager = newContainerManager(fakeElapsed);
        containerManager.checkContainers();
        assertNotNull("node should not have been deleted yet", zk.exists("/a", false));
        assertNotNull("node should not have been deleted yet", zk.exists(sequentialPath, false));
        assertNotNull("node should never be deleted", zk.exists("/c", false));

        fakeElapsed.set(110);
        containerManager.checkContainers();
        assertNull("node should have been deleted", zk.exists("/a", false));
        assertNotNull("node should not have been deleted yet", zk.exists(sequentialPath, false));
        assertNotNull("node should never be deleted", zk.exists("/c", false));

        fakeElapsed.set(210);
        containerManager.checkContainers();
        assertNull("node should have been deleted", zk.exists("/a", false));
        assertNull("node should have been deleted", zk.exists(sequentialPath, false));
        assertNotNull("node should never be deleted", zk.exists("/c", false));
    }

    @Test
    public void testBadUsage() throws KeeperException, InterruptedException {
        for (CreateMode createMode : CreateMode.values()) {
            try {
                zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, createMode, new Stat(), createMode.isTTL() ? 0 : 100);
                fail("should have thrown IllegalArgumentException");
            } catch (IllegalArgumentException dummy) {
                // correct
            }
        }

        for (CreateMode createMode : CreateMode.values()) {
            AsyncCallback.Create2Callback callback = new AsyncCallback.Create2Callback() {
                @Override
                public void processResult(int rc, String path, Object ctx, String name, Stat stat) {
                    // NOP
                }
            };
            try {
                zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, createMode, callback, null, createMode.isTTL() ? 0 : 100);
                fail("should have thrown IllegalArgumentException");
            } catch (IllegalArgumentException dummy) {
                // correct
            }
        }

        try {
            Op op = Op.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_WITH_TTL, 0);
            zk.multi(Collections.singleton(op));
            fail("should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException dummy) {
            // correct
        }
        try {
            Op op = Op.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL_WITH_TTL, 0);
            zk.multi(Collections.singleton(op));
            fail("should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException dummy) {
            // correct
        }
    }

    @Test(expected = KeeperException.UnimplementedException.class)
    public void testDisabled() throws KeeperException, InterruptedException {
        // note, setUp() enables this test based on the test name
        zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_WITH_TTL, new Stat(), 100);
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
