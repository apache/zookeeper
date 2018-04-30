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

import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.server.ServerStats;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockito.Mockito.mock;

public class FileTxnLogTest  extends ZKTestCase {
  protected static final Logger LOG = LoggerFactory.getLogger(FileTxnLogTest.class);

  private static final int KB = 1024;

  @Test
  public void testInvalidPreallocSize() {
    Assert.assertEquals("file should not be padded",
      10 * KB, FilePadding.calculateFileSizeWithPadding(7 * KB, 10 * KB, 0));
    Assert.assertEquals("file should not be padded",
      10 * KB, FilePadding.calculateFileSizeWithPadding(7 * KB, 10 * KB, -1));
  }

  @Test
  public void testCalculateFileSizeWithPaddingWhenNotToCurrentSize() {
    Assert.assertEquals("file should not be padded",
      10 * KB, FilePadding.calculateFileSizeWithPadding(5 * KB, 10 * KB, 10 * KB));
  }

  @Test
  public void testCalculateFileSizeWithPaddingWhenCloseToCurrentSize() {
    Assert.assertEquals("file should be padded an additional 10 KB",
      20 * KB, FilePadding.calculateFileSizeWithPadding(7 * KB, 10 * KB, 10 * KB));
  }

  @Test
  public void testFileSizeGreaterThanPosition() {
    Assert.assertEquals("file should be padded to 40 KB",
      40 * KB, FilePadding.calculateFileSizeWithPadding(31 * KB, 10 * KB, 10 * KB));
  }

  @Test
  public void testPreAllocSizeSmallerThanTxnData() throws IOException {
    File logDir = ClientBase.createTmpDir();
    FileTxnLog fileTxnLog = new FileTxnLog(logDir);

    // Set a small preAllocSize (.5 MB)
    final int preAllocSize = 500 * KB;
    FilePadding.setPreallocSize(preAllocSize);

    // Create dummy txn larger than preAllocSize
    // Since the file padding inserts a 0, we will fill the data with 0xff to ensure we corrupt the data if we put the 0 in the data
    byte[] data = new byte[2 * preAllocSize];
    Arrays.fill(data, (byte) 0xff);

    // Append and commit 2 transactions to the log
    // Prior to ZOOKEEPER-2249, attempting to pad in association with the second transaction will corrupt the first
    fileTxnLog.append(new TxnHeader(1, 1, 1, 1, ZooDefs.OpCode.create),
      new CreateTxn("/testPreAllocSizeSmallerThanTxnData1", data, ZooDefs.Ids.OPEN_ACL_UNSAFE, false, 0));
    fileTxnLog.commit();
    fileTxnLog.append(new TxnHeader(1, 1, 2, 2, ZooDefs.OpCode.create),
      new CreateTxn("/testPreAllocSizeSmallerThanTxnData2", new byte[]{}, ZooDefs.Ids.OPEN_ACL_UNSAFE, false, 0));
    fileTxnLog.commit();
    fileTxnLog.close();

    // Read the log back from disk, this will throw a java.io.IOException: CRC check failed prior to ZOOKEEPER-2249
    FileTxnLog.FileTxnIterator fileTxnIterator = new FileTxnLog.FileTxnIterator(logDir, 0);

    // Verify the data in the first transaction
    CreateTxn createTxn = (CreateTxn) fileTxnIterator.getTxn();
    Assert.assertTrue(Arrays.equals(createTxn.getData(), data));

    // Verify the data in the second transaction
    fileTxnIterator.next();
    createTxn = (CreateTxn) fileTxnIterator.getTxn();
    Assert.assertTrue(Arrays.equals(createTxn.getData(), new byte[]{}));
  }

  @Test
  public void testSetPreallocSize() {
    long customPreallocSize = 10101;
    FileTxnLog.setPreallocSize(customPreallocSize);
    Assert.assertThat(FilePadding.getPreAllocSize(), is(equalTo(customPreallocSize)));
  }

  @Test
  public void testSyncThresholdExceedCount() throws IOException {
    // Given ...

    // Set threshold to -1, as after the first commit it takes 0ms to commit to disk.
    java.lang.System.setProperty("zookeeper.fsync.warningthresholdms", "-1");
    ServerStats.Provider providerMock = mock(ServerStats.Provider.class);
    ServerStats serverStats = new ServerStats(providerMock);

    File logDir = ClientBase.createTmpDir();
    FileTxnLog fileTxnLog = new FileTxnLog(logDir);
    fileTxnLog.setServerStats(serverStats);

    // Verify serverStats is 0 before any commit
    Assert.assertEquals(0L, serverStats.getFsyncThresholdExceedCount());

    // When ...
    for (int i = 0; i < 50; i++) {
      fileTxnLog.append(new TxnHeader(1, 1, 1, 1, ZooDefs.OpCode.create),
              new CreateTxn("/testFsyncThresholdCountIncreased", new byte[]{}, ZooDefs.Ids.OPEN_ACL_UNSAFE, false, 0));
      fileTxnLog.commit(); // only 1 commit, otherwise it will be flaky
      // Then ... verify serverStats is updated to the number of commits (as threshold is set to 0)
      // Assert.assertEquals((long) i + 1 , serverStats.getFsyncThresholdExceedCount());
    }
  }
}
