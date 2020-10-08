package org.apache.zookeeper.test;

import java.io.File;
import java.io.IOException;

import com.google.common.collect.Lists;

import org.apache.zookeeper.metrics.NullMetricsReceiver;
import org.apache.zookeeper.server.backup.BackupFileInfo;
import org.apache.zookeeper.server.backup.BackupUtil;
import org.apache.zookeeper.server.backup.RestoreFromBackupTool;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.Util;
import org.apache.zookeeper.test.TestBackupProvider.BackupInfoLists;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for validating funcationality of the RestoreFromBackupTool
 *
 * The initial state and expected outcome of test cases are specified using help methods that
 * build the necessary objects.
 * The test cases expect:
 *  - the zxid up to which to restore (or Long.MAX_VALUE to restore to latest possible zxid)
 *  - a list of files that exist in the backup store -- uses the 'backupState' helper method
 *  - a list of files that are expected to be copied -- uses the 'expectedCopies' helper method
 *  - whether the tool should succeed or not
 *
 * Note that the backupState and expectedCopies helper methods are the exact same except for their names, two methods
 * are used to allow for better distinction in the test cases.
 *
 * The list of backed-up and/or copied files are specified using a sequence of objects created
 * by the following help methods:
 *  - s(long, long)  : creates a snap with the given starting zxid and zxid required for consistency
 *  - l(long, long)  : creates a log with the given starting and ending zxids
 *  - ll(long, long) : creates a range in which logs where lost
 *
 *  The s, l, and ll methods can be called in any order and the restore tool should work with any
 *  order, but by convention they are listed in increasing zxid order of the first zxid for each
 *  file.
 *  This makes it easier to see the state that the testcase is testing.
 */
public class RestoreToolTest {
    /**
     * Custom implementation of FileTxnSnapLog in order to override and track calls to
     * truncate
      */
    private static class TestFileTxnSnapLog extends FileTxnSnapLog {
        long truncationZxid = -1;

        public TestFileTxnSnapLog(File snapDir, File logDir) throws IOException {
            super(logDir, snapDir, NullMetricsReceiver.get());
        }

        public boolean truncateLog(long zxid) {
            Assert.assertEquals("Should not truncate multiple times", -1, truncationZxid);
            Assert.assertTrue("Truncation zxid must be valid", zxid >= 1);
            Assert.assertTrue("Cannot truncate to infinity", zxid != Long.MAX_VALUE);
            truncationZxid = zxid;
            return true;
        }

        public long getTruncationZxid() { return truncationZxid; }
    }

    static File tmpDir;

    @BeforeClass
    public static void classBefore() throws IOException {
        tmpDir = ClientBase.createTmpDir();
    }

    @AfterClass
    public static void classAfter() {
        ClientBase.recursiveDelete(tmpDir);
    }

    @Test
    public void restoreToLatest_noBackups() throws IOException {
        run(true, BackupInfoLists.EMPTY, BackupInfoLists.EMPTY);
    }

    @Test
    public void restoreToLatest_snapOnly() throws IOException {
        run(true, backupState(s(100, 200)), BackupInfoLists.EMPTY);
    }

    @Test
    public void restoreToLatest_logOnly() throws IOException {
        run(true, backupState(l(100, 200)), BackupInfoLists.EMPTY);
    }

    @Test
    public void restoreToLatest_matchingSnapAndLog() throws IOException {
        run(true,
            backupState(
                    s(100, 200),
                    l(100, 200)),
            expectedCopies(
                    s(100, 200),
                    l(100, 200)));
    }

    @Test
    public void restoreToLatest_additionalLog() throws IOException {
        run(true,
            backupState(
                    s(100, 200),
                    s(50, 55),
                    l(100, 200),
                    l(201, 205)),
            expectedCopies(
                    s(100, 200),
                    l(100, 200),
                    l(201, 205)));
    }

    @Test
    public void restoreToLatest_validWithLostLogBothSides() throws IOException {
        run(true,
            backupState(
                    s(100, 200),
                    s(50, 55),
                    ll(55, 99),
                    l(100, 200),
                    l(201, 205),
                    ll(206, 210)),
            expectedCopies(
                    s(100, 200),
                    l(100, 200),
                    l(201, 205)));
    }

    @Test
    public void restoreToLatest_needOlderSnapDueToLostLog() throws IOException {
        run(true,
            backupState(
                    s(100, 200),
                    s(400, 500),
                    l(50, 249),
                    l(250, 400),
                    ll(401, 410),
                    l(450, 600)),
            expectedCopies(
                    s(100, 200),
                    l(50, 249),
                    l(250, 400)));
    }

    @Test
    public void restoreToLatest_needOlderSnapDueToConsistency() throws IOException {
        run(true,
            backupState(
                    s(100, 200),
                    s(400, 500),
                    l(50, 249),
                    l(250, 450)),
            expectedCopies(
                    s(100, 200),
                    l(50, 249),
                    l(250, 450)));
    }

    @Test
    public void restoreToLatest_useUptoLostLog() throws IOException {
        run(true,
            backupState(
                    s(97, 200),
                    l(45, 48),
                    l(50, 99),
                    l(100, 165),
                    l(170, 210),
                    l(215, 250),
                    l(251, 400),
                    ll(410, 450),
                    l(500, 700)),
            expectedCopies(
                    s(97, 200),
                    l(50, 99),
                    l(100, 165),
                    l(170, 210),
                    l(215, 250),
                    l(251, 400)));
    }

    @Test
    public void restoreToZxid_priorToEarliestSnap() throws IOException {
        run(35,
            false,
            backupState(
                    s(50, 100),
                    l(50, 100)),
            BackupInfoLists.EMPTY);
    }

    @Test
    public void restoreToZxid_pointInLatestSnap() throws IOException {
        run(335,
            true,
            backupState(
                    l(45, 48),
                    l(49, 50),
                    l(50, 75),
                    s(50, 100),
                    l(75, 150),
                    l(150, 350),
                    s(300, 400),
                    l(370, 401),
                    l(450, 500)),
            expectedCopies(
                    l(50, 75),
                    s(50, 100),
                    l(75, 150),
                    l(150, 350)));
    }

    @Test
    public void restoreToZxid_IgnoreLostLogsOutOfScope() throws IOException {
        run(500,
            true,
            backupState(
                    ll(50, 250),
                    l(251, 500),
                    s(300, 400),
                    ll(501, 600)),
            expectedCopies(
                    l(251, 500),
                    s(300, 400)));
    }

    @Test
    public void restoreToZxid_OnLowerEdgeOfLog() throws IOException {
        run(501,
            true,
            backupState(
                    l(50, 250),
                    l(251, 500),
                    s(300, 400),
                    l(501, 600),
                    l(600, 700)),
            expectedCopies(
                    l(251, 500),
                    s(300, 400),
                    l(501, 600)));
    }

    /**
     * Helper method for building the list of files that exist in the backup provider
     * @param descriptions the descriptions of the individual files
     * @return the lists of backup files separated by type
     */
    private BackupInfoLists backupState(BackupFileInfo... descriptions) {
        return BackupInfoLists.buildLists(Lists.newArrayList(descriptions));
    }

    /**
     * Helper method for building the list of files that are expected to be copied by the restore
     * tool
     * <NOTE>Same as 'backupState' but with 'expectedCopies' representing expected to the two are easier to distinguish
     * in the test cases.</NOTE>
     * @param descriptions the descriptions of the individual files
     * @return the lists of restored files separated by type
     */
    private BackupInfoLists expectedCopies(BackupFileInfo... descriptions) {
        return backupState(descriptions);
    }

    /**
     * Helper method for creating a description of a snap in the test cases
     * @param low the starting zxid for the snap
     * @param high the zxid needed for transactional consistency
     * @return the backup file info for the described snap
     */
    private BackupFileInfo s(long low, long high) {
        return new BackupFileInfo(
                new File(TestBackupProvider.backupName(Util.SNAP_PREFIX, low, high)),
                System.currentTimeMillis(),
                100);
    }

    /**
     * Helper method for creating a description of a txlog in the test cases
     * @param low the starting zxid for the log
     * @param high the ending zxid for the log
     * @return the backup file info for the described log
     */
    private BackupFileInfo l(long low, long high) {
        return new BackupFileInfo(
                new File(TestBackupProvider.backupName(Util.TXLOG_PREFIX, low, high)),
                System.currentTimeMillis(),
                100);
    }

    /**
     * Helper method for creating a description of a lost log range in the test cases
     * @param low the first zxid of the range that has been lost
     * @param high the last zxid of the range that has been lost
     * @return the backup file info for the described lost log range
     */
    private BackupFileInfo ll(long low, long high) {
        return new BackupFileInfo(
                new File(TestBackupProvider.backupName(BackupUtil.LOST_LOG_PREFIX, low, high)),
                System.currentTimeMillis(),
                100);
    }



    /**
     * Run a test case
     *  - creates the backup storage in the state specified
     *  - runs the restore tool requesting a restore to the latest possible zxid
     *  - validates the results of the tool (run result plus names of copied files)
     * @param expectedRunResult the expected result of the restore tool
     * @param backupStateDescriptions the descriptions for the files that exist in the
     *                                backup storage provider for this test case
     * @param expectedBackupsCopiedDescriptions the descriptions for the files that are expected
     *                                          to be copied by the restore tool as part of the
     *                                          test run
     * @throws IOException
     */
    private static void run(
            boolean expectedRunResult,
            BackupInfoLists backupStateDescriptions,
            BackupInfoLists expectedBackupsCopiedDescriptions) throws IOException {

        run(Long.MAX_VALUE,
            expectedRunResult,
            backupStateDescriptions,
            expectedBackupsCopiedDescriptions);
    }

    /**
     * Run a test case
     *  - creates the backup storage in the state specified
     *  - runs the restore tool requesting a restore to the requested zxid
     *  - validates the results of the tool (run result plus names of copied files)
     * @param expectedRunResult the expected result of the restore tool
     * @param backupStateDescriptions the descriptions for the files that exist in the
     *                                backup storage provider for this test case
     * @param expectedBackupsCopiedDescriptions the descriptions for the files that are expected
     *                                          to be copied by the restore tool as part of the
     *                                          test run
     * @throws IOException
     */
    private static void run(
            long zxid,
            boolean expectedRunResult,
            BackupInfoLists backupStateDescriptions,
            BackupInfoLists expectedBackupsCopiedDescriptions) throws IOException {

        TestBackupProvider storage = new TestBackupProvider();
        TestFileTxnSnapLog snapLog = new TestFileTxnSnapLog(tmpDir, tmpDir);
        RestoreFromBackupTool tool = new RestoreFromBackupTool(storage, snapLog, zxid, false);

        Assert.assertTrue("Cannot expect to have copied LostLogs",
                expectedBackupsCopiedDescriptions.lostLogs.isEmpty());

        storage.setAvailableBackups(backupStateDescriptions);

        Assert.assertEquals("Restore result", expectedRunResult, tool.run());
        Assert.assertArrayEquals("Snaps",
                TestBackupProvider.getBackupNames(expectedBackupsCopiedDescriptions.snaps),
                TestBackupProvider.getBackupNames(storage.getCopiedSnaps()));
        Assert.assertArrayEquals("Logs",
                TestBackupProvider.getBackupNames(expectedBackupsCopiedDescriptions.logs),
                TestBackupProvider.getBackupNames(storage.getCopiedLogs()));

        if (zxid == Long.MAX_VALUE) {
            Assert.assertEquals("Truncation should NOT have been called.",
                    -1,
                    snapLog.getTruncationZxid());

        } else if (expectedRunResult) {
            Assert.assertEquals("Truncation should have been called with the proper zxid",
                    zxid,
                    snapLog.getTruncationZxid());
        }
    }
}
