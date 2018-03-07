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

import org.apache.commons.io.FileUtils;
import org.apache.zookeeper.test.ClientBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public class TxnLogToolTest {
    private static final File testData = new File(
            System.getProperty("test.data.dir", "build/test/data"));

    private File mySnapDir;

    @Before
    public void setUp() throws IOException {
        File snapDir = new File(testData, "invalidsnap");
        mySnapDir = ClientBase.createTmpDir();
        FileUtils.copyDirectory(snapDir, mySnapDir);
    }

    @After
    public void tearDown() throws IOException {
        FileUtils.deleteDirectory(mySnapDir);
    }

    @Test
    public void testDumpMode() throws Exception {
        // Arrange
        File logfile = new File(new File(mySnapDir, "version-2"), "log.274");
        TxnLogTool lt = new TxnLogTool();
        lt.init(false, false, logfile.toString());

        // Act
        lt.dump();

        // Assert
        // no exception thrown
    }

    @Test(expected = TxnLogTool.TxnLogToolException.class)
    public void testInitMissingFile() throws FileNotFoundException, TxnLogTool.TxnLogToolException {
        // Arrange
        File logfile = new File("this_file_should_not_exists");
        TxnLogTool lt = new TxnLogTool();

        // Act
        lt.init(false, false, logfile.toString());
    }

    @Test(expected = TxnLogTool.TxnLogToolException.class)
    public void testInitWithRecoveryFileExists() throws IOException, TxnLogTool.TxnLogToolException {
        // Arrange
        File snapDir = new File(testData, "invalidsnap");
        File logfile = new File(new File(snapDir, "version-2"), "log.274");
        File recoveryFile = new File(new File(snapDir, "version-2"), "log.274.fixed");
        recoveryFile.createNewFile();
        TxnLogTool lt = new TxnLogTool();

        // Act
        lt.init(true, false, logfile.toString());
    }

    @Test(expected = TxnLogTool.TxnLogToolException.class)
    public void testInitWithRecoveryFileNotWritable() throws IOException, TxnLogTool.TxnLogToolException {
        // Arrange
        File snapDir = new File(testData, "invalidsnap");
        snapDir.setWritable(false);
        File logfile = new File(new File(snapDir, "version-2"), "log.274");
        TxnLogTool lt = new TxnLogTool();

        // Act
        lt.init(true, false, logfile.toString());
    }

    @Test(expected = IOException.class)
    public void testDumpWithCrcError() throws Exception {
        // Arrange
        File logfile = new File(new File(mySnapDir, "version-2"), "log.42");
        TxnLogTool lt = new TxnLogTool();
        lt.init(false, false, logfile.toString());

        // Act
        lt.dump();
    }

    @Test
    public void testRecoveryFixBrokenFile() throws Exception {
        // Arrange
        File logfile = new File(new File(mySnapDir, "version-2"), "log.42");
        TxnLogTool lt = new TxnLogTool();
        lt.init(true, false, logfile.toString());

        // Act
        lt.dump();

        // Assert
        // Should be able to dump the recovered logfile
        File fixedLogDir = new File(testData, "fixedLog");

        logfile = new File(new File(mySnapDir, "version-2"), "log.42.fixed");
        lt.init(false, false, logfile.toString());
        lt.dump();
    }
}
