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

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.junit.Test;

public class TokenBucketTest {
    @Test
    public void testTryAquireDecrementsTokens() {
        TokenBucket tb = new TokenBucket(100, 10, 10);
        tb.nextRefillTime = noRefillTime();
        for (int i = 0; i < 5; i++) {
            assertTrue(tb.tryAquire());
        }
        assertEquals(5, tb.getTokenCount());
    }

    @Test
    public void testInitialTokens() {
        TokenBucket tb = new TokenBucket(100, 10, 100);
        tb.nextRefillTime = noRefillTime();
        assertEquals("Initial token amount should be set", 100, tb.getTokenCount());
        assertTrue(tb.tryAquire());
        assertEquals(99L, tb.getTokenCount());
        assertTrue(tb.tryAquire());
        assertEquals(98L, tb.getTokenCount());
    }

    @Test
    public void testNoMoreTokens() {
        TokenBucket tb = new TokenBucket(100, 10, 0);
        tb.nextRefillTime = noRefillTime();
        assertFalse(tb.tryAquire());
        tb.nextRefillTime = Time.currentElapsedTime();
        for (int i = 0; i < 10; i++) {
            assertTrue(tb.tryAquire());
        }
        assertFalse(tb.tryAquire());
    }

    @Test
    public void testRefill() {
        TokenBucket tb = new TokenBucket(100, 10, 0);
        tb.nextRefillTime = Time.currentElapsedTime();
        assertTrue(tb.tryAquire());
        assertEquals(9, tb.getTokenCount()); // refilled 10 and took 1
        tb.nextRefillTime = Time.currentElapsedTime();
        assertTrue(tb.tryAquire());
        assertEquals(18, tb.getTokenCount());
        tb.nextRefillTime = Time.currentElapsedTime() - (2 * tb.refreshPeriodMillis);
        assertTrue(tb.tryAquire());
        assertEquals(47, tb.getTokenCount()); // refilled 30 and took 1
    }

    @Test
    public void testRefillTime() throws InterruptedException {
        TokenBucket tb = new TokenBucket(100, 1, 0);
        Thread.sleep(1000);
        assertTrue(tb.tryAquire());
        Thread.sleep(1000);
        assertTrue(tb.tryAquire());
        assertFalse(tb.tryAquire());
    }

    @Test
    public void testCapacity() {
        TokenBucket tb = new TokenBucket(15, 10, 0);
        tb.nextRefillTime = Time.currentElapsedTime();
        tb.tryAquire();
        assertEquals(9, tb.getTokenCount()); // refilled 10 and took 1
        tb.nextRefillTime = Time.currentElapsedTime();
        tb.tryAquire();
        // could only refill up to capacity of 15, and took 1
        assertEquals(14, tb.getTokenCount());
    }

    @Test
    public void testMultipleThreadBurst() throws Exception {
        final TokenBucket tb = new TokenBucket(15, 1, 10);
        // not all threads might complete in default period of 1 second,
        // so use 30 second period instead
        tb.refreshPeriodMillis = TimeUnit.SECONDS.toNanos(30);
        final CountDownLatch start = new CountDownLatch(1);
        ExecutorService threadPool = Executors.newFixedThreadPool(10);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Future<Boolean> future = threadPool.submit(new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    start.await();
                    return tb.tryAquire();
                }
            });
            futures.add(future);
        }
        tb.nextRefillTime = noRefillTime();
        start.countDown();
        for (Future<Boolean> future : futures) {
            assertEquals(true, future.get());
        }
        assertEquals(0, tb.getTokenCount());
    }

    @Test
    public void testRateLimiterFactory() throws InterruptedException {
        QuorumPeerConfig.setConfig(getQuorumPeerConfig(1, 1));
        RateLimiter limiter = RateLimiter.Factory.create(TokenBucket.class.getName());
        assertEquals(true, limiter.tryAquire());
        assertEquals(false, limiter.tryAquire());
        Thread.sleep(1000);
        assertEquals(true, limiter.tryAquire());
    }

    @Test
    public void testRateLimiterFromSystemProperty() throws InterruptedException {
        QuorumPeerConfig.setConfig(new QuorumPeerConfig()); // empty config
        System.setProperty(TokenBucket.MAX_CLIENT_CNXN_BURST, "1");
        System.setProperty(TokenBucket.MAX_CLIENT_CNXN_RATE, "1");
        RateLimiter limiter = RateLimiter.Factory.create(TokenBucket.class.getName());
        assertEquals(true, limiter.tryAquire());
        assertEquals(false, limiter.tryAquire());
        Thread.sleep(1000);
        assertEquals(true, limiter.tryAquire());
    }

    @Test
    public void testBypass() {
        RateLimiter limiter = RateLimiter.Factory.create(null);
        assertSuccessfulAcquire(20, limiter);
        limiter = RateLimiter.Factory.create("bad.class"); // fall back to
                                                           // bypass
        assertSuccessfulAcquire(20, limiter);
    }

    public static QuorumPeerConfig getQuorumPeerConfig(int burst, int rate) {
        Properties properties = new Properties();
        properties.setProperty(TokenBucket.MAX_CLIENT_CNXN_BURST, String.valueOf(burst));
        properties.setProperty(TokenBucket.MAX_CLIENT_CNXN_RATE, String.valueOf(rate));
        QuorumPeerConfig config = new QuorumPeerConfig();
        config.setRateLimiterImpl(TokenBucket.class.getName());
        config.setProperties(properties);
        return config;
    }

    public static class NoConnectionsRateLimiter implements RateLimiter {
        @Override
        public boolean tryAquire() {
            return false;
        }
    }

    @Test
    public void testCustomRateLimiter() {
        RateLimiter limiter = RateLimiter.Factory.create(NoConnectionsRateLimiter.class.getName());
        assertFalse(limiter.tryAquire());
    }

    private void assertSuccessfulAcquire(int n, RateLimiter limiter) {
        for (int i = 0; i < n; i++) {
            assertEquals(true, limiter.tryAquire());
        }
    }

    // returns some time far in the future so no refill is triggered
    private long noRefillTime() {
        return Time.currentElapsedTime() + SECONDS.toMillis(10000);
    }
}
