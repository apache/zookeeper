package org.apache.zookeeper.server.backup;

import java.io.File;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.common.ConfigException;
import org.junit.Test;

import com.twitter.util.Duration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BackupConfigTest {
    private static final File DEFAULT_STATUS_DIR = new File("/backup/status");
    private static final File DEFAULT_TMP_DIR = new File("/tmp/backup");
    private static final File DEFAULT_HDFS_CONFIG = new File("/hdfs/config");
    private static final String DEFAULT_HDFS_PATH = "/hdfs/path";

    @Test
    public void testEnabled() throws Exception {
        assertFalse(new BackupConfig.Builder().build().isPresent());
        assertTrue(builder().build().isPresent());
        assertFalse(builder()
            .withProperty(BackupConfig.PROP_BACKUP_ENABLED, "false")
            .build().isPresent());
        assertTrue(builder()
            .withProperty(BackupConfig.PROP_BACKUP_ENABLED, "true")
            .build().isPresent());
    }

    @Test
    public void testStatusDir() throws Exception {
        try {
            new BackupConfig.Builder()
                .enabled(true)
                .tmpDir(DEFAULT_TMP_DIR)
                .hdfsConfig(DEFAULT_HDFS_CONFIG)
                .hdfsPath(DEFAULT_HDFS_PATH)
                .build();
            assertTrue(false);
        } catch (ConfigException exc) {
            assertTrue(true);
        }

        assertEquals(DEFAULT_STATUS_DIR, builder()
            .build().get()
            .getStatusDir());
        File expected = new File("/expected");
        assertEquals(expected, builder()
            .statusDir(expected)
            .build().get()
            .getStatusDir());
        assertEquals(expected, builder()
            .withProperty(BackupConfig.PROP_BACKUP_STATUS_DIR, expected.getAbsolutePath())
            .build().get()
            .getStatusDir());
    }

    @Test
    public void testTmpDir() throws Exception {
        try {
            new BackupConfig.Builder()
                .enabled(true)
                .statusDir(DEFAULT_STATUS_DIR)
                .hdfsConfig(DEFAULT_HDFS_CONFIG)
                .hdfsPath(DEFAULT_HDFS_PATH)
                .build();
            assertTrue(false);
        } catch (ConfigException exc) {
            assertTrue(true);
        }

        assertEquals(DEFAULT_TMP_DIR, builder()
            .build().get()
            .getTmpDir());
        File expected = new File("/expected");
        assertEquals(expected, builder()
            .tmpDir(expected)
            .build().get()
            .getTmpDir());
        assertEquals(expected, builder()
            .withProperty(BackupConfig.PROP_BACKUP_TMP_DIR, expected.getAbsolutePath())
            .build().get()
            .getTmpDir());
    }

    @Test
    public void testInterval() throws Exception {
        assertEquals(BackupConfig.DEFAULT_BACKUP_INTERVAL, builder()
            .build().get()
            .getInterval());
        Duration expected = Duration.fromTimeUnit(3, TimeUnit.MINUTES);
        assertEquals(expected, builder()
            .interval(expected)
            .build().get()
            .getInterval());
        assertEquals(expected, builder()
            .withProperty(BackupConfig.PROP_BACKUP_INTERVAL, "3")
            .build().get()
            .getInterval());
    }

    @Test
    public void testHDFSConfig() throws Exception {
        try {
            new BackupConfig.Builder()
                .enabled(true)
                .statusDir(DEFAULT_STATUS_DIR)
                .tmpDir(DEFAULT_TMP_DIR)
                .hdfsPath(DEFAULT_HDFS_PATH)
                .build();
            assertTrue(false);
        } catch (ConfigException exc) {
            assertTrue(true);
        }

        assertEquals(DEFAULT_HDFS_CONFIG, builder()
            .build().get()
            .getHDFSConfig());
        File expected = new File("/expected");
        assertEquals(expected, builder()
            .hdfsConfig(expected)
            .build().get()
            .getHDFSConfig());
        assertEquals(expected, builder()
            .withProperty(BackupConfig.PROP_BACKUP_HDFS_CONFIG, expected.getAbsolutePath())
            .build().get()
            .getHDFSConfig());

    }

    @Test
    public void testHDFSPath() throws Exception {
        try {
            new BackupConfig.Builder()
                .enabled(true)
                .statusDir(DEFAULT_STATUS_DIR)
                .tmpDir(DEFAULT_TMP_DIR)
                .hdfsConfig(DEFAULT_HDFS_CONFIG)
                .build();
            assertTrue(false);
        } catch (ConfigException exc) {
            assertTrue(true);
        }

        assertEquals(DEFAULT_HDFS_PATH, builder()
            .build().get()
            .getHDFSPath());
        String expected = "/expected";
        assertEquals(expected, builder()
            .hdfsPath(expected)
            .build().get()
            .getHDFSPath());
        assertEquals(expected, builder()
            .withProperty(BackupConfig.PROP_BACKUP_HDFS_PATH, expected)
            .build().get()
            .getHDFSPath());
    }

    @Test
    public void testRetentionPeriod() throws Exception {
        assertEquals(BackupConfig.DEFAULT_RETENTION_DAYS, builder()
            .build().get()
            .getRetentionPeriod().inDays());
        int expected = 100;
        assertEquals(expected, builder()
            .retentionDays(expected)
            .build().get()
            .getRetentionPeriod().inDays());
        assertEquals(expected, builder()
            .withProperty(BackupConfig.PROP_BACKUP_RETENTION_DAYS, "100")
            .build().get()
            .getRetentionPeriod().inDays());
    }

    @Test
    public void testRetentionMaintenanceInterval() throws Exception {
        assertEquals(BackupConfig.DEFAULT_RETENTION_MAINTENANCE_INTERVAL, builder()
            .build().get()
            .getRetentionMaintenanceInterval());
        Duration expected = Duration.fromTimeUnit(3, TimeUnit.HOURS);
        assertEquals(expected, builder()
            .retentionMaintenanceInterval(expected)
            .build().get()
            .getRetentionMaintenanceInterval());
        assertEquals(expected, builder()
            .withProperty(BackupConfig.PROP_BACKUP_RETENTION_MAINTENANCE_INTERVAL_HOURS, "3")
            .build().get()
            .getRetentionMaintenanceInterval());
    }

    private BackupConfig.Builder builder() {
        return new BackupConfig.Builder()
            .enabled(true)
            .statusDir(DEFAULT_STATUS_DIR)
            .tmpDir(DEFAULT_TMP_DIR)
            .hdfsConfig(DEFAULT_HDFS_CONFIG)
            .hdfsPath(DEFAULT_HDFS_PATH);
    }
}
