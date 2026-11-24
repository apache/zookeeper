/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.recipes.leader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.recipes.leader.LeaderElectionSupport.EventType;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test for {@link LeaderElectionSupport}.
 */
public class LeaderElectionSupportTest extends ClientBase {

    private static final Logger LOGGER = LoggerFactory.getLogger(LeaderElectionSupportTest.class);
    private static final String TEST_ROOT_NODE = "/" + System.currentTimeMillis() + "_";

    private ZooKeeper zooKeeper;

    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();

        zooKeeper = createClient();

        zooKeeper.create(
            TEST_ROOT_NODE + Thread.currentThread().getId(),
            new byte[0],
            ZooDefs.Ids.OPEN_ACL_UNSAFE,
            CreateMode.PERSISTENT);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (zooKeeper != null) {
            zooKeeper.delete(TEST_ROOT_NODE + Thread.currentThread().getId(), -1);
        }

        super.tearDown();
    }

    @Test
    public void testNode() throws Exception {
        LeaderElectionSupport electionSupport = createLeaderElectionSupport();

        electionSupport.start();
        Thread.sleep(3000);
        electionSupport.stop();
    }

    @Test
    public void testNodes3() throws Exception {
        int testIterations = 3;
        final CountDownLatch latch = new CountDownLatch(testIterations);
        final AtomicInteger failureCounter = new AtomicInteger();

        for (int i = 0; i < testIterations; i++) {
            runElectionSupportThread(latch, failureCounter);
        }

        assertEquals(0, failureCounter.get());

        if (!latch.await(10, TimeUnit.SECONDS)) {
            LOGGER.info("Waited for all threads to start, but timed out. We had {} failures.", failureCounter);
        }
    }

    @Test
    public void testNodes9() throws Exception {
        int testIterations = 9;
        final CountDownLatch latch = new CountDownLatch(testIterations);
        final AtomicInteger failureCounter = new AtomicInteger();

        for (int i = 0; i < testIterations; i++) {
            runElectionSupportThread(latch, failureCounter);
        }

        assertEquals(0, failureCounter.get());

        if (!latch.await(10, TimeUnit.SECONDS)) {
            LOGGER.info("Waited for all threads to start, but timed out. We had {} failures.", failureCounter);
        }
    }

    @Test
    public void testNodes20() throws Exception {
        int testIterations = 20;
        final CountDownLatch latch = new CountDownLatch(testIterations);
        final AtomicInteger failureCounter = new AtomicInteger();

        for (int i = 0; i < testIterations; i++) {
            runElectionSupportThread(latch, failureCounter);
        }

        assertEquals(0, failureCounter.get());

        if (!latch.await(10, TimeUnit.SECONDS)) {
            LOGGER.info("Waited for all threads to start, but timed out. We had {} failures.", failureCounter);
        }
    }

    @Test
    public void testNodes100() throws Exception {
        int testIterations = 100;
        final CountDownLatch latch = new CountDownLatch(testIterations);
        final AtomicInteger failureCounter = new AtomicInteger();

        for (int i = 0; i < testIterations; i++) {
            runElectionSupportThread(latch, failureCounter);
        }

        assertEquals(0, failureCounter.get());

        if (!latch.await(20, TimeUnit.SECONDS)) {
            LOGGER.info("Waited for all threads to start, but timed out. We had {} failures.", failureCounter);
        }
    }

    @Test
    public void testOfferShuffle() throws InterruptedException {
        int testIterations = 10;
        final CountDownLatch latch = new CountDownLatch(testIterations);
        final AtomicInteger failureCounter = new AtomicInteger();
        List<Thread> threads = new ArrayList<>(testIterations);

        for (int i = 1; i <= testIterations; i++) {
            threads.add(runElectionSupportThread(latch, failureCounter, Math.min(i * 1200, 10000)));
        }

        if (!latch.await(60, TimeUnit.SECONDS)) {
            LOGGER.info("Waited for all threads to start, but timed out. We had {} failures.", failureCounter);
        }
    }

    @Test
    public void testGetLeaderHostName() throws Exception {
        LeaderElectionSupport electionSupport = createLeaderElectionSupport();

        electionSupport.start();

        // Sketchy: We assume there will be a leader (probably us) in 3 seconds.
        Thread.sleep(3000);

        String leaderHostName = electionSupport.getLeaderHostName();

        assertNotNull(leaderHostName);
        assertEquals("foohost", leaderHostName);

        electionSupport.stop();
    }

    @Test
    public void testReadyOffer() throws Exception {
        final ArrayList<EventType> events = new ArrayList<>();
        final CountDownLatch electedComplete = new CountDownLatch(1);

        final LeaderElectionSupport electionSupport1 = createLeaderElectionSupport();
        electionSupport1.start();
        LeaderElectionSupport electionSupport2 = createLeaderElectionSupport();
        LeaderElectionAware listener = new LeaderElectionAware() {
            boolean stoppedElectedNode = false;
            @Override
            public void onElectionEvent(EventType eventType) {
                events.add(eventType);
                if (!stoppedElectedNode
                    && eventType == EventType.DETERMINE_COMPLETE) {
                    stoppedElectedNode = true;
                    try {
                        // stopping the ELECTED node, so re-election will happen.
                        electionSupport1.stop();
                    } catch (Exception e) {
                        LOGGER.error("Unexpected exception", e);
                    }
                }
                if (eventType == EventType.ELECTED_COMPLETE) {
                    electedComplete.countDown();
                }
            }
        };
        electionSupport2.addListener(listener);
        electionSupport2.start();
        // waiting for re-election.
        electedComplete.await(CONNECTION_TIMEOUT / 3, TimeUnit.MILLISECONDS);

        final ArrayList<EventType> expectedevents = new ArrayList<>();
        expectedevents.add(EventType.START);
        expectedevents.add(EventType.OFFER_START);
        expectedevents.add(EventType.OFFER_COMPLETE);
        expectedevents.add(EventType.DETERMINE_START);
        expectedevents.add(EventType.DETERMINE_COMPLETE);
        expectedevents.add(EventType.DETERMINE_START);
        expectedevents.add(EventType.DETERMINE_COMPLETE);
        expectedevents.add(EventType.ELECTED_START);
        expectedevents.add(EventType.ELECTED_COMPLETE);

        assertEquals(expectedevents, events, "Events has failed to executed in the order");

        electionSupport2.stop();
    }

    @Test
    public void testCompetingOfferDeletedWhileReadingOffers() throws Exception {
        String rootNodeName = TEST_ROOT_NODE + Thread.currentThread().getId();
        // a competing offer that is withdrawn after the election lists the offers but before it reads them
        String competingOffer = zooKeeper.create(rootNodeName + "/n_", "otherhost".getBytes(),
            ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
        AtomicBoolean withdrawn = new AtomicBoolean();
        ZooKeeper electionZooKeeper = createZooKeeper(new ZooKeeperOverrides() {
            @Override
            public void beforeGetData(String path) throws Exception {
                if (path.equals(competingOffer) && !withdrawn.getAndSet(true)) {
                    zooKeeper.delete(competingOffer, -1);
                }
            }
        });
        try {
            LeaderElectionSupport electionSupport = createLeaderElectionSupport();
            electionSupport.setZooKeeper(electionZooKeeper);
            List<EventType> events = recordEvents(electionSupport);

            electionSupport.start();

            assertTrue(withdrawn.get(), "The competing offer was not withdrawn while reading the offers");
            assertFalse(events.contains(EventType.FAILED), "Election failed: " + events);
            assertTrue(events.contains(EventType.ELECTED_COMPLETE), "Not elected: " + events);
            electionSupport.stop();
            assertEquals(0, zooKeeper.getChildren(rootNodeName, false).size());
        } finally {
            electionZooKeeper.close();
        }
    }

    @Test
    public void testFailedStartRemovesItsOffer() throws Exception {
        String rootNodeName = TEST_ROOT_NODE + Thread.currentThread().getId();
        ZooKeeper electionZooKeeper = createZooKeeper(new ZooKeeperOverrides() {
            @Override
            public void beforeGetChildren(String path) throws Exception {
                throw new KeeperException.ConnectionLossException();
            }
        });
        try {
            LeaderElectionSupport electionSupport = createLeaderElectionSupport();
            electionSupport.setZooKeeper(electionZooKeeper);
            List<EventType> events = recordEvents(electionSupport);

            electionSupport.start();

            assertTrue(events.contains(EventType.FAILED), "Election did not fail: " + events);
            assertEquals(0, zooKeeper.getChildren(rootNodeName, false).size(),
                "The failed election left its offer behind");
        } finally {
            electionZooKeeper.close();
        }
    }

    @Test
    public void testRestartFromFailedListenerKeepsNewOffer() throws Exception {
        String rootNodeName = TEST_ROOT_NODE + Thread.currentThread().getId();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        ZooKeeper electionZooKeeper = createZooKeeper(new ZooKeeperOverrides() {
            @Override
            public void beforeGetChildren(String path) throws Exception {
                if (failOnce.getAndSet(false)) {
                    throw new KeeperException.ConnectionLossException();
                }
            }
        });
        try {
            LeaderElectionSupport electionSupport = createLeaderElectionSupport();
            electionSupport.setZooKeeper(electionZooKeeper);
            List<EventType> events = recordEvents(electionSupport);
            AtomicBoolean restarted = new AtomicBoolean();
            // a listener that starts the election again when it fails
            electionSupport.addListener(eventType -> {
                if (eventType == EventType.FAILED && !restarted.getAndSet(true)) {
                    electionSupport.start();
                }
            });

            electionSupport.start();

            assertTrue(events.contains(EventType.ELECTED_COMPLETE), "Restarted election not elected: " + events);
            List<String> offers = zooKeeper.getChildren(rootNodeName, false);
            assertEquals(1, offers.size(), "Expected only the restarted election's offer: " + offers);
            assertNotNull(zooKeeper.exists(rootNodeName + "/" + offers.get(0), false));
            electionSupport.stop();
            assertEquals(0, zooKeeper.getChildren(rootNodeName, false).size());
        } finally {
            electionZooKeeper.close();
        }
    }

    @Test
    public void testOwnOfferMissingFailsElection() throws Exception {
        String rootNodeName = TEST_ROOT_NODE + Thread.currentThread().getId();
        ZooKeeper electionZooKeeper = createZooKeeper(new ZooKeeperOverrides() {
            @Override
            public void beforeGetChildren(String path) throws Exception {
                // our offer is removed by someone else before the offers are listed
                for (String child : zooKeeper.getChildren(rootNodeName, false)) {
                    zooKeeper.delete(rootNodeName + "/" + child, -1);
                }
            }
        });
        try {
            LeaderElectionSupport electionSupport = createLeaderElectionSupport();
            electionSupport.setZooKeeper(electionZooKeeper);
            List<EventType> events = recordEvents(electionSupport);

            electionSupport.start();

            assertTrue(events.contains(EventType.FAILED), "Election did not report a failure: " + events);
            assertFalse(events.contains(EventType.ELECTED_COMPLETE), "Elected without an offer: " + events);
        } finally {
            electionZooKeeper.close();
        }
    }

    @Test
    public void testFailureAfterWatchRemovesOffer() throws Exception {
        String rootNodeName = TEST_ROOT_NODE + Thread.currentThread().getId();
        String competingOffer = zooKeeper.create(rootNodeName + "/n_", "otherhost".getBytes(),
            ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
        AtomicBoolean failGetChildren = new AtomicBoolean();
        ZooKeeper electionZooKeeper = createZooKeeper(new ZooKeeperOverrides() {
            @Override
            public void beforeGetChildren(String path) throws Exception {
                if (failGetChildren.get()) {
                    throw new KeeperException.ConnectionLossException();
                }
            }
        });
        try {
            LeaderElectionSupport electionSupport = createLeaderElectionSupport();
            electionSupport.setZooKeeper(electionZooKeeper);
            List<EventType> events = recordEvents(electionSupport);
            CountDownLatch failed = new CountDownLatch(1);
            electionSupport.addListener(eventType -> {
                if (eventType == EventType.FAILED) {
                    failed.countDown();
                }
            });

            electionSupport.start();
            assertTrue(events.contains(EventType.READY_COMPLETE), "Not waiting behind the competing offer: " + events);

            // the competing offer goes away and running the election again fails
            failGetChildren.set(true);
            zooKeeper.delete(competingOffer, -1);

            assertTrue(failed.await(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS), "Election did not fail: " + events);
            assertEquals(0, zooKeeper.getChildren(rootNodeName, false).size(),
                "The failed election left its offer behind");
        } finally {
            electionZooKeeper.close();
        }
    }

    @Test
    public void testStopDuringElectionAfterWatchSendsNoFailure() throws Exception {
        String rootNodeName = TEST_ROOT_NODE + Thread.currentThread().getId();
        String firstOffer = zooKeeper.create(rootNodeName + "/n_", "otherhost".getBytes(),
            ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
        String secondOffer = zooKeeper.create(rootNodeName + "/n_", "otherhost".getBytes(),
            ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
        AtomicReference<LeaderElectionSupport> electionRef = new AtomicReference<>();
        AtomicReference<Thread> stopThread = new AtomicReference<>();
        AtomicBoolean stopWasBlocked = new AtomicBoolean();
        AtomicBoolean armed = new AtomicBoolean();
        ZooKeeper electionZooKeeper = createZooKeeper(new ZooKeeperOverrides() {
            @Override
            public void beforeExists(String path) throws Exception {
                if (path.equals(firstOffer) && armed.getAndSet(false)) {
                    // while the election runs after the watch, the first offer goes away
                    // and the election is stopped from another thread
                    zooKeeper.delete(firstOffer, -1);
                    Thread t = new Thread(() -> electionRef.get().stop());
                    stopThread.set(t);
                    t.start();
                    t.join(500);
                    stopWasBlocked.set(t.isAlive());
                }
            }
        });
        try {
            LeaderElectionSupport electionSupport = createLeaderElectionSupport();
            electionSupport.setZooKeeper(electionZooKeeper);
            electionRef.set(electionSupport);
            List<EventType> events = recordEvents(electionSupport);
            CountDownLatch stopped = new CountDownLatch(1);
            electionSupport.addListener(eventType -> {
                if (eventType == EventType.STOP_COMPLETE) {
                    stopped.countDown();
                }
            });

            electionSupport.start();
            assertTrue(events.contains(EventType.READY_COMPLETE), "Not waiting behind the second offer: " + events);

            armed.set(true);
            zooKeeper.delete(secondOffer, -1);

            assertTrue(stopped.await(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS), "Election was not stopped: " + events);
            assertNotNull(stopThread.get(), "The election did not run after the watch");
            stopThread.get().join(CONNECTION_TIMEOUT);
            assertTrue(stopWasBlocked.get(), "stop() ran while the election was running");
            assertFalse(eventsAfterStop(events).contains(EventType.FAILED), "Failure reported after stop: " + events);
            assertEquals(0, zooKeeper.getChildren(rootNodeName, false).size());
        } finally {
            electionZooKeeper.close();
        }
    }

    @Test
    public void testStopFromReadyListenerSendsNoFailure() throws Exception {
        String rootNodeName = TEST_ROOT_NODE + Thread.currentThread().getId();
        String competingOffer = zooKeeper.create(rootNodeName + "/n_", "otherhost".getBytes(),
            ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
        LeaderElectionSupport electionSupport = createLeaderElectionSupport();
        List<EventType> events = recordEvents(electionSupport);
        // a listener that stops the election as soon as it has to wait behind another offer
        electionSupport.addListener(eventType -> {
            if (eventType == EventType.READY_START) {
                electionSupport.stop();
            }
        });

        electionSupport.start();
        assertTrue(events.contains(EventType.STOP_COMPLETE), "Election was not stopped: " + events);

        zooKeeper.delete(competingOffer, -1);
        // the watch event for the delete is queued before this callback, on the same event thread
        CountDownLatch watchDelivered = new CountDownLatch(1);
        zooKeeper.sync(rootNodeName, (rc, path, ctx) -> watchDelivered.countDown(), null);
        assertTrue(watchDelivered.await(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS));

        assertFalse(eventsAfterStop(events).contains(EventType.FAILED), "Failure reported after stop: " + events);
        assertEquals(0, zooKeeper.getChildren(rootNodeName, false).size());
    }

    @Test
    public void testStopAfterOfferSendsNoFailure() throws Exception {
        String rootNodeName = TEST_ROOT_NODE + Thread.currentThread().getId();
        LeaderElectionSupport electionSupport = createLeaderElectionSupport();
        List<EventType> events = recordEvents(electionSupport);
        // a listener that stops the election as soon as its offer is made
        electionSupport.addListener(eventType -> {
            if (eventType == EventType.OFFER_COMPLETE) {
                electionSupport.stop();
            }
        });

        electionSupport.start();

        assertTrue(events.contains(EventType.STOP_COMPLETE), "Election was not stopped: " + events);
        assertFalse(eventsAfterStop(events).contains(EventType.FAILED), "Failure reported after stop: " + events);
        assertEquals(0, zooKeeper.getChildren(rootNodeName, false).size());
    }

    private static List<EventType> eventsAfterStop(List<EventType> events) {
        synchronized (events) {
            return new ArrayList<>(events.subList(events.indexOf(EventType.STOP_COMPLETE) + 1, events.size()));
        }
    }

    private interface ZooKeeperOverrides {
        default void beforeGetData(String path) throws Exception {
        }

        default void beforeGetChildren(String path) throws Exception {
        }

        default void beforeExists(String path) throws Exception {
        }
    }

    private ZooKeeper createZooKeeper(ZooKeeperOverrides overrides) throws Exception {
        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(hostPort, CONNECTION_TIMEOUT, watcher) {
            @Override
            public byte[] getData(String path, boolean watch, Stat stat) throws KeeperException, InterruptedException {
                try {
                    overrides.beforeGetData(path);
                } catch (KeeperException | InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return super.getData(path, watch, stat);
            }

            @Override
            public Stat exists(String path, Watcher watcher) throws KeeperException, InterruptedException {
                try {
                    overrides.beforeExists(path);
                } catch (KeeperException | InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return super.exists(path, watcher);
            }

            @Override
            public List<String> getChildren(String path, boolean watch) throws KeeperException, InterruptedException {
                try {
                    overrides.beforeGetChildren(path);
                } catch (KeeperException | InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return super.getChildren(path, watch);
            }
        };
        try {
            watcher.waitForConnected(CONNECTION_TIMEOUT);
        } catch (Exception e) {
            zk.close();
            throw e;
        }
        return zk;
    }

    private static List<EventType> recordEvents(LeaderElectionSupport electionSupport) {
        List<EventType> events = Collections.synchronizedList(new ArrayList<>());
        electionSupport.addListener(events::add);
        return events;
    }

    private LeaderElectionSupport createLeaderElectionSupport() {
        LeaderElectionSupport electionSupport = new LeaderElectionSupport();

        electionSupport.setZooKeeper(zooKeeper);
        electionSupport.setRootNodeName(TEST_ROOT_NODE + Thread.currentThread().getId());
        electionSupport.setHostName("foohost");

        return electionSupport;
    }

    private Thread runElectionSupportThread(
        final CountDownLatch latch,
        final AtomicInteger failureCounter) {
        return runElectionSupportThread(latch, failureCounter, 3000);
    }

    private Thread runElectionSupportThread(
        final CountDownLatch latch,
        final AtomicInteger failureCounter,
        final long sleepDuration) {
        final LeaderElectionSupport electionSupport = createLeaderElectionSupport();

        Thread t = new Thread(() -> {
            try {
                electionSupport.start();
                Thread.sleep(sleepDuration);
                electionSupport.stop();

                latch.countDown();
            } catch (Exception e) {
                LOGGER.warn("Failed to run leader election.", e);
                failureCounter.incrementAndGet();
            }
        });

        t.start();

        return t;
    }

}
