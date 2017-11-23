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

import org.apache.zookeeper.ZKTestCase;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link FixedIntervalBackoffStrategy}.
 */
public class FixedIntervalBackoffStrategyTest extends ZKTestCase {

    // Input validation tests
    @Test(expected = IllegalArgumentException.class)
    public void intervalMillisRangeMin() {
        FixedIntervalBackoffStrategy.builder()
            .setIntervalMillis(-1L)
            .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void intervalMillisRangeMax() {
        FixedIntervalBackoffStrategy.builder()
            .setIntervalMillis(Long.MAX_VALUE + 1L)
            .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void intervalsRangeMin() {
        FixedIntervalBackoffStrategy.builder()
            .setIntervals(-2L)
            .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void maxBackoffRangeMax() {
        FixedIntervalBackoffStrategy.builder()
            .setIntervals(Long.MAX_VALUE + 1L)
            .build();
    }

    // Test the generated intervals are what we expect
    @Test
    public void generateWaitIntervals() {
        final FixedIntervalBackoffStrategy strategy = FixedIntervalBackoffStrategy
            .builder()
            .setIntervalMillis(5000L)
            .setIntervals(3)
            .build();

        assertEquals(5000L, strategy.nextWaitMillis());
        assertEquals(5000L, strategy.nextWaitMillis());
        assertEquals(5000L, strategy.nextWaitMillis());
        assertEquals(BackoffStrategy.STOP, strategy.nextWaitMillis());
    }

    @Test
    public void generateWaitIntervalsNoLimits() {
        final FixedIntervalBackoffStrategy strategy = FixedIntervalBackoffStrategy
            .builder()
            .setIntervalMillis(2000L)
            .setIntervals(-1L)
            .build();

        for (int i = 0; i < 10_000; i++) {
            long currentWait = strategy.nextWaitMillis();
            assertEquals(2000L, strategy.nextWaitMillis());
        }
    }
}
