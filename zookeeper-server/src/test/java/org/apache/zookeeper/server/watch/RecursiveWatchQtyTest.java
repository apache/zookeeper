/**
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

package org.apache.zookeeper.server.watch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class RecursiveWatchQtyTest {
    private WatchManager watchManager;

    private static final int clientQty = 25;
    private static final int iterations = 1000;

    private static class DummyWatcher implements Watcher {
        @Override
        public void process(WatchedEvent event) {
            // NOP
        }
    }

    @BeforeEach
    public void setup() {
        watchManager = new WatchManager();
    }

    @Test
    public void testRecursiveQty() {
        WatcherModeManager manager = new WatcherModeManager();
        DummyWatcher watcher = new DummyWatcher();
        manager.setWatcherMode(watcher, "/a", WatcherMode.DEFAULT_WATCHER_MODE);
        assertEquals(0, manager.getRecursiveQty());
        manager.setWatcherMode(watcher, "/a", WatcherMode.PERSISTENT_RECURSIVE);
        assertEquals(1, manager.getRecursiveQty());
        manager.setWatcherMode(watcher, "/a/b", WatcherMode.PERSISTENT_RECURSIVE);
        assertEquals(2, manager.getRecursiveQty());
        manager.setWatcherMode(watcher, "/a", WatcherMode.PERSISTENT_RECURSIVE);
        assertEquals(2, manager.getRecursiveQty());
        manager.setWatcherMode(watcher, "/a/b", WatcherMode.PERSISTENT);
        assertEquals(1, manager.getRecursiveQty());
        manager.setWatcherMode(watcher, "/a/b", WatcherMode.PERSISTENT_RECURSIVE);
        assertEquals(2, manager.getRecursiveQty());
        manager.setWatcherMode(watcher, "/a/b", WatcherMode.DEFAULT_WATCHER_MODE);
        assertEquals(1, manager.getRecursiveQty());
        manager.setWatcherMode(watcher, "/a", WatcherMode.PERSISTENT);
        assertEquals(0, manager.getRecursiveQty());
    }

    @Test
    public void testAddRemove() {
        Watcher watcher1 = new DummyWatcher();
        Watcher watcher2 = new DummyWatcher();

        watchManager.addWatch("/a", watcher1, WatcherMode.PERSISTENT_RECURSIVE);
        watchManager.addWatch("/b", watcher2, WatcherMode.PERSISTENT_RECURSIVE);
        assertEquals(2, watchManager.getRecursiveWatchQty());
        assertTrue(watchManager.removeWatcher("/a", watcher1));
        assertTrue(watchManager.removeWatcher("/b", watcher2));
        assertEquals(0, watchManager.getRecursiveWatchQty());
    }

    @Test
    public void testAddRemoveAlt() {
        Watcher watcher1 = new DummyWatcher();
        Watcher watcher2 = new DummyWatcher();

        watchManager.addWatch("/a", watcher1, WatcherMode.PERSISTENT_RECURSIVE);
        watchManager.addWatch("/b", watcher2, WatcherMode.PERSISTENT_RECURSIVE);
        assertEquals(2, watchManager.getRecursiveWatchQty());
        watchManager.removeWatcher(watcher1);
        watchManager.removeWatcher(watcher2);
        assertEquals(0, watchManager.getRecursiveWatchQty());
    }

    @Test
    public void testDoubleAdd() {
        Watcher watcher = new DummyWatcher();

        watchManager.addWatch("/a", watcher, WatcherMode.PERSISTENT_RECURSIVE);
        watchManager.addWatch("/a", watcher, WatcherMode.PERSISTENT_RECURSIVE);
        assertEquals(1, watchManager.getRecursiveWatchQty());
        watchManager.removeWatcher(watcher);
        assertEquals(0, watchManager.getRecursiveWatchQty());
    }

    @Test
    public void testSameWatcherMultiPath() {
        Watcher watcher = new DummyWatcher();

        watchManager.addWatch("/a", watcher, WatcherMode.PERSISTENT_RECURSIVE);
        watchManager.addWatch("/a/b", watcher, WatcherMode.PERSISTENT_RECURSIVE);
        watchManager.addWatch("/a/b/c", watcher, WatcherMode.PERSISTENT_RECURSIVE);
        assertEquals(3, watchManager.getRecursiveWatchQty());
        assertTrue(watchManager.removeWatcher("/a/b", watcher));
        assertEquals(2, watchManager.getRecursiveWatchQty());
        watchManager.removeWatcher(watcher);
        assertEquals(0, watchManager.getRecursiveWatchQty());
    }

    @Test
    public void testChangeType() {
        Watcher watcher = new DummyWatcher();

        watchManager.addWatch("/a", watcher, WatcherMode.PERSISTENT);
        assertEquals(0, watchManager.getRecursiveWatchQty());
        watchManager.addWatch("/a", watcher, WatcherMode.PERSISTENT_RECURSIVE);
        assertEquals(1, watchManager.getRecursiveWatchQty());
        watchManager.addWatch("/a", watcher, WatcherMode.STANDARD);
        assertEquals(0, watchManager.getRecursiveWatchQty());
        assertTrue(watchManager.removeWatcher("/a", watcher));
        assertEquals(0, watchManager.getRecursiveWatchQty());
    }

    @Test
    public void testRecursiveQtyConcurrency() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        WatcherModeManager manager = new WatcherModeManager();
        ExecutorService threadPool = Executors.newFixedThreadPool(clientQty);
        List<Future<?>> tasks = null;
        CountDownLatch completedLatch = new CountDownLatch(clientQty);
        try {
            tasks = IntStream.range(0, clientQty)
                    .mapToObj(__ -> threadPool.submit(() -> iterate(manager, completedLatch)))
                    .collect(Collectors.toList());
            try {
                completedLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } finally {
            if (tasks != null) {
                tasks.forEach(t -> t.cancel(true));
            }
            threadPool.shutdownNow();
        }

        int expectedRecursiveQty = (int) manager.getWatcherModes().values()
                .stream()
                .filter(mode -> mode == WatcherMode.PERSISTENT_RECURSIVE)
                .count();
        assertEquals(expectedRecursiveQty, manager.getRecursiveQty());
    }

    private void iterate(WatcherModeManager manager, CountDownLatch completedLatch) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        try {
            for (int i = 0; i < iterations; ++i) {
                String path = "/" + random.nextInt(clientQty);
                boolean doSet = random.nextInt(100) > 33;    // 2/3 will be sets
                if (doSet) {
                    WatcherMode mode = WatcherMode.values()[random.nextInt(WatcherMode.values().length)];
                    manager.setWatcherMode(new DummyWatcher(), path, mode);
                } else {
                    manager.removeWatcher(new DummyWatcher(), path);
                }

                int sleepMillis = random.nextInt(2);
                if (sleepMillis > 0) {
                    try {
                        Thread.sleep(sleepMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } finally {
            completedLatch.countDown();
        }
    }
}