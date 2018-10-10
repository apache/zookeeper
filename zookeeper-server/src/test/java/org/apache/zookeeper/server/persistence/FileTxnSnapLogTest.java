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

import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.TestUtils;
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

    @Test
    public void testDirCheckWithCorrectFiles() throws IOException {
        twoDirSetupWithCorrectFiles();

        try {
            new FileTxnSnapLog(logDir, snapDir);
        } catch (FileTxnSnapLog.LogDirContentCheckException e) {
            Assert.fail("Should not throw LogDirContentCheckException.");
        } catch ( FileTxnSnapLog.SnapDirContentCheckException e){
            Assert.fail("Should not throw SnapDirContentCheckException.");
        }
    }

    @Test
    public void testDirCheckWithSingleDirSetup() throws IOException {
        singleDirSetupWithCorrectFiles();

        try {
            new FileTxnSnapLog(logDir, logDir);
        } catch (FileTxnSnapLog.LogDirContentCheckException e) {
            Assert.fail("Should not throw LogDirContentCheckException.");
        } catch ( FileTxnSnapLog.SnapDirContentCheckException e){
            Assert.fail("Should not throw SnapDirContentCheckException.");
        }
    }

    @Test(expected = FileTxnSnapLog.LogDirContentCheckException.class)
    public void testDirCheckWithSnapFilesInLogDir() throws IOException {
        twoDirSetupWithCorrectFiles();

        // add snapshot files to the log version dir
        createSnapshotFile(logVersionDir,3);
        createSnapshotFile(logVersionDir,4);

        new FileTxnSnapLog(logDir, snapDir);
    }

    @Test(expected = FileTxnSnapLog.SnapDirContentCheckException.class)
    public void testDirCheckWithLogFilesInSnapDir() throws IOException {
        twoDirSetupWithCorrectFiles();

        // add transaction log files to the snap version dir
        createLogFile(snapVersionDir,3);
        createLogFile(snapVersionDir,4);

        new FileTxnSnapLog(logDir, snapDir);
    }
}
