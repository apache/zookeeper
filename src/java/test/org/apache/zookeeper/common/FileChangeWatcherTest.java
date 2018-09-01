package org.apache.zookeeper.common;

import org.apache.commons.io.FileUtils;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.test.ClientBase;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class FileChangeWatcherTest extends ZKTestCase {
    private static File tempDir;
    private static File tempFile;

    private static final Logger LOG = LoggerFactory.getLogger(FileChangeWatcherTest.class);

    @BeforeClass
    public static void createTempFile() throws IOException {
        tempDir = ClientBase.createEmptyTestDir();
        tempFile = File.createTempFile("zk_test_", "", tempDir);
        tempFile.deleteOnExit();
    }

    @Test
    public void testCallbackWorksOnFileChanges() throws IOException, InterruptedException {
        FileChangeWatcher watcher = null;
        try {
            final List<WatchEvent<?>> events = new ArrayList<>();
            watcher = new FileChangeWatcher(
                    tempDir.toPath(),
                    new Consumer<WatchEvent<?>>() {
                        @Override
                        public void accept(WatchEvent<?> event) {
                            LOG.info("Got an update: " + event.kind() + " " + event.context());
                            synchronized (events) {
                                events.add(event);
                                events.notifyAll();
                            }
                        }
                    });
            watcher.waitForBackgroundThreadToStart();
            Thread.sleep(1000L); // XXX hack
            for (int i = 0; i < 3; i++) {
                LOG.info("Modifying file, attempt " + (i + 1));
                FileUtils.writeStringToFile(tempFile, "Hello world " + i + "\n", StandardCharsets.UTF_8, true);
                synchronized (events) {
                    if (events.size() < i + 1) {
                        events.wait(3000L);
                    }
                    assertEquals("Wrong number of events", i + 1, events.size());
                    WatchEvent<?> event = events.get(i);
                    assertEquals(StandardWatchEventKinds.ENTRY_MODIFY, event.kind());
                    assertEquals(tempFile.getName(), event.context().toString());
                }
            }
        } finally {
            if (watcher != null) {
                watcher.stopAndJoinBackgroundThread();
            }
        }
    }

    @Test
    public void testCallbackWorksOnFileTouched() throws IOException, InterruptedException {
        FileChangeWatcher watcher = null;
        try {
            final List<WatchEvent<?>> events = new ArrayList<>();
            watcher = new FileChangeWatcher(
                    tempDir.toPath(),
                    new Consumer<WatchEvent<?>>() {
                        @Override
                        public void accept(WatchEvent<?> event) {
                            LOG.info("Got an update: " + event.kind() + " " + event.context());
                            synchronized (events) {
                                events.add(event);
                                events.notifyAll();
                            }
                        }
                    });
            watcher.waitForBackgroundThreadToStart();
            Thread.sleep(1000L); // XXX hack
            LOG.info("Touching file");
            FileUtils.touch(tempFile);
            synchronized (events) {
                if (events.isEmpty()) {
                    events.wait(3000L);
                }
                assertFalse(events.isEmpty());
                WatchEvent<?> event = events.get(0);
                assertEquals(StandardWatchEventKinds.ENTRY_MODIFY, event.kind());
                assertEquals(tempFile.getName(), event.context().toString());
            }
        } finally {
            if (watcher != null) {
                watcher.stopAndJoinBackgroundThread();
            }
        }
    }

    @Test
    public void testCallbackWorksOnFileAdded() throws IOException, InterruptedException {
        FileChangeWatcher watcher = null;
        try {
            final List<WatchEvent<?>> events = new ArrayList<>();
            watcher = new FileChangeWatcher(
                    tempDir.toPath(),
                    new Consumer<WatchEvent<?>>() {
                        @Override
                        public void accept(WatchEvent<?> event) {
                            LOG.info("Got an update: " + event.kind() + " " + event.context());
                            synchronized (events) {
                                events.add(event);
                                events.notifyAll();
                            }
                        }
                    });
            watcher.waitForBackgroundThreadToStart();
            Thread.sleep(1000L); // XXX hack
            File tempFile2 = File.createTempFile("zk_test_", "", tempDir);
            tempFile2.deleteOnExit();
            synchronized (events) {
                if (events.isEmpty()) {
                    events.wait(3000L);
                }
                assertFalse(events.isEmpty());
                WatchEvent<?> event = events.get(0);
                assertEquals(StandardWatchEventKinds.ENTRY_CREATE, event.kind());
                assertEquals(tempFile2.getName(), event.context().toString());
            }
        } finally {
            if (watcher != null) {
                watcher.stopAndJoinBackgroundThread();
            }
        }
    }

    @Test
    public void testCallbackWorksOnFileDeleted() throws IOException, InterruptedException {
        FileChangeWatcher watcher = null;
        try {
            final List<WatchEvent<?>> events = new ArrayList<>();
            watcher = new FileChangeWatcher(
                    tempDir.toPath(),
                    new Consumer<WatchEvent<?>>() {
                        @Override
                        public void accept(WatchEvent<?> event) {
                            LOG.info("Got an update: " + event.kind() + " " + event.context());
                            synchronized (events) {
                                events.add(event);
                                events.notifyAll();
                            }
                        }
                    });
            watcher.waitForBackgroundThreadToStart();
            Thread.sleep(1000L); // XXX hack
            tempFile.delete();
            synchronized (events) {
                if (events.isEmpty()) {
                    events.wait(3000L);
                }
                assertFalse(events.isEmpty());
                WatchEvent<?> event = events.get(0);
                assertEquals(StandardWatchEventKinds.ENTRY_DELETE, event.kind());
                assertEquals(tempFile.getName(), event.context().toString());
            }
        } finally {
            if (watcher != null) {
                watcher.stopAndJoinBackgroundThread();
            }
        }
    }

    @Test
    public void testCallbackErrorDoesNotCrashWatcherThread() throws IOException, InterruptedException {
        FileChangeWatcher watcher = null;
        try {
            final List<WatchEvent<?>> events = new ArrayList<>();
            final AtomicInteger callCount = new AtomicInteger(0);
            watcher = new FileChangeWatcher(
                    tempDir.toPath(),
                    new Consumer<WatchEvent<?>>() {
                        @Override
                        public void accept(WatchEvent<?> event) {
                            LOG.info("Got an update: " + event.kind() + " " + event.context());
                            synchronized (callCount) {
                                if (callCount.getAndIncrement() == 0) {
                                    callCount.notifyAll();
                                    throw new RuntimeException("This error should not crash the watcher thread");
                                }
                            }
                            synchronized (events) {
                                events.add(event);
                                events.notifyAll();
                            }
                        }
                    });
            watcher.waitForBackgroundThreadToStart();
            Thread.sleep(1000L); // XXX hack
            LOG.info("Modifying file");
            FileUtils.writeStringToFile(tempFile, "Hello world\n", StandardCharsets.UTF_8, true);
            synchronized (callCount) {
                while (callCount.get() == 0) {
                    callCount.wait(3000L);
                }
            }
            LOG.info("Modifying file again");
            FileUtils.writeStringToFile(tempFile, "Hello world again\n", StandardCharsets.UTF_8, true);
            synchronized (events) {
                if (events.isEmpty()) {
                    events.wait(3000L);
                }
                assertEquals(2, callCount.get());
                assertFalse(events.isEmpty());
                WatchEvent<?> event = events.get(0);
                assertEquals(StandardWatchEventKinds.ENTRY_MODIFY, event.kind());
                assertEquals(tempFile.getName(), event.context().toString());
            }
        } finally {
            if (watcher != null) {
                watcher.stopAndJoinBackgroundThread();
            }
        }

    }
}
