package org.apache.zookeeper.server.quorum;

public interface BackoffStrategy {

    long STOP = -1L;

    /**
     * Get the number of milliseconds to wait before retrying the operation,
     * or {@code BackoffStrategy.STOP} if no more retries should be made.
     * @return the number of milliseconds to wait before retrying the operation.
     * @throws IllegalStateException
     */
    long nextWaitMillis() throws IllegalStateException;

    /**
     * Reset to it's initial state.
     */
    void reset();
}
