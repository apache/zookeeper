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

package org.apache.zookeeper.test;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.Lists;

import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.backup.BackupFileInfo;
import org.apache.zookeeper.server.backup.BackupUtil;
import org.apache.zookeeper.server.backup.BackupUtil.BackupFileType;
import org.apache.zookeeper.server.backup.RetentionManager.BackupStorageRetentionTask;
import org.apache.zookeeper.server.persistence.Util;
import org.apache.zookeeper.test.TestBackupProvider.BackupInfoLists;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.twitter.finagle.stats.InMemoryStatsReceiver;
import com.twitter.finagle.stats.ReadableCounter;
import com.twitter.finagle.stats.StatsReceiver;

/**
 * Tests the backup storage retention manager
 */
public class BackupRetentionManagerTest extends ZKTestCase {
    private static final Logger LOG = LoggerFactory.getLogger(BackupRetentionManagerTest.class);
    private static final long retentionAsOfTime = System.currentTimeMillis();

    public void testNoFilesYoungerThanRetentionPeriod() throws Exception {
        run(
            backupState(s(100, -5), s(200, -4), l(100, -5), l(200, -4), ll(50, -7)),
            10,
            BackupInfoLists.EMPTY);
    }

    @Test
    public void testRetentionOfLastSnapBeforeCutoff() throws Exception {
        run(
            backupState(s(100, -5), s(200, -4), l(100, -5), l(200, -4), ll(50, -7)),
            4,
            expectedDeletions(ll(50, -7)));
    }

    @Test
    public void testAllFilesOlderThanCutOff() throws Exception {
        run(
            backupState(s(100, -5), s(200, -4), l(100, -5), l(200, -4), ll(50, -7)),
            2,
            expectedDeletions(s(100, -5), l(100, -5), ll(50, -7)));
    }

    @Test
    public void testNoSnapsAndOtherFilesYoungerThanCutOff() throws Exception {
        run(
            backupState(l(100, -5), l(200, -4), ll(50, -7)),
            10,
            BackupInfoLists.EMPTY);
    }

    @Test
    public void testCommonCase() throws Exception {
        run(
            backupState(
                    s(50, -20), s(100, -15), s(200, -10), s(300, -5), s(400, -2),
                    l(49, -20), l(75, -15), l(104, -15), l(150, -15), l(200, -14),
                    l(300, -4), l(340, -2), l(500, -1),
                    ll(30, -25), ll(600, 0)),
            14,
            expectedDeletions(s(50, -20), l(49, -20), l(75, -15), ll(30, -25)));
    }

    @Test
    public void testChildDirectoriesFullDepth() throws Exception {
        BackupInfoLists state = new BackupInfoLists();
        BackupInfoLists deletions = new BackupInfoLists();
        
        File[] dirs = new File[] { null, new File("z2"), new File("z1/c1"), new File("z1/c2"),
                new File("z2/c1"), new File("z3/sz/c1"), new File("z3/sz/c2") };
        
        for (File dir : dirs) {
            state.add(
                backupState(
                    s(dir, 50, -20), s(dir, 100, -15), s(dir, 200, -10), s(dir, 300, -5),
                    s(dir, 400, -2),
                    l(dir, 49, -20), l(dir, 75, -15), l(dir, 104, -15), l(dir, 150, -15),
                    l(dir, 200, -14), l(dir, 300, -4), l(dir, 340, -2), l(dir, 500, -1),
                    ll(dir, 30, -25), ll(dir, 600, 0)));
            deletions.add(
                expectedDeletions(
                    s(dir, 50, -20), l(dir, 49, -20), l(dir, 75, -15), ll(dir, 30, -25)));
        }

        File dir = new File("z1/c3");
        state.add(
            backupState(
                s(dir, 100, -10), s(dir, 150, -4),
                l(dir, 100, -10), l(dir, 150, -4),
                ll(dir, 50, -12)));

        dir = new File("z2/c2");
        state.add(
            backupState(
                s(dir, 10, -21), s(dir, 50, -20), s(dir, 100, -15), s(dir, 200, -10),
                s(dir, 300, -5), s(dir, 400, -2), s(dir, 500, -1),
                l(dir, 10, -21), l(dir, 49, -20), l(dir, 75, -15), l(dir, 104, -15),
                l(dir, 150, -15), l(dir, 200, -14), l(dir, 300, -4), l(dir, 340, -2),
                l(dir, 500, -1), l(dir, 600, -1),
                ll(dir, 5, -26), ll(dir, 30, -25), ll(dir, 600, 0)));
        deletions.add(
            expectedDeletions(
                s(dir, 10, -21), s(dir, 50, -20),
                l(dir, 10, -21), l(dir, 49, -20), l(dir, 75, -15),
                ll(dir, 5, -26), ll(dir, 30, -25)));

        run(state, 5, 14, false, deletions);
    }

    @Test
    public void testChildDirectoriesPartialDepth() throws Exception {
        BackupInfoLists state = new BackupInfoLists();
        BackupInfoLists deletions = new BackupInfoLists();

        File[] dirs = new File[] { new File("z1/c1"), new File("z1/c2"), new File("z2/c1"),
                new File("z2/sz/c1"), new File("z2/sz/c2") };
        File[] deletionDirs = new File[] { new File("z1/c1"), new File("z1/c2"), new File("z2/c1")};

        for (File dir : dirs) {
            state.add(
                backupState(
                    s(dir, 50, -20), s(dir, 100, -15), s(dir, 200, -10), s(dir, 300, -5),
                    s(dir, 400, -2),
                    l(dir, 49, -20), l(dir, 75, -15), l(dir, 104, -15), l(dir, 150, -15),
                    l(dir, 200, -14), l(dir, 300, -4), l(dir, 340, -2), l(dir, 500, -1),
                    ll(dir, 30, -25), ll(dir, 600, 0)));
        }

        for (File dir : deletionDirs) {
            deletions.add(
                expectedDeletions(
                    s(dir, 50, -20), l(dir, 49, -20), l(dir, 75, -15), ll(dir, 30, -25)));
        }

        run(state, 2, 14, false, deletions);
    }

    @Test
    public void testRecursionDepthOfZero() throws Exception {
        File c1 = new File("c1");

        run(
            backupState(
                s(100, -5), s(200, -4), l(100, -5), l(200, -4), ll(50, -7),
                s(c1, 100, -5), s(c1, 200, -4), l(c1, 100, -5), l(c1, 200, -4), ll(c1, 50, -7)),
            0,
            expectedDeletions(
                s(100, -5), l(100, -5), ll(50, -7)));
    }

    @Test
    public void testAdaptiveSnapRetention() throws Exception {
        
        // With a 10 day retention period, the interval cut-offs are at 2 days and 5 days
        // We'll have a bunch of snaps in each interval, some of which can be removed, others
        // which cannot.

        run(
            backupState(
                // low latency period
                sm(544, nt(0, 0, 1)), sm(528, nt(0, 0, 10)), sm(512, nt(0, 0, 40)),
                sm(496, nt(0, 1, 0)), sm(480, nt(0, 1, 5)), sm(464, nt(0, 1, 10)),
                sm(448, nt(0, 1, 12)), sm(432, nt(0, 1, 15)), sm(416, nt(0, 1, 30)),
                sm(400, nt(0, 1, 46)), sm(384, nt(1, 20, 30)), sm(368, nt(1, 23, 55)),
                // medium latency period
                sm(352, nt(2, 0, 15)), sm(336, nt(2, 0, 30)), sm(320, nt(2, 0, 45)),
                sm(304, nt(2, 1, 0)), sm(288, nt(2, 1, 15)), sm(272, nt(3, 12, 0)),
                sm(256, nt(3, 12, 15)), sm(240, nt(3, 12, 30)), sm(224, nt(3, 16, 0)),
                sm(208, nt(4, 23, 30)),
                // long latency period
                sm(192, nt(5, 0, 15)), sm(176, nt(5, 1, 30)), sm(160, nt(5, 2, 30)),
                sm(144, nt(5, 3, 30)), sm(128, nt(5, 4, 30)), sm(112, nt(6, 10, 0)),
                sm(96, nt(9, 15, 0)), sm(80, nt(9, 16, 0)), sm(64, nt(9, 20, 0)),
                // outside of retention period
                sm(48, nt(10, 0, 5)), sm(32, nt(10, 8, 0)), sm(16, nt(12, 0, 0))),
            0, // no recursion
            10,
            true, // use adaptive snap retention
            expectedDeletions(
                    // low latency period deletions
                    sm(464, nt(0, 1, 10)), sm(448, nt(0, 1, 12)),
                    // medium latency period deletions
                    sm(320, nt(2, 0, 45)), sm(304, nt(2, 1, 0)), sm(256, nt(3, 12, 15)),
                    // high latency period deletions
                    sm(176, nt(5, 1, 30)), sm(160, nt(5, 2, 30)), sm(144, nt(5, 3, 30)),
                    sm(80, nt(9, 16, 0)),
                    // outside of retention period deletions
                    sm(32, nt(10, 8, 0)), sm(16, nt(12, 0, 0))));
    }

    @Test
    public void testAdaptiveSnapRetentionWithLostLogs() throws Exception {
        run(
            backupState(
                // low latency period
                sm(544, nt(0, 0, 1)), sm(528, nt(0, 0, 10)), sm(512, nt(0, 0, 40)),
                sm(496, nt(0, 1, 0)), sm(480, nt(0, 1, 5)), sm(464, nt(0, 1, 10)),
                sm(448, nt(0, 1, 12)), sm(432, nt(0, 1, 15)), sm(416, nt(0, 1, 30)),
                sm(400, nt(0, 1, 46)), sm(384, nt(1, 20, 30)), sm(368, nt(1, 23, 55)),
                // medium latency period
                sm(352, nt(2, 0, 15)), sm(336, nt(2, 0, 30)), sm(320, nt(2, 0, 45)),
                sm(304, nt(2, 1, 0)), sm(288, nt(2, 1, 15)), sm(272, nt(3, 12, 0)),
                sm(256, nt(3, 12, 15)), sm(240, nt(3, 12, 30)), sm(224, nt(3, 16, 0)),
                sm(208, nt(4, 23, 30)),
                // long latency period
                sm(192, nt(5, 0, 15)), sm(176, nt(5, 1, 30)), sm(160, nt(5, 2, 30)),
                sm(144, nt(5, 3, 30)), sm(128, nt(5, 4, 30)), sm(112, nt(6, 10, 0)),
                sm(96, nt(9, 15, 0)), sm(80, nt(9, 16, 0)), sm(64, nt(9, 20, 0)),
                // outside of retention period
                sm(48, nt(10, 0, 5)), sm(32, nt(10, 8, 0)), sm(16, nt(12, 0, 0)),
                llm(36, nt(10, 6, 0)), llm(144, nt(5, 3, 30)), llm(464, nt(0, 1, 10))),
            0, // no recursion
            10,
            true, // use adaptive snap retention
            expectedDeletions(
                    // low latency period deletions
                    sm(448, nt(0, 1, 12)),
                    // medium latency period deletions
                    sm(320, nt(2, 0, 45)), sm(304, nt(2, 1, 0)), sm(256, nt(3, 12, 15)),
                    // high latency period deletions
                    sm(176, nt(5, 1, 30)), sm(80, nt(9, 16, 0)),
                    // outside of retention period deletions
                    sm(32, nt(10, 8, 0)), sm(16, nt(12, 0, 0)),
                    // lostLogs
                    llm(36, nt(10, 6, 0))));
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
     * Helper method for building the list of files that are expected to be deleted by the retention
     * manager
     * @param descriptions the descriptions of the individual files
     * @return the lists of restored files separated by type
     */
    private BackupInfoLists expectedDeletions(BackupFileInfo... descriptions) {
        return backupState(descriptions);
    }

    /**
     * Helper method for creating a description of a snap in the test cases
     * @param path the path for the file
     * @param low the starting zxid for the snap
     * @param modDateOffsetDays modification date adjustment from current time, in days
     * @return the backup file info for the described snap
     */
    private BackupFileInfo s(File path, long low, long modDateOffsetDays) {
        return createFile(path, Util.SNAP_PREFIX, low, TimeUnit.DAYS.toMillis(modDateOffsetDays));
    }

    private BackupFileInfo s(long low, long modDateOffsetDays) {
        return s(null, low, modDateOffsetDays);
    }

    private BackupFileInfo sm(File path, long low, long timeOffset) {
        return createFile(path, Util.SNAP_PREFIX, low, timeOffset);
    }

    private BackupFileInfo sm(long low, long timeOffset) {
        return sm(null, low, timeOffset);
    }

    private long t(int days, int hours, int minutes) {
        return TimeUnit.DAYS.toMillis(days)
                + TimeUnit.HOURS.toMillis(hours)
                + TimeUnit.MINUTES.toMillis(minutes);
    }

    private long nt(int days, int hours, int minutes) {
        return -t(days, hours, minutes);
    }

    /**
     * Helper method for creating a description of a txlog in the test cases
     * @param path the path for the file
     * @param low the starting zxid for the log
     * @param modDateOffsetDays modification date adjustment from current time, in days
     * @return the backup file info for the described log
     */
    private BackupFileInfo l(File path, long low, long modDateOffsetDays) {
        return createFile(path, Util.TXLOG_PREFIX, low, TimeUnit.DAYS.toMillis(modDateOffsetDays));
    }

    private BackupFileInfo l(long low, long modDateOffsetDays) {
        return l(null, low, modDateOffsetDays);
    }

    /**
     * Helper method for creating a description of a lost log range in the test cases
     * @param path the path for the file
     * @param low the first zxid of the range that has been lost
     * @param modDateOffsetDays modification date adjustment from current time, in days
     * @return the backup file info for the described lost log range
     */
    private BackupFileInfo ll(File path, long low, long modDateOffsetDays) {
        return createFile(
                path,
                BackupUtil.LOST_LOG_PREFIX,
                low,
                TimeUnit.DAYS.toMillis(modDateOffsetDays));
    }

    private BackupFileInfo ll(long low, long modDateOffsetDays) {
        return ll(null, low, modDateOffsetDays);
    }

    private BackupFileInfo llm(long low, long timeOffset) {
        return createFile(null, BackupUtil.LOST_LOG_PREFIX, low, timeOffset);
    }

    private BackupFileInfo createFile(File path, String prefix, long zxid, long timeOffset) {
        return new BackupFileInfo(
                new File(path, TestBackupProvider.backupName(prefix, zxid, zxid)),
                retentionAsOfTime + timeOffset,
                100);
    }

    private void printList(String title, List<BackupFileInfo> list) {
        LOG.info(title);

        if (list.isEmpty()) {
            LOG.info("EMPTY");
            return;
        }

        for(BackupFileInfo bfi : list) {
            LOG.info(bfi.getBackedUpFile().toString());
        }
    }

    private void run(BackupInfoLists startingBackupState,
                     int retentionDays,
                     BackupInfoLists expectedDeletions) throws IOException {
        run(startingBackupState, 0, retentionDays, false, expectedDeletions);
    }

    private void run(BackupInfoLists startingBackupState,
                     int recursionDepth,
                     int retentionDays,
                     boolean useAdaptiveSnapRetention,
                     BackupInfoLists expectedDeletions) throws IOException {

        TestBackupProvider storage = new TestBackupProvider();
        storage.setAvailableBackups(startingBackupState);

        InMemoryStatsReceiver statsReceiver = new InMemoryStatsReceiver();
        StatsReceiver brmScope = statsReceiver.scope("backup_retention_manager");

        BackupStorageRetentionTask retentionTask =
                new BackupStorageRetentionTask(
                        false, /* not dry run */
                        storage,
                        recursionDepth,
                        retentionDays,
                        useAdaptiveSnapRetention,
                        retentionAsOfTime,
                        statsReceiver);

        retentionTask.run();

        BackupInfoLists deletedFiles = storage.getDeletedLists();

        Assert.assertEquals(1, rc(brmScope, "iterations", "count").apply());

        printList("Expected deleted snaps:", expectedDeletions.snaps);
        printList("Actual deleted snaps:", deletedFiles.snaps);
        Assert.assertArrayEquals("Snaps",
                TestBackupProvider.getBackupNames(expectedDeletions.snaps),
                TestBackupProvider.getBackupNames(deletedFiles.snaps));
        assertStatCountsPerDir(expectedDeletions.snaps, brmScope, BackupFileType.SNAPSHOT);

        printList("Expected deleted logs:", expectedDeletions.logs);
        printList("Actual deleted logs:", deletedFiles.logs);
        Assert.assertArrayEquals("Logs",
                TestBackupProvider.getBackupNames(expectedDeletions.logs),
                TestBackupProvider.getBackupNames(deletedFiles.logs));
        assertStatCountsPerDir(expectedDeletions.logs, brmScope, BackupFileType.TXNLOG);

        printList("Expected deleted lostLogs:", expectedDeletions.lostLogs);
        printList("Actual deleted lostLogs:", deletedFiles.lostLogs);
        Assert.assertArrayEquals("LostLogs",
                TestBackupProvider.getBackupNames(expectedDeletions.lostLogs),
                TestBackupProvider.getBackupNames(deletedFiles.lostLogs));
        assertStatCountsPerDir(expectedDeletions.lostLogs, brmScope, BackupFileType.LOSTLOG);
    }

    private void assertStatCountsPerDir(
            List<BackupFileInfo> expectedDeletions,
            StatsReceiver statsReceiver,
            BackupFileType fileType) {

        HashMap<String, Integer> countsPerDir = new HashMap<>();

        for (BackupFileInfo bfi : expectedDeletions) {
            String parent = bfi.getBackedUpFile().getParent();
            parent = parent == null ? "base" : parent.replace("/", "\\");

            if (!countsPerDir.containsKey(parent)) {
                countsPerDir.put(parent, 1);
            } else {
                countsPerDir.put(parent, countsPerDir.get(parent) + 1);
            }
        }

        for (Map.Entry<String, Integer> e : countsPerDir.entrySet()) {
            StatsReceiver purgedScope = statsReceiver.scope(e.getKey()).scope("purged");

            Assert.assertEquals(
                    "childDir: " + e.getKey(),
                    (int)e.getValue(),
                    rcv(purgedScope, fileType, "count"));
        }
    }

    private long rcv(StatsReceiver stats, BackupFileType fileType, String name) {
        return rc(stats, fileType, name).apply();
    }

    private ReadableCounter rc(StatsReceiver stats, BackupFileType fileType, String name) {
        return rc(stats, BackupUtil.getPrefix(fileType), name);
    }

    private ReadableCounter rc(StatsReceiver stats, String scope, String name) {
        return rc(stats.scope(scope), name);
    }

    private ReadableCounter rc(StatsReceiver stats, String name) {
        return (ReadableCounter)stats.counter0(name);
    }
}
