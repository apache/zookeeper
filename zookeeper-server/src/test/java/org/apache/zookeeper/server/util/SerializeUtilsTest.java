/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.txn.Txn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.jupiter.api.Test;

public class SerializeUtilsTest {

    @Test
    public void testSerializeRequestRequestHeaderIsNull() {
        Request request = new Request(0, null, null, 0);
        byte[] data = SerializeUtils.serializeRequest(request);
        assertNull(data);
    }

    @Test
    public void testSerializeRequestWithoutTxn() throws IOException {
        TxnHeader hdr = new TxnHeader();
        hdr.setClientId(1);
        hdr.setCxid(2);
        hdr.setType(3);
        hdr.setZxid(4);
        Request request = new Request(hdr.getClientId(), hdr, null, hdr.getZxid());

        // Act
        byte[] data = SerializeUtils.serializeRequest(request);

        // Assert
        assertNotNull(data);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
        hdr.serialize(boa, "hdr");
        baos.close();
        assertArrayEquals(baos.toByteArray(), data);
    }

    @Test
    public void testSerializeRequestWithTxn() throws IOException {
        Txn txn = new Txn();
        TxnHeader hdr = new TxnHeader();
        hdr.setClientId(1);
        hdr.setCxid(2);
        hdr.setType(3);
        hdr.setZxid(4);
        Request request = new Request(hdr.getClientId(), hdr, txn, hdr.getZxid());

        // Act
        byte[] data = SerializeUtils.serializeRequest(request);

        // Assert
        assertNotNull(data);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
        hdr.serialize(boa, "hdr");
        txn.serialize(boa, "txn");
        baos.close();
        assertArrayEquals(baos.toByteArray(), data);
    }

}
