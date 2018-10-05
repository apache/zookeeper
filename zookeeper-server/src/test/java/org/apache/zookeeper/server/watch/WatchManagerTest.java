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

import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.server.DumbWatcher;
import org.apache.zookeeper.server.ServerCnxn;

import org.apache.zookeeper.ZKTestCase;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(Parameterized.class)
public class WatchManagerTest extends ZKTestCase {
    protected static final Logger LOG = LoggerFactory.getLogger(WatchManagerTest.class);

    private static final String PATH_PREFIX = "path";

    private ConcurrentHashMap<Integer, DumbWatcher> watchers;
    private Random r;
    private String className;

    public WatchManagerTest(String className) {
        this.className = className;
    }

    @Parameterized.Parameters
    public static List<Object []> data() {
        return Arrays.asList(new Object [][] {
            {WatchManager.class.getName()},
            {WatchManagerOptimized.class.getName()}
        });
    }

    @Before
    public void setUp() {
        watchers = new ConcurrentHashMap<Integer, DumbWatcher>();
        r = new Random(System.nanoTime());
    }

    public IWatchManager getWatchManager() throws IOException {
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

        public AddWatcherWorker(IWatchManager manager,
                int paths, int watchers, AtomicInteger watchesAdded) {
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

        public WatcherTriggerWorker(IWatchManager manager,
                int paths, AtomicInteger triggeredCount) {
            this.manager = manager;
            this.paths = paths;
            this.triggeredCount = triggeredCount;
        }

        @Override
        public void run() {
            while (!stopped) {
                String path = PATH_PREFIX + r.nextInt(paths);
                WatcherOrBitSet s = manager.triggerWatch(
                        path, EventType.NodeDeleted);
                if (s != null) {
                    triggeredCount.addAndGet(s.size());
                }
                try {
                    Thread.sleep(r.nextInt(10));
                } catch (InterruptedException e) {}
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

        public RemoveWatcherWorker(IWatchManager manager,
                int paths, int watchers, AtomicInteger watchesRemoved) {
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
                } catch (InterruptedException e) {}
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

        public CreateDeadWatchersWorker(IWatchManager manager,
                int watchers, Set<Watcher> removedWatchers) {
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
                } catch (InterruptedException e) {}
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
    @Test(timeout = 90000)
    public void testAddAndTriggerWatcher() throws IOException {
        IWatchManager manager = getWatchManager();
        int paths = 1;
        int watchers = 10000;

        // 1. start 5 workers to trigger watchers on that path
        //    count all the watchers have been fired
        AtomicInteger watchTriggered = new AtomicInteger();
        List<WatcherTriggerWorker> triggerWorkers =
                new ArrayList<WatcherTriggerWorker>();
        for (int i = 0; i < 5; i++) {
            WatcherTriggerWorker worker =
                    new WatcherTriggerWorker(manager, paths, watchTriggered);
            triggerWorkers.add(worker);
            worker.start();
        }

        // 2. start 5 workers to add different watchers on the same path
        //    count all the watchers being added
        AtomicInteger watchesAdded = new AtomicInteger();
        List<AddWatcherWorker> addWorkers = new ArrayList<AddWatcherWorker>();
        for (int i = 0; i < 5; i++) {
            AddWatcherWorker worker = new AddWatcherWorker(
                    manager, paths, watchers, watchesAdded);
            addWorkers.add(worker);
            worker.start();
        }

        while (watchesAdded.get() < 100000) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {}
        }

        // 3. stop all the addWorkers
        for (AddWatcherWorker worker: addWorkers) {
            worker.shutdown();
        }

        // 4. running the trigger worker a bit longer to make sure
        //    all watchers added are fired
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {}

        // 5. stop all triggerWorkers
        for (WatcherTriggerWorker worker: triggerWorkers) {
            worker.shutdown();
        }

        // 6. make sure the total watch triggered is same as added
        Assert.assertTrue(watchesAdded.get() > 0);
        Assert.assertEquals(watchesAdded.get(), watchTriggered.get());
    }

    /**
     * Concurrently add and remove watch, make sure the watches left +
     * the watches removed are equal to the total added watches.
     */
    @Test(timeout = 90000)
    public void testRemoveWatcherOnPath() throws IOException {
        IWatchManager manager = getWatchManager();
        int paths = 10;
        int watchers = 10000;

        // 1. start 5 workers to remove watchers on those path
        //    record the watchers have been removed
        AtomicInteger watchesRemoved = new AtomicInteger();
        List<RemoveWatcherWorker> removeWorkers =
                new ArrayList<RemoveWatcherWorker>();
        for (int i = 0; i < 5; i++) {
            RemoveWatcherWorker worker =
                    new RemoveWatcherWorker(manager, paths, watchers, watchesRemoved);
            removeWorkers.add(worker);
            worker.start();
        }

        // 2. start 5 workers to add different watchers on different path
        //    record the watchers have been added
        AtomicInteger watchesAdded = new AtomicInteger();
        List<AddWatcherWorker> addWorkers = new ArrayList<AddWatcherWorker>();
        for (int i = 0; i < 5; i++) {
            AddWatcherWorker worker = new AddWatcherWorker(
                    manager, paths, watchers, watchesAdded);
            addWorkers.add(worker);
            worker.start();
        }

        while (watchesAdded.get() < 100000) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {}
        }

        // 3. stop all workers
        for (RemoveWatcherWorker worker: removeWorkers) {
            worker.shutdown();
        }
        for (AddWatcherWorker worker: addWorkers) {
            worker.shutdown();
        }

        // 4. sleep for a while to make sure all the thread exited
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {}

        // 5. make sure left watches + removed watches = added watches
        Assert.assertTrue(watchesAdded.get() > 0);
        Assert.assertTrue(watchesRemoved.get() > 0);
        Assert.assertTrue(manager.size() > 0);
        Assert.assertEquals(
                watchesAdded.get(), watchesRemoved.get() + manager.size());
    }

    /**
     * Concurrently add watch while close the watcher to simulate the
     * client connections closed on prod.
     */
    @Test(timeout = 90000)
    public void testDeadWatchers() throws IOException {
        System.setProperty("zookeeper.watcherCleanThreshold", "10");
        System.setProperty("zookeeper.watcherCleanIntervalInSeconds", "1");

        IWatchManager manager = getWatchManager();
        int paths = 1;
        int watchers = 100000;

        // 1. start 5 workers to randomly mark those watcher as dead
        //    and remove them from watch manager
        Set<Watcher> deadWatchers = new HashSet<Watcher>();
        List<CreateDeadWatchersWorker> deadWorkers =
                new ArrayList<CreateDeadWatchersWorker>();
        for (int i = 0; i < 5; i++) {
            CreateDeadWatchersWorker worker = new CreateDeadWatchersWorker(
                    manager, watchers, deadWatchers);
            deadWorkers.add(worker);
            worker.start();
        }

        // 2. start 5 workers to add different watchers on the same path
        AtomicInteger watchesAdded = new AtomicInteger();
        List<AddWatcherWorker> addWorkers = new ArrayList<AddWatcherWorker>();
        for (int i = 0; i < 5; i++) {
            AddWatcherWorker worker = new AddWatcherWorker(
                    manager, paths, watchers, watchesAdded);
            addWorkers.add(worker);
            worker.start();
        }

        while (watchesAdded.get() < 50000) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {}
        }

        // 3. stop all workers
        for (CreateDeadWatchersWorker worker: deadWorkers) {
            worker.shutdown();
        }
        for (AddWatcherWorker worker: addWorkers) {
            worker.shutdown();
        }

        // 4. sleep for a while to make sure all the thread exited
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {}

        // 5. make sure the dead watchers are not in the existing watchers
        WatchesReport existingWatchers = manager.getWatches();
        for (Watcher w: deadWatchers) {
            Assert.assertFalse(
                    existingWatchers.hasPaths(((ServerCnxn) w).getSessionId()));
        }
    }
}
