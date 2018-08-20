package org.apache.zookeeper.common;

import com.sun.nio.file.SensitivityWatchEventModifier;
import org.apache.zookeeper.server.ZooKeeperThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.function.Consumer;

/**
 * Instances of this class can be used to watch a directory for file changes. When a file is added to, deleted from,
 * or is modified in the given directory, the callback provided by the user will be called from a background thread.
 * Some things to keep in mind:
 * <ul>
 * <li>The callback should be thread-safe.</li>
 * <li>Changes that happen around the time the thread is started may be missed.</li>
 * <li>There is a delay between a file changing and the callback firing.</li>
 * <li>The watch is not recursive - changes to subdirectories will not trigger a callback.</li>
 * </ul>
 */
public final class FileChangeWatcher {
    private static final Logger LOG = LoggerFactory.getLogger(FileChangeWatcher.class);

    private final WatcherThread watcherThread;

    /**
     * Creates a watcher that watches <code>dirPath</code> and invokes <code>callback</code> on changes.
     *
     * @param dirPath the directory to watch.
     * @param callback the callback to invoke with events. <code>event.kind()</code> will return the type of event,
     *                 and <code>event.context()</code> will return the filename relative to <code>dirPath</code>.
     * @throws IOException if there is an error creating the WatchService.
     */
    public FileChangeWatcher(Path dirPath, Consumer<WatchEvent<?>> callback) throws IOException {
        FileSystem fs = dirPath.getFileSystem();
        WatchService watchService = fs.newWatchService();
        if (LOG.isDebugEnabled()) {
            LOG.debug("Registering with watch service: " + dirPath);
        }
        dirPath.register(
                watchService,
                new WatchEvent.Kind<?>[]{
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.OVERFLOW},
                SensitivityWatchEventModifier.HIGH);
        this.watcherThread = new WatcherThread(watchService, callback);
        this.watcherThread.setDaemon(true);
        this.watcherThread.start();
    }

    /**
     * Waits for the background thread to enter the main loop before returning. This method exists mostly to make
     * the unit tests simpler, which is why it is package private.
     *
     * @throws InterruptedException if this thread is interrupted while waiting for the background thread to start.
     */
    void waitForBackgroundThreadToStart() throws InterruptedException {
        synchronized (watcherThread) {
            while (!watcherThread.started) {
                watcherThread.wait();
            }
        }
    }

    /**
     * Tells the background thread to stop. Does not wait for it to exit.
     */
    public void stop() {
        watcherThread.shouldStop = true;
        watcherThread.interrupt();
    }

    /**
     * Tells the background thread to stop and waits for it to exit. Only used by unit tests, which is why it is package
     * private.
     */
    void stopAndJoinBackgroundThread() throws InterruptedException {
        stop();
        watcherThread.join();
    }

    /**
     * Inner class that implements the watcher thread logic.
     */
    private static class WatcherThread extends ZooKeeperThread {
        private static final String THREAD_NAME = "FileChangeWatcher";

        volatile boolean shouldStop;
        volatile boolean started;
        final WatchService watchService;
        final Consumer<WatchEvent<?>> callback;

        WatcherThread(WatchService watchService, Consumer<WatchEvent<?>> callback) {
            super(THREAD_NAME);
            this.shouldStop = this.started = false;
            this.watchService = watchService;
            this.callback = callback;
        }

        @Override
        public void run() {
            LOG.info(getName() + " thread started");
            synchronized (this) {
                started = true;
                this.notifyAll();
            }
            try {
                runLoop();
            } finally {
                try {
                    watchService.close();
                } catch (IOException e) {
                    LOG.warn("Error closing watch service", e);
                }
                LOG.info(getName() + " thread finished");
            }
        }

        private void runLoop() {
            while (!shouldStop) {
                WatchKey key;
                try {
                    key = watchService.take();
                } catch (InterruptedException|ClosedWatchServiceException e) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(getName() + " was interrupted and is shutting down ...");
                    }
                    break;
                }
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Got file changed event: " + event.kind() + " with context: " + event.context());
                    }
                    try {
                        callback.accept(event);
                    } catch (Throwable e) {
                        LOG.error("Error from callback", e);
                    }
                }
                boolean isKeyValid = key.reset();
                if (!isKeyValid) {
                    // This is likely a problem, it means that file reloading is broken, probably because the
                    // directory we are watching was deleted or otherwise became inaccessible (unmounted, permissions
                    // changed, ???).
                    // For now, we log an error and exit the watcher thread.
                    LOG.error("Watch key no longer valid, maybe the directory is inaccessible?");
                    break;
                }
            }
        }
    }
}
