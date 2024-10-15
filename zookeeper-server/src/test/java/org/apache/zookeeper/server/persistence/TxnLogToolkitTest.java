/*
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

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsNot.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.io.FileUtils;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.txn.CheckVersionTxn;
import org.apache.zookeeper.txn.CreateContainerTxn;
import org.apache.zookeeper.txn.CreateTTLTxn;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.DeleteTxn;
import org.apache.zookeeper.txn.ErrorTxn;
import org.apache.zookeeper.txn.MultiTxn;
import org.apache.zookeeper.txn.SetDataTxn;
import org.apache.zookeeper.txn.Txn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TxnLogToolkitTest {

    private static final File testData = new File(System.getProperty("test.data.dir", "src/test/resources/data"));

    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private File mySnapDir;

    @BeforeEach
    public void setUp() throws IOException {
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
        File snapDir = new File(testData, "invalidsnap");
        mySnapDir = ClientBase.createTmpDir();
        FileUtils.copyDirectory(snapDir, mySnapDir);
    }

    @AfterEach
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

    @Test
    public void testInitMissingFile() throws FileNotFoundException, TxnLogToolkit.TxnLogToolkitException {
        assertThrows(TxnLogToolkit.TxnLogToolkitException.class, () -> {
            // Arrange & Act
            File logfile = new File("this_file_should_not_exists");
            TxnLogToolkit lt = new TxnLogToolkit(false, false, logfile.toString(), true);
        });
    }

    @Test
    public void testMultiTxnDecode() throws IOException {
        // MultiTxn with multi ops including errors
        List<Txn> txns = new ArrayList<>();
        txns.add(newSubTxn(ZooDefs.OpCode.error, new ErrorTxn(KeeperException.Code.NONODE.intValue())));
        txns.add(newSubTxn(ZooDefs.OpCode.error, new ErrorTxn(KeeperException.Code.RUNTIMEINCONSISTENCY.intValue())));
        txns.add(newSubTxn(ZooDefs.OpCode.create, new CreateTxn("/test", "test-data".getBytes(StandardCharsets.UTF_8), ZooDefs.Ids.OPEN_ACL_UNSAFE, true, 1)));
        txns.add(newSubTxn(ZooDefs.OpCode.createContainer, new CreateContainerTxn("/test_container", "test-data".getBytes(StandardCharsets.UTF_8), ZooDefs.Ids.OPEN_ACL_UNSAFE, 2)));
        txns.add(newSubTxn(ZooDefs.OpCode.createTTL, new CreateTTLTxn("/test_container", "test-data".getBytes(StandardCharsets.UTF_8), ZooDefs.Ids.OPEN_ACL_UNSAFE, 2, 20)));
        txns.add(newSubTxn(ZooDefs.OpCode.setData, new SetDataTxn("/test_set_data", "test-data".getBytes(StandardCharsets.UTF_8), 4)));
        txns.add(newSubTxn(ZooDefs.OpCode.delete, new DeleteTxn("/test_delete")));
        txns.add(newSubTxn(ZooDefs.OpCode.check, new CheckVersionTxn("/test_check_version", 5)));
        MultiTxn multiTxn = new MultiTxn(txns);
        String formattedTxnStr = TxnLogToolkit.getFormattedTxnStr(multiTxn);
        assertEquals("error:-101;error:-2;create:/test,test-data,[31,s{'world,'anyone}\n"
                    + "],true,1;createContainer:/test_container,test-data,[31,s{'world,'anyone}\n"
                    + "],2;createTTL:/test_container,test-data,[31,s{'world,'anyone}\n"
                    + "],2,20;setData:/test_set_data,test-data,4;delete:/test_delete;check:/test_check_version,5", formattedTxnStr);
    }

    private static Txn newSubTxn(int type, Record record) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
            record.serialize(boa, "request");
            ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());
            return new Txn(type, bb.array());
        }
    }

    @Test
    public void testInitWithRecoveryFileExists() {
        assertThrows(TxnLogToolkit.TxnLogToolkitException.class, () -> {
            // Arrange & Act
            File logfile = new File(new File(mySnapDir, "version-2"), "log.274");
            File recoveryFile = new File(new File(mySnapDir, "version-2"), "log.274.fixed");
            recoveryFile.createNewFile();
            TxnLogToolkit lt = new TxnLogToolkit(true, false, logfile.toString(), true);
        });
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
        assertTrue(m.find(), "Output doesn't indicate CRC error for the broken session id: " + output);
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
