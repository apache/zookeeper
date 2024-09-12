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

package org.apache.zookeeper.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.Test;

public class ZooKeeperBuilderTest extends ClientBase {
    private void testClient(BlockingQueue<WatchedEvent> events, ZooKeeper zk) throws Exception {
        zk.exists("/test", true);
        zk.create("/test", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        Thread.sleep(100);
        zk.close();

        WatchedEvent connected = events.poll(10, TimeUnit.SECONDS);
        assertNotNull(connected);
        assertEquals(Watcher.Event.EventType.None, connected.getType());
        assertEquals(Watcher.Event.KeeperState.SyncConnected, connected.getState());

        WatchedEvent created = events.poll(10, TimeUnit.SECONDS);
        assertNotNull(created);
        assertEquals(Watcher.Event.EventType.NodeCreated, created.getType());
        assertEquals("/test", created.getPath());

        // A sleep(100) before disconnect approve that events receiving in closing is indeterminate,
        // but the last should be closed. See ZOOKEEPER-4702.
        WatchedEvent closed = null;
        long timeoutMs = TimeUnit.SECONDS.toMillis(10);
        long deadlineMs = Time.currentElapsedTime() + timeoutMs;
        while (timeoutMs > 0 && (closed == null || closed.getState() != Watcher.Event.KeeperState.Closed)) {
            WatchedEvent event = events.poll(10, TimeUnit.SECONDS);
            if (event != null) {
                closed = event;
            }
            timeoutMs = deadlineMs - Time.currentElapsedTime();
        }
        assertNotNull(closed);
        assertEquals(Watcher.Event.EventType.None, closed.getType());
        assertEquals(Watcher.Event.KeeperState.Closed, closed.getState());
    }

    @Test
    public void testBuildClient() throws Exception {
        BlockingQueue<WatchedEvent> events = new LinkedBlockingQueue<>();
        ZooKeeper zk = new ZooKeeperBuilder(hostPort, 1000)
            .withDefaultWatcher(events::offer)
            .build();
        testClient(events, zk);
    }

    @Test
    public void testBuildAdminClient() throws Exception {
        BlockingQueue<WatchedEvent> events = new LinkedBlockingQueue<>();
        ZooKeeper zk = new ZooKeeperBuilder(hostPort, 1000)
            .withDefaultWatcher(events::offer)
            .buildAdmin();
        testClient(events, zk);
    }
}
