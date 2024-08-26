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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.PathFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * HDFS implementation of BackupStorageProvider
 */
public class HdfsBackupStorage implements BackupStorageProvider {
    private static final Logger LOG = LoggerFactory.getLogger(HdfsBackupStorage.class);
    public static final String TMP_FILE_PREFIX = "TMP_";

    private final Path storagePath;
    protected FileSystem store;
    private final ReadWriteLock rwLock;
    private final Lock sharedLock;
    private final Lock exclusiveLock;

    private static class PrefixFilter implements PathFilter {
        private final String prefix;
        public PrefixFilter(String prefix) { this.prefix = prefix; }
        public boolean accept(Path path) {
            return path.getName().startsWith(prefix);
        }
    }

    /**
     * @param hdfsConfig location of hdfs configuration files
     * @param hdfsPath the path, relative to the home directory, to use as the as the base path in
     *                 hdfs for the other interfaces
     * @throws IOException
     */
    public HdfsBackupStorage(File hdfsConfig, String hdfsPath) throws IOException {
        LOG.info("hdfsConfig={}", hdfsConfig);
        LOG.info("hdfsPath={}", hdfsPath);

        rwLock = new ReentrantReadWriteLock();
        sharedLock = rwLock.readLock();
        exclusiveLock = rwLock.writeLock();

        store = FileSystem.get(buildHdfsConfiguration(hdfsConfig));
        Path home = store.getHomeDirectory();
        storagePath = new Path(home, hdfsPath);

        LOG.info("Backup base storage path is {}", storagePath);

        if (!store.exists(storagePath)) {
            LOG.info("Creating path {}", storagePath);
            store.mkdirs(storagePath);
        }
    }

    @Override
    public List<File> getDirectories(File path) throws IOException {
        Path fullPath = fullPath(path);
        FileStatus[] files = store.listStatus(fullPath);
        ArrayList<File> dirs = new ArrayList<File>();

        for (FileStatus status : files) {
            if (status.isDirectory()) {
                dirs.add(new File(path, status.getPath().getName()));
            }
        }

        return dirs;
    }

    @Override
    public BackupFileInfo getBackupFileInfo(File file) throws IOException {
        Path fullPath = fullPath(file);
        FileStatus status = store.getFileStatus(fullPath);
        return new BackupFileInfo(file, status.getModificationTime(), status.getLen());
    }

    @Override
    public List<BackupFileInfo> getBackupFileInfos(final File path, String prefix) throws IOException {
        Path fullPath = fullPath(path);
        FileStatus[] files = store.listStatus(fullPath, new PrefixFilter(prefix));

        if (files.length == 0) {
            return new ArrayList<BackupFileInfo>(0);
        }

        return Lists.newArrayList(
                Lists.transform(Lists.newArrayList(files),
                new Function<FileStatus, BackupFileInfo>() {
                    public BackupFileInfo apply(FileStatus status) {
                        return new BackupFileInfo(
                                new File(path, status.getPath().getName()),
                                status.getModificationTime(),
                                status.getLen());
                    }
                }));
    }

    @Override
    public InputStream open(File path) throws IOException {
        Path fullPath = fullPath(path);
        return store.open(fullPath);
    }

    @Override
    public void copyToBackupStorage(File srcFile, File destFile) throws IOException {
        Path src = new Path(srcFile.getAbsolutePath());
        Path dest = fullPath(destFile);
        Path destTmp = fullPath(tempName(destFile));

        LOG.info("Copying file {} to remote storage as {} via {}",
                srcFile.getAbsolutePath(),
                dest,
                destTmp);

        sharedLock.lock();

        try {
            // First copy to a temporary file and then rename to simulate an atomic copy.
            // cleanupInvalidFiles can be used to remove any failed copies.
            // Note: move/rename is NOT atomic until Hadoop 2.0 but we're accepting the small
            // chance of failure
            store.copyFromLocalFile(src, destTmp);
            store.rename(destTmp, dest);
        } finally {
            sharedLock.unlock();
        }
    }

    @Override
    public void copyToLocalStorage(File srcFile, File destFile) throws IOException {
        Path dest = new Path(destFile.getAbsolutePath());
        Path src = fullPath(srcFile);

        LOG.info("Copying from {} to {}", src, dest);
        store.copyToLocalFile(src, dest);
    }

    @Override
    public void delete(File fileToDelete) throws IOException {
        store.delete(fullPath(fileToDelete), false);
    }

    @Override
    public void cleanupInvalidFiles(File path) throws IOException {
        Path fullPath = fullPath(path);

        exclusiveLock.lock();

        try {
            FileStatus[] files = store.listStatus(fullPath, new PrefixFilter(TMP_FILE_PREFIX));

            for (FileStatus file : files) {
                store.delete(file.getPath(), false /* not recursive */);
            }
        } finally {
            exclusiveLock.unlock();
        }
    }

    /**
     * Create the full HDFS path given the relative path of the file.
     * @param path the name of the file
     * @return the full HDFS path that includes the storagePath and the path
     */
    private Path fullPath(File path) {
        return path == null ? storagePath : new Path(storagePath, path.getPath());
    }

    /**
     * Create a name to be used for a temporary copy of the specified file
     * @param file the file for which to create a temporary name
     * @return the temporary name
     */
    private File tempName(File file) {
        Preconditions.checkArgument(!file.isDirectory());

        String name = TMP_FILE_PREFIX + file.getName();
        return new File(file.getParentFile(), name);
    }

    /**
     * Build the configuration to use for HDFS access.
     * Note that this falls back to the local filesystem if the hdfs configuration
     * files do not exist.
     *
     * @param hdfsConfigDir the configuration directory.
     * @return
     */
    private Configuration buildHdfsConfiguration(File hdfsConfigDir) {
        Configuration config = new Configuration();

        if (hdfsConfigDir != null) {
            Path parentPath = new Path(hdfsConfigDir.getPath());
            LOG.info("hdfs configuration path: {}", parentPath);
            config.addResource(new Path(parentPath, "core-site.xml"));
            config.addResource(new Path(parentPath, "hdfs-site.xml"));
        }

        return config;
    }
}
