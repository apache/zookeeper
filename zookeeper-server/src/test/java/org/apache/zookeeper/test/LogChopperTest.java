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
package org.apache.zookeeper.test;

import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.server.persistence.FileTxnLog;
import org.apache.zookeeper.server.persistence.TxnLog;
import org.apache.zookeeper.server.util.LogChopper;
import org.apache.zookeeper.txn.DeleteTxn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

class Pair<V1, V2> {
    private V1 v1;
    private V2 v2;
    Pair(V1 v1, V2 v2) {
        this.v1 = v1;
        this.v2 = v2;
    }
    public V1 getFirst() {
        return v1;
    }
    public V2 getSecond() {
        return v2;
    }
}

public class LogChopperTest extends ClientBase {

    void rmr(File dir) throws IOException {
        Files.walkFileTree(dir.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    Pair<Long, Long> getFirstLastZxid(File logFile) throws IOException {
        File tmp = createTmpDir();
        Files.copy(logFile.toPath(), new File(tmp, "log.0").toPath());
        FileTxnLog txnLog = new FileTxnLog(tmp);
        TxnLog.TxnIterator it = txnLog.read(0);
        long firstZxid = it.getHeader().getZxid();
        long lastZxid = firstZxid;
        while (it.next()) {
            lastZxid = it.getHeader().getZxid();
        }
        txnLog.close();
        rmr(tmp);
        return new Pair<Long, Long>(firstZxid, lastZxid);
    }

    @Test
    public void testChopper() throws IOException {
        long clientId = 17;
        int cxid = 77;
        long zxid = 1000;
        long time = 1;
        int type = ZooDefs.OpCode.delete;
        DeleteTxn txn = new DeleteTxn("/foo");
        File tmpDir = createTmpDir();
        FileTxnLog txnLog = new FileTxnLog(tmpDir);

        for (int i = 0; i < 100; i++) {
            TxnHeader hdr = new TxnHeader(clientId, cxid, ++zxid, ++time, type);
            txnLog.append(hdr, txn);
        }

        // append a txn with gap
        TxnHeader hdr = new TxnHeader(clientId, cxid, zxid + 10, ++time, type);
        txnLog.append(hdr, txn);

        txnLog.commit();

        // now find the log we just created.
        final File logFile = new File(tmpDir, "log." + Integer.toHexString(1001));
        Pair<Long, Long> firstLast = getFirstLastZxid(logFile);
        Assert.assertEquals(1001, (long)firstLast.getFirst());
        Assert.assertEquals(1110, (long)firstLast.getSecond());

        File choppedFile = new File(tmpDir, "chopped_failed");
        Assert.assertFalse(LogChopper.chop(
                new FileInputStream(logFile),
                new FileOutputStream(choppedFile), 1107));

        choppedFile = new File(tmpDir, "chopped");
        Assert.assertTrue(LogChopper.chop(
                new FileInputStream(logFile),
                new FileOutputStream(choppedFile), 1017));

        firstLast = getFirstLastZxid(choppedFile);
        Assert.assertEquals(1001, (long)firstLast.getFirst());
        Assert.assertEquals(1017, (long)firstLast.getSecond());
    }
}
