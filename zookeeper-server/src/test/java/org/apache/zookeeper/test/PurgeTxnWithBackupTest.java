package org.apache.zookeeper.test;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;

import org.apache.zookeeper.metrics.NullMetricsReceiver;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.backup.BackupPoint;
import org.apache.zookeeper.server.backup.BackupStatus;
import org.apache.zookeeper.server.backup.BackupUtil;
import org.apache.zookeeper.server.backup.RetentionManager;
import org.apache.zookeeper.server.persistence.FileSnap;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.SnapStream;
import org.apache.zookeeper.server.persistence.Util;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.twitter.finagle.stats.InMemoryStatsReceiver;
import com.twitter.finagle.stats.ReadableCounter;
import com.twitter.finagle.stats.StatsReceiver;

public class PurgeTxnWithBackupTest {
    private static final Logger LOG = LoggerFactory.getLogger(PurgeTxnWithBackupTest.class);


    @Test
    public void testUseRetainSnapCountOverBackupStatus() throws Exception {
        run(snapsAndLogs(s(100), s(200), s(300), s(400), l(99), l(200), l(300)),
            b(500, 500),
            3,
            snapsAndLogs(s(200), s(300), s(400), l(200), l(300)));
    }

    @Test
    public void testBackupStatusAtBeginningOfLogFile() throws Exception {
        run(snapsAndLogs(
                s(50), s(100), s(200), s(300), s(400), s(600),
                l(50), l(99), l(150), l(200), l(300), l(450), l(480)),
            b(150, 500),
            3,
            snapsAndLogs(
                s(100), s(200), s(300), s(400), s(600),
                l(99), l(150), l(200), l(300), l(450), l(480)));
    }

    @Test
    public void testBackupStatusAtEndOfLogFile() throws Exception {
        run(snapsAndLogs(
                s(50), s(100), s(200), s(300), s(400), s(600),
                l(50), l(99), l(150), l(200), l(300)),
            b(199, 500),
            3,
            snapsAndLogs(s(200), s(300), s(400), s(600), l(200), l(300)));
    }

    @Test
    public void testBackupStatusInMiddleOfLogFile() throws Exception {
        run(snapsAndLogs(
                s(50), s(100), s(200), s(300), s(400), s(600),
                l(50), l(99), l(150), l(200), l(300), l(450), l(480)),
            b(165, 500),
            3,
            snapsAndLogs(
                s(100), s(200), s(300), s(400), s(600),
                l(99), l(150), l(200), l(300), l(450), l(480)));
    }

    @Test
    public void testBackupStatusAtBeginningOfSnapFile() throws Exception {
        run(snapsAndLogs(
                s(50), s(100), s(200), s(300), s(400), s(600),
                l(50), l(99), l(150), l(200), l(300), l(450), l(480)),
            b(700, 100),
            3,
            snapsAndLogs(
                s(100), s(200), s(300), s(400), s(600),
                l(99), l(150), l(200), l(300), l(450), l(480)));
    }

    @Test
    public void testBackupStatusAtEndOfSnapFile() throws Exception {
        run(snapsAndLogs(
                s(50), s(100), s(200), s(300), s(400), s(600),
                l(50), l(99), l(150), l(200), l(300), l(450), l(480)),
            b(700, 199),
            3,
            snapsAndLogs(
                s(200), s(300), s(400), s(600),
                l(200), l(300), l(450), l(480)));
    }

    @Test
    public void testBackupStatusInMiddleOfSnapFile() throws Exception {
        run(snapsAndLogs(
                s(50), s(100), s(200), s(300), s(400), s(600),
                l(50), l(99), l(150), l(200), l(300), l(450), l(480)),
            b(700, 250),
            3,
            snapsAndLogs(
                s(200), s(300), s(400), s(600),
                l(200), l(300), l(450), l(480)));
    }

    private String s(long zxid) {
        return Util.makeSnapshotName(zxid);
    }

    private String l(long zxid) {
        return Util.makeLogName(zxid);
    }

    private BackupPoint b(long logZxid, long snapZxid) {
        return new BackupPoint(logZxid, snapZxid);
    }

    private String[] snapsAndLogs(String... files) {
        return files;
    }

    private class DataFileNameComparator implements Comparator<String> {
        @Override
        public int compare(String s, String s2) {
            String[] parts = s.split("\\.");
            String[] parts2 = s2.split("\\.");

            int result = parts[0].compareTo(parts2[0]);

            if (result == 0) {
                result = new Long(Long.parseLong(parts[1], 16)).compareTo(Long.parseLong(parts2[1], 16));
            }

            return result;
        }
    }

    private void run(String[] dataFiles,
                     BackupPoint backupPoint,
                     int snapRetainCount,
                     String[] expected) throws IOException {

        File tmpDir = null;
        File backupStatusDir = null;

        try {
            tmpDir = ClientBase.createTmpDir();
            backupStatusDir = ClientBase.createTmpDir();

            File versionedTmpDir = Util.makeVersionedFile(tmpDir);
            versionedTmpDir.mkdirs();

            for (String file : dataFiles) {
                FileSnap snap = new FileSnap(tmpDir);
                File fullName = new File(versionedTmpDir, file);

                if (file.startsWith(Util.SNAP_PREFIX)) {
                    snap.serialize(new DataTree(), new HashMap<Long, Integer>(), fullName, false);
                    Assert.assertTrue(SnapStream.isValidSnapshot(fullName));
                } else {
                    fullName.createNewFile();
                }
            }

            FilenameFilter fileFilter = new FilenameFilter() {
                @Override
                public boolean accept(File file, String s) {
                    return s.startsWith(Util.TXLOG_PREFIX)
                            || s.startsWith(Util.SNAP_PREFIX)
                            || s.startsWith(BackupUtil.LOST_LOG_PREFIX);
                }
            };

            InMemoryStatsReceiver statsReceiver = new InMemoryStatsReceiver();
            StatsReceiver taskScope = statsReceiver.scope("local_purge_task");
            BackupStatus status = new BackupStatus(backupStatusDir);
            status.createIfNeeded();
            status.update(backupPoint);

            String[] startingFiles = versionedTmpDir.list(fileFilter);

            RetentionManager.LocalPurgeTask purgeTask = new RetentionManager.LocalPurgeTask(
                    false, /* not dry run */
                    new FileTxnSnapLog(tmpDir, tmpDir, NullMetricsReceiver.get()),
                    backupStatusDir,
                    snapRetainCount,
                    statsReceiver);
            purgeTask.run();

            Assert.assertEquals(1, rcv(taskScope, "iterations", "count"));

            String[] remainingFiles = versionedTmpDir.list(fileFilter);

            Arrays.sort(remainingFiles, new DataFileNameComparator());
            Arrays.sort(expected, new DataFileNameComparator());

            LOG.info("Remaining files");
            for (String file : remainingFiles) {
                LOG.info(file);
            }
            LOG.info("Expected files");
            for (String file : expected) {
                LOG.info(file);
            }

            Assert.assertArrayEquals(expected, remainingFiles);

            StatsReceiver purgedScope = taskScope.scope("purged");
            Assert.assertEquals(startingFiles.length - remainingFiles.length,
                    rcv(purgedScope, Util.SNAP_PREFIX, "count") +
                        rcv(purgedScope, Util.TXLOG_PREFIX, "count"));

        } finally {
            if (tmpDir != null) {
                ClientBase.recursiveDelete(tmpDir);
            }

            if (backupStatusDir != null) {
                ClientBase.recursiveDelete(backupStatusDir);
            }
        }
    }

    private long rcv(StatsReceiver stats, String scope, String name) {
        return ((ReadableCounter)stats.scope(scope).counter0(name)).apply();
    }

}
