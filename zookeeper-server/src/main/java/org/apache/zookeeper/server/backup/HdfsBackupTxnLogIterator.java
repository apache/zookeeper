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

import com.google.common.collect.Range;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.zookeeper.server.persistence.FileStreamTxnIterator;

public class HdfsBackupTxnLogIterator extends FileStreamTxnIterator {
    private static final Logger LOG = LoggerFactory.getLogger(HdfsBackupTxnLogIterator.class);

    HdfsBackupStorage storage;
    Range<Long> zxidRange = Range.all();
    List<BackupFileInfo> files;
    int currentFile = -1;

    /**
     * Iterate over all txns in all log files
     * @param storage hdfs storage provider to use
     * @throws IOException
     */
    public HdfsBackupTxnLogIterator(HdfsBackupStorage storage) throws IOException {
        this(storage, null, null);
    }

    /**
     * Iterate over all txns in a single log file
     * @param storage hdfs storage provider to use
     * @param file the file to iterate over
     * @throws IOException
     */
    public HdfsBackupTxnLogIterator(HdfsBackupStorage storage, File file) throws IOException {
        this(storage, file, null);
    }

    /**
     * Iterate over all txns in a given zxid range regardless of which file they are in
     * @param storage hdfs storage provider to use
     * @param zxidRange the range to iterate over.
     * @throws IOException
     */
    public HdfsBackupTxnLogIterator(
            HdfsBackupStorage storage,
            Range<Long> zxidRange) throws IOException {

        this(storage, null, zxidRange);
    }

    /**
     * Iterate over all txns within an optional zxid range either in a single file or all files
     * @param storage hdfs storage provider to use
     * @param file the file to iterate over, if null all files in storage provider will be considered
     * @param zxidRange the range to iterate over, if null all zxids will be considered
     * @throws IOException
     */
    public HdfsBackupTxnLogIterator(
            HdfsBackupStorage storage,
            File file,
            Range<Long> zxidRange) throws IOException {

        super(LOG);

        this.storage = storage;

        if (zxidRange != null) {
            this.zxidRange = zxidRange;
        }

        if (file == null) {
            this.files = BackupUtil.getBackupFiles(
                    storage,
                    BackupUtil.BackupFileType.TXNLOG,
                    BackupUtil.ZxidPart.MIN_ZXID,
                    BackupUtil.SortOrder.ASCENDING);
        } else {
            this.files = new ArrayList<BackupFileInfo>();
            this.files.add(storage.getBackupFileInfo(file));
        }

        if (this.zxidRange.hasUpperBound()) {
            setMaxZxid(zxidRange.upperEndpoint());
        }

        goToNextLog();
        next();

        if (this.zxidRange.hasLowerBound()) {
            fastForwardTo(zxidRange.lowerEndpoint());
        }
    }

    public InputStream getNextLog() throws IOException {

        for (currentFile++; currentFile < files.size(); currentFile++) {
            BackupFileInfo file = files.get(currentFile);

            if (zxidRange.isConnected(file.getZxidRange())) {
                return storage.open(file.getBackedUpFile());
            }
        }

        return null;
    }

    public String getCurrentLogFilePath() {
        return currentFile >= 0 && currentFile < files.size()
                ? files.get(currentFile).getBackedUpFile().getName()
                : null;
    }

    public long getStorageSize() throws IOException {
        long sum = 0;
        int i = currentFile + 1;

        if (currentFile >= 0 && currentFile < files.size() && getInputStream() != null) {
            sum += files.get(currentFile).getSize() - getInputStream().getPosition();
        }

        while (i < files.size()) {
            BackupFileInfo file = files.get(i);

            if (zxidRange.isConnected(file.getZxidRange())) {
                sum += file.getSize();
            }
        }

        return sum;
    }
}
