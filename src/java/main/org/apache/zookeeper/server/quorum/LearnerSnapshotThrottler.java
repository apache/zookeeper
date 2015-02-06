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

import org.apache.zookeeper.common.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class to limit the number of concurrent snapshots from a leader to
 * observers and followers.  {@link LearnerHandler} objects should call
 * {@link #beginSnapshot(boolean)} before sending a snapshot and
 * {@link #endSnapshot()} after finishing, successfully or not.
 *
 */
public class LearnerSnapshotThrottler {
    private static final Logger LOG =
            LoggerFactory.getLogger(LearnerSnapshotThrottler.class);

    private final Object snapCountSyncObject = new Object();
    private int snapsInProgress;

    private final int maxConcurrentSnapshots;
    private final long timeoutMillis;

    /**
     * Constructs a new instance limiting the concurrent number of snapshots to
     * <code>maxConcurrentSnapshots</code>.
     * @param maxConcurrentSnapshots maximum concurrent number of snapshots
     * @param timeoutMillis milliseconds to attempt to wait when attempting to
     *                      begin a snapshot that would otherwise be throttled;
     *                      a value of zero means no waiting will be attempted
     * @throws java.lang.IllegalArgumentException when <code>timeoutMillis</code>
     *                                            is negative or
     *                                            <code>maxConcurrentSnaphots</code>
     *                                            is less than 1
     */
    public LearnerSnapshotThrottler(int maxConcurrentSnapshots,
                                    long timeoutMillis) {
        if (timeoutMillis < 0) {
            String errorMsg = "timeout cannot be negative, was " + timeoutMillis;
            throw new IllegalArgumentException(errorMsg);
        }
        if (maxConcurrentSnapshots <= 0) {
            String errorMsg = "maxConcurrentSnapshots must be positive, was " +
                    maxConcurrentSnapshots;
            throw new IllegalArgumentException(errorMsg);
        }

        this.maxConcurrentSnapshots = maxConcurrentSnapshots;
        this.timeoutMillis = timeoutMillis;

        synchronized (snapCountSyncObject) {
            snapsInProgress = 0;
        }
    }

    public LearnerSnapshotThrottler(int maxConcurrentSnapshots) {
        this(maxConcurrentSnapshots, 0);
    }

    /**
     * Indicates that a new snapshot is about to be sent.
     * 
     * @param essential if <code>true</code>, do not throw an exception even
     *                  if throttling limit is reached
     * @throws SnapshotThrottleException if throttling limit has been exceeded
     *                                   and <code>essential == false</code>,
     *                                   even after waiting for the timeout
     *                                   period, if any
     * @throws InterruptedException if thread is interrupted while trying
     *                              to start a snapshot; cannot happen if
     *                              timeout is zero
     */
    public LearnerSnapshot beginSnapshot(boolean essential)
            throws SnapshotThrottleException, InterruptedException {
        int snapshotNumber;

        synchronized (snapCountSyncObject) {
            if (!essential
                && timeoutMillis > 0
                && snapsInProgress >= maxConcurrentSnapshots) {
                long timestamp = Time.currentElapsedTime();
                do {
                    snapCountSyncObject.wait(timeoutMillis);
                } while (snapsInProgress >= maxConcurrentSnapshots
                         && timestamp + timeoutMillis < Time.currentElapsedTime());
            }

            if (essential || snapsInProgress < maxConcurrentSnapshots) {
                snapsInProgress++;
                snapshotNumber = snapsInProgress;
            } else {
                throw new SnapshotThrottleException(snapsInProgress + 1,
                                                    maxConcurrentSnapshots);
            }
        }

        return new LearnerSnapshot(this, snapshotNumber, essential);
    }

    /**
     * Indicates that a snapshot has been completed.
     */
    public void endSnapshot() {
        int newCount;
        synchronized (snapCountSyncObject) {
            snapsInProgress--;
            newCount = snapsInProgress;
            snapCountSyncObject.notify();
        }

        if (newCount < 0) {
            String errorMsg =
                    "endSnapshot() called incorrectly; current snapshot count is "
                            + newCount;
            LOG.error(errorMsg);
        }
    }
}
