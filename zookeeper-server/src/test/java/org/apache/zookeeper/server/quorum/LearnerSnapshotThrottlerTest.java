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

package org.apache.zookeeper.server.quorum;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.zookeeper.ZKTestCase;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LearnerSnapshotThrottlerTest extends ZKTestCase {
    private static final Logger LOG =
            LoggerFactory.getLogger(LearnerSnapshotThrottlerTest.class);

    @Test(expected = SnapshotThrottleException.class)
    public void testTooManySnapshotsNonessential() throws Exception {
        LearnerSnapshotThrottler throttler = new LearnerSnapshotThrottler(5);
        for (int i = 0; i < 6; i++) {
            throttler.beginSnapshot(false);
        }
    }

    @Test(expected = SnapshotThrottleException.class)
    public void testTooManySnapshotsEssential() throws Exception {
        LearnerSnapshotThrottler throttler = new LearnerSnapshotThrottler(5);
        try {
            for (int i = 0; i < 6; i++) {
                throttler.beginSnapshot(true);
            }
        }
        catch (SnapshotThrottleException ex) {
            Assert.fail("essential snapshots should not be throttled");
        }
        throttler.endSnapshot();
        throttler.beginSnapshot(false);
    }

    @Test
    public void testNoThrottle() throws Exception {
        LearnerSnapshotThrottler throttler = new LearnerSnapshotThrottler(5);
        try {
            for (int i = 0; i < 6; i++) {
                throttler.beginSnapshot(true);
            }
        }
        catch (SnapshotThrottleException ex) {
            Assert.fail("essential snapshots should not be throttled");
        }
        throttler.endSnapshot();
        for (int i = 0; i < 5; i++) {
            throttler.endSnapshot();
            throttler.beginSnapshot(false);
        }
    }

    @Test
    public void testTryWithResourceNoThrottle() throws Exception {
        LearnerSnapshotThrottler throttler = new LearnerSnapshotThrottler(1);
        for (int i = 0; i < 3; i++) {
            LearnerSnapshot snapshot = throttler.beginSnapshot(false);
            try {
                Assert.assertFalse(snapshot.isEssential());
                Assert.assertEquals(1, snapshot.getConcurrentSnapshotNumber());
            } finally {
                snapshot.close();
            }
        }
    }

    @Test(expected = SnapshotThrottleException.class)
    public void testTryWithResourceThrottle() throws Exception {
        LearnerSnapshotThrottler throttler = new LearnerSnapshotThrottler(1);
        LearnerSnapshot outer = throttler.beginSnapshot(true);
        try {
            LearnerSnapshot inner = throttler.beginSnapshot(false);
            try {
                Assert.fail("shouldn't be able to have both snapshots open");
            } finally {
                inner.close();
            }
        } finally {
            outer.close();
        }
    }

    @Test
    public void testParallelNoThrottle() throws Exception {
        final int numThreads = 50;

        final LearnerSnapshotThrottler throttler = new LearnerSnapshotThrottler(numThreads);
        ExecutorService threadPool = Executors.newFixedThreadPool(numThreads);
        final CountDownLatch threadStartLatch = new CountDownLatch(numThreads);
        final CountDownLatch snapshotProgressLatch = new CountDownLatch(numThreads);

        List<Future<Boolean>> results = new ArrayList<Future<Boolean>>(numThreads);
        for (int i = 0; i < numThreads; i++) {
            results.add(threadPool.submit(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    threadStartLatch.countDown();
                    try {
                        threadStartLatch.await();

                        throttler.beginSnapshot(false);

                        snapshotProgressLatch.countDown();
                        snapshotProgressLatch.await();

                        throttler.endSnapshot();
                    }
                    catch (Exception e) {
                        return false;
                    }

                    return true;
                }
            }));
        }

        for (Future<Boolean> result : results) {
            Assert.assertTrue(result.get());
        }
    }

    @Test
    public void testPositiveTimeout() throws Exception {
        final LearnerSnapshotThrottler throttler = new LearnerSnapshotThrottler(1, 200);
        ExecutorService threadPool = Executors.newFixedThreadPool(1);

        LearnerSnapshot first = throttler.beginSnapshot(false);
        final CountDownLatch snapshotProgressLatch = new CountDownLatch(1);

        Future<Boolean> result = threadPool.submit(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                try {
                    snapshotProgressLatch.countDown();
                    LearnerSnapshot second = throttler.beginSnapshot(false);
                    second.close();
                }
                catch (Exception e) {
                    return false;
                }

                return true;
            }
        });

        snapshotProgressLatch.await();

        first.close();

        Assert.assertTrue(result.get());
    }

    @Test
    public void testHighContentionWithTimeout() throws Exception {
        int numThreads = 20;

        final LearnerSnapshotThrottler throttler = new LearnerSnapshotThrottler(2, 5000);
        ExecutorService threadPool = Executors.newFixedThreadPool(numThreads);
        final CountDownLatch threadStartLatch = new CountDownLatch(numThreads);

        List<Future<Boolean>> results = new ArrayList<Future<Boolean>>(numThreads);
        for (int i = 0; i < numThreads; i++) {
            results.add(threadPool.submit(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    threadStartLatch.countDown();
                    try {
                        threadStartLatch.await();

                        LearnerSnapshot snap = throttler.beginSnapshot(false);

                        int snapshotNumber = snap.getConcurrentSnapshotNumber();

                        throttler.endSnapshot();

                        return snapshotNumber <= 2;
                    }
                    catch (Exception e) {
                        LOG.error("Exception trying to begin snapshot", e);
                        return false;
                    }
                }
            }));
        }

        for (Future<Boolean> result : results) {
            Assert.assertTrue(result.get());
        }
    }
}
