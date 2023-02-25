/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

public class RateLimiterTest {

    @Test
    public void testAllow_withinInterval() {
        final int rate = 2;
        final RateLimiter rateLimiter = new RateLimiter(rate, 5, TimeUnit.SECONDS);
        for (int i = 0; i < rate; i++) {
            assertTrue(rateLimiter.allow());
        }
        assertFalse(rateLimiter.allow());
    }

    @Test
    public void testAllow_withinInterval_multiThreaded() {
        final int rate = 10;

        final RateLimiter rateLimiter = new RateLimiter(rate, 5, TimeUnit.SECONDS);
        final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(rate + 1);
        for (int i = 0; i < rate; i++) {
            executor.execute(() -> assertTrue(rateLimiter.allow()));
        }
        executor.execute(() -> assertFalse(rateLimiter.allow()));
    }

    @Test
    public void testAllow_exceedInterval() throws Exception {
        final int interval = 1;

        final RateLimiter rateLimiter = new RateLimiter(1, interval, TimeUnit.SECONDS);
        assertTrue(rateLimiter.allow());
        assertFalse(rateLimiter.allow());
        Thread.sleep(TimeUnit.SECONDS.toMillis(interval + 1));
        assertTrue(rateLimiter.allow());
    }
}
