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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
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
