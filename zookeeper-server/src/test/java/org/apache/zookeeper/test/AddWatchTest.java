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
    private Watcher standardWatcher;
    private ZooKeeper zk;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        events = new LinkedBlockingQueue<>();
        standardWatcher = event -> events.add(event);
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
    public void testStandardDataChangedWatcher() throws InterruptedException, KeeperException {
        zk.addWatch(BASE_PATH, standardWatcher, STANDARD_DATA);
        zk.setData(BASE_PATH, new byte[0], -1);
        assertEvent(events, Watcher.Event.EventType.NodeDataChanged, BASE_PATH);
    }

    @Test
    public void testStandardExistWatcher() throws InterruptedException, KeeperException {
        String inExistPath = BASE_PATH + System.currentTimeMillis();
        zk.addWatch(inExistPath, standardWatcher, STANDARD_EXIST);
        zk.create(inExistPath, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertEvent(events, Watcher.Event.EventType.NodeCreated, inExistPath);
    }

    @Test
    public void testStandardChildChangedWatcher() throws InterruptedException, KeeperException {
        zk.addWatch(BASE_PATH, standardWatcher, STANDARD_CHILD);
        zk.create(BASE_PATH + "/child", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
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

    @Test
    public void testBasicAsync()
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
            AsyncCallback.VoidCallback cb = (rc, path, ctx) -> {
                if (rc == 0) {
                    latch.countDown();
                }
            };
            zk.addWatch(BASE_PATH, standardWatcher, STANDARD_DATA, cb, null);
            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
            events.clear(); // clear any events added during client connection
            zk.setData(BASE_PATH, new byte[0], -1);
            assertEvent(events, Watcher.Event.EventType.NodeDataChanged, BASE_PATH);
        }
    }

    @Test
    public void testAsyncDefaultWatcher()
            throws IOException, InterruptedException, KeeperException {
        try (ZooKeeper zk = createClient(new CountdownWatcher(), hostPort)) {
            final CountDownLatch latch = new CountDownLatch(1);
            AsyncCallback.VoidCallback cb = (rc, path, ctx) -> {
                if (rc == 0) {
                    latch.countDown();
                }
            };
            zk.addWatch(BASE_PATH, standardWatcher, STANDARD_DATA, cb, null);
            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
            events.clear(); // clear any events added during client connection
            zk.setData(BASE_PATH, new byte[0], -1);
            assertEvent(events, Watcher.Event.EventType.NodeDataChanged, BASE_PATH);
        }
    }

    @Test
    public void testRemoval() throws InterruptedException, KeeperException {
        zk.addWatch(BASE_PATH, standardWatcher, STANDARD_DATA);
        zk.removeWatches(BASE_PATH, standardWatcher, Watcher.WatcherType.Data, false);
        assertEvent(events, Watcher.Event.EventType.DataWatchRemoved, BASE_PATH);
    }

    @Test
    public void testDisconnect() throws Exception {
        zk.addWatch(BASE_PATH, standardWatcher, STANDARD_DATA);
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

            zk1.addWatch(BASE_PATH, standardWatcher, STANDARD_DATA);
            zk1.setData(BASE_PATH, "one".getBytes(), -1);
            Thread.sleep(1000); // give some time for the event to arrive
            zk2.setData(BASE_PATH, "two".getBytes(), -1);

            Assert.assertEquals(1, events.size());
            assertEvent(events, Watcher.Event.EventType.NodeDataChanged, BASE_PATH);
        }
    }

    @Test
    public void testRootWatcher()
            throws IOException, InterruptedException, KeeperException {
        try (ZooKeeper zk = createClient(new CountdownWatcher(), hostPort)) {
            zk.addWatch("/", standardWatcher, STANDARD_CHILD);
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
}