/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jute;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

// TODO: introduce JuteTestCase as in ZKTestCase
public class BinaryInputArchiveTest {

    @Test
    public void testReadStringCheckLength() {
        byte[] buf = new byte[]{
                Byte.MAX_VALUE, Byte.MAX_VALUE, Byte.MAX_VALUE, Byte.MAX_VALUE};
        ByteArrayInputStream is = new ByteArrayInputStream(buf);
        BinaryInputArchive ia = BinaryInputArchive.getArchive(is);
        try {
            ia.readString("");
            Assert.fail("Should have thrown an IOException");
        } catch (IOException e) {
            Assert.assertTrue("Not 'Unreasonable length' exception: " + e,
                    e.getMessage().startsWith(BinaryInputArchive.UNREASONBLE_LENGTH));
        }
    }

    /**
     * Record length is more than the maxbuffer + extrasize length
     */
    @Test
    public void testReadStringForRecordsHavingLengthMoreThanMaxAllowedSize() {
        int maxBufferSize = 2000;
        int extraMaxBufferSize = 1025;
        //this record size is more than the max allowed size
        int recordSize = maxBufferSize + extraMaxBufferSize + 100;
        BinaryInputArchive ia =
            getBinaryInputArchive(recordSize, maxBufferSize, extraMaxBufferSize);
        try {
            ia.readString("");
            Assert.fail("Should have thrown an IOException");
        } catch (IOException e) {
            Assert.assertTrue("Not 'Unreasonable length' exception: " + e,
                e.getMessage().startsWith(BinaryInputArchive.UNREASONBLE_LENGTH));
        }
    }

    /**
     * Record length is less than then maxbuffer + extrasize length
     */
    @Test
    public void testReadStringForRecordsHavingLengthLessThanMaxAllowedSize()
        throws IOException {
        int maxBufferSize = 2000;
        int extraMaxBufferSize = 1025;
        int recordSize = maxBufferSize + extraMaxBufferSize - 100;
        //Exception is not expected as record size is less than the allowed size
        BinaryInputArchive ia =
            getBinaryInputArchive(recordSize, maxBufferSize, extraMaxBufferSize);
        String s = ia.readString("");
        Assert.assertNotNull(s);
        Assert.assertEquals(recordSize, s.getBytes().length);
    }

    private BinaryInputArchive getBinaryInputArchive(int recordSize, int maxBufferSize,
        int extraMaxBufferSize) {
        byte[] data = getData(recordSize);
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
        return new BinaryInputArchive(dis, maxBufferSize, extraMaxBufferSize);
    }

    private byte[] getData(int recordSize) {
        ByteBuffer buf = ByteBuffer.allocate(recordSize + 4);
        buf.putInt(recordSize);
        byte[] bytes = new byte[recordSize];
        for (int i = 0; i < recordSize; i++) {
            bytes[i] = (byte) 'a';
        }
        buf.put(bytes);
        return buf.array();
    }
}
