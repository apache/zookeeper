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
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
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

        Stat stat = new Stat();
        zk.create("/a/b", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
        assertEvent(events, EventType.NodeCreated, "/a/b", stat);

        zk.create("/a/b/c", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
        assertEvent(events, EventType.NodeCreated, "/a/b/c", stat);

        zk.create("/a/b/c/d", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
        assertEvent(events, EventType.NodeCreated, "/a/b/c/d", stat);

        zk.create("/a/b/c/d/e", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
        assertEvent(events, EventType.NodeCreated, "/a/b/c/d/e", stat);

        stat = zk.setData("/a/b/c/d/e", new byte[0], -1);
        assertEvent(events, EventType.NodeDataChanged, "/a/b/c/d/e", stat);

        zk.delete("/a/b/c/d/e", -1);
        assertEvent(events, EventType.NodeDeleted, "/a/b/c/d/e", zk.exists("/a/b/c/d", false).getPzxid());

        zk.create("/a/b/c/d/e", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
        assertEvent(events, EventType.NodeCreated, "/a/b/c/d/e", stat);
    }

    @Test
    public void testRemoval()
            throws IOException, InterruptedException, KeeperException {
        try (ZooKeeper zk = createClient(new CountdownWatcher(), hostPort)) {
            zk.addWatch("/a/b", persistentWatcher, PERSISTENT_RECURSIVE);
            zk.create("/a", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            Stat stat = new Stat();
            zk.create("/a/b", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
            assertEvent(events, EventType.NodeCreated, "/a/b", stat);
            zk.create("/a/b/c", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
            assertEvent(events, EventType.NodeCreated, "/a/b/c", stat);

            zk.removeWatches("/a/b", persistentWatcher, Watcher.WatcherType.Any, false);
            zk.create("/a/b/c/d", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            assertEvent(events, EventType.PersistentWatchRemoved, "/a/b", WatchedEvent.NO_ZXID);
        }
    }

    @Test
    public void testNoChildEvents() throws Exception {
        try (ZooKeeper zk = createClient()) {
            zk.create("/a", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            zk.addWatch("/", persistentWatcher, PERSISTENT_RECURSIVE);

            BlockingQueue<WatchedEvent> childEvents = new LinkedBlockingQueue<>();
            zk.getChildren("/a", childEvents::add);

            Stat createABStat = new Stat();
            zk.create("/a/b", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, createABStat);
            Stat createABCStat = new Stat();
            zk.create("/a/b/c", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, createABCStat);

            assertEvent(childEvents, Watcher.Event.EventType.NodeChildrenChanged, "/a", createABStat.getPzxid());

            assertEvent(events, Watcher.Event.EventType.NodeCreated, "/a/b", createABStat);
            assertEvent(events, Watcher.Event.EventType.NodeCreated, "/a/b/c", createABCStat);
            assertTrue(events.isEmpty());
        }
    }

    @Test
    public void testDisconnect() throws Exception {
        try (ZooKeeper zk = createClient(new CountdownWatcher(), hostPort)) {
            zk.addWatch("/a/b", persistentWatcher, PERSISTENT_RECURSIVE);
            stopServer();
            assertEvent(events, EventType.None, KeeperState.Disconnected, null, WatchedEvent.NO_ZXID);
            startServer();
            assertEvent(events, EventType.None, KeeperState.SyncConnected, null, WatchedEvent.NO_ZXID);
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
            Stat stat = zk1.setData("/a/b/c", "one".getBytes(), -1);
            assertEvent(events, EventType.NodeDataChanged, "/a/b/c", stat.getMzxid());

            stat = zk2.setData("/a/b/c", "two".getBytes(), -1);
            assertEvent(events, EventType.NodeDataChanged, "/a/b/c", stat.getMzxid());
            stat = zk2.setData("/a/b/c", "three".getBytes(), -1);
            assertEvent(events, EventType.NodeDataChanged, "/a/b/c", stat.getMzxid());
            stat = zk2.setData("/a/b/c", "four".getBytes(), -1);
            assertEvent(events, EventType.NodeDataChanged, "/a/b/c", stat.getMzxid());
        }
    }

    @Test
    public void testSamePathWithDifferentWatchModes() throws Exception {
        try (ZooKeeper zk = createClient()) {
            BlockingQueue<WatchedEvent> dataEvents = new LinkedBlockingQueue<>();
            BlockingQueue<WatchedEvent> childEvents = new LinkedBlockingQueue<>();
            BlockingQueue<WatchedEvent> persistentEvents = new LinkedBlockingQueue<>();
            BlockingQueue<WatchedEvent> recursiveEvents = new LinkedBlockingQueue<>();

            zk.addWatch("/a", persistentEvents::add, PERSISTENT);
            zk.addWatch("/a", recursiveEvents::add, PERSISTENT_RECURSIVE);
            zk.exists("/a", dataEvents::add);

            Stat stat = new Stat();
            zk.create("/a", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
            assertEvent(dataEvents, Watcher.Event.EventType.NodeCreated, "/a", stat);
            assertEvent(persistentEvents, Watcher.Event.EventType.NodeCreated, "/a", stat);
            assertEvent(recursiveEvents, Watcher.Event.EventType.NodeCreated, "/a", stat);

            zk.getData("/a", dataEvents::add, null);
            stat = zk.setData("/a", new byte[0], -1);
            assertEvent(dataEvents, Watcher.Event.EventType.NodeDataChanged, "/a", stat);
            assertEvent(persistentEvents, Watcher.Event.EventType.NodeDataChanged, "/a", stat);
            assertEvent(recursiveEvents, Watcher.Event.EventType.NodeDataChanged, "/a", stat);

            zk.getChildren("/a", childEvents::add);
            zk.create("/a/b", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
            assertEvent(childEvents, Watcher.Event.EventType.NodeChildrenChanged, "/a", stat);
            assertEvent(persistentEvents, Watcher.Event.EventType.NodeChildrenChanged, "/a", stat);
            assertEvent(recursiveEvents, Watcher.Event.EventType.NodeCreated, "/a/b", stat);

            zk.getChildren("/a", childEvents::add);
            zk.delete("/a/b", -1);
            stat = zk.exists("/a", false);
            assertEvent(childEvents, Watcher.Event.EventType.NodeChildrenChanged, "/a", stat.getPzxid());
            assertEvent(persistentEvents, Watcher.Event.EventType.NodeChildrenChanged, "/a", stat.getPzxid());
            assertEvent(recursiveEvents, Watcher.Event.EventType.NodeDeleted, "/a/b", stat.getPzxid());

            zk.getChildren("/a", childEvents::add);
            zk.getData("/a", dataEvents::add, null);
            zk.exists("/a", dataEvents::add);
            zk.delete("/a", -1);
            stat = zk.exists("/", false);
            assertEvent(childEvents, Watcher.Event.EventType.NodeDeleted, "/a", stat.getPzxid());
            assertEvent(dataEvents, Watcher.Event.EventType.NodeDeleted, "/a", stat.getPzxid());
            assertEvent(dataEvents, Watcher.Event.EventType.NodeDeleted, "/a", stat.getPzxid());
            assertEvent(persistentEvents, Watcher.Event.EventType.NodeDeleted, "/a", stat.getPzxid());
            assertEvent(recursiveEvents, Watcher.Event.EventType.NodeDeleted, "/a", stat.getPzxid());
        }
    }

    @Test
    public void testRootWatcher()
            throws IOException, InterruptedException, KeeperException {
        try (ZooKeeper zk = createClient(new CountdownWatcher(), hostPort)) {
            zk.addWatch("/", persistentWatcher, PERSISTENT_RECURSIVE);
            Stat stat = new Stat();

            zk.create("/a", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
            assertEvent(events, EventType.NodeCreated, "/a", stat.getMzxid());

            zk.create("/a/b", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
            assertEvent(events, EventType.NodeCreated, "/a/b", stat.getMzxid());

            zk.create("/b", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
            assertEvent(events, EventType.NodeCreated, "/b", stat.getMzxid());

            zk.create("/b/c", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
            assertEvent(events, EventType.NodeCreated, "/b/c", stat.getMzxid());
        }
    }

    private void assertEvent(BlockingQueue<WatchedEvent> events, EventType eventType, String path, Stat stat)
        throws InterruptedException {
        assertEvent(events, eventType, path, stat.getMzxid());
    }

    private void assertEvent(BlockingQueue<WatchedEvent> events, EventType eventType, String path, long zxid)
        throws InterruptedException {
        assertEvent(events, eventType, KeeperState.SyncConnected, path, zxid);
    }

    private void assertEvent(BlockingQueue<WatchedEvent> events, EventType eventType, KeeperState keeperState,
        String path, long zxid) throws InterruptedException {
        WatchedEvent actualEvent = events.poll(5, TimeUnit.SECONDS);
        assertNotNull(actualEvent);
        WatchedEvent expectedEvent = new WatchedEvent(
            eventType,
            keeperState,
            path,
            zxid
        );
        TestUtils.assertWatchedEventEquals(expectedEvent, actualEvent);
    }
}