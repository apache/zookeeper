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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.zookeeper.ZKTestCase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ByteBufferInputStreamTest extends ZKTestCase {

    private static final byte[] DATA_BYTES_0 = "Apache ZooKeeper".getBytes(StandardCharsets.UTF_8);

    private static byte[] DATA_BYTES;
    @BeforeAll
    public static void setUpClass() {
        int len = DATA_BYTES_0.length + 2;
        DATA_BYTES = new byte[len];
        System.arraycopy(DATA_BYTES_0, 0, DATA_BYTES, 0, DATA_BYTES_0.length);
        DATA_BYTES[len - 2] = (byte) 0x0;
        DATA_BYTES[len - 1] = (byte) 0xff;
    }

    private ByteBuffer bb;
    private ByteBufferInputStream in;
    private byte[] bs;
    @BeforeEach
    public void setUp() throws Exception {
        bb = ByteBuffer.wrap(DATA_BYTES);
        in = new ByteBufferInputStream(bb);
        bs = new byte[]{(byte) 1, (byte) 2, (byte) 3, (byte) 4};
    }

    @Test
    public void testRead() throws Exception {
        for (int i = 0; i < DATA_BYTES.length; i++) {
            int b = in.read();
            assertEquals(DATA_BYTES[i], (byte) b);
        }
        assertEquals(-1, in.read());
    }
    @Test
    public void testReadArrayOffsetLength() throws Exception {
        assertEquals(1, in.read(bs, 2, 1));
        byte[] expected = new byte[]{(byte) 1, (byte) 2, DATA_BYTES[0], (byte) 4};
        assertArrayEquals(expected, bs);
    }
    @Test
    public void testReadArrayOffsetLength_LengthTooLarge() throws Exception {
        assertThrows(IndexOutOfBoundsException.class, () -> {
            in.read(bs, 2, 3);
        });
    }
    @Test
    public void testReadArrayOffsetLength_HitEndOfStream() throws Exception {
        for (int i = 0; i < DATA_BYTES.length - 1; i++) {
            in.read();
        }
        assertEquals(1, in.read(bs, 2, 2));
        byte[] expected = new byte[]{(byte) 1, (byte) 2, DATA_BYTES[DATA_BYTES.length - 1], (byte) 4};
        assertArrayEquals(expected, bs);
    }
    @Test
    public void testReadArrayOffsetLength_AtEndOfStream() throws Exception {
        for (int i = 0; i < DATA_BYTES.length; i++) {
            in.read();
        }
        byte[] expected = Arrays.copyOf(bs, bs.length);
        assertEquals(-1, in.read(bs, 2, 2));
        assertArrayEquals(expected, bs);
    }
    @Test
    public void testReadArrayOffsetLength_0Length() throws Exception {
        byte[] expected = Arrays.copyOf(bs, bs.length);
        assertEquals(0, in.read(bs, 2, 0));
        assertArrayEquals(expected, bs);
    }
    @Test
    public void testReadArray() throws Exception {
        byte[] expected = Arrays.copyOf(DATA_BYTES, 4);
        assertEquals(4, in.read(bs));
        assertArrayEquals(expected, bs);
    }

    @Test
    public void testSkip() throws Exception {
        in.read();
        assertEquals(2L, in.skip(2L));
        assertEquals(DATA_BYTES[3], in.read());
        assertEquals(DATA_BYTES[4], in.read());
    }
    @Test
    public void testSkip2() throws Exception {
        for (int i = 0; i < DATA_BYTES.length / 2; i++) {
            in.read();
        }
        long skipAmount = DATA_BYTES.length / 4;
        assertEquals(skipAmount, in.skip(skipAmount));
        int idx = DATA_BYTES.length / 2 + (int) skipAmount;
        assertEquals(DATA_BYTES[idx++], in.read());
        assertEquals(DATA_BYTES[idx++], in.read());
    }
    @Test
    public void testNegativeSkip() throws Exception {
        in.read();
        assertEquals(0L, in.skip(-2L));
        assertEquals(DATA_BYTES[1], in.read());
        assertEquals(DATA_BYTES[2], in.read());
    }
    @Test
    public void testSkip_HitEnd() throws Exception {
        for (int i = 0; i < DATA_BYTES.length - 1; i++) {
            in.read();
        }
        assertEquals(1L, in.skip(2L));
        assertEquals(-1, in.read());
    }
    @Test
    public void testSkip_AtEnd() throws Exception {
        for (int i = 0; i < DATA_BYTES.length; i++) {
            in.read();
        }
        assertEquals(0L, in.skip(2L));
        assertEquals(-1, in.read());
    }

    @Test
    public void testAvailable() throws Exception {
        for (int i = DATA_BYTES.length; i > 0; i--) {
            assertEquals(i, in.available());
            in.read();
        }
        assertEquals(0, in.available());
    }

}
