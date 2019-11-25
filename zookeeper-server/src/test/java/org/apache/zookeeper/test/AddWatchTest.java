/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.test;

import static org.apache.zookeeper.AddWatchMode.PERSISTENT;
import static org.apache.zookeeper.AddWatchMode.PERSISTENT_RECURSIVE;
import static org.apache.zookeeper.AddWatchMode.STANDARD_CHILD;
import static org.apache.zookeeper.AddWatchMode.STANDARD_DATA;
import static org.apache.zookeeper.AddWatchMode.STANDARD_EXIST;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * The unit test for zookeeper addWatch api for standard one-time watcher.
 */
public class AddWatchTest extends ClientBase {
    private BlockingQueue<WatchedEvent> events;
    public static final String BASE_PATH = "/addWatch";
    private Watcher watcher;
    private ZooKeeper zk;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        events = new LinkedBlockingQueue<>();
        watcher = event -> events.add(event);
        zk = createClient(new CountdownWatcher(), hostPort);
        zk.create(BASE_PATH, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    }

    @After
    public void tearDown() throws Exception {
        if (zk != null) {
            zk.close();
        }
        super.tearDown();
        events.clear();
    }

    @Test
    public void testStandardDataChangedWatch() throws InterruptedException, KeeperException {
        zk.addWatch(BASE_PATH, watcher, STANDARD_DATA);
        zk.setData(BASE_PATH, new byte[0], -1);
        Assert.assertEquals(1, events.size());
        assertEvent(events, Watcher.Event.EventType.NodeDataChanged, BASE_PATH);
    }

    @Test
    public void testDataWatchEquivalence() throws InterruptedException, KeeperException {
        BlockingQueue<WatchedEvent> events1 = new LinkedBlockingQueue<>();
        Watcher watcher1 = event -> events1.add(event);
        BlockingQueue<WatchedEvent> events2 = new LinkedBlockingQueue<>();
        Watcher watcher2 = event -> events2.add(event);

        Stat stat1 = new Stat();
        zk.getData(BASE_PATH, watcher1, stat1);
        zk.setData(BASE_PATH, new byte[0], -1);

        Stat stat2 = zk.addWatch(BASE_PATH, watcher2, STANDARD_DATA);
        zk.setData(BASE_PATH, new byte[0], -1);

        assertEventQueue(events1, events2);
        Assert.assertEquals(stat1.getDataLength(), stat2.getDataLength());
    }

    @Test
    public void testStandardExistWatch() throws InterruptedException, KeeperException {
        String inExistPath = BASE_PATH + System.currentTimeMillis();
        Stat stat = zk.addWatch(inExistPath, watcher, STANDARD_EXIST);
        Assert.assertNull(stat);
        zk.create(inExistPath, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        Assert.assertEquals(1, events.size());
        assertEvent(events, Watcher.Event.EventType.NodeCreated, inExistPath);
        //exist path
        stat = zk.addWatch(BASE_PATH, watcher, STANDARD_EXIST);
        Assert.assertNotNull(stat);
        zk.delete(BASE_PATH, -1);
        Assert.assertEquals(1, events.size());
        assertEvent(events, Watcher.Event.EventType.NodeDeleted, BASE_PATH);
    }

    @Test
    public void testExistWatchEquivalence() throws InterruptedException, KeeperException {
        BlockingQueue<WatchedEvent> events1 = new LinkedBlockingQueue<>();
        Watcher watcher1 = event -> events1.add(event);
        BlockingQueue<WatchedEvent> events2 = new LinkedBlockingQueue<>();
        Watcher watcher2 = event -> events2.add(event);

        String path = BASE_PATH + "/ch-1";
        Stat stat1 = zk.exists(path, watcher1);
        Assert.assertNull(stat1);
        String data = "foo-bar";
        zk.create(path, data.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        zk.delete(path, -1);
        Stat stat2 = zk.addWatch(path, watcher2, STANDARD_EXIST);
        Assert.assertNull(stat2);
        zk.create(path, data.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        assertEventQueue(events1, events2);
    }

    @Test
    public void testExistWatchAsync()
            throws IOException, InterruptedException, KeeperException {
        CountdownWatcher watcher = new CountdownWatcher() {
            @Override
            public synchronized void process(WatchedEvent event) {
                super.process(event);
                events.add(event);
            }
        };
        try (ZooKeeper zk = createClient(watcher, hostPort)) {
            final CountDownLatch latch = new CountDownLatch(1);
            String nonExistentPath = BASE_PATH + System.currentTimeMillis();
            zk.exists(nonExistentPath, watcher, (rc, path, ctx, stat) -> {
                if (rc == KeeperException.NoNodeException.Code.NONODE.intValue()) {
                    Assert.assertNull(stat);
                    latch.countDown();
                }
            }, null);
            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
            events.clear(); // clear any events added during client connection
            zk.create(nonExistentPath, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            assertEvent(events, Watcher.Event.EventType.NodeCreated, nonExistentPath);
        }
        try (ZooKeeper zk = createClient(watcher, hostPort)) {
            final CountDownLatch latch = new CountDownLatch(1);
            String existentPath = BASE_PATH;
            String data = "foo-bar";
            zk.setData(existentPath, data.getBytes(), -1);
            zk.exists(existentPath, watcher, (rc, path, ctx, stat) -> {
                if (rc == 0) {
                    Assert.assertEquals(data.length(), stat.getDataLength());
                    latch.countDown();
                }
            }, null);
            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
            events.clear(); // clear any events added during client connection
            zk.delete(existentPath, -1);
            assertEvent(events, Watcher.Event.EventType.NodeDeleted, existentPath);
        }
    }

    @Test
    public void testStandardChildChangedWatch() throws InterruptedException, KeeperException {
        zk.addWatch(BASE_PATH, watcher, STANDARD_CHILD);
        zk.create(BASE_PATH + "/child", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertEvent(events, Watcher.Event.EventType.NodeChildrenChanged, BASE_PATH);
    }

    @Test
    public void testChildWatchEquivalence() throws InterruptedException, KeeperException {
        BlockingQueue<WatchedEvent> events1 = new LinkedBlockingQueue<>();
        Watcher watcher1 = event -> events1.add(event);
        BlockingQueue<WatchedEvent> events2 = new LinkedBlockingQueue<>();
        Watcher watcher2 = event -> events2.add(event);

        String childPath1 = BASE_PATH + "/ch-1";
        zk.getChildren(BASE_PATH, watcher1);
        zk.create(childPath1, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        String childPath2 = BASE_PATH + "/ch-2";
        zk.addWatch(BASE_PATH, watcher2, STANDARD_CHILD);
        zk.create(childPath2, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        assertEventQueue(events1, events2);
    }

    @Test
    public void testModeChangeFromPersistentToStandardData() throws InterruptedException, KeeperException {
        zk.addWatch(BASE_PATH, watcher, PERSISTENT);
        zk.setData(BASE_PATH, new byte[0], -1);
        zk.create(BASE_PATH + "/child", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertEvent(events, Watcher.Event.EventType.NodeDataChanged, BASE_PATH);
        assertEvent(events, Watcher.Event.EventType.NodeChildrenChanged, BASE_PATH);

        zk.removeWatches(BASE_PATH, watcher, Watcher.WatcherType.Any, false);
        assertEvent(events, Watcher.Event.EventType.PersistentWatchRemoved, BASE_PATH);

        zk.addWatch(BASE_PATH, watcher, STANDARD_DATA);
        zk.setData(BASE_PATH, new byte[0], -1);
        zk.setData(BASE_PATH, new byte[1], -1);
        zk.create(BASE_PATH + "/child2", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        Assert.assertEquals(1, events.size());
        assertEvent(events, Watcher.Event.EventType.NodeDataChanged, BASE_PATH);
    }

    @Test
    public void testModeChangeFromPersistentToStandardChild() throws InterruptedException, KeeperException {
        zk.addWatch(BASE_PATH, watcher, PERSISTENT);
        zk.setData(BASE_PATH, new byte[0], -1);
        zk.create(BASE_PATH + "/child", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertEvent(events, Watcher.Event.EventType.NodeDataChanged, BASE_PATH);
        assertEvent(events, Watcher.Event.EventType.NodeChildrenChanged, BASE_PATH);

        zk.removeWatches(BASE_PATH, watcher, Watcher.WatcherType.Any, false);
        assertEvent(events, Watcher.Event.EventType.PersistentWatchRemoved, BASE_PATH);

        zk.addWatch(BASE_PATH, watcher, STANDARD_CHILD);
        zk.create(BASE_PATH + "/child2", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create(BASE_PATH + "/child3", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.setData(BASE_PATH, new byte[1], -1);

        Assert.assertEquals(1, events.size());
        assertEvent(events, Watcher.Event.EventType.NodeChildrenChanged, BASE_PATH);
    }

    @Test
    public void testModeChangeFromPersistentToStandardExist() throws InterruptedException, KeeperException {
        zk.addWatch(BASE_PATH, watcher, PERSISTENT);
        zk.setData(BASE_PATH, new byte[0], -1);
        zk.create(BASE_PATH + "/child", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertEvent(events, Watcher.Event.EventType.NodeDataChanged, BASE_PATH);
        assertEvent(events, Watcher.Event.EventType.NodeChildrenChanged, BASE_PATH);

        zk.removeWatches(BASE_PATH, watcher, Watcher.WatcherType.Any, false);
        assertEvent(events, Watcher.Event.EventType.PersistentWatchRemoved, BASE_PATH);

        zk.addWatch(BASE_PATH, watcher, STANDARD_EXIST);
        zk.setData(BASE_PATH, new byte[0], -1);
        Assert.assertEquals(1, events.size());
        assertEvent(events, Watcher.Event.EventType.NodeDataChanged, BASE_PATH);
    }

    @Test
    public void testModeChangeFromStandardChildToPersistent() throws InterruptedException, KeeperException {
        zk.addWatch(BASE_PATH, watcher, STANDARD_CHILD);
        zk.create(BASE_PATH + "/child2", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create(BASE_PATH + "/child3", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.setData(BASE_PATH, new byte[1], -1);
        Assert.assertEquals(1, events.size());
        assertEvent(events, Watcher.Event.EventType.NodeChildrenChanged, BASE_PATH);

        zk.addWatch(BASE_PATH, watcher, PERSISTENT);
        zk.setData(BASE_PATH, new byte[0], -1);
        zk.setData(BASE_PATH, new byte[0], -1);
        zk.create(BASE_PATH + "/child", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertEvent(events, Watcher.Event.EventType.NodeDataChanged, BASE_PATH);
        assertEvent(events, Watcher.Event.EventType.NodeDataChanged, BASE_PATH);
        assertEvent(events, Watcher.Event.EventType.NodeChildrenChanged, BASE_PATH);
    }

    @Test
    public void testModeChangeFromStandardDataToPersistent() throws InterruptedException, KeeperException {
        zk.addWatch(BASE_PATH, watcher, STANDARD_DATA);
        zk.setData(BASE_PATH, new byte[1], -1);
        zk.setData(BASE_PATH, new byte[1], -1);
        zk.setData(BASE_PATH, new byte[1], -1);
        Assert.assertEquals(1, events.size());
        assertEvent(events, Watcher.Event.EventType.NodeDataChanged, BASE_PATH);

        zk.addWatch(BASE_PATH, watcher, PERSISTENT);
        zk.setData(BASE_PATH, new byte[0], -1);
        zk.setData(BASE_PATH, new byte[0], -1);
        zk.create(BASE_PATH + "/child", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertEvent(events, Watcher.Event.EventType.NodeDataChanged, BASE_PATH);
        assertEvent(events, Watcher.Event.EventType.NodeDataChanged, BASE_PATH);
        assertEvent(events, Watcher.Event.EventType.NodeChildrenChanged, BASE_PATH);
    }

    @Test
    public void testModeChangeFromStandardExistToPersistent() throws InterruptedException, KeeperException {
        zk.addWatch(BASE_PATH, watcher, STANDARD_EXIST);
        zk.setData(BASE_PATH, new byte[0], -1);
        zk.setData(BASE_PATH, new byte[0], -1);
        zk.setData(BASE_PATH, new byte[0], -1);
        Assert.assertEquals(1, events.size());
        assertEvent(events, Watcher.Event.EventType.NodeDataChanged, BASE_PATH);

        zk.addWatch(BASE_PATH, watcher, PERSISTENT);
        zk.setData(BASE_PATH, new byte[0], -1);
        zk.setData(BASE_PATH, new byte[0], -1);
        zk.create(BASE_PATH + "/child", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertEvent(events, Watcher.Event.EventType.NodeDataChanged, BASE_PATH);
        assertEvent(events, Watcher.Event.EventType.NodeDataChanged, BASE_PATH);
        assertEvent(events, Watcher.Event.EventType.NodeChildrenChanged, BASE_PATH);
    }

    @Test
    public void testDefaultWatcher()
            throws IOException, InterruptedException, KeeperException {
        CountdownWatcher watcher = new CountdownWatcher() {
            @Override
            public synchronized void process(WatchedEvent event) {
                super.process(event);
                events.add(event);
            }
        };
        try (ZooKeeper zk = createClient(watcher, hostPort)) {
            zk.addWatch(BASE_PATH, STANDARD_DATA);
            events.clear(); // clear any events added during client connection
            zk.setData(BASE_PATH, new byte[0], -1);
            assertEvent(events, Watcher.Event.EventType.NodeDataChanged, BASE_PATH);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullWatcherSync() throws InterruptedException, KeeperException {
        zk.addWatch(BASE_PATH, null, STANDARD_CHILD);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullWatcherAsync() throws InterruptedException, IOException {
        CountdownWatcher watcher = new CountdownWatcher() {
            @Override
            public synchronized void process(WatchedEvent event) {
                super.process(event);
                events.add(event);
            }
        };

        try (ZooKeeper zk = createClient(watcher, hostPort)) {
            zk.addWatch(BASE_PATH, null, STANDARD_DATA, (rc, path, ctx, stat) -> {
            }, null);
        }
    }

    @Test
    public void testBasicAsync() throws IOException, InterruptedException, KeeperException {
        CountdownWatcher watcher = new CountdownWatcher() {
            @Override
            public synchronized void process(WatchedEvent event) {
                super.process(event);
                events.add(event);
            }
        };
        try (ZooKeeper zk = createClient(watcher, hostPort)) {
            final CountDownLatch latch = new CountDownLatch(1);
            String data = "foo-bar";
            zk.setData(BASE_PATH, data.getBytes(), -1);
            zk.addWatch(BASE_PATH, this.watcher, STANDARD_DATA, (rc, path, ctx, stat) -> {
                if (rc == 0) {
                    Assert.assertEquals(data.length(), stat.getDataLength());
                    latch.countDown();
                }
            }, null);

            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
            events.clear(); // clear any events added during client connection
            zk.setData(BASE_PATH, data.getBytes(), -1);
            assertEvent(events, Watcher.Event.EventType.NodeDataChanged, BASE_PATH);
        }

        try (ZooKeeper zk = createClient(watcher, hostPort)) {
            final CountDownLatch latch = new CountDownLatch(1);
            String nonExistentPath = BASE_PATH + System.currentTimeMillis();
            zk.addWatch(nonExistentPath, this.watcher, STANDARD_DATA, (rc, path, ctx, stat) -> {
                if (rc == KeeperException.NoNodeException.Code.NONODE.intValue()) {
                    Assert.assertNull(stat);
                    latch.countDown();
                }
            }, null);

            Assert.assertTrue(latch.await(50, TimeUnit.SECONDS));
            events.clear(); // clear any events added during client connection
            zk.create(nonExistentPath, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            assertEvent(events, Watcher.Event.EventType.NodeCreated, nonExistentPath);
        }
    }

    @Test
    public void testAsyncDefaultWatcher()
            throws IOException, InterruptedException, KeeperException {
        try (ZooKeeper zk = createClient(new CountdownWatcher(), hostPort)) {
            final CountDownLatch latch = new CountDownLatch(1);
            String data = "foo-bar";
            zk.setData(BASE_PATH, data.getBytes(), -1);
            AsyncCallback.StatCallback cb = (rc, path, ctx, stat) -> {
                if (rc == 0) {
                    Assert.assertEquals(data.length(), stat.getDataLength());
                    latch.countDown();
                }
            };
            zk.addWatch(BASE_PATH, watcher, STANDARD_DATA, cb, null);
            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
            events.clear(); // clear any events added during client connection
            zk.setData(BASE_PATH, new byte[0], -1);
            assertEvent(events, Watcher.Event.EventType.NodeDataChanged, BASE_PATH);
        }
    }

    @Test
    public void testRemoval() throws InterruptedException, KeeperException {
        zk.addWatch(BASE_PATH, watcher, STANDARD_DATA);
        zk.removeWatches(BASE_PATH, watcher, Watcher.WatcherType.Data, false);
        assertEvent(events, Watcher.Event.EventType.DataWatchRemoved, BASE_PATH);
    }

    @Test
    public void testDisconnect() throws Exception {
        zk.addWatch(BASE_PATH, watcher, STANDARD_DATA);
        stopServer();
        assertEvent(events, Watcher.Event.EventType.None, null);
        startServer();
        assertEvent(events, Watcher.Event.EventType.None, null);
        zk.setData(BASE_PATH, new byte[0], -1);
        assertEvent(events, Watcher.Event.EventType.NodeDataChanged, BASE_PATH);
    }

    @Test
    public void testMultiClient()
            throws IOException, InterruptedException, KeeperException {
        try (ZooKeeper zk1 = createClient(new CountdownWatcher(), hostPort);
            ZooKeeper zk2 = createClient(new CountdownWatcher(), hostPort)) {

            zk1.addWatch(BASE_PATH, watcher, STANDARD_DATA);
            zk1.setData(BASE_PATH, "one".getBytes(), -1);
            Thread.sleep(1000); // give some time for the event to arrive
            zk2.setData(BASE_PATH, "two".getBytes(), -1);

            Assert.assertEquals(1, events.size());
            assertEvent(events, Watcher.Event.EventType.NodeDataChanged, BASE_PATH);
        }
    }

    @Test
    public void testMultiClientMultiWatchMode() throws IOException, InterruptedException, KeeperException {
        BlockingQueue<WatchedEvent> events1 = new LinkedBlockingQueue<>();
        Watcher watcher1 = event -> events1.add(event);
        BlockingQueue<WatchedEvent> events2 = new LinkedBlockingQueue<>();
        Watcher watcher2 = event -> events2.add(event);

        try (ZooKeeper zk1 = createClient(new CountdownWatcher(), hostPort);
             ZooKeeper zk2 = createClient(new CountdownWatcher(), hostPort)) {

            zk1.create("/a", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk1.create("/a/b", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk1.create("/a/b/c", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            zk1.addWatch("/a/b", watcher1, PERSISTENT_RECURSIVE);
            zk1.setData("/a/b/c", "one".getBytes(), -1);
            Thread.sleep(1000); // give some time for the event to arrive
            zk1.setData("/a/b/c", "two".getBytes(), -1);
            zk1.setData("/a/b/c", "three".getBytes(), -1);
            zk1.setData("/a/b/c", "four".getBytes(), -1);
            assertEvent(events1, Watcher.Event.EventType.NodeDataChanged, "/a/b/c");
            assertEvent(events1, Watcher.Event.EventType.NodeDataChanged, "/a/b/c");
            assertEvent(events1, Watcher.Event.EventType.NodeDataChanged, "/a/b/c");
            assertEvent(events1, Watcher.Event.EventType.NodeDataChanged, "/a/b/c");

            zk2.addWatch("/a/b", watcher2, STANDARD_DATA);
            zk2.setData("/a/b", "five".getBytes(), -1);
            zk2.setData("/a/b", "six".getBytes(), -1);
            zk2.create("/a/b/d", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            assertEvent(events1, Watcher.Event.EventType.NodeDataChanged, "/a/b");
            assertEvent(events1, Watcher.Event.EventType.NodeDataChanged, "/a/b");
            assertEvent(events1, Watcher.Event.EventType.NodeCreated, "/a/b/d");
            assertEvent(events2, Watcher.Event.EventType.NodeDataChanged, "/a/b");
        }
    }

    @Test
    public void testRootWatcher()
            throws IOException, InterruptedException, KeeperException {
        try (ZooKeeper zk = createClient(new CountdownWatcher(), hostPort)) {
            zk.addWatch("/", watcher, STANDARD_CHILD);
            zk.create("/a", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            Assert.assertEquals(1, events.size());
            assertEvent(events, Watcher.Event.EventType.NodeChildrenChanged, "/");
        }
    }

    private void assertEvent(BlockingQueue<WatchedEvent> events, Watcher.Event.EventType eventType, String path)
            throws InterruptedException {
        WatchedEvent event = events.poll(5, TimeUnit.SECONDS);
        Assert.assertNotNull(event);
        Assert.assertEquals(eventType, event.getType());
        Assert.assertEquals(path, event.getPath());
    }

    private void assertEventQueue(BlockingQueue<WatchedEvent> events1, BlockingQueue<WatchedEvent> events2)
            throws InterruptedException {
        Assert.assertEquals(events1.size(), events2.size());

        while (!events1.isEmpty() && !events2.isEmpty()) {
            WatchedEvent event1 = events1.poll(5, TimeUnit.SECONDS);
            WatchedEvent event2 = events2.poll(5, TimeUnit.SECONDS);

            Assert.assertEquals(event1.getState(), event2.getState());
            Assert.assertEquals(event1.getType(), event2.getType());
            Assert.assertEquals(event1.getPath(), event2.getPath());
        }

    }
}