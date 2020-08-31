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

package org.apache.zookeeper.server.controller;

import java.io.IOException;
import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.junit.After;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZooKeeperServerControllerEndToEndTest extends ControllerTestBase {
    private static final Logger LOG = LoggerFactory.getLogger(ZooKeeperServerControllerEndToEndTest.class);
    private ZooKeeper zkClient;
    private static final String AnyPath = "/Any";
    private static final byte[] AnyData = new byte[] {0x0, 0x1};

    @After
    @Override
    public void cleanup() throws InterruptedException {
        if (zkClient != null) {
            zkClient.close();
        }
        super.cleanup();
    }

    private void initClient(Watcher watcher) throws IOException {
        zkClient = new ZooKeeper("localhost:" + config.getClientPortAddress().getPort(), 10000, watcher);
    }

    @Test
    public void verifyClientConnects() throws Exception {
        // Basic validation: we can connect and get events.
        BlockingStateWatcher watcher = new BlockingStateWatcher(Watcher.Event.KeeperState.SyncConnected);
        this.initClient(watcher);
        watcher.waitForEvent();
    }

    @Test
    public void verifyClientDisconnectsAndReconnects() throws Exception {
        // Setup: First connect to the server and wait for connected.
        BlockingStateWatcher watcher = new BlockingStateWatcher(Watcher.Event.KeeperState.SyncConnected);
        initClient(watcher);
        watcher.waitForEvent();

        // Force a disconnection through the controller and ensure we get the events in order:
        // 1: Disconnected
        // 2: SyncConnected
        watcher.reset(
                new Watcher.Event.KeeperState[] {
                        Watcher.Event.KeeperState.Disconnected,
                        Watcher.Event.KeeperState.SyncConnected
                });
        Assert.assertTrue(commandClient
                .trySendCommand(ControlCommand.Action.CLOSECONNECTION, String.valueOf(zkClient.getSessionId())));
        watcher.waitForEvent();
    }

    @Ignore
    public void verifySessionExpiration() throws Exception {
        // Setup: First connect to the server and wait for connected.
        BlockingStateWatcher watcher = new BlockingStateWatcher(Watcher.Event.KeeperState.SyncConnected);
        initClient(watcher);
        watcher.waitForEvent();

        // Force an expiration.
        // 1: Disconnected
        // 2: Expired
        watcher.reset(
                new Watcher.Event.KeeperState[] {
                        Watcher.Event.KeeperState.Disconnected,
                        Watcher.Event.KeeperState.Expired
                });
        Assert.assertTrue(commandClient
                .trySendCommand(ControlCommand.Action.EXPIRESESSION, String.valueOf(zkClient.getSessionId())));
        watcher.waitForEvent();
    }

    @Ignore
    public void verifyGlobalSessionExpiration() throws Exception {
        // Step 1: Connect.
        BlockingStateWatcher stateWatcher = new BlockingStateWatcher(Watcher.Event.KeeperState.SyncConnected);
        initClient(stateWatcher);
        stateWatcher.waitForEvent();

        // Step 2: Add an ephemeral node (upgrades session to global).
        BlockingPathWatcher pathWatcher = new BlockingPathWatcher(AnyPath, Watcher.Event.EventType.NodeCreated);

        zkClient.exists(AnyPath, pathWatcher);
        Assert.assertEquals(AnyPath,
                zkClient.create(AnyPath, AnyData, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL));
        pathWatcher.waitForEvent();

        // Force expire all sessions.
        stateWatcher.reset(Watcher.Event.KeeperState.Expired);
        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.EXPIRESESSION));
        stateWatcher.waitForEvent();
    }

    @Ignore
    public void verifyRejectAcceptSessions() throws Exception {
        // Tell the server to reject new requests.
        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.REJECTCONNECTIONS));
        EventWaiter watcher = new BlockingStateWatcher(Watcher.Event.KeeperState.SyncConnected);
        initClient(watcher);
        try {
            watcher.waitForEvent(100);
            Assert.fail("should have failed connecting");
        } catch (TimeoutException ex) {
        }
        // Now accept requests. We should get a connection quickly.
        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.RESET));
        watcher.waitForEvent();
    }

    private long timedTransaction() throws Exception {
        long startTime = System.currentTimeMillis();
        zkClient.exists(AnyPath, false);
        return System.currentTimeMillis() - startTime;
    }

    @Test
    public void verifyAddDelay() throws Exception {
        EventWaiter watcher = new BlockingStateWatcher(Watcher.Event.KeeperState.SyncConnected);

        initClient(watcher);
        watcher.waitForEvent();
        timedTransaction();

        // Add 200 ms of delay to each response.
        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.ADDDELAY, String.valueOf(200)));
        long delayedDuration = timedTransaction();

        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.RESET));
        long resetDuration = timedTransaction();

        Assert.assertTrue(delayedDuration - resetDuration > 200);
    }

    @Test
    public void verifyFailAllRequests() throws Exception {
        // Step 1: Connect.
        BlockingStateWatcher stateWatcher = new BlockingStateWatcher(Watcher.Event.KeeperState.SyncConnected);
        initClient(stateWatcher);
        stateWatcher.waitForEvent();

        // Step 2: Tell the server to fail requests.
        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.FAILREQUESTS));

        try {
            zkClient.exists(AnyPath, null);
            Assert.fail("should have failed");
        } catch (KeeperException ex) {
        }

        // 2nd should fail: we haven't reset.
        try {
            zkClient.exists(AnyPath, null);
            Assert.fail("should still fail");
        } catch (KeeperException ex) {
        }

        // Reset; future requests should succeed.
        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.RESET));

        zkClient.exists(AnyPath, null);
    }

    @Test
    public void verifyFailRequestCount() throws Exception {
        // Step 1: Connect.
        BlockingStateWatcher stateWatcher = new BlockingStateWatcher(Watcher.Event.KeeperState.SyncConnected);
        initClient(stateWatcher);
        stateWatcher.waitForEvent();

        // Step 2: Tell the server to fail 1 request.
        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.FAILREQUESTS, "1"));

        try {
            zkClient.exists(AnyPath, null);
            Assert.fail("should have failed");
        } catch (KeeperException ex) {
        }

        // Have not reset; should succeed.
        zkClient.exists(AnyPath, null);
    }

    @Test
    public void verifyServerEatsAllResponses() throws Exception {
        // Step 1: Connect.
        BlockingStateWatcher watcher = new BlockingStateWatcher(Watcher.Event.KeeperState.SyncConnected);
        initClient(watcher);
        watcher.waitForEvent();

        // No data yet.
        Assert.assertNull(zkClient.exists(AnyPath, null));

        // Step 2: Tell the server to eat responses...nom...nom...nom....
        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.NORESPONSE));

        try {
            BlockingPathWatcher pathWatcher = new BlockingPathWatcher(AnyPath, Watcher.Event.EventType.NodeCreated);
            // This async call should succeed in setting the data, but never send a response.
            zkClient.create(AnyPath, AnyData, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL, pathWatcher, null);
            pathWatcher.waitForEvent(500);
            Assert.fail("should time out since the event should never come");
        } catch (TimeoutException ex) {
        }

        // Re-enable responses.
        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.RESET));
        watcher.reset(Watcher.Event.KeeperState.SyncConnected);

        try {
            // Even though we get a good response, the client doesn't know about
            // the transaction id (xid). This should terminate the connection and
            // throw a KeeperException.
            zkClient.exists(AnyPath, false);
            Assert.fail("should have failed with bad xid");
        } catch (KeeperException ex) {
            // The client believes it has fallen behind so deems this a connection loss.
            Assert.assertTrue(ex instanceof KeeperException.ConnectionLossException);
        }

        // The client should reconnect and be healthy after this.
        watcher.waitForEvent();
        Assert.assertNotNull(zkClient.exists(AnyPath, false));
    }

    /**
     * Our watcher interface is called back on a potentially separate thread.
     * Tests should be logically consolidated into a single method in the following format:
     * for each action in my test
     *     Setup test action
     *     Kick off async action
     *     await state change
     *     verify state
     *
     * To enable this logical pattern, the watcher has an ordered set of states to wait on.
     * When all the states have arrived (in order), the notifier is unblocked.
     */
    private abstract class EventWaiter implements Watcher, AsyncCallback.StringCallback {
        private final int DEFAULT_WAIT_DURATION = 10000;
        private CountDownLatch eventNotification;

        public EventWaiter() {
            reset();
        }

        protected void reset() {
            eventNotification = new CountDownLatch(1);
        }

        @Override
        public void process(WatchedEvent event) {
            // NO-OP. Derived classes should override if required.
            LOG.info("WatchedEvent: {}", event);
        }

        @Override
        public void processResult(int rc, String path, Object ctx, String name) {
            // NO-OP. Derived classes to implement if required.
            LOG.info("StringCallback: {}, {}, {}, {}", rc, path, ctx, name);
        }

        public void notifyListener() {
            eventNotification.countDown();
        }

        public void waitForEvent() throws InterruptedException, TimeoutException {
            waitForEvent(DEFAULT_WAIT_DURATION);
        }

        public void waitForEvent(int waitDurationInMs) throws InterruptedException, TimeoutException {
            // Wait ten seconds and throw if we time out.
            if (!eventNotification.await(waitDurationInMs, TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("Timed out waiting for event");
            }
        }
    }

    private class BlockingStateWatcher extends EventWaiter {
        private Object lockMe = new Object();
        private LinkedList<Event.KeeperState> statesToWaitFor;

        public BlockingStateWatcher(Event.KeeperState stateToNotifyOn) {
            reset(stateToNotifyOn);
        }

        @Override
        public void process(WatchedEvent event) {
            LOG.info("State transition: {}", event.getState());

            boolean shouldNotify = false;
            synchronized (lockMe) {
                if (!statesToWaitFor.isEmpty() && statesToWaitFor.getFirst() == event.getState()) {
                    statesToWaitFor.removeFirst();
                    shouldNotify = statesToWaitFor.isEmpty();
                }
            }

            if (shouldNotify) {
                notifyListener();
            }
        }

        public void reset(Event.KeeperState stateToNotifyOn) {
            reset(new Event.KeeperState[] {stateToNotifyOn});
        }

        public void reset(Event.KeeperState[] orderedStatesToWaitOn) {
            if (orderedStatesToWaitOn == null) {
                throw new IllegalArgumentException("orderedStatesToWaitOn can't be null.");
            }

            if (orderedStatesToWaitOn.length <= 0) {
                throw new IllegalArgumentException("orderedStatesToWaitOn length must be positive.");
            }

            synchronized (lockMe) {
                super.reset();
                statesToWaitFor = new LinkedList<>();

                for (Event.KeeperState state : orderedStatesToWaitOn) {
                    statesToWaitFor.add(state);
                }
            }
        }
    }

    private class BlockingPathWatcher extends EventWaiter {
        private String pathToNotifyOn;
        private Event.EventType requiredEventType;

        public BlockingPathWatcher(String pathToNotifyOn, Event.EventType requiredEventType) {
            reset(pathToNotifyOn, requiredEventType);
        }

        public void reset(String pathToNotifyOn, Event.EventType requiredEventType) {
            super.reset();
            this.pathToNotifyOn = pathToNotifyOn;
            this.requiredEventType = requiredEventType;
        }

        @Override
        public void process(WatchedEvent event) {
            LOG.info("WatchEvent {} for path {}", event.getType(), event.getPath());
            if (pathToNotifyOn != null && event.getType() == requiredEventType
                    && pathToNotifyOn.equalsIgnoreCase(event.getPath())) {
                notifyListener();
            }
        }
    }

}
