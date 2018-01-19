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
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.txn.SetDataTxn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FileTxnSnapLogTest {

    /**
     * Test verifies the auto creation of data dir and data log dir.
     * Sets "zookeeper.datadir.autocreate" to true.
     */
    @Test
    public void testWithAutoCreateDataLogDir() throws IOException {
        File tmpDir = ClientBase.createEmptyTestDir();
        File dataDir = new File(tmpDir, "data");
        File snapDir = new File(tmpDir, "data_txnlog");
        Assert.assertFalse("data directory already exists", dataDir.exists());
        Assert.assertFalse("snapshot directory already exists", snapDir.exists());

        String priorAutocreateDirValue = System.getProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE);
        System.setProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE, "true");
        FileTxnSnapLog fileTxnSnapLog;
        try {
            fileTxnSnapLog = new FileTxnSnapLog(dataDir, snapDir);
        } finally {
            if (priorAutocreateDirValue == null) {
                System.clearProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE);
            } else {
                System.setProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE, priorAutocreateDirValue);
            }
        }
        Assert.assertTrue(dataDir.exists());
        Assert.assertTrue(snapDir.exists());
        Assert.assertTrue(fileTxnSnapLog.getDataDir().exists());
        Assert.assertTrue(fileTxnSnapLog.getSnapDir().exists());
    }

    /**
     * Test verifies server should fail when data dir or data log dir doesn't
     * exists. Sets "zookeeper.datadir.autocreate" to false.
     */
    @Test
    public void testWithoutAutoCreateDataLogDir() throws Exception {
        File tmpDir = ClientBase.createEmptyTestDir();
        File dataDir = new File(tmpDir, "data");
        File snapDir = new File(tmpDir, "data_txnlog");
        Assert.assertFalse("data directory already exists", dataDir.exists());
        Assert.assertFalse("snapshot directory already exists", snapDir.exists());

        String priorAutocreateDirValue = System.getProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE);
        System.setProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE, "false");
        try {
            FileTxnSnapLog fileTxnSnapLog = new FileTxnSnapLog(dataDir, snapDir);
        } catch (FileTxnSnapLog.DatadirException e) {
            Assert.assertFalse(dataDir.exists());
            Assert.assertFalse(snapDir.exists());
            return;
        } finally {
            if (priorAutocreateDirValue == null) {
                System.clearProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE);
            } else {
                System.setProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE, priorAutocreateDirValue);
            }
        }
        Assert.fail("Expected exception from FileTxnSnapLog");
    }

    @Test
    public void testAutoCreateDb() throws IOException {
        File tmpDir = ClientBase.createEmptyTestDir();
        File dataDir = new File(tmpDir, "data");
        File snapDir = new File(tmpDir, "data_txnlog");
        Assert.assertTrue("cannot create data directory", dataDir.mkdir());
        Assert.assertTrue("cannot create snapshot directory", snapDir.mkdir());
        File initFile = new File(dataDir, "initialize");
        Assert.assertFalse("initialize file already exists", initFile.exists());

        String priorAutocreateDbValue = System.getProperty(FileTxnSnapLog.ZOOKEEPER_DB_AUTOCREATE);
        Map<Long, Integer> sessions = new ConcurrentHashMap<>();

        attemptAutoCreateDb(dataDir, snapDir, sessions, priorAutocreateDbValue, "false", -1L);

        attemptAutoCreateDb(dataDir, snapDir, sessions, priorAutocreateDbValue, "true", 0L);

        Assert.assertTrue("cannot create initialize file", initFile.createNewFile());
        attemptAutoCreateDb(dataDir, snapDir, sessions, priorAutocreateDbValue, "false", 0L);
    }

    @Test
    public void testGetTxnLogSyncElapsedTime() throws IOException {
        File tmpDir = ClientBase.createEmptyTestDir();
        FileTxnSnapLog fileTxnSnapLog = new FileTxnSnapLog(new File(tmpDir, "data"),
                new File(tmpDir, "data_txnlog"));

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

    private void attemptAutoCreateDb(File dataDir, File snapDir, Map<Long, Integer> sessions,
                                     String priorAutocreateDbValue, String autoCreateValue,
                                     long expectedValue) throws IOException {
        sessions.clear();
        System.setProperty(FileTxnSnapLog.ZOOKEEPER_DB_AUTOCREATE, autoCreateValue);
        FileTxnSnapLog fileTxnSnapLog = new FileTxnSnapLog(dataDir, snapDir);

        try {
            long zxid = fileTxnSnapLog.restore(new DataTree(), sessions, new FileTxnSnapLog.PlayBackListener() {
                @Override
                public void onTxnLoaded(TxnHeader hdr, Record rec) {
                    // empty by default
                }
            });
            Assert.assertEquals("unexpected zxid", expectedValue, zxid);
        } finally {
            if (priorAutocreateDbValue == null) {
                System.clearProperty(FileTxnSnapLog.ZOOKEEPER_DB_AUTOCREATE);
            } else {
                System.setProperty(FileTxnSnapLog.ZOOKEEPER_DB_AUTOCREATE, priorAutocreateDbValue);
            }
        }
    }

    @Test
    public void testDirCheckWithCorrectFiles() throws IOException {
        File tmpDir = ClientBase.createEmptyTestDir();
        File logDir = new File(tmpDir, "logdir");
        File snapDir = new File(tmpDir, "snapdir");
        File logVersionDir = new File(logDir, FileTxnSnapLog.version +  FileTxnSnapLog.VERSION);
        File snapVersionDir = new File(snapDir, FileTxnSnapLog.version +  FileTxnSnapLog.VERSION);

        if (!logVersionDir.exists()) {
            logVersionDir.mkdirs();
        }
        if (!snapVersionDir.exists()) {
            snapVersionDir.mkdirs();
        }

        Assert.assertTrue(logVersionDir.exists());
        Assert.assertTrue(snapVersionDir.exists());

        // transaction log files in log dir - correct
        File logFile1 = new File(logVersionDir.getPath() +File.separator + Util.makeLogName(1L));
        logFile1.createNewFile();
        File logFile2 = new File(logVersionDir.getPath() +File.separator + Util.makeLogName(2L));
        logFile2.createNewFile();

        // snapshot files in snap dir - correct
        File snapFile1 = new File(snapVersionDir.getPath() +File.separator + Util.makeSnapshotName(1L));
        snapFile1.createNewFile();
        File snapFile2 = new File(snapVersionDir.getPath() +File.separator + Util.makeSnapshotName(2L));
        snapFile2.createNewFile();

        Assert.assertTrue(logFile1.exists());
        Assert.assertTrue(logFile2.exists());
        Assert.assertTrue(snapFile1.exists());
        Assert.assertTrue(snapFile2.exists());

        String priorAutocreateDirValue = System.getProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE);
        System.setProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE, "false");
        FileTxnSnapLog fileTxnSnapLog;
        try {
            fileTxnSnapLog = new FileTxnSnapLog(logDir, snapDir);
        } catch (FileTxnSnapLog.LogdirContentCheckException e) {
            Assert.fail("Should not throw LogdirContentCheckException.");
        } catch (FileTxnSnapLog.SnapdirContentCheckException e) {
            Assert.fail("Should not throw SnapdirContentCheckException.");
        } finally {
            if (priorAutocreateDirValue == null) {
                System.clearProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE);
            } else {
                System.setProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE, priorAutocreateDirValue);
            }
        }
    }

    @Test
    public void testDirCheckWithSameLogAndSnapDirs() throws IOException {
        File tmpDir = ClientBase.createEmptyTestDir();
        File logDir = new File(tmpDir, "logdir");

        File logVersionDir = new File(logDir, FileTxnSnapLog.version +  FileTxnSnapLog.VERSION);

        if (!logVersionDir.exists()) {
            logVersionDir.mkdirs();
        }

        Assert.assertTrue(logVersionDir.exists());

        // transaction log and snapshot files in the same dir in case transaction log dir and snapshot dir are configured to be the same
        File logFile1 = new File(logVersionDir.getPath() +File.separator + Util.makeLogName(1L));
        logFile1.createNewFile();
        File logFile2 = new File(logVersionDir.getPath() +File.separator + Util.makeLogName(2L));
        logFile2.createNewFile();
        File snapFile1 = new File(logVersionDir.getPath() +File.separator + Util.makeSnapshotName(1L));
        snapFile1.createNewFile();
        File snapFile2 = new File(logVersionDir.getPath() +File.separator + Util.makeSnapshotName(2L));
        snapFile2.createNewFile();

        Assert.assertTrue(logFile1.exists());
        Assert.assertTrue(logFile2.exists());
        Assert.assertTrue(snapFile1.exists());
        Assert.assertTrue(snapFile2.exists());

        String priorAutocreateDirValue = System.getProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE);
        System.setProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE, "false");
        FileTxnSnapLog fileTxnSnapLog;
        try {
            fileTxnSnapLog = new FileTxnSnapLog(logDir, logDir);
        } catch (FileTxnSnapLog.LogdirContentCheckException e) {
            Assert.fail("Should not throw LogdirContentCheckException.");
        } catch (FileTxnSnapLog.SnapdirContentCheckException e) {
            Assert.fail("Should not throw SnapdirContentCheckException.");
        } finally {
            if (priorAutocreateDirValue == null) {
                System.clearProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE);
            } else {
                System.setProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE, priorAutocreateDirValue);
            }
        }
    }

    @Test(expected = FileTxnSnapLog.LogdirContentCheckException.class)
    public void testDirCheckWithSnapFilesInLogDir() throws IOException {
        File tmpDir = ClientBase.createEmptyTestDir();
        File logDir = new File(tmpDir, "logdir");
        File snapDir = new File(tmpDir, "snapdir");
        File logVersionDir = new File(logDir, FileTxnSnapLog.version +  FileTxnSnapLog.VERSION);
        File snapVersionDir = new File(snapDir, FileTxnSnapLog.version +  FileTxnSnapLog.VERSION);

        if (!logVersionDir.exists()) {
            logVersionDir.mkdirs();
        }
        if (!snapVersionDir.exists()) {
            snapVersionDir.mkdirs();
        }

        Assert.assertTrue(logVersionDir.exists());
        Assert.assertTrue(snapVersionDir.exists());

        // transaction log files in log dir - correct
        File logFile1 = new File(logVersionDir.getPath() +File.separator + Util.makeLogName(1L));
        logFile1.createNewFile();
        File logFile2 = new File(logVersionDir.getPath() +File.separator + Util.makeLogName(2L));
        logFile2.createNewFile();

        // snapshot files in log dir - incorrect
        File snapFile3 = new File(logVersionDir.getPath() +File.separator + Util.makeSnapshotName(3L));
        snapFile3.createNewFile();
        File snapFile4 = new File(logVersionDir.getPath() +File.separator + Util.makeSnapshotName(4L));
        snapFile4.createNewFile();

        // snapshot files in snap dir - correct
        File snapFile1 = new File(snapVersionDir.getPath() +File.separator + Util.makeSnapshotName(1L));
        snapFile1.createNewFile();
        File snapFile2 = new File(snapVersionDir.getPath() +File.separator + Util.makeSnapshotName(2L));
        snapFile2.createNewFile();

        Assert.assertTrue(logFile1.exists());
        Assert.assertTrue(logFile2.exists());

        Assert.assertTrue(snapFile3.exists());
        Assert.assertTrue(snapFile4.exists());

        Assert.assertTrue(snapFile1.exists());
        Assert.assertTrue(snapFile2.exists());

        String priorAutocreateDirValue = System.getProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE);
        System.setProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE, "false");
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
    }

    @Test(expected = FileTxnSnapLog.SnapdirContentCheckException.class)
    public void testDirCheckWithLogFilesInSnapDir() throws IOException {
        File tmpDir = ClientBase.createEmptyTestDir();
        File logDir = new File(tmpDir, "logdir");
        File snapDir = new File(tmpDir, "snapdir");
        File logVersionDir = new File(logDir, FileTxnSnapLog.version +  FileTxnSnapLog.VERSION);
        File snapVersionDir = new File(snapDir, FileTxnSnapLog.version +  FileTxnSnapLog.VERSION);

        if (!logVersionDir.exists()) {
            logVersionDir.mkdirs();
        }
        if (!snapVersionDir.exists()) {
            snapVersionDir.mkdirs();
        }

        Assert.assertTrue(logVersionDir.exists());
        Assert.assertTrue(snapVersionDir.exists());

        // transaction log files in log dir - correct
        File logFile1 = new File(logVersionDir.getPath() +File.separator + Util.makeLogName(1L));
        logFile1.createNewFile();
        File logFile2 = new File(logVersionDir.getPath() +File.separator + Util.makeLogName(2L));
        logFile2.createNewFile();

        // snapshot files in snap dir - correct
        File snapFile1 = new File(snapVersionDir.getPath() +File.separator + Util.makeSnapshotName(1L));
        snapFile1.createNewFile();
        File snapFile2 = new File(snapVersionDir.getPath() +File.separator + Util.makeSnapshotName(2L));
        snapFile2.createNewFile();

        // transaction log files in snap dir - incorrect
        File logFile3 = new File(snapVersionDir.getPath() +File.separator + Util.makeLogName(3L));
        logFile3.createNewFile();
        File logFile4 = new File(snapVersionDir.getPath() +File.separator + Util.makeLogName(4L));
        logFile4.createNewFile();

        Assert.assertTrue(logFile1.exists());
        Assert.assertTrue(logFile2.exists());

        Assert.assertTrue(snapFile1.exists());
        Assert.assertTrue(snapFile2.exists());

        Assert.assertTrue(logFile3.exists());
        Assert.assertTrue(logFile4.exists());

        String priorAutocreateDirValue = System.getProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE);
        System.setProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE, "false");
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
    }

}
