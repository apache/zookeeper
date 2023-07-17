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

public class ToStringOutputArchiveTest {

    @Test
    public void testDataSize() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(32);
        ToStringOutputArchive outputArchive = new ToStringOutputArchive(baos);
        int dataSize = 0;
        assertEquals(dataSize, outputArchive.getDataSize());
        assertEquals(dataSize, baos.size());

        int boolSize = 1;
        dataSize += boolSize;
        outputArchive.writeBool(true, "bool");
        checkDataSize(dataSize, baos, outputArchive);

        String comma = ",";
        byte b = Byte.MAX_VALUE;
        int byteSize = String.valueOf(b).length();
        dataSize += comma.length() + byteSize;
        outputArchive.writeByte(b, "byte");
        checkDataSize(dataSize, baos, outputArchive);

        int i = 1;
        int intSize = String.valueOf(i).length();
        dataSize += comma.length() + intSize;
        outputArchive.writeInt(i, "int");
        checkDataSize(dataSize, baos, outputArchive);

        long l = 8L;
        int longSize = String.valueOf(l).length();
        dataSize += comma.length() + longSize;
        outputArchive.writeLong(l, "long");
        checkDataSize(dataSize, baos, outputArchive);

        String apostrophe = "'";
        String str = "ab";
        int strSize = str.length();
        dataSize += comma.length() + apostrophe.length() + strSize;
        outputArchive.writeString(str, "string");
        checkDataSize(dataSize, baos, outputArchive);


        float f = 12.0f;
        int floatSize = String.valueOf(f).length();
        dataSize += comma.length() + floatSize;
        outputArchive.writeFloat(f, "float");
        checkDataSize(dataSize, baos, outputArchive);

        double d = 12.44d;
        int doubleSize = String.valueOf(d).length();
        dataSize += comma.length() + doubleSize;
        outputArchive.writeDouble(d, "double");
        checkDataSize(dataSize, baos, outputArchive);

        byte[] bytes = new byte[4];
        bytes[0] = 'a';
        bytes[1] = 'b';
        bytes[2] = 'c';
        bytes[3] = 'd';
        String poundSign = "#";
        int bytesSize = Integer.toHexString(bytes[0]).length()
                + Integer.toHexString(bytes[1]).length()
                + Integer.toHexString(bytes[2]).length()
                + Integer.toHexString(bytes[3]).length();
        dataSize += comma.length() + poundSign.length() + bytesSize;
        outputArchive.writeBuffer(bytes, "bytes");
        checkDataSize(dataSize, baos, outputArchive);

        String schema = "custom";
        String user1 = "horizon";
        String user2 = "zhao";
        WhoAmIResponse whoAmIResponse = new WhoAmIResponse();
        whoAmIResponse.setClientInfo(Arrays.asList(
                new ClientInfo(schema, user1),
                new ClientInfo(schema, user2)));
        String whoAmIResponseStr = whoAmIResponse.toString().replace("\n", "");

        String startRecordSign = "s{";
        String endRecordSign = "}";
        dataSize += comma.length() + startRecordSign.length() + whoAmIResponseStr.length() + endRecordSign.length();
        outputArchive.writeRecord(whoAmIResponse, "record");
        checkDataSize(dataSize, baos, outputArchive);

    }

    private void checkDataSize(int dataSize, ByteArrayOutputStream baos, OutputArchive outputArchive) {
        assertEquals(dataSize, outputArchive.getDataSize());
        assertEquals(baos.size(), outputArchive.getDataSize());
    }
}
