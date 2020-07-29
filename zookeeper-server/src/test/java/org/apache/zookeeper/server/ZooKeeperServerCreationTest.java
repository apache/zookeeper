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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.proto.ConnectRequest;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.Test;

public class ZooKeeperServerCreationTest {

    /**
     * Test the default ZooKeeperServer and call processConnectRequest() to make sure
     * that all needed fields are initialized properly, etc.
     */
    @Test
    public void testDefaultConstructor() throws Exception {
        File tmpDir = ClientBase.createEmptyTestDir();
        FileTxnSnapLog fileTxnSnapLog = new FileTxnSnapLog(new File(tmpDir, "data"), new File(tmpDir, "data_txnlog"));

        ZooKeeperServer zks = new ZooKeeperServer() {
            @Override
            public void submitRequest(Request si) {
                // NOP
            }
        };
        zks.setTxnLogFactory(fileTxnSnapLog);
        zks.setZKDatabase(new ZKDatabase(fileTxnSnapLog));
        zks.createSessionTracker();

        ServerCnxnFactory cnxnFactory = ServerCnxnFactory.createFactory();
        ServerCnxn cnxn = new MockServerCnxn();

        ConnectRequest connReq = new ConnectRequest();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
        connReq.serialize(boa, "connect");
        zks.processConnectRequest(cnxn, ByteBuffer.wrap(baos.toByteArray()));
    }

}
