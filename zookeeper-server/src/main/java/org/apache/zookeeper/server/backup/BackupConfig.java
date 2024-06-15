package org.apache.zookeeper.server.backup;

import java.io.File;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Optional;
import com.twitter.util.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.zookeeper.common.ConfigException;
import org.apache.zookeeper.common.VerifyingFileFactory;

public class BackupConfig {
    public static final String PROP_BACKUP_ENABLED = "backup.enabled";
    public static final String PROP_BACKUP_STATUS_DIR = "backup.statusDir";
    public static final String PROP_BACKUP_TMP_DIR = "backup.tmpDir";
    public static final String PROP_BACKUP_INTERVAL = "backup.interval";
    public static final String PROP_BACKUP_HDFS_CONFIG = "backup.hdfsConfig";
    public static final String PROP_BACKUP_HDFS_PATH = "backup.hdfsPath";
    public static final String PROP_BACKUP_RETENTION_DAYS = "backup.retentionDays";
    public static final String PROP_BACKUP_RETENTION_MAINTENANCE_INTERVAL_HOURS =
        "backup.retentionMaintenanceIntervalHours";

    public static final boolean DEFAULT_ENABLED = false;
    public static final int DEFAULT_RETENTION_DAYS = 0;
    public static final Duration DEFAULT_BACKUP_INTERVAL =
        Duration.fromTimeUnit(15, TimeUnit.MINUTES);
    public static final Duration DEFAULT_RETENTION_MAINTENANCE_INTERVAL =
        Duration.fromTimeUnit(24, TimeUnit.HOURS);

    private static final Logger LOG = LoggerFactory.getLogger(BackupConfig.class);

    private final File statusDir;
    private final File tmpDir;
    private final Duration interval; // 15 minutes;
    private final File hdfsConfig;
    private final String hdfsPath;
    private final Duration retentionPeriod; // Days = 0;
    private final Duration retentionMaintenanceInterval; // Hours = 24;

    public BackupConfig(Builder builder) {
        this.statusDir = builder._statusDir.get();
        this.tmpDir = builder._tmpDir.get();
        this.interval = builder._interval.or(DEFAULT_BACKUP_INTERVAL);
        this.hdfsConfig = builder._hdfsConfig.get();
        this.hdfsPath = builder._hdfsPath.get();
        this.retentionPeriod = Duration.fromTimeUnit(
            builder._retentionDays.or(DEFAULT_RETENTION_DAYS),
            TimeUnit.DAYS
        );
        this.retentionMaintenanceInterval = builder._retentionMaintenanceInterval.or(
            DEFAULT_RETENTION_MAINTENANCE_INTERVAL);
    }

    public File getStatusDir() {
        return statusDir;
    }

    public File getTmpDir() {
        return tmpDir;
    }

    public Duration getInterval() {
        return interval;
    }

    public File getHDFSConfig() {
        return hdfsConfig;
    }

    public String getHDFSPath() {
        return hdfsPath;
    }

    public Duration getRetentionPeriod() {
        return retentionPeriod;
    }

    public Duration getRetentionMaintenanceInterval() {
        return retentionMaintenanceInterval;
    }

    public static class Builder {
        private static final VerifyingFileFactory vff = new VerifyingFileFactory.Builder(LOG)
            .warnForRelativePath()
            .build();

        private Optional<Boolean> _enabled = Optional.absent();
        private Optional<File> _statusDir = Optional.absent();
        private Optional<File> _tmpDir = Optional.absent();
        private Optional<Duration> _interval = Optional.absent();
        private Optional<File> _hdfsConfig = Optional.absent();
        private Optional<String> _hdfsPath = Optional.absent();
        private Optional<Integer> _retentionDays = Optional.absent();
        private Optional<Duration> _retentionMaintenanceInterval = Optional.absent();

        public Builder() {
        }

        public Builder enabled(boolean enabled) {
            this._enabled = Optional.of(enabled);
            return this;
        }

        public Builder statusDir(File dir) {
            this._statusDir = Optional.of(dir);
            return this;
        }

        public Builder tmpDir(File dir) {
            this._tmpDir = Optional.of(dir);
            return this;
        }

        public Builder interval(Duration interval) {
            this._interval = Optional.of(interval);
            return this;
        }

        public Builder hdfsConfig(File file) {
            this._hdfsConfig = Optional.of(file);
            return this;
        }

        public Builder hdfsPath(String path) {
            this._hdfsPath = Optional.of(path);
            return this;
        }

        public Builder retentionDays(int days) {
            this._retentionDays = Optional.of(days);
            return this;
        }

        public Builder retentionMaintenanceInterval(Duration interval) {
            this._retentionMaintenanceInterval = Optional.of(interval);
            return this;
        }

        public Optional<BackupConfig> build() throws ConfigException {
            final Optional<BackupConfig> result;
            boolean enabled = _enabled.or(DEFAULT_ENABLED);
            if (enabled) {
                if (!_statusDir.isPresent()) {
                    throw new ConfigException("BackupConfig statusDir not specified");
                }
                if (!_tmpDir.isPresent()) {
                    throw new ConfigException("BackupConfig tmpDir not specified");
                }
                if (!_hdfsConfig.isPresent()) {
                    throw new ConfigException("BackupConfig hdfsConfig not specified");
                }
                if (!_hdfsPath.isPresent()) {
                    throw new ConfigException("BackupConfig hdfsPath not specified");
                }

                result = Optional.of(new BackupConfig(this));
            } else {
                result = Optional.absent();
            }
            return result;
        }

        public Builder withProperties(Properties properties) throws ConfigException {
            return withProperties(properties, "");
        }

        public Builder withProperties(Properties properties, String prefix)
            throws ConfigException {
            {
                String key = prefix + PROP_BACKUP_ENABLED;
                String prop = properties.getProperty(key);
                if (prop != null) {
                    enabled(parseBoolean(key, prop));
                }
            }
            {
                String key = prefix + PROP_BACKUP_STATUS_DIR;
                String prop = properties.getProperty(key);
                if (prop != null) {
                    statusDir(vff.create(prop));
                }
            }
            {
                String key = prefix + PROP_BACKUP_TMP_DIR;
                String prop = properties.getProperty(key);
                if (prop != null) {
                    tmpDir(vff.create(prop));
                }
            }
            {
                String key = prefix + PROP_BACKUP_INTERVAL;
                String prop = properties.getProperty(key);
                if (prop != null) {
                    long minutes = parseLong(key, prop);
                    interval(Duration.fromTimeUnit(minutes, TimeUnit.MINUTES));
                }
            }
            {
                String key = prefix + PROP_BACKUP_HDFS_CONFIG;
                String prop = properties.getProperty(key);
                if (prop != null) {
                    hdfsConfig(vff.create(prop));
                }
            }
            {
                String key = prefix + PROP_BACKUP_HDFS_PATH;
                String prop = properties.getProperty(key);
                if (prop != null) {
                    hdfsPath(prop);
                }
            }
            {
                String key = prefix + PROP_BACKUP_RETENTION_DAYS;
                String prop = properties.getProperty(key);
                if (prop != null) {
                    retentionDays(parseInt(key, prop));
                }
            }
            {
                String key = prefix + PROP_BACKUP_RETENTION_MAINTENANCE_INTERVAL_HOURS;
                String prop = properties.getProperty(key);
                if (prop != null) {
                    int h = parseInt(key, prop);
                    retentionMaintenanceInterval(Duration.fromTimeUnit(h, TimeUnit.HOURS));
                }
            }
            return this;
        }

        protected Builder withProperty(String key, String val) throws ConfigException {
            Properties properties = new Properties();
            properties.setProperty(key, val);
            return withProperties(properties);
        }

        private static long parseLong(String key, String value) throws ConfigException {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException exc) {
                throw new ConfigException(String.format("parsing %s", key), exc);
            }
        }

        private static int parseInt(String key, String value) throws ConfigException {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException exc) {
                throw new ConfigException(String.format("parsing %s", key), exc);
            }
        }

        private static boolean parseBoolean(String key, String value) throws ConfigException {
            boolean result;
            switch (value.toLowerCase()) {
                case "yes":
                    result = true;
                    break;
                case "no":
                    result = false;
                    break;
                default:
                    result = Boolean.parseBoolean(value);
                    break;
            }
            return result;
        }
    }
}
