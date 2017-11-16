package org.apache.zookeeper.server.quorum;

/**
 * A {@link BackoffStrategy} that increases the wait time between each
 * interval up to the configured maximum wait time.
 */
public class ExponentialBackoffStrategy implements BackoffStrategy {

    // Sensible default values to use if not set by the user
    private static final long DEFAULT_INITIAL_BACKOFF_MILLIS = 500L;  // 0.5s
    private static final long DEFAULT_MAX_BACKOFF_MILLIS = 30_000L;  // 30s
    private static final long DEFAULT_MAX_ELAPSED_MILLIS = 5 * 60_000L; // 10m
    private static final double DEFAULT_BACKOFF_MULTIPLE = 1.5;

    // internal values per instance
    private final long initialBackoffMillis;
    private final long maxBackoffMillis;
    private final long maxElapsedMillis;
    private final double backoffMultiple;

    // internal state
    private long nextWait;
    private long totalElapsed;
    private final boolean limitBackoffMillis;
    private final boolean checkElapsedTime;

    /**
     * Construct a new instance.
     * @param builder the Builder to use for configuring this BackoffStrategy
     */
    private ExponentialBackoffStrategy(Builder builder) {
        this.initialBackoffMillis = builder.initialBackoffMillis;
        this.maxBackoffMillis = builder.maxBackoffMillis;
        this.maxElapsedMillis = builder.maxElapsedMillis;
        this.backoffMultiple = builder.backoffMultiple;

        if(maxBackoffMillis == -1) {
            limitBackoffMillis = false;
        } else {
            limitBackoffMillis = true;
        }

        if(maxElapsedMillis == -1) {
            checkElapsedTime = false;
        } else {
            checkElapsedTime = true;
        }

        reset();
    }


    @Override
    public long nextWaitMillis() throws IllegalStateException {
        // check if we have exceeded the allowed maximum elapsed time
        if(checkElapsedTime && totalElapsed > maxElapsedMillis) {
            return BackoffStrategy.STOP;
        }

        long waitMillis = nextWait;

        // calculate the next wait milliseconds
        nextWait = Math.round(nextWait * backoffMultiple);

        // don't exceed the allowed maximum wait milliseconds
        // if a maximum was configured
        if(limitBackoffMillis && nextWait > maxBackoffMillis) {
            nextWait = maxBackoffMillis;
        }

        // track total elapsed time, even if we don't wait we have to assume
        // that some amount of time passed outside of the wait or we'll never
        // hit the elapsed time limit
        totalElapsed += waitMillis != 0 ? waitMillis : 1L;
        return waitMillis;
    }

    @Override
    public void reset() {
        nextWait = this.initialBackoffMillis;
        totalElapsed = 0;
    }

    /**
     *
     * @return a new {@link Builder} instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for instances of {@link ExponentialBackoffStrategy}.
     */
    public static final class Builder {
        private long initialBackoffMillis = DEFAULT_INITIAL_BACKOFF_MILLIS;
        private long maxBackoffMillis = DEFAULT_MAX_BACKOFF_MILLIS;
        private long maxElapsedMillis = DEFAULT_MAX_ELAPSED_MILLIS;
        private double backoffMultiple = DEFAULT_BACKOFF_MULTIPLE;

        /**
         * Set the initial number of milliseconds to wait.  Valid values are
         * 0 to Long.MAX_VALUE, 0 resulting in no wait time in the initial
         * interval.
         * @param milliseconds the initial number of milliseconds to wait
         * @return this Builder
         */
        public Builder setInitialBackoff(long milliseconds) {
            this.initialBackoffMillis = milliseconds;
            return this;
        }

        /**
         * Set the maximum number of wait milliseconds that can be generated.
         * Valid values are -1 to Long.MAX_VALUE, 0 resulting in 0
         * milliseconds in each wait interval (no wait) & -1 resulting in no
         * limit to the number of milliseconds in each wait interval.
         * @param milliseconds the maximum number of wait milliseconds
         * @return this Builder
         */
        public Builder setMaxBackoff(long milliseconds) {
            this.maxBackoffMillis = milliseconds;
            return this;
        }

        /**
         * Set the total number of milliseconds that can be provided to wait.
         * Valid values are -1 to Long.MAX_VALUE.  0 would result in
         * {@link BackoffStrategy#STOP} returned on the first call to
         * {@link ExponentialBackoffStrategy#nextWaitMillis()}.  -1 would
         * result in no maximum being applied to the BackoffStrategy.
         * @param milliseconds the maximum elapsed milliseconds this BackoffStrategy
         *                     can provide
         * @return this Builder
         */
        public Builder setMaxElapsed(long milliseconds) {
            this.maxElapsedMillis = milliseconds;
            return this;
        }

        /**
         * Set the multiple applied to the previous backoff milliseconds
         * value to get to the next backoff milliseconds value.  Valid values
         * must be greater than 0.0 and less than Double.MAX_VALUE.
         * @param multiple the backoff multiple
         * @return this Builder
         */
        public Builder setBackoffMultiplier(double multiple) {
            this.backoffMultiple = multiple;
            return this;
        }

        /**
         * Construct a new {@link ExponentialBackoffStrategy} instance using this
         * Builder's configuration.
         * @return a new ExponentialBackoffStrategy instance
         */
        public ExponentialBackoffStrategy build() {
            // Valid Range: 0 to Long.MAX_VALUE, 0 meaning no initial wait
            validateInclusiveBetween(0L, Long.MAX_VALUE, initialBackoffMillis);
            // Valid Range: 0 to Long.MAX_VALUE, 0 meaning no limit
            validateInclusiveBetween(-1L, Long.MAX_VALUE, maxBackoffMillis);
            // Valid Range: 0 to Long.MAX_VALUE, 0 meaning no limit
            validateInclusiveBetween(-1L, Long.MAX_VALUE, maxElapsedMillis);
            // Valid Range: 1.0001 to Double.MAX_VALUE
            validateExclusiveBetween(0.0, Double.MAX_VALUE, backoffMultiple);

            return new ExponentialBackoffStrategy(this);
        }

        private void validateInclusiveBetween(long min, long max, long value) {
            if(value < min) {
                throw new IllegalArgumentException();
            }

            if(value > max) {
                throw new IllegalArgumentException();
            }
        }

        private void validateExclusiveBetween(double min, double max, double
            value) {
            if(value <= min) {
                throw new IllegalArgumentException();
            }

            if(value >= max) {
                throw new IllegalArgumentException();
            }
        }
    }
}
