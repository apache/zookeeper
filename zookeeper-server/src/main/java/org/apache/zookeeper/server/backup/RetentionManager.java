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

package org.apache.zookeeper.server.backup;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Optional;
import com.google.common.collect.Range;
import com.twitter.finagle.stats.*;
import org.apache.zookeeper.metrics.MetricsReceiver;
import org.apache.zookeeper.server.backup.BackupUtil.BackupFileType;
import org.apache.zookeeper.server.backup.BackupUtil.SortOrder;
import org.apache.zookeeper.server.backup.BackupUtil.ZxidPart;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.Util;
import org.apache.zookeeper.server.util.ZxidUtils;
import org.apache.zookeeper.twitter.metrics.FinagleStatsReceiverWrapper;
import org.apache.zookeeper.twitter.server.metrics.JvmStatsRegisterer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Manages the retention policies for backup enabled hosts.
 * Controls purging of local snapshot and txnlog files as well as the retention policies
 * for backed-up files
 */
public class RetentionManager {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionManager.class);

    private final ArrayList<Timer> timers;
    private final Configuration config;
    private final StatsReceiver statsReceiver;

    private volatile boolean isRunning;

    /**
     * Configuration for the Retention Manager
     */
    public static class Configuration {
        public boolean dryRun;
        public long localPurgeInterval;
        public File snapDir;
        public File txnlogDir;
        public File backupStatusDir;
        public int snapRetainCount;
        public long hdfsRetentionMaintenanceInterval;
        public File hdfsConfigDir;
        public String hdfsPath;
        public int hdfsRecursionDepth;
        public int hdfsBackupRetentionDays;
        public boolean hdfsUseAdaptiveSnapRetention;

        /**
         * Constructor
         * @param dryRun whether to only report actions that will be taken
         * @param localPurgeInterval the interval at which to run local purge, in milliseconds
         * @param snapDir the location of the local snapshots directory
         * @param txnlogDir the location of the local txnlog directory
         * @param backupStatusDir the location of the local backup status directory
         * @param snapRetainCount the minimum number of snapshots to retain locally
         * @param hdfsRetentionMaintenanceInterval the interval at which to run hdfs retention
         *                                         maintenance, in milliseconds
         * @param hdfsConfigDir the location of hdfs configuration
         * @param hdfsPath the path to the backups in hdfs
         * @param hdfsRecursionDepth the depth to which to recurse from hdfsPath; each directory
         *                           is treated individually for retention calculations
         * @param hdfsBackupRetentionDays the retention period to for backups, in days
         * @param hdfsUseAdaptiveSnapRetention whether to use adaptive retention for snapshots;
         *                                     when true snapshots are pruned in a progressively
         *                                     more aggresive manner as they age
         */
        public Configuration(
                boolean dryRun,
                long localPurgeInterval,
                File snapDir,
                File txnlogDir,
                File backupStatusDir,
                int snapRetainCount,
                long hdfsRetentionMaintenanceInterval,
                File hdfsConfigDir,
                String hdfsPath,
                int hdfsRecursionDepth,
                int hdfsBackupRetentionDays,
                boolean hdfsUseAdaptiveSnapRetention) {

            this.dryRun = dryRun;
            this.localPurgeInterval = localPurgeInterval;
            this.snapDir = snapDir;
            this.txnlogDir = txnlogDir;
            this.backupStatusDir = backupStatusDir;
            this.snapRetainCount = snapRetainCount;
            this.hdfsRetentionMaintenanceInterval = hdfsRetentionMaintenanceInterval;
            this.hdfsConfigDir = hdfsConfigDir;
            this.hdfsPath = hdfsPath;
            this.hdfsRecursionDepth = hdfsRecursionDepth;
            this.hdfsBackupRetentionDays = hdfsBackupRetentionDays;
            this.hdfsUseAdaptiveSnapRetention = hdfsUseAdaptiveSnapRetention;
        }

        @Override
        public String toString() {
            StringBuilder b = new StringBuilder();

            buildLine(b, "LocalPurgeInterval", this.localPurgeInterval);
            buildLine(b, "SnapDir", this.snapDir);
            buildLine(b, "TxnLogDir", this.txnlogDir);
            buildLine(b, "BackupStatusDir", this.backupStatusDir);
            buildLine(b, "SnapRetainCount", this.snapRetainCount);
            buildLine(b, "HdfsRetentionInterval", this.hdfsRetentionMaintenanceInterval);
            buildLine(b, "HdfsConfigDir", this.hdfsConfigDir);
            buildLine(b, "HdfsBasePath", this.hdfsPath);
            buildLine(b, "HdfsRecursionDepth", this.hdfsRecursionDepth);
            buildLine(b, "HdfsRetentionDays", this.hdfsBackupRetentionDays);
            buildLine(b, "HdfsUseAdaptiveSnapRetention", this.hdfsUseAdaptiveSnapRetention);

            return b.toString();
        }

        private void buildLine(StringBuilder b, String tag, Object value) {
            b.append(tag);
            b.append(": ");
            b.append(value);
            b.append("\n");
        }
    }

    public RetentionManager(Configuration config, StatsReceiver statsReceiver) {
        this.config = config;
        this.timers = new ArrayList<Timer>();
        this.isRunning = false;
        this.statsReceiver = statsReceiver.scope("RetentionManager");
    }

    /**
     * Start the tasks and wait for them to be stopped.
     * @throws InterruptedException
     * @throws IOException
     */
    public synchronized void run() throws InterruptedException, IOException {
        if (!isRunning) {
            start();
        }

        while (isRunning) {
            this.wait();
        }
    }

    /**
     * Start the retention manager tasks if applicable.
     * @throws IOException
     */
    public synchronized void start() throws IOException {
        if (config.localPurgeInterval > 0) {
            Timer timer = new Timer("RetentionManager_LocalPurgeTask", true);
            MetricsReceiver metricsReceiver = new FinagleStatsReceiverWrapper(statsReceiver);
            JvmStatsRegisterer.register(metricsReceiver);
            TimerTask task = new LocalPurgeTask(
                config.dryRun,
                new FileTxnSnapLog(config.txnlogDir, config.snapDir, metricsReceiver),
                config.backupStatusDir,
                config.snapRetainCount,
                statsReceiver);

            timer.scheduleAtFixedRate(task, 0, config.localPurgeInterval);
            timers.add(timer);

            LOG.info("Added LocalPurgeTask.");
        } else {
            LOG.info("Not starting LocalPurgeTask since purge interval is undefined.");
        }

        if (config.hdfsBackupRetentionDays > 0 && config.hdfsRetentionMaintenanceInterval > 0) {

            Timer timer = new Timer("RetentionManager_HdfsRetentionMaintenance", true);
            TimerTask task = new BackupStorageRetentionTask(
                    config.dryRun,
                    new HdfsBackupStorage(config.hdfsConfigDir, config.hdfsPath),
                    config.hdfsRecursionDepth,
                    config.hdfsBackupRetentionDays,
                    config.hdfsUseAdaptiveSnapRetention,
                    statsReceiver);

            timer.scheduleAtFixedRate(task, 0, config.hdfsRetentionMaintenanceInterval);
            timers.add(timer);

            LOG.info("Added HdfsRetentionTask.");
        } else {
            LOG.info("Not starting HdfsRetentionTask (retentionDays={}, interval={}).",
                    config.hdfsBackupRetentionDays, config.hdfsRetentionMaintenanceInterval);
        }

        if (timers.isEmpty()) {
            LOG.info("No tasks to run; not starting RetentionManager.");
            return;
        }

        isRunning = true;
        LOG.info("Started RetentionManager.");
    }

    /**
     * Stop the retention manager tasks
     */
    public synchronized void stop() {
        for (Timer timer : timers) {
            timer.cancel();
            if (LOG.isDebugEnabled()) {
                LOG.debug("Cancelled timer: {}.", timer);
            }
        }

        timers.clear();
        isRunning = false;
        LOG.info("Stopped RetentionManager.");
        this.notifyAll();
    }

    /**
     * Equivalent of PurgeTxnLog task but handles coordination with the backup service
     * Will not delete snapshots and txnlog files locally until they have been backed-up.
     */
    public static class LocalPurgeTask extends TimerTask {
        private final boolean dryRun;
        private final FileTxnSnapLog snapLog;
        private final File backupStatusDir;
        private final int snapRetainCount;

        private final Counter iterations;
        private final Stat iterationDuration;
        private final StatsReceiver deletedScope;

        private final Gauge snapsRemainingCount;
        private final Gauge snapsRemainingBytes;
        private final Gauge txnLogRemainingCount;
        private final Gauge txnLogRemainingBytes;

        /**
         * Constructor
         * @param snapLog snapshot and txnlog manager
         * @param backupStatusDir location of the backup status file
         * @param snapRetainCount minimum number of snapshots to always retain locally
         */
        public LocalPurgeTask(
                boolean dryRun,
                FileTxnSnapLog snapLog,
                File backupStatusDir,
                int snapRetainCount,
                StatsReceiver statsReceiver) {

            this.dryRun = dryRun;
            this.snapLog = snapLog;
            this.snapRetainCount = snapRetainCount;
            this.backupStatusDir = backupStatusDir;

            StatsReceiver taskScope = statsReceiver.scope("local_purge_task");
            StatsReceiver iterationScope = taskScope.scope("iterations");
            this.iterations = iterationScope.counter0("count");
            this.iterationDuration = iterationScope.stat0("duration");

            this.deletedScope = taskScope.scope("purged");

            StatsReceiver liveFiles = taskScope.scope("live");
            StatsReceiver snapsRemaining = liveFiles.scope("snap");
            this.snapsRemainingCount =
                    StatsReceivers.addGauge(snapsRemaining, this.countSnaps, "count");
            this.snapsRemainingBytes =
                    StatsReceivers.addGauge(snapsRemaining, this.countSnapBytes, "bytes");

            StatsReceiver txnLogsRemaining = liveFiles.scope("txnlog");
            this.txnLogRemainingCount =
                    StatsReceivers.addGauge(txnLogsRemaining, this.countTxnLogs, "count");
            this.txnLogRemainingBytes =
                    StatsReceivers.addGauge(txnLogsRemaining, this.countTxnLogBytes, "bytes");
        }

        private final Callable<Float> countSnaps = new Callable<Float>() {
            @Override
            public Float call() {
                try {
                    return (float)snapLog.findNRecentSnapshots(Integer.MAX_VALUE).size();
                } catch (IOException e) {
                    return -1f;
                }
            }
        };

        private final Callable<Float> countSnapBytes = new Callable<Float>() {
            @Override
            public Float call() {
                try {
                    float size = 0;

                    for (File f : snapLog.findNRecentSnapshots(Integer.MAX_VALUE)) {
                        size += f.length();
                    }

                    return size;
                } catch (IOException e) {
                    return -1f;
                }
            }
        };

        private final Callable<Float> countTxnLogs = new Callable<Float>() {
            @Override
            public Float call() {
                return (float)snapLog.getSnapshotLogs(Long.MIN_VALUE).length;
            }
        };

        private final Callable<Float> countTxnLogBytes = new Callable<Float>() {
            @Override
            public Float call() {
                float size = 0;

                for (File f : snapLog.getSnapshotLogs(Long.MIN_VALUE)) {
                    size += f.length();
                }

                return size;
            }
        };

        @Override
        public void run() {
            long startTime = System.currentTimeMillis();
            LOG.info("Local purge task started.");
            iterations.incr();

            try {
                BackupStatus backupStatus = new BackupStatus(backupStatusDir);
                BackupPoint backedupIds = backupStatus.read();
                long backupZxid =
                        Math.min(backedupIds.getLogZxid(), backedupIds.getSnapZxid());

                LOG.info("Backedup zxid: {}", ZxidUtils.zxidToString(backupZxid));

                for (File file : snapLog.getFilesEligibleForDeletion(snapRetainCount, backupZxid)) {
                    Optional<String[]> parts = Util.getFilenameParts(file.getName());
                    long fileSize = file.getTotalSpace();

                    if (!parts.isPresent()) {
                        throw new IllegalArgumentException("Unknow filetype: " + file.getName());
                    }

                    String filePrefix = parts.get()[0];

                    LOG.info("Removing file {} with last modified time of {}.",
                            file.getPath(),
                            DateFormat.getDateTimeInstance().format(file.lastModified()));

                    if (!dryRun) {
                        if (file.delete()) {
                            StatsReceiver statScope = deletedScope.scope(filePrefix);
                            statScope.counter0("count").incr();
                            statScope.stat0("bytes").add(fileSize);
                        } else {
                            LOG.warn("Could not delete file {}.", file.getPath());
                        }
                    }
                }

                LOG.info("Local purge task completed successfully.");

            } catch (Exception e) {
                LOG.error("Local purge task completed with an error.", e);
            }

            long endTime = System.currentTimeMillis();

            iterationDuration.add(endTime - startTime);
        }
    }

    /**
     * Manages retention of files backedup to a backup storage provider.
     * All snapshots less than retentionDays olds are kept plus one additional one (in order to
     * always have a full retentionDays worth of starting points for restores).
     * All logs and lostlog files that are required for the retained snapshots are also kept.
     * All other files are deleted.
     */
    public static class BackupStorageRetentionTask extends TimerTask {
        private final boolean dryRun;
        private final int retentionDays;
        private final BackupStorageProvider storage;
        private int recursionDepth;
        private final long asOfTime;
        private final boolean useAdaptiveSnapRetention;
        private final long lowLatencyRecoveryThreshold;
        private final long mediumLatencyRecoveryThreshold;

        private final StatsReceiver statsReceiver;

        private enum AdaptiveSnapMinInterval {
            LOW_LATENCY(TimeUnit.MINUTES.toMillis(15)),
            MEDIUM_LATENCY(TimeUnit.MINUTES.toMillis(60)),
            HIGH_LATENCY(TimeUnit.HOURS.toMillis(6));

            private long interval;

            AdaptiveSnapMinInterval(long interval) {
                this.interval = interval;
            }

            public long getInterval() {
                return this.interval;
            }
        }

        /**
         * Constructor
         * @param storage the backup storage provider where to perform retention maintenance
         * @param recursionDepth the depth to recurse to; each directory is processed independently
         * @param retentionDays the number of days to retain backups
         */
        public BackupStorageRetentionTask(
                boolean dryRun,
                BackupStorageProvider storage,
                int recursionDepth,
                int retentionDays,
                boolean useAdaptiveSnapRetention,
                StatsReceiver statsReceiver) throws IOException {

            this(dryRun,
                    storage,
                    recursionDepth,
                    retentionDays,
                    useAdaptiveSnapRetention,
                    0,
                    statsReceiver);
        }

        /**
         * Constructor
         * @param storage the backup storage provider where to perform retention maintenance
         * @param recursionDepth the depth to recurse to; each directory is processed independently
         * @param retentionDays the number of days to retain backups
         * @param asOfTime perform retention as if running at this point in time; 0 mean current
         *                 time
         */
        public BackupStorageRetentionTask(
                boolean dryRun,
                BackupStorageProvider storage,
                int recursionDepth,
                int retentionDays,
                boolean useAdaptiveSnapRetention,
                long asOfTime,
                StatsReceiver statsReceiver) throws IOException {

            this.dryRun = dryRun;
            this.storage = storage;
            this.recursionDepth = recursionDepth;
            this.retentionDays = retentionDays;
            this.asOfTime = asOfTime;
            this.useAdaptiveSnapRetention = useAdaptiveSnapRetention;
            this.statsReceiver = statsReceiver.scope("backup_retention_manager");

            long lowLatDays = 2;
            long medLatDays = Math.max((long) Math.ceil(retentionDays * 0.50), lowLatDays + 1);
            this.lowLatencyRecoveryThreshold = TimeUnit.DAYS.toMillis(lowLatDays);
            this.mediumLatencyRecoveryThreshold = TimeUnit.DAYS.toMillis(medLatDays);

            if (useAdaptiveSnapRetention) {
                LOG.info("Using low latency recovery threshold of {} ({} days) and medium "
                                + "latency recovery threshold of {} ({} days).",
                        this.lowLatencyRecoveryThreshold,
                        lowLatDays,
                        this.mediumLatencyRecoveryThreshold,
                        medLatDays);
            }
        }

        @Override
        public void run() {
            LOG.info("Backup storage retention maintenance starting.");

            long startTime = System.currentTimeMillis();
            StatsReceiver iterationsScope = statsReceiver.scope("iterations");
            iterationsScope.counter0("count").incr();

            try {
                long currentTime = asOfTime == 0 ? System.currentTimeMillis() : asOfTime;
                long retentionCutoffTime = currentTime - TimeUnit.DAYS.toMillis(retentionDays);

                LOG.info("Backup retention cutoff time is: {}.",
                        DateFormat.getDateTimeInstance().format(retentionCutoffTime));

                int remainingDepth = this.recursionDepth;
                Queue<File> currentLevel = new LinkedList<File>();
                processDirectory(null, currentTime, retentionCutoffTime);
                currentLevel.add(null);

                while (!currentLevel.isEmpty() && remainingDepth > 0) {
                    Queue<File> nextLevel = new LinkedList<File>();

                    while(!currentLevel.isEmpty()) {
                        File current = currentLevel.remove();
                        List<File> childDirs = storage.getDirectories(current);

                        for (File childDir : childDirs) {
                            nextLevel.add(childDir);
                            processDirectory(childDir, currentTime, retentionCutoffTime);
                        }
                    }

                    currentLevel = nextLevel;
                    remainingDepth--;
                }

                LOG.info("Backup storage retention maintenance completed successfully.");

            } catch (Exception e) {
                LOG.error("Backup storage retention maintenance completed with an error.", e);
            }

            iterationsScope.stat0("duration").add(System.currentTimeMillis() - startTime);
        }


        private void processDirectory(
                File childDir,
                long currentTime,
                long retentionCutoffTime) throws IOException {

            String childName = childDir == null ? "base" : childDir.getPath().replace("/", "\\");
            StatsReceiver clusterScope = statsReceiver.scope(childName);
            StatsReceiver iterationsScope = clusterScope.scope("iterations");
            StatsReceiver purgedScope = clusterScope.scope("purged");
            long startTime = System.currentTimeMillis();
            ArrayList<BackupFileInfo> filesToDelete = new ArrayList<BackupFileInfo>();

            LOG.info("Processing {}", childDir == null ? "<hdfsBaseDir>" : childDir.toString());
            iterationsScope.counter0("count").incr();

            List<BackupFileInfo> logs = BackupUtil.getBackupFiles(
                    storage,
                    childDir,
                    BackupFileType.TXNLOG,
                    ZxidPart.MIN_ZXID,
                    SortOrder.ASCENDING);
            List<BackupFileInfo> lostLogs = BackupUtil.getBackupFiles(
                    storage,
                    childDir,
                    BackupFileType.LOSTLOG,
                    ZxidPart.MIN_ZXID,
                    SortOrder.ASCENDING);
            List<BackupFileInfo> snaps = BackupUtil.getBackupFiles(
                    storage,
                    childDir,
                    BackupFileType.SNAPSHOT,
                    ZxidPart.MIN_ZXID,
                    SortOrder.ASCENDING);

            // This is the zxid of the first snap that does NOT get deleted due to
            // age
            long cutoffZxid = snaps.isEmpty()
                    ? Long.MAX_VALUE
                    : snaps.get(0).getZxid(ZxidPart.MIN_ZXID);
            Optional<BackupFileInfo> lastNotDeleted = Optional.absent();

            // Find all snapshot that can be deleted neither because they fall outside of the
            // retention period or because they can be pruned based on the recovery latency sla
            for (int i = 0; i < snaps.size() - 1; i++) {
                BackupFileInfo current = snaps.get(i);
                BackupFileInfo next = snaps.get(i + 1);
                Boolean deleteCurrent;

                if (isSnapOutsideOfRetentionPeriod(current, next, retentionCutoffTime)) {
                    deleteCurrent = true;
                    cutoffZxid = next.getZxid(ZxidPart.MIN_ZXID);
                } else {
                    deleteCurrent =
                            !isSnapNeededForRecoveryLatencySla(
                                    current,
                                    next,
                                    lastNotDeleted,
                                    currentTime,
                                    lostLogs);
                }

                if (deleteCurrent) {
                    filesToDelete.add(current);
                } else {
                    lastNotDeleted = Optional.fromNullable(current);
                }
            }


            // Gather the log and lostlog files that are no longer needed for restoring any
            // of the retained snapshots and that are older than the cutoff time
            for (BackupFileInfo bfi : logs) {

                if (bfi.getZxid(ZxidPart.MAX_ZXID) >= cutoffZxid
                        || bfi.getModificationTime() >= retentionCutoffTime) {
                    break;
                }

                filesToDelete.add(bfi);
            }

            // Gather the log and lostlog files that are no longer needed for restoring any
            // of the retained snapshots and that are older than the cutoff time
            for (BackupFileInfo bfi : lostLogs) {

                if (bfi.getZxid(ZxidPart.MAX_ZXID) >= cutoffZxid
                        || bfi.getModificationTime() >= retentionCutoffTime) {
                    break;
                }

                filesToDelete.add(bfi);
            }

            for (BackupFileInfo bfi : filesToDelete) {
                LOG.info("Deleting file {} with modification time {} from backup storage.",
                        bfi.getBackedUpFile(),
                        DateFormat.getDateTimeInstance().format(bfi.getModificationTime()));

                if (!dryRun) {
                    StatsReceiver fileTypeScope =
                            purgedScope.scope(BackupUtil.getPrefix(bfi.getFileType()));
                    long fileSize = bfi.getSize();

                    storage.delete(bfi.getBackedUpFile());

                    fileTypeScope.counter0("count").incr();
                    fileTypeScope.stat0("bytes").add(fileSize);
                }
            }

            iterationsScope.stat0("duration").add(System.currentTimeMillis() - startTime);
        }

        private boolean isSnapOutsideOfRetentionPeriod(
                BackupFileInfo currentSnap,
                BackupFileInfo nextSnap,
                long retentionCutoffTime) {

            return currentSnap.getModificationTime() < retentionCutoffTime &&
                    nextSnap.getModificationTime() < retentionCutoffTime;
        }

        private boolean isSnapNeededForRecoveryLatencySla(
                BackupFileInfo currentSnap,
                BackupFileInfo nextSnap,
                Optional<BackupFileInfo> lastRetainedSnap,
                long currentTime,
                List<BackupFileInfo> lostLogs) {

            if (!useAdaptiveSnapRetention) {
                return true;
            }

            AdaptiveSnapMinInterval adaptiveInterval =
                    getAdaptiveInterval(currentTime, nextSnap.getModificationTime());
            long lastRetainedTime = lastRetainedSnap.isPresent()
                    ? lastRetainedSnap.get().getModificationTime()
                    : 0;
            long timeBetweenPreviousAndNext = nextSnap.getModificationTime() - lastRetainedTime;

            if (timeBetweenPreviousAndNext < adaptiveInterval.getInterval()) {
                long start = lastRetainedSnap.isPresent()
                        ? lastRetainedSnap.get().getZxid(ZxidPart.MAX_ZXID)
                        : Long.MIN_VALUE;
                Range<Long> range = Range.closed(start, currentSnap.getZxid(ZxidPart.MAX_ZXID));

                return doesRangeContainLostLogs(range, lostLogs);
            }

            return true;
        }

        private AdaptiveSnapMinInterval getAdaptiveInterval(long currentTime, long fileTime) {
            long timeDiff = currentTime - fileTime;

            if (timeDiff <= lowLatencyRecoveryThreshold) {
                return AdaptiveSnapMinInterval.LOW_LATENCY;
            } else if (timeDiff <= mediumLatencyRecoveryThreshold) {
                return AdaptiveSnapMinInterval.MEDIUM_LATENCY;
            } else {
                return AdaptiveSnapMinInterval.HIGH_LATENCY;
            }
        }

        private boolean doesRangeContainLostLogs(Range<Long> range, List<BackupFileInfo> lostLogs) {

            for(BackupFileInfo current : lostLogs) {
                if (current.getZxid(ZxidPart.MIN_ZXID) > range.upperEndpoint()) {
                    break;
                }

                if (range.isConnected(current.getZxidRange())) {
                    return true;
                }
            }

            return false;
        }

    }
}
