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
import org.apache.zookeeper.data.Stat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class WatcherFuncTest extends ClientBase {

    private static class SimpleWatcher implements Watcher {

        private LinkedBlockingQueue<WatchedEvent> events = new LinkedBlockingQueue<>();
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

        public void verify(List<WatchedEvent> expected) throws InterruptedException {
            List<WatchedEvent> actual = new ArrayList<>();
            WatchedEvent event;
            while (actual.size() < expected.size() && (event = events.poll(30, TimeUnit.SECONDS)) != null) {
                actual.add(event);
            }
            assertEquals(expected.size(), actual.size());
            for (int i = 0; i < expected.size(); i++) {
                TestUtils.assertWatchedEventEquals(expected.get(i), actual.get(i));
            }
            events.clear();
        }

    }

    private SimpleWatcher client_dwatch;
    private volatile CountDownLatch client_latch;
    private ZooKeeper client;
    private SimpleWatcher lsnr_dwatch;
    private volatile CountDownLatch lsnr_latch;
    private ZooKeeper lsnr;

    private List<WatchedEvent> expected;

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

        expected = new ArrayList<>();
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

    private void addEvent(List<WatchedEvent> events, EventType eventType, String path, Stat stat) {
        addEvent(events, eventType, path, stat.getMzxid());
    }

    private void addEvent(List<WatchedEvent> events, EventType eventType, String path, long zxid) {
        events.add(new WatchedEvent(eventType, KeeperState.SyncConnected, path, zxid));
    }

    private long delete(String path) throws InterruptedException, KeeperException {
        client.delete(path, -1);
        int lastSlash = path.lastIndexOf('/');
        String parent = (lastSlash == 0)
            ? "/"
            : path.substring(0, lastSlash);
        // the deletion's zxid will be reflected in the parent's Pzxid
        return client.exists(parent, false).getPzxid();
    }

    @Test
    public void testExistsSync() throws IOException, InterruptedException, KeeperException {
        assertNull(lsnr.exists("/foo", true));
        assertNull(lsnr.exists("/foo/bar", true));

        Stat stat = new Stat();
        client.create("/foo", "parent".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
        addEvent(expected, EventType.NodeCreated, "/foo", stat);
        client.create("/foo/bar", "child".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
        addEvent(expected, EventType.NodeCreated, "/foo/bar", stat);

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

        stat = client.setData("/foo", "parent".getBytes(), -1);
        addEvent(expected, EventType.NodeDataChanged, "/foo", stat);
        stat = client.setData("/foo/bar", "child".getBytes(), -1);
        addEvent(expected, EventType.NodeDataChanged, "/foo/bar", stat);

        verify();

        assertNotNull(lsnr.exists("/foo", true));
        assertNotNull(lsnr.exists("/foo/bar", true));

        long deleteZxid = delete("/foo/bar");
        addEvent(expected, EventType.NodeDeleted, "/foo/bar", deleteZxid);
        deleteZxid = delete("/foo");
        addEvent(expected, EventType.NodeDeleted, "/foo", deleteZxid);

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

        Stat stat = client.setData("/foo", "parent".getBytes(), -1);
        addEvent(expected, EventType.NodeDataChanged, "/foo", stat);
        stat = client.setData("/foo/bar", "child".getBytes(), -1);
        addEvent(expected, EventType.NodeDataChanged, "/foo/bar", stat);

        verify();

        assertNotNull(lsnr.getData("/foo", true, null));
        assertNotNull(lsnr.getData("/foo/bar", true, null));

        long deleteZxid = delete("/foo/bar");
        addEvent(expected, EventType.NodeDeleted, "/foo/bar", deleteZxid);
        deleteZxid = delete("/foo");
        addEvent(expected, EventType.NodeDeleted, "/foo", deleteZxid);

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

        Stat stat = new Stat();
        client.create("/foo/bar", "child".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
        addEvent(expected, EventType.NodeChildrenChanged, "/foo", stat); // /foo
        assertNotNull(lsnr.getChildren("/foo/bar", true));

        client.setData("/foo", "parent".getBytes(), -1);
        client.setData("/foo/bar", "child".getBytes(), -1);

        assertNotNull(lsnr.exists("/foo", true));

        assertNotNull(lsnr.getChildren("/foo", true));
        assertNotNull(lsnr.getChildren("/foo/bar", true));

        long deleteZxid = delete("/foo/bar");
        addEvent(expected, EventType.NodeDeleted, "/foo/bar", deleteZxid); // /foo/bar childwatch
        addEvent(expected, EventType.NodeChildrenChanged, "/foo", deleteZxid); // /foo
        deleteZxid = delete("/foo");
        addEvent(expected, EventType.NodeDeleted, "/foo", deleteZxid);

        verify();
    }

    @Test
    public void testExistsSyncWObj() throws IOException, InterruptedException, KeeperException {
        SimpleWatcher w1 = new SimpleWatcher(null);
        SimpleWatcher w2 = new SimpleWatcher(null);
        SimpleWatcher w3 = new SimpleWatcher(null);
        SimpleWatcher w4 = new SimpleWatcher(null);

        List<WatchedEvent> e2 = new ArrayList<>();

        assertNull(lsnr.exists("/foo", true));
        assertNull(lsnr.exists("/foo", w1));

        assertNull(lsnr.exists("/foo/bar", w2));
        assertNull(lsnr.exists("/foo/bar", w3));
        assertNull(lsnr.exists("/foo/bar", w3));
        assertNull(lsnr.exists("/foo/bar", w4));

        Stat stat = new Stat();
        client.create("/foo", "parent".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
        addEvent(expected, EventType.NodeCreated, "/foo", stat);
        client.create("/foo/bar", "child".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
        addEvent(e2, EventType.NodeCreated, "/foo/bar", stat);

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

        stat = client.setData("/foo", "parent".getBytes(), -1);
        addEvent(expected, EventType.NodeDataChanged, "/foo", stat);
        stat = client.setData("/foo/bar", "child".getBytes(), -1);
        addEvent(e2, EventType.NodeDataChanged, "/foo/bar", stat);

        lsnr_dwatch.verify(new ArrayList<>()); // not reg so should = 0
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

        long deleteZxid = delete("/foo/bar");
        addEvent(e2, EventType.NodeDeleted, "/foo/bar", deleteZxid);
        deleteZxid = delete("/foo");
        addEvent(expected, EventType.NodeDeleted, "/foo", deleteZxid);

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

        List<WatchedEvent> e2 = new ArrayList<>();

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

        Stat stat = client.setData("/foo", "parent".getBytes(), -1);
        addEvent(expected, EventType.NodeDataChanged, "/foo", stat);
        stat = client.setData("/foo/bar", "child".getBytes(), -1);
        addEvent(e2, EventType.NodeDataChanged, "/foo/bar", stat);

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

        long deleteZxid = delete("/foo/bar");
        addEvent(e2, EventType.NodeDeleted, "/foo/bar", deleteZxid);
        deleteZxid = delete("/foo");
        addEvent(expected, EventType.NodeDeleted, "/foo", deleteZxid);

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

        List<WatchedEvent> e2 = new ArrayList<>();

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

        Stat stat = new Stat();
        client.create("/foo/bar", "child".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
        addEvent(expected, EventType.NodeChildrenChanged, "/foo", stat); // /foo
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

        long deleteZxid = delete("/foo/bar");
        addEvent(e2, EventType.NodeDeleted, "/foo/bar", deleteZxid);
        addEvent(expected, EventType.NodeChildrenChanged, "/foo", deleteZxid); // /foo
        deleteZxid = delete("/foo");
        addEvent(expected, EventType.NodeDeleted, "/foo", deleteZxid);

        lsnr_dwatch.verify(expected);
        w1.verify(expected);
        w2.verify(e2);
        w3.verify(e2);
        w4.verify(e2);
        expected.clear();
        e2.clear();
    }

}
