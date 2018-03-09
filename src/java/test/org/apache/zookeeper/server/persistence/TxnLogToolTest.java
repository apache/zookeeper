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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertThat;


public class TxnLogToolTest {
    private static final File testData = new File(
            System.getProperty("test.data.dir", "build/test/data"));

    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private File mySnapDir;

    @Before
    public void setUp() throws IOException {
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
        File snapDir = new File(testData, "invalidsnap");
        mySnapDir = ClientBase.createTmpDir();
        FileUtils.copyDirectory(snapDir, mySnapDir);
    }

    @After
    public void tearDown() throws IOException {
        System.setOut(System.out);
        System.setErr(System.err);
        mySnapDir.setWritable(true);
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
        File logfile = new File(new File(mySnapDir, "version-2"), "log.274");
        File recoveryFile = new File(new File(mySnapDir, "version-2"), "log.274.fixed");
        recoveryFile.createNewFile();
        TxnLogTool lt = new TxnLogTool();

        // Act
        lt.init(true, false, logfile.toString());
    }

    @Test
    public void testDumpWithCrcError() throws Exception {
        // Arrange
        File logfile = new File(new File(mySnapDir, "version-2"), "log.42");
        TxnLogTool lt = new TxnLogTool();
        lt.init(false, false, logfile.toString());

        // Act
        lt.dump();

        // Assert
        String output = outContent.toString();
        assertThat(output, containsString("CRC ERROR - 3/6/18 11:06:09 AM CET session 0x8061fac5ddeb0000"));
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
        logfile = new File(new File(mySnapDir, "version-2"), "log.42.fixed");
        lt.init(false, false, logfile.toString());
        lt.dump();
    }
}
