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

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.admin.ZooKeeperAdmin;
import org.apache.zookeeper.client.internal.PrivateZooKeeper;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.Test;

public class ZooKeeperBuilderTest extends ClientBase {
    public abstract static class AbstractZooKeeper extends ZooKeeper {
        public AbstractZooKeeper(ZooKeeperOptions options) throws IOException {
            super(options);
        }
    }

    public static class MismatchConstructorZooKeeper extends ZooKeeper {
        public MismatchConstructorZooKeeper(String connectString, int connectionTimeoutMs) throws IOException {
            super(connectString, connectionTimeoutMs, null);
        }
    }

    public static class ArithmeticExceptionZooKeeper extends ZooKeeper {
        public ArithmeticExceptionZooKeeper(ZooKeeperOptions options) throws Exception {
            super(options);
            throw new ArithmeticException();
        }
    }

    public static class EOFExceptionZooKeeper extends ZooKeeper {
        public EOFExceptionZooKeeper(ZooKeeperOptions options) throws IOException {
            super(options);
            throw new EOFException();
        }
    }

    public static class TimeoutExceptionZooKeeper extends ZooKeeper {
        public TimeoutExceptionZooKeeper(ZooKeeperOptions options) throws Exception {
            super(options);
            throw new TimeoutException();
        }
    }

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
        // but the last should be closed.
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
    public void testBuildZkClient() throws Exception {
        BlockingQueue<WatchedEvent> events = new LinkedBlockingQueue<>();
        ZooKeeper zk = new ZooKeeperBuilder(hostPort, 1000)
                .withDefaultWatcher(events::offer)
                .build(ZooKeeper.class);
        testClient(events, zk);
    }

    @Test
    public void testBuildAdminClient() throws Exception {
        BlockingQueue<WatchedEvent> events = new LinkedBlockingQueue<>();
        ZooKeeper zk = new ZooKeeperBuilder(hostPort, 1000)
            .withDefaultWatcher(events::offer)
            .build(ZooKeeperAdmin.class);
        testClient(events, zk);
    }

    @Test
    public void testConversionWithOptions() throws Exception {
        ZooKeeperBuilder builder = new ZooKeeperBuilder("127.0.0.1", 1000)
            .withCanBeReadOnly(true)
            .withDefaultWatcher(ignored -> {})
            .withSession(32413209, new byte[8])
            .withHostProvider(StaticHostProvider::new)
            .withClientConfig(new ZKClientConfig());
        ZooKeeperOptions options1 = builder.toOptions();
        ZooKeeperOptions options2 = options1.toBuilder().toOptions();
        Method[] methods = ZooKeeperOptions.class.getDeclaredMethods();
        for (Method method : methods) {
            if (method.getName().equals("toBuilder")) {
                continue;
            }
            Object option1 = method.invoke(options1);
            Object option2 = method.invoke(options2);
            if (method.getReturnType().isPrimitive()) {
                assertEquals(option1, option2, method.getName());
            } else {
                assertSame(option1, option2, method.getName());
            }
        }
    }

    @Test
    public void testPrivateZooKeeper() throws Exception {
        ZooKeeperBuilder builder = new ZooKeeperBuilder("127.0.0.1", 1000);
        try {
            builder.build(PrivateZooKeeper.class);
            fail("expect exception");
        } catch (IllegalArgumentException ex) {
            assertThat(ex.getCause(), instanceOf(IllegalAccessException.class));
        }
    }

    @Test
    public void testAbstractZooKeeper() throws Exception {
        ZooKeeperBuilder builder = new ZooKeeperBuilder("127.0.0.1", 1000);
        try {
            builder.build(AbstractZooKeeper.class);
            fail("expect exception");
        } catch (IllegalArgumentException ex) {
            assertThat(ex.getCause(), instanceOf(InstantiationException.class));
        }
    }

    @Test
    public void testMismatchConstructorZooKeeper() throws Exception {
        ZooKeeperBuilder builder = new ZooKeeperBuilder("127.0.0.1", 1000);
        try {
            builder.build(MismatchConstructorZooKeeper.class);
            fail("expect exception");
        } catch (IllegalArgumentException ex) {
            assertThat(ex.getCause(), instanceOf(NoSuchMethodException.class));
        }
    }

    @Test
    public void testRuntimeExceptionZooKeeper() throws Exception {
        ZooKeeperBuilder builder = new ZooKeeperBuilder("127.0.0.1", 1000);
        assertThrows(ArithmeticException.class, () -> builder.build(ArithmeticExceptionZooKeeper.class));
    }

    @Test
    public void testIOExceptionZooKeeper() throws Exception {
        ZooKeeperBuilder builder = new ZooKeeperBuilder("127.0.0.1", 1000);
        assertThrows(EOFException.class, () -> builder.build(EOFExceptionZooKeeper.class));
    }

    @Test
    public void testOtherExceptionZooKeeper() throws Exception {
        ZooKeeperBuilder builder = new ZooKeeperBuilder("127.0.0.1", 1000);
        try {
            builder.build(TimeoutExceptionZooKeeper.class);
            fail("expect exception");
        } catch (IOException ex) {
            assertThat(ex.getCause(), instanceOf(TimeoutException.class));
        }
    }
}
