/*
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.zookeeper.ZKTestCase;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LearnerSyncThrottlerTest extends ZKTestCase {

    private static final Logger LOG = LoggerFactory.getLogger(LearnerSyncThrottlerTest.class);

    @ParameterizedTest
    @EnumSource(LearnerSyncThrottler.SyncType.class)
    public void testTooManySyncsNonessential(LearnerSyncThrottler.SyncType syncType) {
        assertThrows(SyncThrottleException.class, () -> {
            LearnerSyncThrottler throttler = new LearnerSyncThrottler(5, syncType);
            for (int i = 0; i < 6; i++) {
                throttler.beginSync(false);
            }
        });
    }

    @ParameterizedTest
    @EnumSource(LearnerSyncThrottler.SyncType.class)
    public void testTooManySyncsEssential(LearnerSyncThrottler.SyncType syncType) {
        assertThrows(SyncThrottleException.class, () -> {
            LearnerSyncThrottler throttler = new LearnerSyncThrottler(5, syncType);
            try {
                for (int i = 0; i < 6; i++) {
                    throttler.beginSync(true);
                }
            } catch (SyncThrottleException ex) {
                fail("essential syncs should not be throttled");
            }
            throttler.endSync();
            throttler.beginSync(false);
        });
    }

    @ParameterizedTest
    @EnumSource(LearnerSyncThrottler.SyncType.class)
    public void testNoThrottle(LearnerSyncThrottler.SyncType syncType) throws Exception {
        LearnerSyncThrottler throttler = new LearnerSyncThrottler(5, syncType);
        try {
            for (int i = 0; i < 6; i++) {
                throttler.beginSync(true);
            }
        } catch (SyncThrottleException ex) {
            fail("essential syncs should not be throttled");
        }
        throttler.endSync();
        for (int i = 0; i < 5; i++) {
            throttler.endSync();
            throttler.beginSync(false);
        }
        assertTrue(true, "should get here without exception");
    }

    @ParameterizedTest
    @EnumSource(LearnerSyncThrottler.SyncType.class)
    public void testTryWithResourceNoThrottle(LearnerSyncThrottler.SyncType syncType) throws Exception {
        LearnerSyncThrottler throttler = new LearnerSyncThrottler(1, syncType);
        for (int i = 0; i < 3; i++) {
            throttler.beginSync(false);
            try {
                assertEquals(1, throttler.getSyncInProgress());
            } finally {
                throttler.endSync();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(LearnerSyncThrottler.SyncType.class)
    public void testTryWithResourceThrottle(LearnerSyncThrottler.SyncType syncType) throws Exception {
        LearnerSyncThrottler throttler = new LearnerSyncThrottler(1, syncType);
        try {
            throttler.beginSync(true);
            try {
                throttler.beginSync(false);
                fail("shouldn't be able to have both syncs open");
            } catch (SyncThrottleException e) {
            }
            throttler.endSync();
        } catch (SyncThrottleException e) {
            fail("First sync shouldn't be throttled");
        }
    }

    @ParameterizedTest
    @EnumSource(LearnerSyncThrottler.SyncType.class)
    public void testParallelNoThrottle(LearnerSyncThrottler.SyncType syncType) {
        final int numThreads = 50;

        final LearnerSyncThrottler throttler = new LearnerSyncThrottler(numThreads, syncType);
        ExecutorService threadPool = Executors.newFixedThreadPool(numThreads);
        final CountDownLatch threadStartLatch = new CountDownLatch(numThreads);
        final CountDownLatch syncProgressLatch = new CountDownLatch(numThreads);

        List<Future<Boolean>> results = new ArrayList<Future<Boolean>>(numThreads);
        for (int i = 0; i < numThreads; i++) {
            results.add(threadPool.submit(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    threadStartLatch.countDown();
                    try {
                        threadStartLatch.await();

                        throttler.beginSync(false);

                        syncProgressLatch.countDown();
                        syncProgressLatch.await();

                        throttler.endSync();
                    } catch (Exception e) {
                        return false;
                    }

                    return true;
                }
            }));
        }

        try {
            for (Future<Boolean> result : results) {
                assertTrue(result.get());
            }
        } catch (Exception e) {

        } finally {
            threadPool.shutdown();
        }
    }

}
