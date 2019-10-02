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

package org.apache.zookeeper.test;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.server.watch.WatchManager;
import org.apache.zookeeper.server.watch.WatcherMode;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class RecursiveWatchQtyTest {
    private WatchManager watchManager;

    private static class DummyWatcher implements Watcher {
        @Override
        public void process(WatchedEvent event) {
            // NOP
        }
    }

    @Before
    public void setup() {
        watchManager = new WatchManager();
    }

    @Test
    public void testAddRemove() {
        Watcher watcher1 = new DummyWatcher();
        Watcher watcher2 = new DummyWatcher();

        watchManager.addWatch("/a", watcher1, WatcherMode.PERSISTENT_RECURSIVE);
        watchManager.addWatch("/b", watcher2, WatcherMode.PERSISTENT_RECURSIVE);
        Assert.assertEquals(2, watchManager.getRecursiveWatchQty());
        Assert.assertTrue(watchManager.removeWatcher("/a", watcher1));
        Assert.assertTrue(watchManager.removeWatcher("/b", watcher2));
        Assert.assertEquals(0, watchManager.getRecursiveWatchQty());
    }

    @Test
    public void testAddRemoveAlt() {
        Watcher watcher1 = new DummyWatcher();
        Watcher watcher2 = new DummyWatcher();

        watchManager.addWatch("/a", watcher1, WatcherMode.PERSISTENT_RECURSIVE);
        watchManager.addWatch("/b", watcher2, WatcherMode.PERSISTENT_RECURSIVE);
        Assert.assertEquals(2, watchManager.getRecursiveWatchQty());
        watchManager.removeWatcher(watcher1);
        watchManager.removeWatcher(watcher2);
        Assert.assertEquals(0, watchManager.getRecursiveWatchQty());
    }

    @Test
    public void testDoubleAdd() {
        Watcher watcher = new DummyWatcher();

        watchManager.addWatch("/a", watcher, WatcherMode.PERSISTENT_RECURSIVE);
        watchManager.addWatch("/a", watcher, WatcherMode.PERSISTENT_RECURSIVE);
        Assert.assertEquals(1, watchManager.getRecursiveWatchQty());
        watchManager.removeWatcher(watcher);
        Assert.assertEquals(0, watchManager.getRecursiveWatchQty());
    }

    @Test
    public void testSameWatcherMultiPath() {
        Watcher watcher = new DummyWatcher();

        watchManager.addWatch("/a", watcher, WatcherMode.PERSISTENT_RECURSIVE);
        watchManager.addWatch("/a/b", watcher, WatcherMode.PERSISTENT_RECURSIVE);
        watchManager.addWatch("/a/b/c", watcher, WatcherMode.PERSISTENT_RECURSIVE);
        Assert.assertEquals(3, watchManager.getRecursiveWatchQty());
        Assert.assertTrue(watchManager.removeWatcher("/a/b", watcher));
        Assert.assertEquals(2, watchManager.getRecursiveWatchQty());
        watchManager.removeWatcher(watcher);
        Assert.assertEquals(0, watchManager.getRecursiveWatchQty());
    }

    @Test
    public void testChangeType() {
        Watcher watcher = new DummyWatcher();

        watchManager.addWatch("/a", watcher, WatcherMode.PERSISTENT);
        Assert.assertEquals(0, watchManager.getRecursiveWatchQty());
        watchManager.addWatch("/a", watcher, WatcherMode.PERSISTENT_RECURSIVE);
        Assert.assertEquals(1, watchManager.getRecursiveWatchQty());
        watchManager.addWatch("/a", watcher, WatcherMode.STANDARD);
        Assert.assertEquals(0, watchManager.getRecursiveWatchQty());
        Assert.assertTrue(watchManager.removeWatcher("/a", watcher));
        Assert.assertEquals(0, watchManager.getRecursiveWatchQty());
    }
}