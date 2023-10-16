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
package org.apache.jute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import org.apache.zookeeper.data.ClientInfo;
import org.apache.zookeeper.proto.WhoAmIResponse;
import org.junit.jupiter.api.Test;

public class BinaryOutputArchiveTest {

    @Test
    public void testDataSize() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(32);
        BinaryOutputArchive outputArchive = BinaryOutputArchive.getArchive(baos);
        int dataSize = 0;
        checkDataSize(dataSize, baos, outputArchive);

        int boolSize = 1;
        dataSize += boolSize;
        outputArchive.writeBool(true, "bool");
        checkDataSize(dataSize, baos, outputArchive);

        int byteSize = 1;
        dataSize += byteSize;
        outputArchive.writeByte(Byte.MAX_VALUE, "byte");
        checkDataSize(dataSize, baos, outputArchive);

        int intSize = 4;
        dataSize += intSize;
        outputArchive.writeInt(1, "int");
        checkDataSize(dataSize, baos, outputArchive);

        int longSize = 8;
        dataSize += longSize;
        outputArchive.writeLong(8L, "long");
        checkDataSize(dataSize, baos, outputArchive);

        int stringLengthSize = 4;
        String str = "ab";
        dataSize += stringLengthSize + str.length();
        outputArchive.writeString(str, "string");
        checkDataSize(dataSize, baos, outputArchive);

        int floatSize = 4;
        dataSize += floatSize;
        outputArchive.writeFloat(12.0f, "float");
        checkDataSize(dataSize, baos, outputArchive);

        int doubleSize = 8;
        dataSize += doubleSize;
        outputArchive.writeDouble(12.44d, "double");
        checkDataSize(dataSize, baos, outputArchive);

        int bytesLengthSize = 4;
        byte[] bytes = new byte[4];
        bytes[0] = 'a';
        bytes[1] = 'b';
        bytes[2] = 'c';
        bytes[3] = 'd';
        dataSize += bytesLengthSize + bytes.length;
        outputArchive.writeBuffer(bytes, "bytes");
        checkDataSize(dataSize, baos, outputArchive);

        String schema = "custom";
        String user1 = "horizon";
        String user2 = "zhao";
        WhoAmIResponse whoAmIResponse = new WhoAmIResponse();
        whoAmIResponse.setClientInfo(Arrays.asList(
                new ClientInfo(schema, user1),
                new ClientInfo(schema, user2)));

        int listSizeLength = 4;
        int clientInfo1Length = stringLengthSize + schema.length() + stringLengthSize + user1.length();
        int clientInfo2Length = stringLengthSize + schema.length() + stringLengthSize + user2.length();
        dataSize += listSizeLength + clientInfo1Length + clientInfo2Length;
        outputArchive.writeRecord(whoAmIResponse, "record");
        checkDataSize(dataSize, baos, outputArchive);
    }

    private void checkDataSize(int dataSize, ByteArrayOutputStream baos, OutputArchive outputArchive) {
        assertEquals(dataSize, outputArchive.getDataSize());
        assertEquals(baos.size(), outputArchive.getDataSize());
    }

}
