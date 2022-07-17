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

package org.apache.zookeeper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.io.File;
import java.io.IOException;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.txn.Txn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.Test;

public class TxnLogCountTest {

    /**
     * Test ZkDatabase's txnCount
     */
    @Test
    public void testTxnLogCount() throws IOException {
        File tmpDir = ClientBase.createTmpDir();
        FileTxnSnapLog snapLog = new FileTxnSnapLog(tmpDir, tmpDir);
        ZKDatabase zkDatabase = new ZKDatabase(snapLog);
        int txnRequestCnt = 10;
        int nonTxnRequestCnt = 10;
        for (int i = 0; i < txnRequestCnt && zkDatabase.append(mockTxnRequest()); i++) {}
        assertEquals(txnRequestCnt, zkDatabase.getTxnCount());

        for (int i = 0; i < nonTxnRequestCnt && !zkDatabase.append(mockNonTxnRequest()); i++) {}
        assertEquals(nonTxnRequestCnt, zkDatabase.getTxnCount());
    }

    private Request mockTxnRequest() throws IOException {
        final TxnHeader hdr = new TxnHeader();
        hdr.setClientId(1);
        hdr.setCxid(2);
        hdr.setType(3);
        hdr.setZxid(4);
        final Txn txn = new Txn();
        return new Request(hdr.getClientId(), hdr, txn, hdr.getZxid());
    }

    private Request mockNonTxnRequest() {
        return new Request(0, null, null, 0);
    }
}
