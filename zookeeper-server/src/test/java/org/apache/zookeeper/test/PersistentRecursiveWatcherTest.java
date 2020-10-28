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

import static org.apache.zookeeper.AddWatchMode.PERSISTENT_RECURSIVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PersistentRecursiveWatcherTest extends ClientBase {
    private static final Logger LOG = LoggerFactory.getLogger(PersistentRecursiveWatcherTest.class);
    private BlockingQueue<WatchedEvent> events;
    private Watcher persistentWatcher;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();

        events = new LinkedBlockingQueue<>();
        persistentWatcher = event -> events.add(event);
    }

    @Test
    public void testBasic()
            throws IOException, InterruptedException, KeeperException {
        try (ZooKeeper zk = createClient(new CountdownWatcher(), hostPort)) {
            zk.addWatch("/a/b", persistentWatcher, PERSISTENT_RECURSIVE);
            internalTestBasic(zk);
        }
    }

    @Test
    public void testBasicAsync()
            throws IOException, InterruptedException, KeeperException {
        try (ZooKeeper zk = createClient(new CountdownWatcher(), hostPort)) {
            final CountDownLatch latch = new CountDownLatch(1);
            AsyncCallback.VoidCallback cb = (rc, path, ctx) -> {
                if (rc == KeeperException.Code.OK.intValue()) {
                    latch.countDown();
                }
            };
            zk.addWatch("/a/b", persistentWatcher, PERSISTENT_RECURSIVE, cb, null);
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            internalTestBasic(zk);
        }
    }

    private void internalTestBasic(ZooKeeper zk) throws KeeperException, InterruptedException {
        zk.create("/a", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/a/b", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/a/b/c", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/a/b/c/d", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/a/b/c/d/e", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.setData("/a/b/c/d/e", new byte[0], -1);
        zk.delete("/a/b/c/d/e", -1);
        zk.create("/a/b/c/d/e", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        assertEvent(events, Watcher.Event.EventType.NodeCreated, "/a/b");
        assertEvent(events, Watcher.Event.EventType.NodeCreated, "/a/b/c");
        assertEvent(events, Watcher.Event.EventType.NodeCreated, "/a/b/c/d");
        assertEvent(events, Watcher.Event.EventType.NodeCreated, "/a/b/c/d/e");
        assertEvent(events, Watcher.Event.EventType.NodeDataChanged, "/a/b/c/d/e");
        assertEvent(events, Watcher.Event.EventType.NodeDeleted, "/a/b/c/d/e");
        assertEvent(events, Watcher.Event.EventType.NodeCreated, "/a/b/c/d/e");
    }

    @Test
    public void testRemoval()
            throws IOException, InterruptedException, KeeperException {
        try (ZooKeeper zk = createClient(new CountdownWatcher(), hostPort)) {
            zk.addWatch("/a/b", persistentWatcher, PERSISTENT_RECURSIVE);
            zk.create("/a", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk.create("/a/b", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk.create("/a/b/c", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            assertEvent(events, Watcher.Event.EventType.NodeCreated, "/a/b");
            assertEvent(events, Watcher.Event.EventType.NodeCreated, "/a/b/c");

            zk.removeWatches("/a/b", persistentWatcher, Watcher.WatcherType.Any, false);
            zk.create("/a/b/c/d", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            assertEvent(events, Watcher.Event.EventType.PersistentWatchRemoved, "/a/b");
        }
    }

    @Test
    public void testDisconnect() throws Exception {
        try (ZooKeeper zk = createClient(new CountdownWatcher(), hostPort)) {
            zk.addWatch("/a/b", persistentWatcher, PERSISTENT_RECURSIVE);
            stopServer();
            assertEvent(events, Watcher.Event.EventType.None, null);
            startServer();
            assertEvent(events, Watcher.Event.EventType.None, null);
            internalTestBasic(zk);
        }
    }

    @Test
    public void testMultiClient()
            throws IOException, InterruptedException, KeeperException {
        try (ZooKeeper zk1 = createClient(new CountdownWatcher(), hostPort); ZooKeeper zk2 = createClient(new CountdownWatcher(), hostPort)) {

            zk1.create("/a", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk1.create("/a/b", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk1.create("/a/b/c", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            zk1.addWatch("/a/b", persistentWatcher, PERSISTENT_RECURSIVE);
            zk1.setData("/a/b/c", "one".getBytes(), -1);
            Thread.sleep(1000); // give some time for the event to arrive

            zk2.setData("/a/b/c", "two".getBytes(), -1);
            zk2.setData("/a/b/c", "three".getBytes(), -1);
            zk2.setData("/a/b/c", "four".getBytes(), -1);

            assertEvent(events, Watcher.Event.EventType.NodeDataChanged, "/a/b/c");
            assertEvent(events, Watcher.Event.EventType.NodeDataChanged, "/a/b/c");
            assertEvent(events, Watcher.Event.EventType.NodeDataChanged, "/a/b/c");
            assertEvent(events, Watcher.Event.EventType.NodeDataChanged, "/a/b/c");
        }
    }

    @Test
    public void testRootWatcher()
            throws IOException, InterruptedException, KeeperException {
        try (ZooKeeper zk = createClient(new CountdownWatcher(), hostPort)) {
            zk.addWatch("/", persistentWatcher, PERSISTENT_RECURSIVE);
            zk.create("/a", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk.create("/a/b", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk.create("/b", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk.create("/b/c", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            assertEvent(events, Watcher.Event.EventType.NodeCreated, "/a");
            assertEvent(events, Watcher.Event.EventType.NodeCreated, "/a/b");
            assertEvent(events, Watcher.Event.EventType.NodeCreated, "/b");
            assertEvent(events, Watcher.Event.EventType.NodeCreated, "/b/c");
        }
    }

    private void assertEvent(BlockingQueue<WatchedEvent> events, Watcher.Event.EventType eventType, String path)
            throws InterruptedException {
        WatchedEvent event = events.poll(5, TimeUnit.SECONDS);
        assertNotNull(event);
        assertEquals(eventType, event.getType());
        assertEquals(path, event.getPath());
    }
}