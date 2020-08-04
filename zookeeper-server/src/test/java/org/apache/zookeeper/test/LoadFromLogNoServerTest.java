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

package org.apache.zookeeper.test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.server.DataNode;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileHeader;
import org.apache.zookeeper.server.persistence.FileTxnLog;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.DeleteTxn;
import org.apache.zookeeper.txn.MultiTxn;
import org.apache.zookeeper.txn.Txn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoadFromLogNoServerTest extends ZKTestCase {

    protected static final Logger LOG = LoggerFactory.getLogger(LoadFromLogNoServerTest.class);

    /**
     * For ZOOKEEPER-1046. Verify if cversion and pzxid if incremented
     * after create/delete failure during restore.
     */
    @Test
    public void testTxnFailure() throws Exception {
        try {
            ZooKeeperServer.setDigestEnabled(true);

            long count = 1;
            File tmpDir = ClientBase.createTmpDir();
            FileTxnSnapLog logFile = new FileTxnSnapLog(tmpDir, tmpDir);
            DataTree dt = new DataTree();
            dt.createNode("/test", new byte[0], null, 0, -1, 1, 1);
            for (count = 1; count <= 3; count++) {
                dt.createNode("/test/" + count, new byte[0], null, 0, -1, count, Time.currentElapsedTime());
            }
            long digestBefore = dt.getTreeDigest();

            DataNode zk = dt.getNode("/test");

            // Make create to fail, then verify cversion.
            LOG.info("Attempting to create /test/{}", (count - 1));
            doOp(logFile, ZooDefs.OpCode.create, "/test/" + (count - 1), dt, zk, -1);
            assertNotEquals(digestBefore, dt.getTreeDigest());

            LOG.info("Attempting to create /test/{}", (count - 1));
            digestBefore = dt.getTreeDigest();
            doOp(logFile, ZooDefs.OpCode.create, "/test/" + (count - 1), dt, zk, zk.stat.getCversion() + 1);
            assertNotEquals(digestBefore, dt.getTreeDigest());

            LOG.info("Attempting to create /test/{}", (count - 1));
            digestBefore = dt.getTreeDigest();
            doOp(logFile, ZooDefs.OpCode.multi, "/test/" + (count - 1), dt, zk, zk.stat.getCversion() + 1);
            assertNotEquals(digestBefore, dt.getTreeDigest());

            LOG.info("Attempting to create /test/{}", (count - 1));
            digestBefore = dt.getTreeDigest();
            doOp(logFile, ZooDefs.OpCode.multi, "/test/" + (count - 1), dt, zk, -1);
            assertNotEquals(digestBefore, dt.getTreeDigest());

            // Make delete fo fail, then verify cversion.
            // this doesn't happen anymore, we only set the cversion on create
            // LOG.info("Attempting to delete " + "/test/" + (count + 1));
            // doOp(logFile, OpCode.delete, "/test/" + (count + 1), dt, zk);
        } finally {
            ZooKeeperServer.setDigestEnabled(false);
        }
    }

    /*
     * Does create/delete depending on the type and verifies
     * if cversion before the operation is 1 less than cversion afer.
     */
    private void doOp(FileTxnSnapLog logFile, int type, String path, DataTree dt, DataNode parent, int cversion) throws Exception {
        int lastSlash = path.lastIndexOf('/');
        String parentName = path.substring(0, lastSlash);

        int prevCversion = parent.stat.getCversion();
        long prevPzxid = parent.stat.getPzxid();
        List<String> child = dt.getChildren(parentName, null, null);
        StringBuilder childStr = new StringBuilder();
        for (String s : child) {
            childStr.append(s).append(" ");
        }
        LOG.info("Children: {} for {}", childStr, parentName);
        LOG.info("(cverions, pzxid): {}, {}", prevCversion, prevPzxid);

        Record txn = null;
        TxnHeader txnHeader = null;
        if (type == ZooDefs.OpCode.delete) {
            txn = new DeleteTxn(path);
            txnHeader = new TxnHeader(0xabcd, 0x123, prevPzxid + 1, Time.currentElapsedTime(), ZooDefs.OpCode.delete);
        } else if (type == ZooDefs.OpCode.create) {
            txnHeader = new TxnHeader(0xabcd, 0x123, prevPzxid + 1, Time.currentElapsedTime(), ZooDefs.OpCode.create);
            txn = new CreateTxn(path, new byte[0], null, false, cversion);
        } else if (type == ZooDefs.OpCode.multi) {
            txnHeader = new TxnHeader(0xabcd, 0x123, prevPzxid + 1, Time.currentElapsedTime(), ZooDefs.OpCode.create);
            txn = new CreateTxn(path, new byte[0], null, false, cversion);
            List<Txn> txnList = new ArrayList<Txn>();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
            txn.serialize(boa, "request");
            ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());
            Txn txact = new Txn(ZooDefs.OpCode.create, bb.array());
            txnList.add(txact);
            txn = new MultiTxn(txnList);
            txnHeader = new TxnHeader(0xabcd, 0x123, prevPzxid + 1, Time.currentElapsedTime(), ZooDefs.OpCode.multi);
        }
        logFile.processTransaction(txnHeader, dt, null, txn);

        int newCversion = parent.stat.getCversion();
        long newPzxid = parent.stat.getPzxid();
        child = dt.getChildren(parentName, null, null);
        childStr = new StringBuilder();
        for (String s : child) {
            childStr.append(s).append(" ");
        }
        LOG.info("Children: {} for {}", childStr, parentName);
        LOG.info("(cverions, pzxid): {}, {}", newCversion, newPzxid);
        assertTrue((newCversion == prevCversion + 1 && newPzxid == prevPzxid + 1),
                type + " <cversion, pzxid> verification failed. Expected: <" + (prevCversion + 1) + ", "
                        + (prevPzxid + 1) + ">, found: <" + newCversion + ", " + newPzxid + ">");
    }

    /**
     * Simulates ZOOKEEPER-1069 and verifies that flush() before padLogFile
     * fixes it.
     */
    @Test
    public void testPad() throws Exception {
        File tmpDir = ClientBase.createTmpDir();
        FileTxnLog txnLog = new FileTxnLog(tmpDir);
        TxnHeader txnHeader = new TxnHeader(0xabcd, 0x123, 0x123, Time.currentElapsedTime(), ZooDefs.OpCode.create);
        Record txn = new CreateTxn("/Test", new byte[0], null, false, 1);
        txnLog.append(txnHeader, txn);
        FileInputStream in = new FileInputStream(tmpDir.getPath() + "/log." + Long.toHexString(txnHeader.getZxid()));
        BinaryInputArchive ia = BinaryInputArchive.getArchive(in);
        FileHeader header = new FileHeader();
        header.deserialize(ia, "fileheader");
        LOG.info("Received magic : {} Expected : {}", header.getMagic(), FileTxnLog.TXNLOG_MAGIC);
        assertTrue(header.getMagic() == FileTxnLog.TXNLOG_MAGIC, "Missing magic number ");
    }

}
