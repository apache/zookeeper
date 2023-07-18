/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.apache.zookeeper.server.DumbWatcher;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ServerMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WatchManagerTest extends ZKTestCase {

    protected static final Logger LOG = LoggerFactory.getLogger(WatchManagerTest.class);

    private static final String PATH_PREFIX = "/path";

    private ConcurrentHashMap<Integer, DumbWatcher> watchers;
    private Random r;

    public static Stream<Arguments> data() {
        return Stream.of(
            Arguments.of(WatchManager.class.getName()),
            Arguments.of(WatchManagerOptimized.class.getName()));
    }

    @BeforeEach
    public void setUp() {
        ServerMetrics.getMetrics().resetAll();
        watchers = new ConcurrentHashMap<>();
        r = new Random(System.nanoTime());
    }

    public IWatchManager getWatchManager(String className) throws IOException {
        System.setProperty(WatchManagerFactory.ZOOKEEPER_WATCH_MANAGER_NAME, className);
        return WatchManagerFactory.createWatchManager();
    }

    public DumbWatcher createOrGetWatcher(int watcherId) {
        if (!watchers.containsKey(watcherId)) {
            DumbWatcher watcher = new DumbWatcher(watcherId);
            watchers.putIfAbsent(watcherId, watcher);
        }
        return watchers.get(watcherId);
    }

    public class AddWatcherWorker extends Thread {

        private final IWatchManager manager;
        private final int paths;
        private final int watchers;
        private final AtomicInteger watchesAdded;
        private volatile boolean stopped = false;

        public AddWatcherWorker(
                IWatchManager manager, int paths, int watchers, AtomicInteger watchesAdded) {
            this.manager = manager;
            this.paths = paths;
            this.watchers = watchers;
            this.watchesAdded = watchesAdded;
        }

        @Override
        public void run() {
            while (!stopped) {
                String path = PATH_PREFIX + r.nextInt(paths);
                Watcher watcher = createOrGetWatcher(r.nextInt(watchers));
                if (manager.addWatch(path, watcher)) {
                    watchesAdded.addAndGet(1);
                }
            }
        }

        public void shutdown() {
            stopped = true;
        }

    }

    public class WatcherTriggerWorker extends Thread {

        private final IWatchManager manager;
        private final int paths;
        private final AtomicInteger triggeredCount;
        private volatile boolean stopped = false;

        public WatcherTriggerWorker(
                IWatchManager manager, int paths, AtomicInteger triggeredCount) {
            this.manager = manager;
            this.paths = paths;
            this.triggeredCount = triggeredCount;
        }

        @Override
        public void run() {
            while (!stopped) {
                String path = PATH_PREFIX + r.nextInt(paths);
                WatcherOrBitSet s = manager.triggerWatch(path, EventType.NodeDeleted, -1);
                if (s != null) {
                    triggeredCount.addAndGet(s.size());
                }
                try {
                    Thread.sleep(r.nextInt(10));
                } catch (InterruptedException e) {
                }
            }
        }

        public void shutdown() {
            stopped = true;
        }

    }

    public class RemoveWatcherWorker extends Thread {

        private final IWatchManager manager;
        private final int paths;
        private final int watchers;
        private final AtomicInteger watchesRemoved;
        private volatile boolean stopped = false;

        public RemoveWatcherWorker(
                IWatchManager manager, int paths, int watchers, AtomicInteger watchesRemoved) {
            this.manager = manager;
            this.paths = paths;
            this.watchers = watchers;
            this.watchesRemoved = watchesRemoved;
        }

        @Override
        public void run() {
            while (!stopped) {
                String path = PATH_PREFIX + r.nextInt(paths);
                Watcher watcher = createOrGetWatcher(r.nextInt(watchers));
                if (manager.removeWatcher(path, watcher)) {
                    watchesRemoved.addAndGet(1);
                }
                try {
                    Thread.sleep(r.nextInt(10));
                } catch (InterruptedException e) {
                }
            }
        }

        public void shutdown() {
            stopped = true;
        }

    }

    public class CreateDeadWatchersWorker extends Thread {

        private final IWatchManager manager;
        private final int watchers;
        private final Set<Watcher> removedWatchers;
        private volatile boolean stopped = false;

        public CreateDeadWatchersWorker(
                IWatchManager manager, int watchers, Set<Watcher> removedWatchers) {
            this.manager = manager;
            this.watchers = watchers;
            this.removedWatchers = removedWatchers;
        }

        @Override
        public void run() {
            while (!stopped) {
                DumbWatcher watcher = createOrGetWatcher(r.nextInt(watchers));
                watcher.setStale();
                manager.removeWatcher(watcher);
                synchronized (removedWatchers) {
                    removedWatchers.add(watcher);
                }
                try {
                    Thread.sleep(r.nextInt(10));
                } catch (InterruptedException e) {
                }
            }
        }

        public void shutdown() {
            stopped = true;
        }

    }

    /**
     * Concurrently add and trigger watch, make sure the watches triggered
     * are the same as the number added.
     */
    @ParameterizedTest
    @MethodSource("data")
    @Timeout(value = 90)
    public void testAddAndTriggerWatcher(String className) throws IOException {
        IWatchManager manager = getWatchManager(className);
        int paths = 1;
        int watchers = 10000;

        // 1. start 5 workers to trigger watchers on that path
        //    count all the watchers have been fired
        AtomicInteger watchTriggered = new AtomicInteger();
        List<WatcherTriggerWorker> triggerWorkers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            WatcherTriggerWorker worker = new WatcherTriggerWorker(manager, paths, watchTriggered);
            triggerWorkers.add(worker);
            worker.start();
        }

        // 2. start 5 workers to add different watchers on the same path
        //    count all the watchers being added
        AtomicInteger watchesAdded = new AtomicInteger();
        List<AddWatcherWorker> addWorkers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            AddWatcherWorker worker = new AddWatcherWorker(manager, paths, watchers, watchesAdded);
            addWorkers.add(worker);
            worker.start();
        }

        while (watchesAdded.get() < 100000) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
            }
        }

        // 3. stop all the addWorkers
        for (AddWatcherWorker worker : addWorkers) {
            worker.shutdown();
        }

        // 4. running the trigger worker a bit longer to make sure
        //    all watchers added are fired
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
        }

        // 5. stop all triggerWorkers
        for (WatcherTriggerWorker worker : triggerWorkers) {
            worker.shutdown();
        }

        // 6. make sure the total watch triggered is same as added
        assertTrue(watchesAdded.get() > 0);
        assertEquals(watchesAdded.get(), watchTriggered.get());
    }

    /**
     * Concurrently add and remove watch, make sure the watches left +
     * the watches removed are equal to the total added watches.
     */
    @ParameterizedTest
    @MethodSource("data")
    @Timeout(value = 90)
    public void testRemoveWatcherOnPath(String className) throws IOException {
        IWatchManager manager = getWatchManager(className);
        int paths = 10;
        int watchers = 10000;

        // 1. start 5 workers to remove watchers on those path
        //    record the watchers have been removed
        AtomicInteger watchesRemoved = new AtomicInteger();
        List<RemoveWatcherWorker> removeWorkers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            RemoveWatcherWorker worker = new RemoveWatcherWorker(manager, paths, watchers, watchesRemoved);
            removeWorkers.add(worker);
            worker.start();
        }

        // 2. start 5 workers to add different watchers on different path
        //    record the watchers have been added
        AtomicInteger watchesAdded = new AtomicInteger();
        List<AddWatcherWorker> addWorkers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            AddWatcherWorker worker = new AddWatcherWorker(manager, paths, watchers, watchesAdded);
            addWorkers.add(worker);
            worker.start();
        }

        while (watchesAdded.get() < 100000) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
            }
        }

        // 3. stop all workers
        for (RemoveWatcherWorker worker : removeWorkers) {
            worker.shutdown();
        }
        for (AddWatcherWorker worker : addWorkers) {
            worker.shutdown();
        }

        // 4. sleep for a while to make sure all the thread exited
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
        }

        // 5. make sure left watches + removed watches = added watches
        assertTrue(watchesAdded.get() > 0);
        assertTrue(watchesRemoved.get() > 0);
        assertTrue(manager.size() > 0);
        assertEquals(watchesAdded.get(), watchesRemoved.get() + manager.size());
    }

    /**
     * Test add, contains and remove on generic watch manager.
     */
    @ParameterizedTest
    @MethodSource("data")
    public void testAddRemoveWatcher(String className) throws IOException {
        IWatchManager manager = getWatchManager(className);
        Watcher watcher1 = new DumbWatcher();
        Watcher watcher2 = new DumbWatcher();

        // given: add watcher1 to "/node1"
        manager.addWatch("/node1", watcher1);

        // then: contains or remove should fail on mismatch path and watcher pair
        assertFalse(manager.containsWatcher("/node1", watcher2));
        assertFalse(manager.containsWatcher("/node2", watcher1));
        assertFalse(manager.removeWatcher("/node1", watcher2));
        assertFalse(manager.removeWatcher("/node2", watcher1));

        // then: contains or remove should succeed on matching path and watcher pair
        assertTrue(manager.containsWatcher("/node1", watcher1));
        assertTrue(manager.removeWatcher("/node1", watcher1));

        // then: contains or remove should fail on removed path and watcher pair
        assertFalse(manager.containsWatcher("/node1", watcher1));
        assertFalse(manager.removeWatcher("/node1", watcher1));
    }

    /**
     * Test containsWatcher on all pairs, and removeWatcher on mismatch pairs.
     */
    @Test
    public void testContainsMode() {
        IWatchManager manager = new WatchManager();
        Watcher watcher1 = new DumbWatcher();
        Watcher watcher2 = new DumbWatcher();

        // given: add watcher1 to "/node1" in persistent mode
        assertTrue(manager.addWatch("/node1", watcher1, WatcherMode.PERSISTENT));
        assertNotEquals(WatcherMode.PERSISTENT, WatcherMode.DEFAULT_WATCHER_MODE);

        // then: contains should succeed on watcher1 to "/node1" in persistent and any mode
        assertTrue(manager.containsWatcher("/node1", watcher1));
        assertTrue(manager.containsWatcher("/node1", watcher1, null));
        assertTrue(manager.containsWatcher("/node1", watcher1, WatcherMode.PERSISTENT));

        // then: contains and remove should fail on mismatch watcher
        assertFalse(manager.containsWatcher("/node1", watcher2));
        assertFalse(manager.containsWatcher("/node1", watcher2, null));
        assertFalse(manager.containsWatcher("/node1", watcher2, WatcherMode.STANDARD));
        assertFalse(manager.containsWatcher("/node1", watcher2, WatcherMode.PERSISTENT));
        assertFalse(manager.containsWatcher("/node1", watcher2, WatcherMode.PERSISTENT_RECURSIVE));
        assertFalse(manager.removeWatcher("/node1", watcher2));
        assertFalse(manager.removeWatcher("/node1", watcher2, null));
        assertFalse(manager.removeWatcher("/node1", watcher2, WatcherMode.STANDARD));
        assertFalse(manager.removeWatcher("/node1", watcher2, WatcherMode.PERSISTENT));
        assertFalse(manager.removeWatcher("/node1", watcher2, WatcherMode.PERSISTENT_RECURSIVE));

        // then: contains and remove should fail on mismatch path
        assertFalse(manager.containsWatcher("/node2", watcher1));
        assertFalse(manager.containsWatcher("/node2", watcher1, null));
        assertFalse(manager.containsWatcher("/node2", watcher1, WatcherMode.STANDARD));
        assertFalse(manager.containsWatcher("/node2", watcher1, WatcherMode.PERSISTENT));
        assertFalse(manager.containsWatcher("/node2", watcher1, WatcherMode.PERSISTENT_RECURSIVE));
        assertFalse(manager.removeWatcher("/node2", watcher1));
        assertFalse(manager.removeWatcher("/node2", watcher1, null));
        assertFalse(manager.removeWatcher("/node2", watcher1, WatcherMode.STANDARD));
        assertFalse(manager.removeWatcher("/node2", watcher1, WatcherMode.PERSISTENT));
        assertFalse(manager.removeWatcher("/node2", watcher1, WatcherMode.PERSISTENT_RECURSIVE));

        // then: contains and remove should fail on mismatch modes
        assertFalse(manager.containsWatcher("/node1", watcher1, WatcherMode.STANDARD));
        assertFalse(manager.containsWatcher("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));
        assertFalse(manager.removeWatcher("/node1", watcher1, WatcherMode.STANDARD));
        assertFalse(manager.removeWatcher("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));

        // when: add watcher1 to "/node1" in remaining modes
        assertTrue(manager.addWatch("/node1", watcher1, WatcherMode.STANDARD));
        assertTrue(manager.addWatch("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));

        // then: contains should succeed on watcher to "/node1" in all modes
        assertTrue(manager.containsWatcher("/node1", watcher1));
        assertTrue(manager.containsWatcher("/node1", watcher1, null));
        assertTrue(manager.containsWatcher("/node1", watcher1, WatcherMode.STANDARD));
        assertTrue(manager.containsWatcher("/node1", watcher1, WatcherMode.PERSISTENT));
        assertTrue(manager.containsWatcher("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));
    }

    /**
     * Test repeatedly {@link WatchManager#addWatch(String, Watcher, WatcherMode)}.
     */
    @Test
    public void testAddModeRepeatedly() {
        IWatchManager manager = new WatchManager();
        Watcher watcher1 = new DumbWatcher();

        // given: add watcher1 to "/node1" in all modes
        manager.addWatch("/node1", watcher1, WatcherMode.STANDARD);
        manager.addWatch("/node1", watcher1, WatcherMode.PERSISTENT);
        manager.addWatch("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE);

        // when: add watcher1 to "/node1" in these modes repeatedly
        assertFalse(manager.addWatch("/node1", watcher1, WatcherMode.STANDARD));
        assertFalse(manager.addWatch("/node1", watcher1, WatcherMode.PERSISTENT));
        assertFalse(manager.addWatch("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));

        // then: contains and remove should work normally on watcher1 to "/node1"
        assertTrue(manager.containsWatcher("/node1", watcher1));
        assertTrue(manager.containsWatcher("/node1", watcher1, null));
        assertTrue(manager.containsWatcher("/node1", watcher1, WatcherMode.STANDARD));
        assertTrue(manager.removeWatcher("/node1", watcher1, WatcherMode.STANDARD));
        assertFalse(manager.containsWatcher("/node1", watcher1, WatcherMode.STANDARD));
        assertFalse(manager.removeWatcher("/node1", watcher1, WatcherMode.STANDARD));

        assertTrue(manager.containsWatcher("/node1", watcher1));
        assertTrue(manager.containsWatcher("/node1", watcher1, null));
        assertTrue(manager.containsWatcher("/node1", watcher1, WatcherMode.PERSISTENT));
        assertTrue(manager.removeWatcher("/node1", watcher1, WatcherMode.PERSISTENT));
        assertFalse(manager.containsWatcher("/node1", watcher1, WatcherMode.PERSISTENT));
        assertFalse(manager.removeWatcher("/node1", watcher1, WatcherMode.PERSISTENT));

        assertTrue(manager.containsWatcher("/node1", watcher1));
        assertTrue(manager.containsWatcher("/node1", watcher1, null));
        assertTrue(manager.containsWatcher("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));
        assertTrue(manager.removeWatcher("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));
        assertFalse(manager.containsWatcher("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));
        assertFalse(manager.removeWatcher("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));

        assertFalse(manager.containsWatcher("/node1", watcher1));
        assertFalse(manager.containsWatcher("/node1", watcher1, null));
        assertFalse(manager.removeWatcher("/node1", watcher1));
        assertFalse(manager.removeWatcher("/node1", watcher1, null));
    }

    /**
     * Test {@link WatchManager#removeWatcher(String, Watcher, WatcherMode)} on one pair should not break others.
     */
    @Test
    public void testRemoveModeOne() {
        IWatchManager manager = new WatchManager();
        Watcher watcher1 = new DumbWatcher();
        Watcher watcher2 = new DumbWatcher();

        // given: add watcher1 to "/node1" and watcher2 to "/node2" in all modes
        assertTrue(manager.addWatch("/node1", watcher1, WatcherMode.STANDARD));
        assertTrue(manager.addWatch("/node1", watcher1, WatcherMode.PERSISTENT));
        assertTrue(manager.addWatch("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));
        assertTrue(manager.addWatch("/node2", watcher2, WatcherMode.STANDARD));
        assertTrue(manager.addWatch("/node2", watcher2, WatcherMode.PERSISTENT));
        assertTrue(manager.addWatch("/node2", watcher2, WatcherMode.PERSISTENT_RECURSIVE));

        // when: remove one pair
        assertTrue(manager.removeWatcher("/node1", watcher1, WatcherMode.STANDARD));

        // then: contains and remove should succeed on other pairs
        assertTrue(manager.containsWatcher("/node1", watcher1, WatcherMode.PERSISTENT));
        assertTrue(manager.containsWatcher("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));
        assertTrue(manager.containsWatcher("/node2", watcher2, WatcherMode.STANDARD));
        assertTrue(manager.containsWatcher("/node2", watcher2, WatcherMode.PERSISTENT));
        assertTrue(manager.containsWatcher("/node2", watcher2, WatcherMode.PERSISTENT_RECURSIVE));
        assertTrue(manager.removeWatcher("/node1", watcher1, WatcherMode.PERSISTENT));
        assertTrue(manager.removeWatcher("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));
        assertTrue(manager.removeWatcher("/node2", watcher2, WatcherMode.STANDARD));
        assertTrue(manager.removeWatcher("/node2", watcher2, WatcherMode.PERSISTENT));
        assertTrue(manager.removeWatcher("/node2", watcher2, WatcherMode.PERSISTENT_RECURSIVE));
    }

    /**
     * Test {@link WatchManager#removeWatcher(String, Watcher, WatcherMode)} with {@code null} watcher mode.
     */
    @Test
    public void testRemoveModeAll() {
        IWatchManager manager = new WatchManager();
        Watcher watcher1 = new DumbWatcher();

        // given: add watcher1 to "/node1" in all modes
        assertTrue(manager.addWatch("/node1", watcher1, WatcherMode.STANDARD));
        assertTrue(manager.addWatch("/node1", watcher1, WatcherMode.PERSISTENT));
        assertTrue(manager.addWatch("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));

        // when: remove watcher1 using null watcher mode
        assertTrue(manager.removeWatcher("/node1", watcher1, null));

        // then: contains and remove should fail on watcher1 to "/node1" in all modes
        assertFalse(manager.containsWatcher("/node1", watcher1));
        assertFalse(manager.containsWatcher("/node1", watcher1, null));
        assertFalse(manager.containsWatcher("/node1", watcher1, WatcherMode.STANDARD));
        assertFalse(manager.containsWatcher("/node1", watcher1, WatcherMode.PERSISTENT));
        assertFalse(manager.containsWatcher("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));
        assertFalse(manager.removeWatcher("/node1", watcher1));
        assertFalse(manager.removeWatcher("/node1", watcher1, null));
        assertFalse(manager.removeWatcher("/node1", watcher1, WatcherMode.STANDARD));
        assertFalse(manager.removeWatcher("/node1", watcher1, WatcherMode.PERSISTENT));
        assertFalse(manager.removeWatcher("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));

        // given: add watcher1 to "/node1" in all modes
        assertTrue(manager.addWatch("/node1", watcher1, WatcherMode.STANDARD));
        assertTrue(manager.addWatch("/node1", watcher1, WatcherMode.PERSISTENT));
        assertTrue(manager.addWatch("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));

        // then: remove watcher1 without a mode should behave same to removing all modes
        assertTrue(manager.removeWatcher("/node1", watcher1));

        assertFalse(manager.containsWatcher("/node1", watcher1));
        assertFalse(manager.containsWatcher("/node1", watcher1, null));
        assertFalse(manager.containsWatcher("/node1", watcher1, WatcherMode.STANDARD));
        assertFalse(manager.containsWatcher("/node1", watcher1, WatcherMode.PERSISTENT));
        assertFalse(manager.containsWatcher("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));
        assertFalse(manager.removeWatcher("/node1", watcher1));
        assertFalse(manager.removeWatcher("/node1", watcher1, null));
        assertFalse(manager.removeWatcher("/node1", watcher1, WatcherMode.STANDARD));
        assertFalse(manager.removeWatcher("/node1", watcher1, WatcherMode.PERSISTENT));
        assertFalse(manager.removeWatcher("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));
    }

    /**
     * Test {@link WatchManager#removeWatcher(String, Watcher)}.
     */
    @Test
    public void testRemoveModeAllDefault() {
        IWatchManager manager = new WatchManager();
        Watcher watcher1 = new DumbWatcher();

        // given: add watcher1 to "/node1" in all modes
        assertTrue(manager.addWatch("/node1", watcher1, WatcherMode.STANDARD));
        assertTrue(manager.addWatch("/node1", watcher1, WatcherMode.PERSISTENT));
        assertTrue(manager.addWatch("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));

        // then: remove watcher1 without a mode should behave same to removing all modes
        assertTrue(manager.removeWatcher("/node1", watcher1));

        assertFalse(manager.containsWatcher("/node1", watcher1));
        assertFalse(manager.containsWatcher("/node1", watcher1, null));
        assertFalse(manager.containsWatcher("/node1", watcher1, WatcherMode.STANDARD));
        assertFalse(manager.containsWatcher("/node1", watcher1, WatcherMode.PERSISTENT));
        assertFalse(manager.containsWatcher("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));
        assertFalse(manager.removeWatcher("/node1", watcher1));
        assertFalse(manager.removeWatcher("/node1", watcher1, null));
        assertFalse(manager.removeWatcher("/node1", watcher1, WatcherMode.STANDARD));
        assertFalse(manager.removeWatcher("/node1", watcher1, WatcherMode.PERSISTENT));
        assertFalse(manager.removeWatcher("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));
    }

    /**
     * Test {@link WatchManager#removeWatcher(String, Watcher, WatcherMode)} all modes individually.
     */
    @Test
    public void testRemoveModeAllIndividually() {
        IWatchManager manager = new WatchManager();
        Watcher watcher1 = new DumbWatcher();

        // given: add watcher1 to "/node1" in all modes
        assertTrue(manager.addWatch("/node1", watcher1, WatcherMode.STANDARD));
        assertTrue(manager.addWatch("/node1", watcher1, WatcherMode.PERSISTENT));
        assertTrue(manager.addWatch("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));

        // when: remove all modes individually
        assertTrue(manager.removeWatcher("/node1", watcher1, WatcherMode.STANDARD));
        assertTrue(manager.removeWatcher("/node1", watcher1, WatcherMode.PERSISTENT));
        assertTrue(manager.removeWatcher("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));

        // then: contains and remove should fail on watcher1 to "/node1" in all modes
        assertFalse(manager.containsWatcher("/node1", watcher1));
        assertFalse(manager.containsWatcher("/node1", watcher1, null));
        assertFalse(manager.containsWatcher("/node1", watcher1, WatcherMode.STANDARD));
        assertFalse(manager.containsWatcher("/node1", watcher1, WatcherMode.PERSISTENT));
        assertFalse(manager.containsWatcher("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));
        assertFalse(manager.removeWatcher("/node1", watcher1));
        assertFalse(manager.removeWatcher("/node1", watcher1, null));
        assertFalse(manager.removeWatcher("/node1", watcher1, WatcherMode.STANDARD));
        assertFalse(manager.removeWatcher("/node1", watcher1, WatcherMode.PERSISTENT));
        assertFalse(manager.removeWatcher("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));
    }

    /**
     * Test {@link WatchManager#removeWatcher(String, Watcher, WatcherMode)} on mismatch pair should break nothing.
     */
    @Test
    public void testRemoveModeMismatch() {
        IWatchManager manager = new WatchManager();
        Watcher watcher1 = new DumbWatcher();
        Watcher watcher2 = new DumbWatcher();

        // given: add watcher1 to "/node1" and watcher2 to "/node2" in all modes
        assertTrue(manager.addWatch("/node1", watcher1, WatcherMode.STANDARD));
        assertTrue(manager.addWatch("/node1", watcher1, WatcherMode.PERSISTENT));
        assertTrue(manager.addWatch("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));
        assertTrue(manager.addWatch("/node2", watcher2, WatcherMode.STANDARD));
        assertTrue(manager.addWatch("/node2", watcher2, WatcherMode.PERSISTENT));
        assertTrue(manager.addWatch("/node2", watcher2, WatcherMode.PERSISTENT_RECURSIVE));

        // when: remove mismatch path and watcher pairs
        assertFalse(manager.removeWatcher("/node1", watcher2));
        assertFalse(manager.removeWatcher("/node1", watcher2, null));
        assertFalse(manager.removeWatcher("/node1", watcher2, WatcherMode.STANDARD));
        assertFalse(manager.removeWatcher("/node1", watcher2, WatcherMode.PERSISTENT));
        assertFalse(manager.removeWatcher("/node1", watcher2, WatcherMode.PERSISTENT_RECURSIVE));

        // then: no existing watching pairs should break
        assertTrue(manager.containsWatcher("/node1", watcher1));
        assertTrue(manager.containsWatcher("/node1", watcher1, null));
        assertTrue(manager.containsWatcher("/node1", watcher1, WatcherMode.STANDARD));
        assertTrue(manager.containsWatcher("/node1", watcher1, WatcherMode.PERSISTENT));
        assertTrue(manager.containsWatcher("/node1", watcher1, WatcherMode.PERSISTENT_RECURSIVE));
        assertTrue(manager.containsWatcher("/node2", watcher2));
        assertTrue(manager.containsWatcher("/node2", watcher2, null));
        assertTrue(manager.containsWatcher("/node2", watcher2, WatcherMode.STANDARD));
        assertTrue(manager.containsWatcher("/node2", watcher2, WatcherMode.PERSISTENT));
        assertTrue(manager.containsWatcher("/node2", watcher2, WatcherMode.PERSISTENT_RECURSIVE));
    }

    /**
     * Concurrently add watch while close the watcher to simulate the
     * client connections closed on prod.
     */
    @ParameterizedTest
    @MethodSource("data")
    @Timeout(value = 90)
    public void testDeadWatchers(String className) throws IOException {
        System.setProperty("zookeeper.watcherCleanThreshold", "10");
        System.setProperty("zookeeper.watcherCleanIntervalInSeconds", "1");

        IWatchManager manager = getWatchManager(className);
        int paths = 1;
        int watchers = 100000;

        // 1. start 5 workers to randomly mark those watcher as dead
        //    and remove them from watch manager
        Set<Watcher> deadWatchers = new HashSet<>();
        List<CreateDeadWatchersWorker> deadWorkers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            CreateDeadWatchersWorker worker = new CreateDeadWatchersWorker(manager, watchers, deadWatchers);
            deadWorkers.add(worker);
            worker.start();
        }

        // 2. start 5 workers to add different watchers on the same path
        AtomicInteger watchesAdded = new AtomicInteger();
        List<AddWatcherWorker> addWorkers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            AddWatcherWorker worker = new AddWatcherWorker(manager, paths, watchers, watchesAdded);
            addWorkers.add(worker);
            worker.start();
        }

        while (watchesAdded.get() < 50000) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
            }
        }

        // 3. stop all workers
        for (CreateDeadWatchersWorker worker : deadWorkers) {
            worker.shutdown();
        }
        for (AddWatcherWorker worker : addWorkers) {
            worker.shutdown();
        }

        // 4. sleep for a while to make sure all the thread exited
        // the cleaner may wait as long as CleanerInterval+CleanerInterval/2+1
        // So need to sleep as least that long
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
        }

        // 5. make sure the dead watchers are not in the existing watchers
        WatchesReport existingWatchers = manager.getWatches();
        for (Watcher w : deadWatchers) {
            assertFalse(existingWatchers.hasPaths(((ServerCnxn) w).getSessionId()));
        }
    }

    private void checkMetrics(String metricName, long min, long max, double avg, long cnt, long sum) {
        Map<String, Object> values = MetricsUtils.currentServerMetrics();

        assertEquals(min, values.get("min_" + metricName));
        assertEquals(max, values.get("max_" + metricName));
        assertEquals(avg, (Double) values.get("avg_" + metricName), 0.000001);
        assertEquals(cnt, values.get("cnt_" + metricName));
        assertEquals(sum, values.get("sum_" + metricName));
    }

    private void checkMostRecentWatchedEvent(DumbWatcher watcher, String path, EventType eventType, long zxid) {
        assertEquals(path, watcher.getMostRecentPath());
        assertEquals(eventType, watcher.getMostRecentEventType());
        assertEquals(zxid, watcher.getMostRecentZxid());
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testWatcherMetrics(String className) throws IOException {
        IWatchManager manager = getWatchManager(className);
        ServerMetrics.getMetrics().resetAll();

        DumbWatcher watcher1 = new DumbWatcher(1);
        DumbWatcher watcher2 = new DumbWatcher(2);

        final String path1 = "/path1";
        final String path2 = "/path2";

        final String path3 = "/path3";

        //both watcher1 and watcher2 are watching path1
        manager.addWatch(path1, watcher1);
        manager.addWatch(path1, watcher2);

        //path2 is watched by watcher1
        manager.addWatch(path2, watcher1);

        manager.triggerWatch(path3, EventType.NodeCreated, 1);
        //path3 is not being watched so metric is 0
        checkMetrics("node_created_watch_count", 0L, 0L, 0D, 0L, 0L);
        // Watchers shouldn't have received any events yet so the zxid should be -1.
        checkMostRecentWatchedEvent(watcher1, null, null, -1);
        checkMostRecentWatchedEvent(watcher2, null, null, -1);

        //path1 is watched by two watchers so two fired
        manager.triggerWatch(path1, EventType.NodeCreated, 2);
        checkMetrics("node_created_watch_count", 2L, 2L, 2D, 1L, 2L);
        checkMostRecentWatchedEvent(watcher1, path1, EventType.NodeCreated, 2);
        checkMostRecentWatchedEvent(watcher2, path1, EventType.NodeCreated, 2);

        //path2 is watched by one watcher so one fired now total is 3
        manager.triggerWatch(path2, EventType.NodeCreated, 3);
        checkMetrics("node_created_watch_count", 1L, 2L, 1.5D, 2L, 3L);
        checkMostRecentWatchedEvent(watcher1, path2, EventType.NodeCreated, 3);
        checkMostRecentWatchedEvent(watcher2, path1, EventType.NodeCreated, 2);

        //watches on path1 are no longer there so zero fired
        manager.triggerWatch(path1, EventType.NodeDataChanged, 4);
        checkMetrics("node_changed_watch_count", 0L, 0L, 0D, 0L, 0L);
        checkMostRecentWatchedEvent(watcher1, path2, EventType.NodeCreated, 3);
        checkMostRecentWatchedEvent(watcher2, path1, EventType.NodeCreated, 2);

        //both watcher and watcher are watching path1
        manager.addWatch(path1, watcher1);
        manager.addWatch(path1, watcher2);

        //path2 is watched by watcher1
        manager.addWatch(path2, watcher1);

        manager.triggerWatch(path1, EventType.NodeDataChanged, 5);
        checkMetrics("node_changed_watch_count", 2L, 2L, 2D, 1L, 2L);
        checkMostRecentWatchedEvent(watcher1, path1, EventType.NodeDataChanged, 5);
        checkMostRecentWatchedEvent(watcher2, path1, EventType.NodeDataChanged, 5);

        manager.triggerWatch(path2, EventType.NodeDeleted, 6);
        checkMetrics("node_deleted_watch_count", 1L, 1L, 1D, 1L, 1L);
        checkMostRecentWatchedEvent(watcher1, path2, EventType.NodeDeleted, 6);
        checkMostRecentWatchedEvent(watcher2, path1, EventType.NodeDataChanged, 5);

        //make sure that node created watch count is not impacted by the fire of other event types
        checkMetrics("node_created_watch_count", 1L, 2L, 1.5D, 2L, 3L);
    }

}
