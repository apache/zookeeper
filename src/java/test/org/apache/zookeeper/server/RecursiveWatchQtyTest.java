package org.apache.zookeeper.server;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
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

        watchManager.addWatch("/a", watcher1, WatchManager.Type.PERSISTENT_RECURSIVE);
        watchManager.addWatch("/b", watcher2, WatchManager.Type.PERSISTENT_RECURSIVE);
        Assert.assertEquals(2, watchManager.getRecursiveWatchQty());
        watchManager.removeWatcher("/a", watcher1);
        watchManager.removeWatcher("/b", watcher2);
        Assert.assertEquals(0, watchManager.getRecursiveWatchQty());
    }

    @Test
    public void testAddRemoveAlt() {
        Watcher watcher1 = new DummyWatcher();
        Watcher watcher2 = new DummyWatcher();

        watchManager.addWatch("/a", watcher1, WatchManager.Type.PERSISTENT_RECURSIVE);
        watchManager.addWatch("/b", watcher2, WatchManager.Type.PERSISTENT_RECURSIVE);
        Assert.assertEquals(2, watchManager.getRecursiveWatchQty());
        watchManager.removeWatcher(watcher1);
        watchManager.removeWatcher(watcher2);
        Assert.assertEquals(0, watchManager.getRecursiveWatchQty());
    }

    @Test
    public void testDoubleAdd() {
        Watcher watcher = new DummyWatcher();

        watchManager.addWatch("/a", watcher, WatchManager.Type.PERSISTENT_RECURSIVE);
        watchManager.addWatch("/a", watcher, WatchManager.Type.PERSISTENT_RECURSIVE);
        Assert.assertEquals(1, watchManager.getRecursiveWatchQty());
        watchManager.removeWatcher(watcher);
        Assert.assertEquals(0, watchManager.getRecursiveWatchQty());
    }

    @Test
    public void testSameWatcherMultiPath() {
        Watcher watcher = new DummyWatcher();

        watchManager.addWatch("/a", watcher, WatchManager.Type.PERSISTENT_RECURSIVE);
        watchManager.addWatch("/a/b", watcher, WatchManager.Type.PERSISTENT_RECURSIVE);
        watchManager.addWatch("/a/b/c", watcher, WatchManager.Type.PERSISTENT_RECURSIVE);
        Assert.assertEquals(3, watchManager.getRecursiveWatchQty());
        watchManager.removeWatcher("/a/b", watcher);
        Assert.assertEquals(2, watchManager.getRecursiveWatchQty());
        watchManager.removeWatcher(watcher);
        Assert.assertEquals(0, watchManager.getRecursiveWatchQty());
    }

    @Test
    public void testChangeType() {
        Watcher watcher = new DummyWatcher();

        watchManager.addWatch("/a", watcher, WatchManager.Type.PERSISTENT);
        Assert.assertEquals(0, watchManager.getRecursiveWatchQty());
        watchManager.addWatch("/a", watcher, WatchManager.Type.PERSISTENT_RECURSIVE);
        Assert.assertEquals(1, watchManager.getRecursiveWatchQty());
        watchManager.addWatch("/a", watcher, WatchManager.Type.STANDARD);
        Assert.assertEquals(0, watchManager.getRecursiveWatchQty());
        watchManager.removeWatcher("/a", watcher);
        Assert.assertEquals(0, watchManager.getRecursiveWatchQty());
    }
}
