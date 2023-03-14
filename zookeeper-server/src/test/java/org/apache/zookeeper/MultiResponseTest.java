/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
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
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.ByteBufferInputStream;
import org.junit.jupiter.api.Test;

public class MultiResponseTest extends ZKTestCase {

    public void testRoundTrip() throws IOException {
        MultiResponse response = new MultiResponse();

        response.add(new OpResult.CheckResult());
        response.add(new OpResult.CreateResult("foo-bar"));
        response.add(new OpResult.DeleteResult());

        Stat s = new Stat();
        s.setCzxid(546);
        response.add(new OpResult.SetDataResult(s));

        MultiResponse decodedResponse = codeDecode(response);

        assertEquals(response, decodedResponse);
        assertEquals(response.hashCode(), decodedResponse.hashCode());
    }

    @Test
    public void testEmptyRoundTrip() throws IOException {
        MultiResponse result = new MultiResponse();
        MultiResponse decodedResult = codeDecode(result);

        assertEquals(result, decodedResult);
        assertEquals(result.hashCode(), decodedResult.hashCode());
    }

    private MultiResponse codeDecode(MultiResponse request) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
        request.serialize(boa, "result");
        baos.close();
        ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());
        bb.rewind();

        BinaryInputArchive bia = BinaryInputArchive.getArchive(new ByteBufferInputStream(bb));
        MultiResponse decodedRequest = new MultiResponse();
        decodedRequest.deserialize(bia, "result");
        return decodedRequest;
    }

}
