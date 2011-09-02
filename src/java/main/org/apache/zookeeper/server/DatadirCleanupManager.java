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

package org.apache.zookeeper.server;

import java.io.File;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class manages the cleanup of snapshots and corresponding transaction
 * logs by scheduling the auto purge task with the specified
 * 'autopurge.purgeInterval'. It keeps the most recent
 * 'autopurge.snapRetainCount' number of snapshots and corresponding transaction
 * logs.
 */
public class DatadirCleanupManager {

    private static final Logger LOG = LoggerFactory.getLogger(DatadirCleanupManager.class);

    /**
     * Status of the dataDir purge task
     */
    public enum PurgeTaskStatus {
        NOT_STARTED, STARTED, COMPLETED;
    }

    private PurgeTaskStatus purgeTaskStatus = PurgeTaskStatus.NOT_STARTED;

    private final String snapDir;

    private final String dataLogDir;

    private final int snapRetainCount;

    private final int purgeInterval;

    private Timer timer;

    /**
     * Constructor of DatadirCleanupManager. It takes the parameters to schedule
     * the purge task.
     * 
     * @param snapDir
     *            snapshot directory
     * @param dataLogDir
     *            transaction log directory
     * @param snapRetainCount
     *            number of snapshots to be retained after purge
     * @param purgeInterval
     *            purge interval in hours
     */
    public DatadirCleanupManager(String snapDir, String dataLogDir, int snapRetainCount,
            int purgeInterval) {
        this.snapDir = snapDir;
        this.dataLogDir = dataLogDir;
        this.snapRetainCount = snapRetainCount;
        this.purgeInterval = purgeInterval;
        LOG.info("autopurge.snapRetainCount set to " + snapRetainCount);
        LOG.info("autopurge.purgeInterval set to " + purgeInterval);
    }

    /**
     * Validates the purge configuration and schedules the purge task. Purge
     * task keeps the most recent <code>snapRetainCount</code> number of
     * snapshots and deletes the remaining for every <code>purgeInterval</code>
     * hour(s).
     * <p>
     * <code>purgeInterval</code> of <code>0</code> or
     * <code>negative integer</code> will not schedule the purge task.
     * </p>
     * 
     * @see PurgeTxnLog#purge(File, File, int)
     */
    public void start() {
        if (PurgeTaskStatus.STARTED == purgeTaskStatus) {
            LOG.warn("Purge task is already running.");
            return;
        }
        // Don't schedule the purge task with zero or negative purge interval.
        if (purgeInterval <= 0) {
            LOG.info("Purge task is not scheduled.");
            return;
        }

        timer = new Timer("PurgeTask", true);
        TimerTask task = new PurgeTask(dataLogDir, snapDir, snapRetainCount);
        timer.scheduleAtFixedRate(task, 0, TimeUnit.HOURS.toMillis(purgeInterval));

        purgeTaskStatus = PurgeTaskStatus.STARTED;
    }

    /**
     * Shutdown the purge task.
     */
    public void shutdown() {
        if (PurgeTaskStatus.STARTED == purgeTaskStatus) {
            LOG.info("Shutting down purge task.");
            timer.cancel();
            purgeTaskStatus = PurgeTaskStatus.COMPLETED;
        } else {
            LOG.warn("Purge task not started. Ignoring shutdown!");
        }
    }

    static class PurgeTask extends TimerTask {
        private String logsDir;
        private String snapsDir;
        private int snapRetainCount;

        public PurgeTask(String dataDir, String snapDir, int count) {
            logsDir = dataDir;
            snapsDir = snapDir;
            snapRetainCount = count;
        }

        @Override
        public void run() {
            LOG.info("Purge task started.");
            try {
                PurgeTxnLog.purge(new File(logsDir), new File(snapsDir), snapRetainCount);
            } catch (Exception e) {
                LOG.error("Error occured while purging.", e);
            }
            LOG.info("Purge task completed.");
        }
    }

    /**
     * Returns the status of the purge task.
     * 
     * @return the status of the purge task
     */
    public PurgeTaskStatus getPurgeTaskStatus() {
        return purgeTaskStatus;
    }

    /**
     * Returns the snapshot directory.
     * 
     * @return the snapshot directory.
     */
    public String getSnapDir() {
        return snapDir;
    }

    /**
     * Returns transaction log directory.
     * 
     * @return the transaction log directory.
     */
    public String getDataLogDir() {
        return dataLogDir;
    }

    /**
     * Returns purge interval in hours.
     * 
     * @return the purge interval in hours.
     */
    public int getPurgeInterval() {
        return purgeInterval;
    }

    /**
     * Returns the number of snapshots to be retained after purge.
     * 
     * @return the number of snapshots to be retained after purge.
     */
    public int getSnapRetainCount() {
        return snapRetainCount;
    }
}
