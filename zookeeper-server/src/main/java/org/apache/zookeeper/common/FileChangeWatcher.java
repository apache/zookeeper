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

package org.apache.zookeeper.common;

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

    public enum State {
        NEW,      // object created but start() not called yet
        STARTING, // start() called but background thread has not entered main loop
        RUNNING,  // background thread is running
        STOPPING, // stop() called but background thread has not exited main loop
        STOPPED   // stop() called and background thread has exited, or background thread crashed
    }

    private final WatcherThread watcherThread;
    private State state; // protected by synchronized(this)

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
                        StandardWatchEventKinds.OVERFLOW});
        state = State.NEW;
        this.watcherThread = new WatcherThread(watchService, callback);
        this.watcherThread.setDaemon(true);
    }

    /**
     * Returns the current {@link FileChangeWatcher.State}.
     * @return the current state.
     */
    public synchronized State getState() {
        return state;
    }

    /**
     * Blocks until the current state becomes <code>desiredState</code>.
     * Currently only used by tests, thus package-private.
     * @param desiredState the desired state.
     * @throws InterruptedException if the current thread gets interrupted.
     */
    synchronized void waitForState(State desiredState) throws InterruptedException {
        while (this.state != desiredState) {
            this.wait();
        }
    }

    /**
     * Sets the state to <code>newState</code>.
     * @param newState the new state.
     */
    private synchronized void setState(State newState) {
        state = newState;
        this.notifyAll();
    }

    /**
     * Atomically sets the state to <code>update</code> if and only if the
     * state is currently <code>expected</code>.
     * @param expected the expected state.
     * @param update the new state.
     * @return true if the update succeeds, or false if the current state
     *         does not equal <code>expected</code>.
     */
    private synchronized boolean compareAndSetState(State expected, State update) {
        if (state == expected) {
            setState(update);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Atomically sets the state to <code>update</code> if and only if the
     * state is currently one of <code>expectedStates</code>.
     * @param expectedStates the expected states.
     * @param update the new state.
     * @return true if the update succeeds, or false if the current state
     *         does not equal any of the <code>expectedStates</code>.
     */
    private synchronized boolean compareAndSetState(State[] expectedStates, State update) {
        for (State expected : expectedStates) {
            if (state == expected) {
                setState(update);
                return true;
            }
        }
        return false;
    }

    /**
     * Tells the background thread to start. Does not wait for it to be running.
     * Calling this method more than once has no effect.
     */
    public void start() {
        if (!compareAndSetState(State.NEW, State.STARTING)) {
            // If previous state was not NEW, start() has already been called.
            return;
        }
        this.watcherThread.start();
    }

    /**
     * Tells the background thread to stop. Does not wait for it to exit.
     */
    public void stop() {
        if (compareAndSetState(
                new State[]{State.RUNNING, State.STARTING},
                State.STOPPING)) {
            watcherThread.interrupt();
        }
    }

    /**
     * Inner class that implements the watcher thread logic.
     */
    private class WatcherThread extends ZooKeeperThread {
        private static final String THREAD_NAME = "FileChangeWatcher";

        final WatchService watchService;
        final Consumer<WatchEvent<?>> callback;

        WatcherThread(WatchService watchService, Consumer<WatchEvent<?>> callback) {
            super(THREAD_NAME);
            this.watchService = watchService;
            this.callback = callback;
        }

        @Override
        public void run() {
            try {
                LOG.info(getName() + " thread started");
                if (!compareAndSetState(
                        FileChangeWatcher.State.STARTING,
                        FileChangeWatcher.State.RUNNING)) {
                    // stop() called shortly after start(), before
                    // this thread started running.
                    FileChangeWatcher.State state = FileChangeWatcher.this.getState();
                    if (state != FileChangeWatcher.State.STOPPING) {
                        throw new IllegalStateException("Unexpected state: " + state);
                    }
                    return;
                }
                runLoop();
            } catch (Exception e) {
                LOG.warn("Error in runLoop()", e);
                throw e;
            } finally {
                try {
                    watchService.close();
                } catch (IOException e) {
                    LOG.warn("Error closing watch service", e);
                }
                LOG.info(getName() + " thread finished");
                FileChangeWatcher.this.setState(FileChangeWatcher.State.STOPPED);
            }
        }

        private void runLoop() {
            while (FileChangeWatcher.this.getState() == FileChangeWatcher.State.RUNNING) {
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
