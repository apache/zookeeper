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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.apache.zookeeper.ZKTestCase;
import org.junit.Test;

/**
 * Unit tests for {@link ExponentialBackoffStrategy}.
 */
public class ExponentialBackoffStrategyTest extends ZKTestCase {

    // Input validation tests
    @Test(expected = IllegalArgumentException.class)
    public void initialBackoffRangeMin() {
        ExponentialBackoffStrategy.builder()
            .setInitialBackoff(-1L)
            .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void initialBackoffRangeMax() {
        ExponentialBackoffStrategy.builder()
            .setInitialBackoff(Long.MAX_VALUE + 1L)
            .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void maxBackoffRangeMin() {
        ExponentialBackoffStrategy.builder()
            .setMaxBackoff(-2L)
            .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void maxBackoffRangeMax() {
        ExponentialBackoffStrategy.builder()
            .setMaxBackoff(Long.MAX_VALUE + 1L)
            .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void maxElapsedRangeMin() {
        ExponentialBackoffStrategy.builder()
            .setMaxElapsed(-2L)
            .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void maxElapsedRangeMax() {
        ExponentialBackoffStrategy.builder()
            .setMaxElapsed(Long.MIN_VALUE)
            .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void multipleRangeMin() {
        ExponentialBackoffStrategy.builder()
            .setBackoffMultiplier(-0.00000000001)
            .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void multipleRangeMax() {
        ExponentialBackoffStrategy.builder()
            .setBackoffMultiplier(Double.MAX_VALUE + 0.1)
            .build();
    }

    // Test the generated intervals are what we expect
    @Test
    public void generateWaitIntervals() {
        final ExponentialBackoffStrategy strategy = ExponentialBackoffStrategy
            .builder()
            .setInitialBackoff(10L)
            .setMaxBackoff(50L)
            .setMaxElapsed(80L)
            .setBackoffMultiplier(1.5)
            .build();

        assertEquals(10L, strategy.nextWaitMillis());
        assertEquals(15L, strategy.nextWaitMillis());
        assertEquals(23L, strategy.nextWaitMillis());
        assertEquals(35L, strategy.nextWaitMillis());
        assertEquals(BackoffStrategy.STOP, strategy.nextWaitMillis());
    }


    @Test
    public void exponentialDecreasingBackoff() {
        final ExponentialBackoffStrategy strategy =
            ExponentialBackoffStrategy.builder()
                .setInitialBackoff(100L)
                .setMaxBackoff(50L)
                .setBackoffMultiplier(0.6)
                .setMaxElapsed(1_000L)
                .build();


        assertEquals(100L, strategy.nextWaitMillis());
        assertEquals(50L, strategy.nextWaitMillis());
        assertEquals(30L, strategy.nextWaitMillis());
        assertEquals(18L, strategy.nextWaitMillis());
        assertEquals(11L, strategy.nextWaitMillis());
        assertEquals(7L, strategy.nextWaitMillis());
        assertEquals(4L, strategy.nextWaitMillis());
        assertEquals(2L, strategy.nextWaitMillis());
        assertEquals(1L, strategy.nextWaitMillis());
        // total elapsed so far is 223, so check that we get 777 more
        // intervals of 1ms then STOP
        for (int i = 0; i <= 777; i++) {
            assertEquals(1L, strategy.nextWaitMillis());
        }
        assertEquals(BackoffStrategy.STOP, strategy.nextWaitMillis());
    }


    @Test
    public void generateWaitIntervalsNoWaits() {
        final ExponentialBackoffStrategy strategy = ExponentialBackoffStrategy
            .builder()
            .setInitialBackoff(0L)
            .setMaxBackoff(0L)
            .setMaxElapsed(100L)
            .setBackoffMultiplier(10.0)
            .build();

        for (int i = 0; i <= 100; i++) {
            assertEquals(0L, strategy.nextWaitMillis());
        }
        assertEquals(BackoffStrategy.STOP, strategy.nextWaitMillis());
    }

    @Test
    public void generateWaitIntervalsNoLimits() {
        final ExponentialBackoffStrategy strategy = ExponentialBackoffStrategy
            .builder()
            .setInitialBackoff(1L)
            .setMaxBackoff(-1L)
            .setMaxElapsed(-1L)
            .setBackoffMultiplier(2.5)
            .build();

        assertEquals(1L, strategy.nextWaitMillis());
        assertEquals(3L, strategy.nextWaitMillis());
        assertEquals(8L, strategy.nextWaitMillis());
        assertEquals(20L, strategy.nextWaitMillis());
        assertEquals(50L, strategy.nextWaitMillis());
        assertEquals(125L, strategy.nextWaitMillis());
        assertEquals(313L, strategy.nextWaitMillis());
        assertEquals(783L, strategy.nextWaitMillis());

        long previousWait = 783L;
        for (int i = 0; i < 10_000; i++) {
            long currentWait = strategy.nextWaitMillis();
            assertNotEquals(BackoffStrategy.STOP, currentWait);
            assertTrue("Expected " + previousWait + " to be greater than " +
                currentWait, previousWait <= currentWait);
            previousWait = currentWait;
        }
    }
}
