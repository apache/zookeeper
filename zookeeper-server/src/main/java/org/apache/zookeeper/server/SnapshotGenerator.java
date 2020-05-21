/**
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

package org.apache.zookeeper.server;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Util class Used to control the behavior abouthow we take snapshot.
 */
public class SnapshotGenerator {
    private static final Logger LOG = LoggerFactory.getLogger(SnapshotGenerator.class);

    public static final String PURGE_AFTER_SNAPSHOT = "zookeeper.purgeAfterSnapshot.enabled";
    private static boolean purgeAfterSnapshot;

    public static final String FSYNC_SNAPSHOT_FROM_SCHEDULER = "zookeeper.fsyncSnapshotFromScheduler";
    private static boolean fsyncSnapshotFromScheduler;

    static {
        purgeAfterSnapshot = Boolean.getBoolean(PURGE_AFTER_SNAPSHOT);
        LOG.info("{} = {}", PURGE_AFTER_SNAPSHOT, purgeAfterSnapshot);

        fsyncSnapshotFromScheduler = Boolean.parseBoolean(
                System.getProperty(FSYNC_SNAPSHOT_FROM_SCHEDULER, "true"));
        LOG.info("{} = {}", FSYNC_SNAPSHOT_FROM_SCHEDULER, fsyncSnapshotFromScheduler);
    }

    public static boolean getPurgeAfterSnapshot() {
        return purgeAfterSnapshot;
    }

    public static void setPurgeAfterSnapshot(boolean enabled) {
        purgeAfterSnapshot = enabled;
        LOG.info("{} = {}", PURGE_AFTER_SNAPSHOT, purgeAfterSnapshot);
    }

    public static void setFsyncSnapshotFromScheduler(boolean fsync) {
        fsyncSnapshotFromScheduler = fsync;
        LOG.info("{} = {}", FSYNC_SNAPSHOT_FROM_SCHEDULER, fsyncSnapshotFromScheduler);
    }

    public static boolean getFsyncSnapshotFromScheduler() {
        return fsyncSnapshotFromScheduler;
    }

    private final ZooKeeperServer zks;
    private final ExecutorService worker;
    private final AtomicBoolean isTakingSnapshot;

    public SnapshotGenerator(final ZooKeeperServer zks) {
        this.zks = zks;
        this.worker = Executors.newFixedThreadPool(1);
        this.isTakingSnapshot = new AtomicBoolean(false);
    }

    public boolean takeSnapshot(boolean syncSnap) {
        // Only allow a single snapshot in progress.
        if (isTakingSnapshot.compareAndSet(false, true)) {
            this.worker.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        zks.takeSnapshot(syncSnap);
                        if (purgeAfterSnapshot) {
                            zks.purge();
                        }
                    } catch (Exception e) {
                        LOG.warn("Unexpected exception", e);
                    } finally {
                        isTakingSnapshot.compareAndSet(true, false);
                    }
                }
            });
            return true;
        } else {
            LOG.warn("Previous snapshot is still in-flight, too busy to snap, skipping");
            return false;
        }
    }

    public boolean isSnapInProgress() {
        return isTakingSnapshot.get();
    }

}
