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
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.core.IsNot.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;


public class TxnLogToolkitTest {
    private static final File testData = new File(
            System.getProperty("test.data.dir", "src/test/resources/data"));

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
        TxnLogToolkit lt = new TxnLogToolkit(false, false, logfile.toString(), true);

        // Act
        lt.dump(null);

        // Assert
        // no exception thrown
    }

    @Test(expected = TxnLogToolkit.TxnLogToolkitException.class)
    public void testInitMissingFile() throws FileNotFoundException, TxnLogToolkit.TxnLogToolkitException {
        // Arrange & Act
        File logfile = new File("this_file_should_not_exists");
        TxnLogToolkit lt = new TxnLogToolkit(false, false, logfile.toString(), true);
    }

    @Test(expected = TxnLogToolkit.TxnLogToolkitException.class)
    public void testInitWithRecoveryFileExists() throws IOException, TxnLogToolkit.TxnLogToolkitException {
        // Arrange & Act
        File logfile = new File(new File(mySnapDir, "version-2"), "log.274");
        File recoveryFile = new File(new File(mySnapDir, "version-2"), "log.274.fixed");
        recoveryFile.createNewFile();
        TxnLogToolkit lt = new TxnLogToolkit(true, false, logfile.toString(), true);
    }

    @Test
    public void testDumpWithCrcError() throws Exception {
        // Arrange
        File logfile = new File(new File(mySnapDir, "version-2"), "log.42");
        TxnLogToolkit lt = new TxnLogToolkit(false, false, logfile.toString(), true);

        // Act
        lt.dump(null);

        // Assert
        String output = outContent.toString();
        Pattern p = Pattern.compile("^CRC ERROR.*session 0x8061fac5ddeb0000 cxid 0x0 zxid 0x8800000002 createSession 30000$", Pattern.MULTILINE);
        Matcher m = p.matcher(output);
        assertTrue("Output doesn't indicate CRC error for the broken session id: " + output, m.find());
    }

    @Test
    public void testRecoveryFixBrokenFile() throws Exception {
        // Arrange
        File logfile = new File(new File(mySnapDir, "version-2"), "log.42");
        TxnLogToolkit lt = new TxnLogToolkit(true, false, logfile.toString(), true);

        // Act
        lt.dump(null);

        // Assert
        String output = outContent.toString();
        assertThat(output, containsString("CRC FIXED"));

        // Should be able to dump the recovered logfile with no CRC error
        outContent.reset();
        logfile = new File(new File(mySnapDir, "version-2"), "log.42.fixed");
        lt = new TxnLogToolkit(false, false, logfile.toString(), true);
        lt.dump(null);
        output = outContent.toString();
        assertThat(output, not(containsString("CRC ERROR")));
    }

    @Test
    public void testRecoveryInteractiveMode() throws Exception {
        // Arrange
        File logfile = new File(new File(mySnapDir, "version-2"), "log.42");
        TxnLogToolkit lt = new TxnLogToolkit(true, false, logfile.toString(), false);

        // Act
        lt.dump(new Scanner("y\n"));

        // Assert
        String output = outContent.toString();
        assertThat(output, containsString("CRC ERROR"));

        // Should be able to dump the recovered logfile with no CRC error
        outContent.reset();
        logfile = new File(new File(mySnapDir, "version-2"), "log.42.fixed");
        lt = new TxnLogToolkit(false, false, logfile.toString(), true);
        lt.dump(null);
        output = outContent.toString();
        assertThat(output, not(containsString("CRC ERROR")));
    }
}
