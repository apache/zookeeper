/**
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

package org.apache.zookeeper.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.zookeeper.proto.ConnectRequest;
import org.apache.zookeeper.proto.ConnectResponse;
import org.junit.jupiter.api.Test;

public class ProtocolManagerTest {

    private static byte[] serializeConnectRequest(ConnectRequest request, boolean withReadOnly) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        OutputArchive oa = BinaryOutputArchive.getArchive(baos);
        request.serialize(oa, "connect");
        baos.close();
        byte[] bytes = baos.toByteArray();

        if (withReadOnly) {
            return bytes;
        } else {
            return Arrays.copyOf(bytes, bytes.length - 1);
        }
    }

    @Test
    public void testDeserializeConnectRequestWithReadonly() throws IOException {
        ProtocolManager protocolManager = new ProtocolManager();

        ConnectRequest req1 = new ConnectRequest();
        req1.setPasswd(new byte[16]);
        req1.setReadOnly(true);

        byte[] bytes = serializeConnectRequest(req1, true);

        // Current protocol.
        assertEquals(45, bytes.length);

        InputArchive ia = BinaryInputArchive.getArchive(new ByteArrayInputStream(bytes));
        ConnectRequest req2 = protocolManager.deserializeConnectRequest(ia);

        assertEquals(true, protocolManager.isReadonlyAvailable());
        assertEquals(true, req2.getReadOnly());
    }

    @Test
    public void testDeserializeConnectRequestWithoutReadonly() throws IOException {
        ProtocolManager protocolManager = new ProtocolManager();

        ConnectRequest req1 = new ConnectRequest();
        req1.setPasswd(new byte[16]);
        // Should get truncated.
        req1.setReadOnly(true);

        byte[] bytes = serializeConnectRequest(req1, false);

        // 3.4 protocol.
        assertEquals(44, bytes.length);

        InputArchive ia = BinaryInputArchive.getArchive(new ByteArrayInputStream(bytes));
        ConnectRequest req2 = protocolManager.deserializeConnectRequest(ia);

        assertEquals(false, protocolManager.isReadonlyAvailable());
        assertEquals(false, req2.getReadOnly());
    }

    private static ProtocolManager prepareProtocolManager(boolean withReadOnly) throws IOException {
        ProtocolManager protocolManager = new ProtocolManager();

        ConnectRequest req1 = new ConnectRequest();
        req1.setPasswd(new byte[16]);
        req1.setReadOnly(true);

        byte[] bytes = serializeConnectRequest(req1, withReadOnly);

        InputArchive ia = BinaryInputArchive.getArchive(new ByteArrayInputStream(bytes));
        // Detects the current protocol.
        ConnectRequest req2 = protocolManager.deserializeConnectRequest(ia);

        return protocolManager;
    }

    @Test
    public void testSerializeConnectResponseWithReadonly() throws IOException {
        ProtocolManager protocolManager = prepareProtocolManager(true);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive os = BinaryOutputArchive.getArchive(baos);

        ConnectResponse rsp = new ConnectResponse();
        rsp.setPasswd(new byte[16]);
        rsp.setReadOnly(true);

        // Should use the current protocol.
        protocolManager.serializeConnectResponse(rsp, os);

        baos.close();

        byte[] bytes = baos.toByteArray();

        assertEquals(37, bytes.length);
        assertEquals(1, bytes[bytes.length - 1]);
    }

    @Test
    public void testSerializeConnectResponseWithoutReadonly() throws IOException {
        ProtocolManager protocolManager = prepareProtocolManager(false);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive os = BinaryOutputArchive.getArchive(baos);

        ConnectResponse rsp = new ConnectResponse();
        rsp.setPasswd(new byte[16]);
        rsp.setReadOnly(true);

        // Should use the 3.4 protocol.
        protocolManager.serializeConnectResponse(rsp, os);

        baos.close();

        byte[] bytes = baos.toByteArray();

        assertEquals(36, bytes.length);
    }
}
