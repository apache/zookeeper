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
import java.util.ArrayList;
import java.util.List;

import com.google.common.base.Preconditions;
import com.google.common.collect.Range;

import org.apache.zookeeper.metrics.NullMetricsReceiver;
import org.apache.zookeeper.server.backup.BackupUtil.BackupFileType;
import org.apache.zookeeper.server.backup.BackupUtil.ZxidPart;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.util.ZxidUtils;

/**
 *  This is a tool to restore, from backup storage, a snapshot and set of transaction log files
 *  that combine to represent a transactionally consistent image of a ZooKeeper ensemble at
 *  some zxid.
 */
public class RestoreFromBackupTool {
    private static final int MAX_RETRIES = 10;

    BackupStorageProvider storage;

    FileTxnSnapLog snapLog;
    long zxidToRestore;
    boolean dryRun = false;

    List<BackupFileInfo> logs;
    List<BackupFileInfo> snaps;
    List<BackupFileInfo> filesToCopy;

    int mostRecentLogNeededIndex;
    int snapNeededIndex;
    int oldestLogNeededIndex;

    static public void main(String[] args) {
        RestoreFromBackupTool tool = new RestoreFromBackupTool();

        tool.parseArgs(args);

        if (!tool.runWithRetries()) {
            System.err.println("Restore failed. See log for details.");
            System.exit(10);
        }
    }

    public static void usage() {
        System.out.println("Usage: RestoreFromBackupTool restore <restore_point> <backup_store> <data_destination> <log_destination>");
        System.out.println("    restore_point: the point to restore to, either the string 'latest' or a zxid in hex format.");
        System.out.println("    backup_store: the connection information for the backup store");
        System.out.println("       For HDFS the format is: hdfs:<config_path>:<backup_path>");
        System.out.println("           config_path: the path to the hdfs configuration");
        System.out.println("           backup_path: the path within hdfs where the backups are stored");
        System.out.println("    data_destination: local destination path for restored snapshots");
        System.out.println("    log_destination: local destination path for restored txlogs");
    }

    /**
     * Default constructor; requires using parseArgs to setup state.
     */
    public RestoreFromBackupTool() {
        filesToCopy = new ArrayList<BackupFileInfo>();
        snapNeededIndex = -1;
        mostRecentLogNeededIndex = -1;
        oldestLogNeededIndex = -1;
    }

    /**
     * Constructor
     * @param storageProvider the backup provider from which to restore the backups
     * @param snapLog the snap and log provider to use
     * @param zxidToRestore the zxid upto which to restore, or Long.MAX to restore to the latest
     *                      available transactionally consistent zxid.
     * @param dryRun whether this is a dryrun in which case no files are actually copied
     */
    public RestoreFromBackupTool(
            BackupStorageProvider storageProvider,
            FileTxnSnapLog snapLog,
            long zxidToRestore,
            boolean dryRun) {

        this();

        this.storage = storageProvider;
        this.zxidToRestore = zxidToRestore;
        this.snapLog = snapLog;
        this.dryRun = dryRun;
    }

    /**
     * Parse and validate arguments to the tool
     * @param args the set of arguments
     * @return true if the arguments parse correctly; false in all other cases.
     * @throws IOException if the backup provider cannot be instantiated correctly.
     */
    public void parseArgs(String[] args) {
        if (args.length < 1) {
            System.err.println("Must specify a command to run");
            usage();
            System.exit(1);
        }

        if (!args[0].equals("restore")) {
            System.err.println("Invalid command" + args[0]);
            usage();
            System.exit(2);
        }

        if (args.length != 5 && args.length != 6) {
            System.err.println("Invalid number of arguments for restore command");
            usage();
            System.exit(3);
        }

        if (args[1].equals("latest")) {
            zxidToRestore = Long.MAX_VALUE;
        } else {
            int base = 10;
            String numStr = args[1];

            if (args[1].startsWith("0x")) {
                numStr = args[1].substring(2);
                base = 16;
            }

            try {
                zxidToRestore = Long.parseLong(numStr, base);
            } catch (NumberFormatException nfe) {
                System.err.println("Invalid number specified for restore point");
                usage();
                System.exit(4);
            }
        }

        String[] providerParts = args[2].split(":");

        if (providerParts.length != 3 || !providerParts[0].equals("hdfs")) {
            System.err.println("HDFS is the only backup provider supported or the specification is incorrect.");
            usage();
            System.exit(5);
        }

        if (args.length == 6) {
            if (!args[5].equals("-n")) {
                System.err.println("Invalid argument: " + args[5]);
                usage();
                System.exit(6);
            }

            dryRun = true;
        }

        try {
            storage = new HdfsBackupStorage(new File(providerParts[1]), providerParts[2]);
        } catch (IOException ioe) {
            System.err.println("Could not setup backup provider (HDFS)" + ioe);
            System.exit(7);
        }

        try {
            File snapDir = new File(args[3]);
            File logDir = new File(args[4]);
            snapLog = new FileTxnSnapLog(logDir, snapDir, NullMetricsReceiver.get());
        } catch (IOException ioe) {
            System.err.println("Could not setup transaction log utility." + ioe);
            System.exit(8);
        }
    }

    /**
     * Attempts to perform a restore with up to MAX_RETRIES retries.
     * @return true if the restore completed successfully, false in all other cases.
     */
    public boolean runWithRetries() {
        int tries = 0;

        if (dryRun) {
            System.out.println("This is a DRYRUN, no files will actually be copied.");
        }

        while (tries < MAX_RETRIES) {
            if (run()) {
                return true;
            }

            if (tries >= MAX_RETRIES) {
                break;
            }

            tries++;
            System.err.println("Restore attempt failed; attempting again. "
                    + tries + "/" + MAX_RETRIES);
        }

        System.err.println("Failed to restore after " + (tries + 1) + " attempts.");
        return false;
    }

    /**
     * Attempts to perform a restore.
     * @return true if the restore completed successfully, false in all other cases.
     */
    public boolean run() {
        try {
            if (!findFilesToRestore()) {
                System.err.println("Failed to find a valid snapshot and logs to restore.");
                return false;
            }

            for (BackupFileInfo backedupFile : filesToCopy) {
                String standardFilename = backedupFile.getStandardFile().getName();
                File base = backedupFile.getFileType() == BackupFileType.SNAPSHOT
                        ? snapLog.getSnapDir()
                        : snapLog.getDataDir();
                File destination = new File(base, standardFilename);

                if (!destination.exists()) {
                    System.out.println("Copying " + backedupFile.getBackedUpFile()
                            + " to "+ destination.getPath() + ".");

                    if (!dryRun) {
                        storage.copyToLocalStorage(backedupFile.getBackedUpFile(), destination);
                    }
                } else {
                    System.out.println("Skipping " + backedupFile.getBackedUpFile()
                            + " because it already exists as " + destination.getPath() + ".");
                }
            }

            if (zxidToRestore != Long.MAX_VALUE && !this.dryRun) {
                snapLog.truncateLog(zxidToRestore);
            }
        } catch (IOException ioe) {
            System.err.println("Hit exception when attempting to restore." + ioe.getMessage());
            return false;
        }

        return true;
    }

    /**
     * Finds the set of files (snapshot and txlog) that are required to restore to a transactionally
     * consistent point for the requested zxid.
     * Note that when restoring to the latest zxid, the transactionally consistent point may NOT
     * be the latest backed up zxid if logs have been lost in between; or if there is no
     * transactionally consistent point in which nothing will be restored but the restored will
     * be considered successful.
     * @return true if a transactionally consistent set of files could be found for the requested
     *         restore point; false in all other cases.
     * @throws IOException
     */
    private boolean findFilesToRestore() throws IOException {
        snaps = BackupUtil.getBackupFiles(
                    storage,
                    BackupFileType.SNAPSHOT,
                    ZxidPart.MIN_ZXID,
                    BackupUtil.SortOrder.DESCENDING);
        logs = BackupUtil.getBackupFiles(
                storage,
                new BackupFileType[] { BackupFileType.TXNLOG, BackupFileType.LOSTLOG },
                ZxidPart.MIN_ZXID,
                BackupUtil.SortOrder.DESCENDING);
        filesToCopy = new ArrayList<BackupFileInfo>();

        snapNeededIndex = -1;

        while (findNextPossibleSnapshot()) {
            if (findLogRange()) {
                filesToCopy.add(snaps.get(snapNeededIndex));
                filesToCopy.addAll(logs.subList(mostRecentLogNeededIndex, oldestLogNeededIndex + 1));
                return true;
            }

            if (zxidToRestore != Long.MAX_VALUE) {
                break;
            }
        }

        // Restoring to an empty data tree (i.e. no backup files) is valid for restoring to
        // latest
        if (zxidToRestore == Long.MAX_VALUE) {
            return true;
        }

        return false;
    }

    /**
     * Find the next snapshot whose range is below the requested restore point.
     * Note: in practice this only gets called once when zxidToRestore != Long.MAX
     * @return true if a snapshot is found; false in all other cases.
     */
    private boolean findNextPossibleSnapshot() {
        for (snapNeededIndex++; snapNeededIndex < snaps.size(); snapNeededIndex++) {
            if (snaps.get(snapNeededIndex).getZxidRange().upperEndpoint() <= zxidToRestore) {
                return true;
            }
        }

        return false;
    }

    /**
     * Find the range of log files needed to make the current proposed snapshot transactionally
     * consistent to the requested zxid.
     * When zxidToRestore == Long.MAX the transaction log range terminates either on the most
     * recent backedup txnlog OR the last txnlog prior to a lost log range (assuming that log
     * still makes the snapshot transactionally consistent).
     * @return true if a valid log range is found; false in all other cases.
     */
    private boolean findLogRange() {
        Preconditions.checkState(snapNeededIndex >= 0 && snapNeededIndex < snaps.size());

        if (logs.size() == 0) {
            return false;
        }

        BackupFileInfo snap = snaps.get(snapNeededIndex);
        Range<Long> snapRange = snap.getZxidRange();
        oldestLogNeededIndex = logs.size() - 1;
        mostRecentLogNeededIndex = 0;

        // Find the first txlog that might make the snapshot valid, OR is a lost log
        for (int i = 0; i < logs.size() - 1; i++) {
            BackupFileInfo log = logs.get(i);
            Range<Long> logRange = log.getZxidRange();

            if (logRange.lowerEndpoint() <= snapRange.lowerEndpoint()) {
                oldestLogNeededIndex = i;
                break;
            }
        }

        // Starting if the oldest log that might allow the snapshot to be valid, find the txnlog
        // that includes the restore point, OR is a lost log
        for (int i = oldestLogNeededIndex; i > 0; i--) {
            BackupFileInfo log = logs.get(i);
            Range<Long> logRange = log.getZxidRange();

            if (log.getFileType() == BackupFileType.LOSTLOG
                    || logRange.upperEndpoint() >= zxidToRestore) {

                mostRecentLogNeededIndex = i;
                break;
            }
        }

        return validateLogRange();
    }

    private boolean validateLogRange() {
        Preconditions.checkState(oldestLogNeededIndex >= 0);
        Preconditions.checkState(oldestLogNeededIndex < logs.size());
        Preconditions.checkState(mostRecentLogNeededIndex >= 0);
        Preconditions.checkState(mostRecentLogNeededIndex < logs.size());
        Preconditions.checkState(oldestLogNeededIndex >= mostRecentLogNeededIndex);
        Preconditions.checkState(snapNeededIndex >= 0);
        Preconditions.checkState(snapNeededIndex < snaps.size());

        BackupFileInfo snap = snaps.get(snapNeededIndex);
        BackupFileInfo oldestLog = logs.get(oldestLogNeededIndex);
        BackupFileInfo newestLog = logs.get(mostRecentLogNeededIndex);

        if (oldestLog.getFileType() == BackupFileType.LOSTLOG) {
            System.err.println(
                    "Could not find logs to make the snapshot '" + snap.getBackedUpFile()
                    + "' valid. Lost logs at " + logs.get(oldestLogNeededIndex).getZxidRange());
            return false;
        }

        if (newestLog.getFileType() == BackupFileType.LOSTLOG) {
            if (zxidToRestore == Long.MAX_VALUE
                    && oldestLogNeededIndex != mostRecentLogNeededIndex) {
                // When restoring to the latest, we can use the last valid log prior to lost log
                // range.
                mostRecentLogNeededIndex++;
            } else {
                System.err.println(
                        "Could not find logs to make the snapshot '" + snap.getBackedUpFile()
                        + "' valid. Lost logs at "
                        + logs.get(mostRecentLogNeededIndex).getZxidRange()
                        + ".");
                return false;
            }
        }

        Range<Long> fullRange = oldestLog.getZxidRange().span(newestLog.getZxidRange());

        if (fullRange.lowerEndpoint() > snap.getZxidRange().lowerEndpoint()) {
            System.err.println(
                    "Could not find logs to make snap '" + snap.getBackedUpFile()
                    + "' valid. Logs start at zxid "
                    + ZxidUtils.zxidToString(fullRange.lowerEndpoint())
                    + ".");
            return false;
        }

        if (fullRange.upperEndpoint() < snap.getZxidRange().upperEndpoint()) {
            System.err.println("Could not find logs to make snap '" + snap.getBackedUpFile()
                    + "' valid. Logs end at zxid "
                    + ZxidUtils.zxidToString(fullRange.upperEndpoint())
                    + ".");
            return false;
        }

        if (zxidToRestore != Long.MAX_VALUE && fullRange.upperEndpoint() < zxidToRestore) {
            System.err.println("Could not find logs to restore to zxid " + zxidToRestore
                    + ". Logs end at zxid " + ZxidUtils.zxidToString(fullRange.upperEndpoint())
                    + ".");
            return false;
        }

        return true;
    }
}
