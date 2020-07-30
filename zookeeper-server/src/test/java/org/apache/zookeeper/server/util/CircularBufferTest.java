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

package org.apache.zookeeper.server.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.Test;

public class CircularBufferTest {

    @Test
    public void testCircularBuffer() {
        final int capacity = 3;
        CircularBuffer<String> buffer = new CircularBuffer<>(String.class, capacity);

        assertTrue(buffer.isEmpty());
        assertFalse(buffer.isFull());

        // write to the buffer
        buffer.write("A");
        assertFalse(buffer.isEmpty());
        assertFalse(buffer.isFull());

        buffer.write("B");
        assertFalse(buffer.isEmpty());
        assertFalse(buffer.isFull());

        buffer.write("C");
        assertFalse(buffer.isEmpty());
        assertTrue(buffer.isFull());

        // Buffer is full.
        // Read from buffer
        assertEquals("A", buffer.take());
        assertFalse(buffer.isEmpty());
        assertFalse(buffer.isFull());

        assertEquals("B", buffer.take());
        assertFalse(buffer.isEmpty());
        assertFalse(buffer.isFull());

        assertEquals("C", buffer.take());
        assertTrue(buffer.isEmpty());
        assertFalse(buffer.isFull());

        // write to the buffer
        buffer.write("1");
        assertFalse(buffer.isEmpty());
        assertFalse(buffer.isFull());

        buffer.write("2");
        assertFalse(buffer.isEmpty());
        assertFalse(buffer.isFull());

        buffer.write("3");
        assertFalse(buffer.isEmpty());
        assertTrue(buffer.isFull());

        buffer.write("4"); // 4 overwrites 1
        assertFalse(buffer.isEmpty());
        assertTrue(buffer.isFull());

        // Buffer if full
        // Read from buffer
        assertEquals("2", buffer.take());
        assertFalse(buffer.isEmpty());
        assertFalse(buffer.isFull());

        assertEquals("3", buffer.take());
        assertFalse(buffer.isEmpty());
        assertFalse(buffer.isFull());

        assertEquals("4", buffer.take());
        assertTrue(buffer.isEmpty());
        assertFalse(buffer.isFull());

        // write to the buffer
        buffer.write("a");
        assertFalse(buffer.isEmpty());
        assertFalse(buffer.isFull());

        buffer.write("b");
        assertFalse(buffer.isEmpty());
        assertFalse(buffer.isFull());

        buffer.write("c");
        assertFalse(buffer.isEmpty());
        assertTrue(buffer.isFull());

        buffer.write("d"); // d overwrites a
        assertFalse(buffer.isEmpty());
        assertTrue(buffer.isFull());

        buffer.write("e"); // e overwrites b
        assertFalse(buffer.isEmpty());
        assertTrue(buffer.isFull());

        buffer.write("f"); // f overwrites c
        assertFalse(buffer.isEmpty());
        assertTrue(buffer.isFull());

        buffer.write("g"); // g overwrites d
        assertFalse(buffer.isEmpty());
        assertTrue(buffer.isFull());

        // Buffer is full.
        // Read from buffer
        assertEquals("e", buffer.take());
        assertFalse(buffer.isEmpty());
        assertFalse(buffer.isFull());

        assertEquals("f", buffer.take());
        assertFalse(buffer.isEmpty());
        assertFalse(buffer.isFull());

        assertEquals("g", buffer.take());
        assertTrue(buffer.isEmpty());
        assertFalse(buffer.isFull());
    }

    @Test
    public void testCircularBufferWithCapacity1() {
        final int capacity = 1;
        CircularBuffer<String> buffer = new CircularBuffer<>(String.class, capacity);

        assertTrue(buffer.isEmpty());
        assertFalse(buffer.isFull());

        // write to the buffer
        buffer.write("A");
        assertFalse(buffer.isEmpty());
        assertTrue(buffer.isFull());

        buffer.write("B"); // B overwrite A
        assertFalse(buffer.isEmpty());
        assertTrue(buffer.isFull());

        // Buffer is full.
        // Read from buffer
        assertEquals("B", buffer.take());
        assertTrue(buffer.isEmpty());
        assertFalse(buffer.isFull());
    }

    @Test
    public void testCircularBufferReset() {
        final int capacity = 3;
        CircularBuffer<String> buffer = new CircularBuffer<>(String.class, capacity);

        assertTrue(buffer.isEmpty());
        assertFalse(buffer.isFull());

        // write to the buffer
        buffer.write("A");
        assertFalse(buffer.isEmpty());
        assertFalse(buffer.isFull());
        assertEquals(1, buffer.size());
        assertEquals("A", buffer.peek());

        buffer.write("B");
        assertFalse(buffer.isEmpty());
        assertFalse(buffer.isFull());
        assertEquals(2, buffer.size());
        assertEquals("A", buffer.peek());

        // reset
        buffer.reset();
        assertNull(buffer.peek());
        assertTrue(buffer.isEmpty());
        assertFalse(buffer.isFull());
        assertEquals(0, buffer.size());
    }

    @Test
    public void testCircularBufferIllegalCapacity() {
        try {
            CircularBuffer<String> buffer = new CircularBuffer<>(String.class, 0);
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals("CircularBuffer capacity should be greater than 0", e.getMessage());
        }
    }
}
