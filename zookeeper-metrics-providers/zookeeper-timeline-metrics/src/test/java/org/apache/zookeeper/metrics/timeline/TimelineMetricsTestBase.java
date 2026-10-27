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

package org.apache.zookeeper.metrics.timeline;

/**
 * Base class for Timeline metrics unit tests.
 *
 * <p>Provides common setup and teardown logic for all Timeline metrics tests.</p>
 */
public abstract class TimelineMetricsTestBase {

    /**
     * Helper method to wait for async metric collection to complete.
     *
     * @param maxWaitMs maximum time to wait in milliseconds
     * @param condition condition to wait for
     * @throws InterruptedException if interrupted while waiting
     */
    protected void waitFor(long maxWaitMs, BooleanSupplier condition) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() - start > maxWaitMs) {
                throw new AssertionError("Condition not met within " + maxWaitMs + "ms");
            }
            Thread.sleep(50);
        }
    }

    /**
     * Functional interface for boolean condition checking.
     */
    @FunctionalInterface
    protected interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
