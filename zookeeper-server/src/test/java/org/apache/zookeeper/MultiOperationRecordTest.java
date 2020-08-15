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

package org.apache.zookeeper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.server.ByteBufferInputStream;
import org.junit.jupiter.api.Test;

public class MultiOperationRecordTest extends ZKTestCase {

    @Test
    public void testRoundTrip() throws IOException {
        MultiOperationRecord request = new MultiOperationRecord();
        request.add(Op.check("check", 1));
        request.add(Op.create("create", "create data".getBytes(), ZooDefs.Ids.CREATOR_ALL_ACL, ZooDefs.Perms.ALL));
        request.add(Op.delete("delete", 17));
        request.add(Op.setData("setData", "set data".getBytes(), 19));

        MultiOperationRecord decodedRequest = codeDecode(request);

        assertEquals(request, decodedRequest);
        assertEquals(request.hashCode(), decodedRequest.hashCode());
    }

    @Test
    public void testEmptyRoundTrip() throws IOException {
        MultiOperationRecord request = new MultiOperationRecord();
        MultiOperationRecord decodedRequest = codeDecode(request);

        assertEquals(request, decodedRequest);
        assertEquals(request.hashCode(), decodedRequest.hashCode());
    }

    private MultiOperationRecord codeDecode(MultiOperationRecord request) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
        request.serialize(boa, "request");
        baos.close();
        ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());
        bb.rewind();

        BinaryInputArchive bia = BinaryInputArchive.getArchive(new ByteBufferInputStream(bb));
        MultiOperationRecord decodedRequest = new MultiOperationRecord();
        decodedRequest.deserialize(bia, "request");
        return decodedRequest;
    }

}
