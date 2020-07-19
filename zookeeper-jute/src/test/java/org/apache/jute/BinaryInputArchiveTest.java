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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

// TODO: introduce JuteTestCase as in ZKTestCase

/**
 *
 */
public class BinaryInputArchiveTest {

    @Test
    public void testReadStringCheckLength() {
        byte[] buf = new byte[]{
                Byte.MAX_VALUE, Byte.MAX_VALUE, Byte.MAX_VALUE, Byte.MAX_VALUE};
        ByteArrayInputStream is = new ByteArrayInputStream(buf);
        BinaryInputArchive ia = BinaryInputArchive.getArchive(is);
        try {
            ia.readString("");
            fail("Should have thrown an IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().startsWith(BinaryInputArchive.UNREASONBLE_LENGTH),
                    () -> "Not 'Unreasonable length' exception: " + e);
        }
    }

    private void checkWriterAndReader(TestWriter writer, TestReader reader) {
        TestCheckWriterReader.checkWriterAndReader(
                BinaryOutputArchive::getArchive,
                BinaryInputArchive::getArchive,
                writer,
                reader
        );
    }

    @Test
    public void testInt() {
        final int expected = 4;
        final String tag = "tag1";
        checkWriterAndReader(
                (oa) -> oa.writeInt(expected, tag),
                (ia) -> {
                    int actual = ia.readInt(tag);
                    assertEquals(expected, actual);
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
                    assertEquals(expected, actual);
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
                    assertEquals(expected, actual);
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
                    assertEquals(expected, actual, delta);
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
                    assertEquals(expected, actual, delta);
                }
        );
    }

    @Test
    public void testBuffer() {
        final byte[] expected = "hello-world".getBytes(StandardCharsets.UTF_8);
        final String tag = "tag1";
        checkWriterAndReader(
                (oa) -> oa.writeBuffer(expected, tag),
                (ia) -> {
                    byte[] actual = ia.readBuffer(tag);
                    assertArrayEquals(expected, actual);
                }
        );
    }
  /**
   * Record length is more than the maxbuffer + extrasize length.
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
      fail("Should have thrown an IOException");
    } catch (IOException e) {
      assertTrue(e.getMessage().startsWith(BinaryInputArchive.UNREASONBLE_LENGTH),
              () -> "Not 'Unreasonable length' exception: " + e);
    }
  }

  /**
   * Record length is less than then maxbuffer + extrasize length.
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
    assertNotNull(s);
    assertEquals(recordSize, s.getBytes().length);
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
