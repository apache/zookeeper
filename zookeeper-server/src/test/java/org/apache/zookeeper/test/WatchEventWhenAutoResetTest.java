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
import static org.junit.jupiter.api.Assertions.fail;
import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WatchEventWhenAutoResetTest extends ZKTestCase {

    protected static final Logger LOG = LoggerFactory.getLogger(WatchEventWhenAutoResetTest.class);

    // waiting time for expected condition
    private static final int TIMEOUT = 30000;
    private QuorumUtil qu;
    private EventsWatcher watcher;
    private ZooKeeper zk1, zk2;

    public static class EventsWatcher extends CountdownWatcher {

        private LinkedBlockingQueue<WatchedEvent> dataEvents = new LinkedBlockingQueue<WatchedEvent>();

        @Override
        public void process(WatchedEvent event) {
            super.process(event);
            try {
                if (event.getType() != Event.EventType.None) {
                    dataEvents.put(event);
                }
            } catch (InterruptedException e) {
                LOG.warn("ignoring interrupt during EventsWatcher process");
            }
        }

        public void assertEvent(long timeout, EventType eventType) {
            try {
                WatchedEvent event = dataEvents.poll(timeout, TimeUnit.MILLISECONDS);
                assertNotNull(event, "do not receive a " + eventType);
                assertEquals(eventType, event.getType());
            } catch (InterruptedException e) {
                LOG.warn("ignoring interrupt during EventsWatcher assertEvent");
            }
        }

    }

    private ZooKeeper createClient(QuorumUtil qu, int id, EventsWatcher watcher) throws IOException {
        String hostPort = "127.0.0.1:" + qu.getPeer(id).clientPort;
        ZooKeeper zk = new ZooKeeper(hostPort, TIMEOUT, watcher);
        try {
            watcher.waitForConnected(TIMEOUT);
        } catch (InterruptedException e) {
            // ignoring the interrupt
        } catch (TimeoutException e) {
            fail("can not connect to " + hostPort);
        }
        return zk;
    }

    private ZooKeeper createClient(QuorumUtil qu, int id) throws IOException {
        return createClient(qu, id, new EventsWatcher());
    }

    @BeforeEach
    public void setUp() throws IOException {
        System.setProperty("zookeeper.admin.enableServer", "false");

        qu = new QuorumUtil(1);
        qu.startAll();

        watcher = new EventsWatcher();
        zk1 = createClient(qu, 1, watcher);
        zk2 = createClient(qu, 2);
    }

    @AfterEach
    public void tearDown() throws InterruptedException {
        if (zk1 != null) {
            zk1.close();
            zk1 = null;
        }
        if (zk2 != null) {
            zk2.close();
            zk2 = null;
        }
        if (watcher != null) {
            watcher = null;
        }
        if (qu != null) {
            qu.shutdownAll();
            qu = null;
        }
    }

    @Test
    public void testNodeDataChanged() throws Exception {
        String path = "/test-changed";
        zk1.create(path, new byte[1], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        Stat stat1 = zk1.exists(path, watcher);
        qu.shutdown(1);
        zk2.setData(path, new byte[2], stat1.getVersion());
        qu.start(1);
        watcher.waitForConnected(TIMEOUT);
        watcher.assertEvent(TIMEOUT, EventType.NodeDataChanged);
    }

    @Test
    public void testNodeCreated() throws Exception {
        String path = "/test1-created";

        zk1.exists(path, watcher);
        qu.shutdown(1);
        zk2.create(path, new byte[2], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        qu.start(1);
        watcher.waitForConnected(TIMEOUT * 1000L);
        watcher.assertEvent(TIMEOUT, EventType.NodeCreated);
    }

    @Test
    public void testNodeDeleted() throws Exception {
        String path = "/test-deleted";

        zk1.create(path, new byte[1], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk1.getData(path, watcher, null);
        qu.shutdown(1);
        zk2.delete(path, -1);
        qu.start(1);
        watcher.waitForConnected(TIMEOUT * 1000L);
        watcher.assertEvent(TIMEOUT, EventType.NodeDeleted);

        zk1.create(path, new byte[1], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk1.exists(path, watcher);
        qu.shutdown(1);
        zk2.delete(path, -1);
        qu.start(1);
        watcher.waitForConnected(TIMEOUT * 1000L);
        watcher.assertEvent(TIMEOUT, EventType.NodeDeleted);

        zk1.create(path, new byte[1], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk1.getChildren(path, watcher);
        qu.shutdown(1);
        zk2.delete(path, -1);
        qu.start(1);
        watcher.waitForConnected(TIMEOUT * 1000L);
        watcher.assertEvent(TIMEOUT, EventType.NodeDeleted);
    }

    @Test
    public void testNodeChildrenChanged() throws Exception {
        String path = "/test-children-changed";

        zk1.create(path, new byte[1], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk1.getChildren(path, watcher);
        qu.shutdown(1);
        zk2.create(path + "/children-1", new byte[2], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        qu.start(1);
        watcher.waitForConnected(TIMEOUT * 1000L);
        watcher.assertEvent(TIMEOUT, EventType.NodeChildrenChanged);
    }

}

