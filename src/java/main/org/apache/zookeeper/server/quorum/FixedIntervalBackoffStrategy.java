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

/**
 * {@link BackoffStrategy} that supports configurable fixed intervals, with either an unlimited or fixed number of
 * intervals.
 */
public class FixedIntervalBackoffStrategy implements BackoffStrategy {

    // Defaults are the same as the current implementation
    private static final long DEFAULT_INTERVAL_MILLIS = 1000L;  // 1.0s
    private static final long DEFAULT_INTERVAL_COUNT = 3L;  // 3

    private final long intervalMillis;
    private final long maxIntervals;

    // internal state
    private boolean unlimitedIntervals = false;
    private long currentInterval;

    /**
     * Construct a new instance.
     * @param builder the Builder to use for configuring this BackoffStrategy
     */
    private FixedIntervalBackoffStrategy(Builder builder) {
        this.intervalMillis = builder.intervalMillis;
        this.maxIntervals = builder.maxIntervals;

        if(maxIntervals == -1L) {
            unlimitedIntervals = true;
        }

        reset();
    }

    @Override
    public long nextWaitMillis() throws IllegalStateException {
        currentInterval++;
        if(!unlimitedIntervals && currentInterval > maxIntervals) {
            return BackoffStrategy.STOP;
        }

        return intervalMillis;
    }

    @Override
    public void reset() {
        currentInterval = 0;
    }

    /**
     *
     * @return a new {@link Builder} instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for instances of {@link FixedIntervalBackoffStrategy}.
     */
    public static final class Builder {
        private long intervalMillis = DEFAULT_INTERVAL_MILLIS;
        private long maxIntervals = DEFAULT_INTERVAL_COUNT;

        /**
         * Set the number of milliseconds to wait between each interval.  Valid values are 0 to Long.MAX_VALUE, 0
         * resulting in no wait time between intervals.
         *
         * @param milliseconds the number of milliseconds to wait
         * @return this Builder
         */
        public Builder setIntervalMillis(long milliseconds) {
            this.intervalMillis = milliseconds;
            return this;
        }

        /**
         * Set the number of intervals to wait.  Valid values are -1 to Long.MAX_VALUE, -1 would
         * result in no maximum being applied to the BackoffStrategy.
         *
         * @param intervals the number of intervals to check
         * @return this Builder
         */
        public Builder setIntervals(long intervals) {
            this.maxIntervals = intervals;
            return this;
        }

        /**
         * Construct a new {@link FixedIntervalBackoffStrategy} instance using this
         * Builder's configuration.
         * @return a new FixedIntervalBackoffStrategy instance
         */
        public FixedIntervalBackoffStrategy build() {
            // Valid Range: 0 to Long.MAX_VALUE, 0 meaning no initial wait
            validateInclusiveBetween(0L, Long.MAX_VALUE, intervalMillis);
            // Valid Range: 0 to Long.MAX_VALUE, 0 meaning no limit
            validateInclusiveBetween(-1L, Long.MAX_VALUE, maxIntervals);

            return new FixedIntervalBackoffStrategy(this);
        }

        private void validateInclusiveBetween(long min, long max, long value) {
            if(value < min) {
                throw new IllegalArgumentException();
            }

            if(value > max) {
                throw new IllegalArgumentException();
            }
        }
    }
}
