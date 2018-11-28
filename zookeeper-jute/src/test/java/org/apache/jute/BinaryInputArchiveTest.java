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

import java.io.*;

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

    void checkWriterAndReader(TestWriter writer, TestReader reader) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive oa = BinaryOutputArchive.getArchive(baos);
        try {
            writer.write(oa);
        } catch (IOException e) {
            Assert.fail("Should not throw IOException");
        }
        InputStream is = new ByteArrayInputStream(baos.toByteArray());
        BinaryInputArchive ia = BinaryInputArchive.getArchive(is);
        try {
            reader.read(ia);
        } catch (IOException e) {
            Assert.fail("Should not throw IOException while reading back");
        }
    }

    @Test
    public void testInt() {
        final int expected = 4;
        final String tag = "tag1";
        checkWriterAndReader(
            (oa) -> oa.writeInt(expected, tag),
            (ia) -> {
                int actual = ia.readInt(tag);
                Assert.assertEquals(expected, actual);
            }
        );
    }

    @Test
    public void testBool() {
        final boolean expected = false;
        final String tag = "tag1";
        checkWriterAndReader(
            (oa) -> oa.writeBool(expected, tag),
            (ia) -> {
                    boolean actual = ia.readBool(tag);
                    Assert.assertEquals(expected, actual);
            }
        );
    }

    @Test
    public void testString() {
        final String expected = "hello";
        final String tag = "tag1";
        checkWriterAndReader(
            (oa) -> oa.writeString(expected, tag),
            (ia) -> {
                String actual = ia.readString(tag);
                Assert.assertEquals(expected, actual);
            }
        );
    }

    @Test
    public void testFloat() {
        final float expected = 3.14159f;
        final String tag = "tag1";
        final float delta = 1e-10f;
        checkWriterAndReader(
            (oa) -> oa.writeFloat(expected, tag),
            (ia) -> {
                    float actual = ia.readFloat(tag);
                    Assert.assertEquals(expected, actual, delta);
            }
        );
    }

    @Test
    public void testDouble() {
        final double expected = 3.14159f;
        final String tag = "tag1";
        final float delta = 1e-20f;
        checkWriterAndReader(
            (oa) -> oa.writeDouble(expected, tag),
            (ia) -> {
                    double actual = ia.readDouble(tag);
                    Assert.assertEquals(expected, actual, delta);
            }
        );
    }

    @Test
    public void testBuffer() {
        try {
            final byte[] expected = "hello-world".getBytes("UTF-8");
            final String tag = "tag1";
            checkWriterAndReader(
                    (oa) -> oa.writeBuffer(expected, tag),
                    (ia) -> {
                        byte [] actual = ia.readBuffer(tag);
                        Assert.assertArrayEquals(expected, actual);
                    }
            );
        } catch (UnsupportedEncodingException e) {
            Assert.fail("utf-8 encoding not supported");
        }
    }

}
