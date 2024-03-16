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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Range;

import org.apache.zookeeper.server.FileStreamTxnIteratorTestBase;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test the behavior of the hdfs txn log iterator
 */
public class HdfsBackupTxnLogIteratorTest extends FileStreamTxnIteratorTestBase {
    private static final Logger LOG = LoggerFactory.getLogger(HdfsBackupTxnLogIteratorTest.class);

    private static List<BackupFileInfo> backupFiles = new ArrayList<>(3);

    private class MyBackupStorage extends HdfsBackupStorage {

        private Map<String, BackupFileInfo> backupFiles = new HashMap<String, BackupFileInfo>();

        public MyBackupStorage(List<BackupFileInfo> files) throws IOException {
            super(logDir, logDir.getPath());

            if (files != null) {
                for (BackupFileInfo bfi : files) {
                    String name = bfi.getBackedUpFile().getName();
                    backupFiles.put(name, bfi);
                    LOG.info("Adding entry with key '{}' to backup file list", name);
                }
            }
        }

        @Override
        public BackupFileInfo getBackupFileInfo(File file) throws IOException {
            LOG.info("Getting backup file info for file: {} ({})", file, file.getName());
            return backupFiles.get(file.getName());
        }

        @Override
        public List<BackupFileInfo> getBackupFileInfos(File path, String prefix) throws IOException {
            List<BackupFileInfo> files = new ArrayList<BackupFileInfo>();

            LOG.info("Getting backup file infos for path: {} prefix: {}", path, prefix);

            for (Map.Entry<String, BackupFileInfo> kv : backupFiles.entrySet()) {
                if (kv.getKey().startsWith(prefix)) {
                    files.add(kv.getValue());
                    LOG.info("Return includes: {}", kv.getValue().getBackedUpFile());
                }
            }

            return files;
        }

        @Override
        public List<File> getDirectories(File path) throws IOException {
            return new ArrayList<>();
        }

        @Override
        public InputStream open(File path) throws IOException {
            BackupFileInfo bfi = backupFiles.get(path.getName());
            InputStream stream = null;

            LOG.info("Opening file: {}", path);

            if (bfi != null) {
                stream = new FileInputStream(bfi.getBackedUpFile());
            } else {
                LOG.info("File not found: {}", path);
            }

            return stream;
        }

        @Override
        public void copyToBackupStorage(File srcFile, File destName) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void copyToLocalStorage(File srcName, File destFile) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(File fileToDelete) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cleanupInvalidFiles(File path) throws IOException {
            throw new UnsupportedOperationException();
        }
    }

    private static void createBackupFiles() throws IOException {
        addBackupFile(logFiles.get(0), 10);
        addBackupFile(logFiles.get(1), 219);
        addBackupFile(logFiles.get(2), 229);

        Assert.assertEquals(3, backupFiles.size());
        Assert.assertEquals(1, backupFiles.get(0).getZxid(BackupUtil.ZxidPart.MIN_ZXID));
        Assert.assertEquals(10, backupFiles.get(0).getZxid(BackupUtil.ZxidPart.MAX_ZXID));
        Assert.assertEquals(110, backupFiles.get(1).getZxid(BackupUtil.ZxidPart.MIN_ZXID));
        Assert.assertEquals(219, backupFiles.get(1).getZxid(BackupUtil.ZxidPart.MAX_ZXID));
        Assert.assertEquals(220, backupFiles.get(2).getZxid(BackupUtil.ZxidPart.MIN_ZXID));

        for (BackupFileInfo bfi : backupFiles) {
            LOG.info("Standard: {}  Backedup: {}  Range: {}  Size: {}  ModificationTime: {}",
                bfi.getStandardFile(),
                bfi.getBackedUpFile(),
                bfi.getZxidRange(),
                bfi.getSize(),
                bfi.getModificationTime());
        }
    }

    private static void addBackupFile(File logFile, long highZxid) {
        File backedupName =
            new File(logFile.getParent(), BackupUtil.makeBackupName(logFile.getName(), highZxid));
        logFile.renameTo(backedupName);
        BackupFileInfo bfi =
            new BackupFileInfo(backedupName, System.currentTimeMillis(), logFile.length());

        backupFiles.add(bfi);
    }

    @BeforeClass
    public static void setupClass() throws IOException {
        logDir = ClientBase.createTmpDir();
        createTxnLogFiles(logDir);
        createBackupFiles();
    }

    @Test
    public void testSingleFileIteration() throws Exception {
        MyBackupStorage backupStorage = new MyBackupStorage(backupFiles);
        HdfsBackupTxnLogIterator iter =
                new HdfsBackupTxnLogIterator(backupStorage, backupFiles.get(1).getBackedUpFile());

        long startingZxid = 110;
        long endingZxid = 119;

        checkRange(iter, startingZxid, endingZxid);
    }

    @Test
    public void testAllFilesIteration() throws Exception {
        MyBackupStorage backupStorage = new MyBackupStorage(backupFiles);
        HdfsBackupTxnLogIterator iter = new HdfsBackupTxnLogIterator(backupStorage);

        long startingZxid = 1;
        long endingZxid = 229;

        checkRange(iter, startingZxid, endingZxid);
    }

    @Test
    public void testZxidRangeIteration() throws Exception {
        MyBackupStorage backupStorage = new MyBackupStorage(backupFiles);
        Range<Long> zxidRange = Range.closed(5L, 114L);
        HdfsBackupTxnLogIterator iter = new HdfsBackupTxnLogIterator(backupStorage, zxidRange);


        checkRange(iter, zxidRange.lowerEndpoint(), zxidRange.upperEndpoint());
    }

    @Test
    public void testEmptyStorage() throws Exception {
        MyBackupStorage backupStorage = new MyBackupStorage(null);
        HdfsBackupTxnLogIterator iter = new HdfsBackupTxnLogIterator(backupStorage);

        Assert.assertNull(iter.getHeader());
    }
}
