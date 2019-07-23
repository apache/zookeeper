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

import org.junit.Assert;
import org.junit.Test;

public class CircularBufferTest {

    @Test
    public void testCircularBuffer() {
        final int capacity = 3;
        CircularBuffer<String> buffer = new CircularBuffer<>(String.class, capacity);

        Assert.assertTrue(buffer.isEmpty());
        Assert.assertFalse(buffer.isFull());

        // write to the buffer
        buffer.write("A");
        Assert.assertFalse(buffer.isEmpty());
        Assert.assertFalse(buffer.isFull());

        buffer.write("B");
        Assert.assertFalse(buffer.isEmpty());
        Assert.assertFalse(buffer.isFull());

        buffer.write("C");
        Assert.assertFalse(buffer.isEmpty());
        Assert.assertTrue(buffer.isFull());

        // Buffer is full.
        // Read from buffer
        Assert.assertEquals("A", buffer.take());
        Assert.assertFalse(buffer.isEmpty());
        Assert.assertFalse(buffer.isFull());

        Assert.assertEquals("B", buffer.take());
        Assert.assertFalse(buffer.isEmpty());
        Assert.assertFalse(buffer.isFull());

        Assert.assertEquals("C", buffer.take());
        Assert.assertTrue(buffer.isEmpty());
        Assert.assertFalse(buffer.isFull());

        // write to the buffer
        buffer.write("1");
        Assert.assertFalse(buffer.isEmpty());
        Assert.assertFalse(buffer.isFull());

        buffer.write("2");
        Assert.assertFalse(buffer.isEmpty());
        Assert.assertFalse(buffer.isFull());

        buffer.write("3");
        Assert.assertFalse(buffer.isEmpty());
        Assert.assertTrue(buffer.isFull());

        buffer.write("4"); // 4 overwrites 1
        Assert.assertFalse(buffer.isEmpty());
        Assert.assertTrue(buffer.isFull());

        // Buffer if full
        // Read from buffer
        Assert.assertEquals("2", buffer.take());
        Assert.assertFalse(buffer.isEmpty());
        Assert.assertFalse(buffer.isFull());

        Assert.assertEquals("3", buffer.take());
        Assert.assertFalse(buffer.isEmpty());
        Assert.assertFalse(buffer.isFull());

        Assert.assertEquals("4", buffer.take());
        Assert.assertTrue(buffer.isEmpty());
        Assert.assertFalse(buffer.isFull());

        // write to the buffer
        buffer.write("a");
        Assert.assertFalse(buffer.isEmpty());
        Assert.assertFalse(buffer.isFull());

        buffer.write("b");
        Assert.assertFalse(buffer.isEmpty());
        Assert.assertFalse(buffer.isFull());

        buffer.write("c");
        Assert.assertFalse(buffer.isEmpty());
        Assert.assertTrue(buffer.isFull());

        buffer.write("d"); // d overwrites a
        Assert.assertFalse(buffer.isEmpty());
        Assert.assertTrue(buffer.isFull());

        buffer.write("e"); // e overwrites b
        Assert.assertFalse(buffer.isEmpty());
        Assert.assertTrue(buffer.isFull());

        buffer.write("f"); // f overwrites c
        Assert.assertFalse(buffer.isEmpty());
        Assert.assertTrue(buffer.isFull());

        buffer.write("g"); // g overwrites d
        Assert.assertFalse(buffer.isEmpty());
        Assert.assertTrue(buffer.isFull());

        // Buffer is full.
        // Read from buffer
        Assert.assertEquals("e", buffer.take());
        Assert.assertFalse(buffer.isEmpty());
        Assert.assertFalse(buffer.isFull());

        Assert.assertEquals("f", buffer.take());
        Assert.assertFalse(buffer.isEmpty());
        Assert.assertFalse(buffer.isFull());

        Assert.assertEquals("g", buffer.take());
        Assert.assertTrue(buffer.isEmpty());
        Assert.assertFalse(buffer.isFull());
    }

    @Test
    public void testCircularBufferWithCapacity1() {
        final int capacity = 1;
        CircularBuffer<String> buffer = new CircularBuffer<>(String.class, capacity);

        Assert.assertTrue(buffer.isEmpty());
        Assert.assertFalse(buffer.isFull());

        // write to the buffer
        buffer.write("A");
        Assert.assertFalse(buffer.isEmpty());
        Assert.assertTrue(buffer.isFull());

        buffer.write("B"); // B overwrite A
        Assert.assertFalse(buffer.isEmpty());
        Assert.assertTrue(buffer.isFull());

        // Buffer is full.
        // Read from buffer
        Assert.assertEquals("B", buffer.take());
        Assert.assertTrue(buffer.isEmpty());
        Assert.assertFalse(buffer.isFull());
    }

    @Test
    public void testCircularBufferReset() {
        final int capacity = 3;
        CircularBuffer<String> buffer = new CircularBuffer<>(String.class, capacity);

        Assert.assertTrue(buffer.isEmpty());
        Assert.assertFalse(buffer.isFull());

        // write to the buffer
        buffer.write("A");
        Assert.assertFalse(buffer.isEmpty());
        Assert.assertFalse(buffer.isFull());
        Assert.assertEquals(1, buffer.size());
        Assert.assertEquals("A", buffer.peek());

        buffer.write("B");
        Assert.assertFalse(buffer.isEmpty());
        Assert.assertFalse(buffer.isFull());
        Assert.assertEquals(2, buffer.size());
        Assert.assertEquals("A", buffer.peek());

        // reset
        buffer.reset();
        Assert.assertNull(buffer.peek());
        Assert.assertTrue(buffer.isEmpty());
        Assert.assertFalse(buffer.isFull());
        Assert.assertEquals(0, buffer.size());
    }

    @Test
    public void testCircularBufferIllegalCapacity() {
        try {
            CircularBuffer<String> buffer = new CircularBuffer<>(String.class, 0);
            Assert.fail();
        } catch (IllegalArgumentException e) {
            Assert.assertEquals("CircularBuffer capacity should be greater than 0", e.getMessage());
        }
    }
}
