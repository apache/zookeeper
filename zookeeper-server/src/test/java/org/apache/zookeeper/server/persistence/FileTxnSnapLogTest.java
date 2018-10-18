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

package org.apache.zookeeper.server.persistence;

import org.apache.jute.Record;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.TestUtils;
import org.apache.zookeeper.txn.SetDataTxn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.After;
import org.junit.Before;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class FileTxnSnapLogTest {

    private File tmpDir;

    private File logDir;

    private File snapDir;

    private File logVersionDir;

    private File snapVersionDir;

    @Before
    public void setUp() throws Exception {
        tmpDir = ClientBase.createEmptyTestDir();
        logDir = new File(tmpDir, "logdir");
        snapDir = new File(tmpDir, "snapdir");
    }

    @After
    public void tearDown() throws Exception {
        if(tmpDir != null){
            TestUtils.deleteFileRecursively(tmpDir);
        }
        this.tmpDir = null;
        this.logDir = null;
        this.snapDir = null;
        this.logVersionDir = null;
        this.snapVersionDir = null;
    }

    private File createVersionDir(File parentDir) {
        File versionDir = new File(parentDir, FileTxnSnapLog.version + FileTxnSnapLog.VERSION);
        versionDir.mkdirs();
        return versionDir;
    }

    private void createLogFile(File dir, long zxid) throws IOException {
        File file = new File(dir.getPath() + File.separator + Util.makeLogName(zxid));
        file.createNewFile();
    }

    private void createSnapshotFile(File dir, long zxid) throws IOException {
        File file = new File(dir.getPath() + File.separator + Util.makeSnapshotName(zxid));
        file.createNewFile();
    }

    private void twoDirSetupWithCorrectFiles() throws IOException {
        logVersionDir = createVersionDir(logDir);
        snapVersionDir = createVersionDir(snapDir);

        // transaction log files in log dir
        createLogFile(logVersionDir,1);
        createLogFile(logVersionDir,2);

        // snapshot files in snap dir
        createSnapshotFile(snapVersionDir,1);
        createSnapshotFile(snapVersionDir,2);
    }

    private void singleDirSetupWithCorrectFiles() throws IOException {
        logVersionDir = createVersionDir(logDir);

        // transaction log and snapshot files in the same dir
        createLogFile(logVersionDir,1);
        createLogFile(logVersionDir,2);
        createSnapshotFile(logVersionDir,1);
        createSnapshotFile(logVersionDir,2);
    }

    private FileTxnSnapLog createFileTxnSnapLogWithNoAutoCreateDataDir(File logDir, File snapDir) throws IOException {
        return createFileTxnSnapLogWithAutoCreateDataDir(logDir, snapDir, "false");
    }

    private FileTxnSnapLog createFileTxnSnapLogWithAutoCreateDataDir(File logDir, File snapDir, String autoCreateValue) throws IOException {
        String priorAutocreateDirValue = System.getProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE);
        System.setProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE, autoCreateValue);
        FileTxnSnapLog fileTxnSnapLog;
        try {
            fileTxnSnapLog = new FileTxnSnapLog(logDir, snapDir);
        } finally {
            if (priorAutocreateDirValue == null) {
                System.clearProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE);
            } else {
                System.setProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE, priorAutocreateDirValue);
            }
        }
        return fileTxnSnapLog;
    }

    /**
     * Test verifies the auto creation of log dir and snap dir.
     * Sets "zookeeper.datadir.autocreate" to true.
     */
    @Test
    public void testWithAutoCreateDataDir() throws IOException {
        Assert.assertFalse("log directory already exists", logDir.exists());
        Assert.assertFalse("snapshot directory already exists", snapDir.exists());

        FileTxnSnapLog fileTxnSnapLog = createFileTxnSnapLogWithAutoCreateDataDir(logDir, snapDir, "true");

        Assert.assertTrue(logDir.exists());
        Assert.assertTrue(snapDir.exists());
        Assert.assertTrue(fileTxnSnapLog.getDataDir().exists());
        Assert.assertTrue(fileTxnSnapLog.getSnapDir().exists());
    }

    /**
     * Test verifies server should fail when log dir or snap dir doesn't exist.
     * Sets "zookeeper.datadir.autocreate" to false.
     */
    @Test(expected = FileTxnSnapLog.DatadirException.class)
    public void testWithoutAutoCreateDataDir() throws Exception {
        Assert.assertFalse("log directory already exists", logDir.exists());
        Assert.assertFalse("snapshot directory already exists", snapDir.exists());

        try {
            createFileTxnSnapLogWithAutoCreateDataDir(logDir, snapDir, "false");
        } catch (FileTxnSnapLog.DatadirException e) {
            Assert.assertFalse(logDir.exists());
            Assert.assertFalse(snapDir.exists());
            // rethrow exception
            throw e;
        }
        Assert.fail("Expected exception from FileTxnSnapLog");
    }

    @Test
    public void testGetTxnLogSyncElapsedTime() throws IOException {
        FileTxnSnapLog fileTxnSnapLog = createFileTxnSnapLogWithAutoCreateDataDir(logDir, snapDir, "true");

        TxnHeader hdr = new TxnHeader(1, 1, 1, 1, ZooDefs.OpCode.setData);
        Record txn = new SetDataTxn("/foo", new byte[0], 1);
        Request req = new Request(0, 0, 0, hdr, txn, 0);

        try {
            fileTxnSnapLog.append(req);
            fileTxnSnapLog.commit();
            long syncElapsedTime = fileTxnSnapLog.getTxnLogElapsedSyncTime();
            Assert.assertNotEquals("Did not update syncElapsedTime!", -1L, syncElapsedTime);
        } finally {
            fileTxnSnapLog.close();
        }
    }

    @Test
    public void testDirCheckWithCorrectFiles() throws IOException {
        twoDirSetupWithCorrectFiles();

        try {
            createFileTxnSnapLogWithNoAutoCreateDataDir(logDir, snapDir);
        } catch (FileTxnSnapLog.LogDirContentCheckException | FileTxnSnapLog.SnapDirContentCheckException e) {
            Assert.fail("Should not throw ContentCheckException.");
        }
    }

    @Test
    public void testDirCheckWithSingleDirSetup() throws IOException {
        singleDirSetupWithCorrectFiles();

        try {
            createFileTxnSnapLogWithNoAutoCreateDataDir(logDir, logDir);
        } catch (FileTxnSnapLog.LogDirContentCheckException | FileTxnSnapLog.SnapDirContentCheckException e) {
            Assert.fail("Should not throw ContentCheckException.");
        }
    }

    @Test(expected = FileTxnSnapLog.LogDirContentCheckException.class)
    public void testDirCheckWithSnapFilesInLogDir() throws IOException {
        twoDirSetupWithCorrectFiles();

        // add snapshot files to the log version dir
        createSnapshotFile(logVersionDir,3);
        createSnapshotFile(logVersionDir,4);

        createFileTxnSnapLogWithNoAutoCreateDataDir(logDir, snapDir);
    }

    @Test(expected = FileTxnSnapLog.SnapDirContentCheckException.class)
    public void testDirCheckWithLogFilesInSnapDir() throws IOException {
        twoDirSetupWithCorrectFiles();

        // add transaction log files to the snap version dir
        createLogFile(snapVersionDir,3);
        createLogFile(snapVersionDir,4);

        createFileTxnSnapLogWithNoAutoCreateDataDir(logDir, snapDir);
    }
}
