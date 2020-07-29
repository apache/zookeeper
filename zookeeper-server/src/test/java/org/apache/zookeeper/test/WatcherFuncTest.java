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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class WatcherFuncTest extends ClientBase {

    private static class SimpleWatcher implements Watcher {

        private LinkedBlockingQueue<WatchedEvent> events = new LinkedBlockingQueue<WatchedEvent>();
        private CountDownLatch latch;

        public SimpleWatcher(CountDownLatch latch) {
            this.latch = latch;
        }

        public void process(WatchedEvent event) {
            if (event.getState() == KeeperState.SyncConnected) {
                if (latch != null) {
                    latch.countDown();
                }
            }

            if (event.getType() == EventType.None) {
                return;
            }
            try {
                events.put(event);
            } catch (InterruptedException e) {
                assertTrue(false, "interruption unexpected");
            }
        }
        public void verify(List<EventType> expected) throws InterruptedException {
            WatchedEvent event;
            int count = 0;
            while (count < expected.size() && (event = events.poll(30, TimeUnit.SECONDS)) != null) {
                assertEquals(expected.get(count), event.getType());
                count++;
            }
            assertEquals(expected.size(), count);
            events.clear();
        }

    }

    private SimpleWatcher client_dwatch;
    private volatile CountDownLatch client_latch;
    private ZooKeeper client;
    private SimpleWatcher lsnr_dwatch;
    private volatile CountDownLatch lsnr_latch;
    private ZooKeeper lsnr;

    private List<EventType> expected;

    @BeforeEach
    @Override
    public void setUp() throws Exception {
        super.setUp();

        client_latch = new CountDownLatch(1);
        client_dwatch = new SimpleWatcher(client_latch);
        client = createClient(client_dwatch, client_latch);

        lsnr_latch = new CountDownLatch(1);
        lsnr_dwatch = new SimpleWatcher(lsnr_latch);
        lsnr = createClient(lsnr_dwatch, lsnr_latch);

        expected = new ArrayList<EventType>();
    }

    @AfterEach
    @Override
    public void tearDown() throws Exception {
        client.close();
        lsnr.close();
        super.tearDown();
    }

    protected ZooKeeper createClient(Watcher watcher, CountDownLatch latch) throws IOException, InterruptedException {
        ZooKeeper zk = new ZooKeeper(hostPort, CONNECTION_TIMEOUT, watcher);
        if (!latch.await(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)) {
            fail("Unable to connect to server");
        }
        return zk;
    }

    private void verify() throws InterruptedException {
        lsnr_dwatch.verify(expected);
        expected.clear();
    }

    @Test
    public void testExistsSync() throws IOException, InterruptedException, KeeperException {
        assertNull(lsnr.exists("/foo", true));
        assertNull(lsnr.exists("/foo/bar", true));

        client.create("/foo", "parent".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        expected.add(EventType.NodeCreated);
        client.create("/foo/bar", "child".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        expected.add(EventType.NodeCreated);

        verify();

        assertNotNull(lsnr.exists("/foo", true));
        assertNotNull(lsnr.exists("/foo/bar", true));

        try {
            assertNull(lsnr.exists("/car", true));
            client.setData("/car", "missing".getBytes(), -1);
            fail();
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.NONODE, e.code());
            assertEquals("/car", e.getPath());
        }

        try {
            assertNull(lsnr.exists("/foo/car", true));
            client.setData("/foo/car", "missing".getBytes(), -1);
            fail();
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.NONODE, e.code());
            assertEquals("/foo/car", e.getPath());
        }

        client.setData("/foo", "parent".getBytes(), -1);
        expected.add(EventType.NodeDataChanged);
        client.setData("/foo/bar", "child".getBytes(), -1);
        expected.add(EventType.NodeDataChanged);

        verify();

        assertNotNull(lsnr.exists("/foo", true));
        assertNotNull(lsnr.exists("/foo/bar", true));

        client.delete("/foo/bar", -1);
        expected.add(EventType.NodeDeleted);
        client.delete("/foo", -1);
        expected.add(EventType.NodeDeleted);

        verify();
    }

    @Test
    public void testGetDataSync() throws IOException, InterruptedException, KeeperException {
        try {
            lsnr.getData("/foo", true, null);
            fail();
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.NONODE, e.code());
            assertEquals("/foo", e.getPath());
        }
        try {
            lsnr.getData("/foo/bar", true, null);
            fail();
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.NONODE, e.code());
            assertEquals("/foo/bar", e.getPath());
        }

        client.create("/foo", "parent".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertNotNull(lsnr.getData("/foo", true, null));
        client.create("/foo/bar", "child".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertNotNull(lsnr.getData("/foo/bar", true, null));

        client.setData("/foo", "parent".getBytes(), -1);
        expected.add(EventType.NodeDataChanged);
        client.setData("/foo/bar", "child".getBytes(), -1);
        expected.add(EventType.NodeDataChanged);

        verify();

        assertNotNull(lsnr.getData("/foo", true, null));
        assertNotNull(lsnr.getData("/foo/bar", true, null));

        client.delete("/foo/bar", -1);
        expected.add(EventType.NodeDeleted);
        client.delete("/foo", -1);
        expected.add(EventType.NodeDeleted);

        verify();
    }

    @Test
    public void testGetChildrenSync() throws IOException, InterruptedException, KeeperException {
        try {
            lsnr.getChildren("/foo", true);
            fail();
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.NONODE, e.code());
            assertEquals("/foo", e.getPath());
        }
        try {
            lsnr.getChildren("/foo/bar", true);
            fail();
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.NONODE, e.code());
            assertEquals("/foo/bar", e.getPath());
        }

        client.create("/foo", "parent".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertNotNull(lsnr.getChildren("/foo", true));

        client.create("/foo/bar", "child".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        expected.add(EventType.NodeChildrenChanged); // /foo
        assertNotNull(lsnr.getChildren("/foo/bar", true));

        client.setData("/foo", "parent".getBytes(), -1);
        client.setData("/foo/bar", "child".getBytes(), -1);

        assertNotNull(lsnr.exists("/foo", true));

        assertNotNull(lsnr.getChildren("/foo", true));
        assertNotNull(lsnr.getChildren("/foo/bar", true));

        client.delete("/foo/bar", -1);
        expected.add(EventType.NodeDeleted); // /foo/bar childwatch
        expected.add(EventType.NodeChildrenChanged); // /foo
        client.delete("/foo", -1);
        expected.add(EventType.NodeDeleted);

        verify();
    }

    @Test
    public void testExistsSyncWObj() throws IOException, InterruptedException, KeeperException {
        SimpleWatcher w1 = new SimpleWatcher(null);
        SimpleWatcher w2 = new SimpleWatcher(null);
        SimpleWatcher w3 = new SimpleWatcher(null);
        SimpleWatcher w4 = new SimpleWatcher(null);

        List<EventType> e2 = new ArrayList<EventType>();

        assertNull(lsnr.exists("/foo", true));
        assertNull(lsnr.exists("/foo", w1));

        assertNull(lsnr.exists("/foo/bar", w2));
        assertNull(lsnr.exists("/foo/bar", w3));
        assertNull(lsnr.exists("/foo/bar", w3));
        assertNull(lsnr.exists("/foo/bar", w4));

        client.create("/foo", "parent".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        expected.add(EventType.NodeCreated);
        client.create("/foo/bar", "child".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        e2.add(EventType.NodeCreated);

        lsnr_dwatch.verify(expected);
        w1.verify(expected);
        w2.verify(e2);
        w3.verify(e2);
        w4.verify(e2);
        expected.clear();
        e2.clear();

        // default not registered
        assertNotNull(lsnr.exists("/foo", w1));

        assertNotNull(lsnr.exists("/foo/bar", w2));
        assertNotNull(lsnr.exists("/foo/bar", w3));
        assertNotNull(lsnr.exists("/foo/bar", w4));
        assertNotNull(lsnr.exists("/foo/bar", w4));

        client.setData("/foo", "parent".getBytes(), -1);
        expected.add(EventType.NodeDataChanged);
        client.setData("/foo/bar", "child".getBytes(), -1);
        e2.add(EventType.NodeDataChanged);

        lsnr_dwatch.verify(new ArrayList<EventType>()); // not reg so should = 0
        w1.verify(expected);
        w2.verify(e2);
        w3.verify(e2);
        w4.verify(e2);
        expected.clear();
        e2.clear();

        assertNotNull(lsnr.exists("/foo", true));
        assertNotNull(lsnr.exists("/foo", w1));
        assertNotNull(lsnr.exists("/foo", w1));

        assertNotNull(lsnr.exists("/foo/bar", w2));
        assertNotNull(lsnr.exists("/foo/bar", w2));
        assertNotNull(lsnr.exists("/foo/bar", w3));
        assertNotNull(lsnr.exists("/foo/bar", w4));

        client.delete("/foo/bar", -1);
        expected.add(EventType.NodeDeleted);
        client.delete("/foo", -1);
        e2.add(EventType.NodeDeleted);

        lsnr_dwatch.verify(expected);
        w1.verify(expected);
        w2.verify(e2);
        w3.verify(e2);
        w4.verify(e2);
        expected.clear();
        e2.clear();

    }

    @Test
    public void testGetDataSyncWObj() throws IOException, InterruptedException, KeeperException {
        SimpleWatcher w1 = new SimpleWatcher(null);
        SimpleWatcher w2 = new SimpleWatcher(null);
        SimpleWatcher w3 = new SimpleWatcher(null);
        SimpleWatcher w4 = new SimpleWatcher(null);

        List<EventType> e2 = new ArrayList<EventType>();

        try {
            lsnr.getData("/foo", w1, null);
            fail();
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.NONODE, e.code());
            assertEquals("/foo", e.getPath());
        }
        try {
            lsnr.getData("/foo/bar", w2, null);
            fail();
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.NONODE, e.code());
            assertEquals("/foo/bar", e.getPath());
        }

        client.create("/foo", "parent".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertNotNull(lsnr.getData("/foo", true, null));
        assertNotNull(lsnr.getData("/foo", w1, null));
        client.create("/foo/bar", "child".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertNotNull(lsnr.getData("/foo/bar", w2, null));
        assertNotNull(lsnr.getData("/foo/bar", w3, null));
        assertNotNull(lsnr.getData("/foo/bar", w4, null));
        assertNotNull(lsnr.getData("/foo/bar", w4, null));

        client.setData("/foo", "parent".getBytes(), -1);
        expected.add(EventType.NodeDataChanged);
        client.setData("/foo/bar", "child".getBytes(), -1);
        e2.add(EventType.NodeDataChanged);

        lsnr_dwatch.verify(expected);
        w1.verify(expected);
        w2.verify(e2);
        w3.verify(e2);
        w4.verify(e2);
        expected.clear();
        e2.clear();

        assertNotNull(lsnr.getData("/foo", true, null));
        assertNotNull(lsnr.getData("/foo", w1, null));
        assertNotNull(lsnr.getData("/foo/bar", w2, null));
        assertNotNull(lsnr.getData("/foo/bar", w3, null));
        assertNotNull(lsnr.getData("/foo/bar", w3, null));
        assertNotNull(lsnr.getData("/foo/bar", w4, null));

        client.delete("/foo/bar", -1);
        expected.add(EventType.NodeDeleted);
        client.delete("/foo", -1);
        e2.add(EventType.NodeDeleted);

        lsnr_dwatch.verify(expected);
        w1.verify(expected);
        w2.verify(e2);
        w3.verify(e2);
        w4.verify(e2);
        expected.clear();
        e2.clear();
    }

    @Test
    public void testGetChildrenSyncWObj() throws IOException, InterruptedException, KeeperException {
        SimpleWatcher w1 = new SimpleWatcher(null);
        SimpleWatcher w2 = new SimpleWatcher(null);
        SimpleWatcher w3 = new SimpleWatcher(null);
        SimpleWatcher w4 = new SimpleWatcher(null);

        List<EventType> e2 = new ArrayList<EventType>();

        try {
            lsnr.getChildren("/foo", true);
            fail();
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.NONODE, e.code());
            assertEquals("/foo", e.getPath());
        }
        try {
            lsnr.getChildren("/foo/bar", true);
            fail();
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.NONODE, e.code());
            assertEquals("/foo/bar", e.getPath());
        }

        client.create("/foo", "parent".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertNotNull(lsnr.getChildren("/foo", true));
        assertNotNull(lsnr.getChildren("/foo", w1));

        client.create("/foo/bar", "child".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        expected.add(EventType.NodeChildrenChanged); // /foo
        assertNotNull(lsnr.getChildren("/foo/bar", w2));
        assertNotNull(lsnr.getChildren("/foo/bar", w2));
        assertNotNull(lsnr.getChildren("/foo/bar", w3));
        assertNotNull(lsnr.getChildren("/foo/bar", w4));

        client.setData("/foo", "parent".getBytes(), -1);
        client.setData("/foo/bar", "child".getBytes(), -1);

        assertNotNull(lsnr.exists("/foo", true));
        assertNotNull(lsnr.exists("/foo", w1));
        assertNotNull(lsnr.exists("/foo", true));
        assertNotNull(lsnr.exists("/foo", w1));

        assertNotNull(lsnr.getChildren("/foo", true));
        assertNotNull(lsnr.getChildren("/foo", w1));
        assertNotNull(lsnr.getChildren("/foo/bar", w2));
        assertNotNull(lsnr.getChildren("/foo/bar", w3));
        assertNotNull(lsnr.getChildren("/foo/bar", w4));
        assertNotNull(lsnr.getChildren("/foo/bar", w4));

        client.delete("/foo/bar", -1);
        e2.add(EventType.NodeDeleted); // /foo/bar childwatch
        expected.add(EventType.NodeChildrenChanged); // /foo
        client.delete("/foo", -1);
        expected.add(EventType.NodeDeleted);

        lsnr_dwatch.verify(expected);
        w1.verify(expected);
        w2.verify(e2);
        w3.verify(e2);
        w4.verify(e2);
        expected.clear();
        e2.clear();
    }

}
