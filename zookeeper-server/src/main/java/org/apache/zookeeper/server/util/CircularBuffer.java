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

import java.lang.reflect.Array;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread safe FIFO CircularBuffer implementation.
 * When the buffer is full write operation overwrites the oldest element.
 *
 * Fun thing @todo, make this lock free as this is called on every quorum message
 */
public class CircularBuffer<T> {

    private final T[] buffer;
    private final int capacity;
    private int oldest;
    private AtomicInteger numberOfElements = new AtomicInteger();

    @SuppressWarnings("unchecked")
    public CircularBuffer(Class<T> clazz, int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("CircularBuffer capacity should be greater than 0");
        }
        this.buffer = (T[]) Array.newInstance(clazz, capacity);
        this.capacity = capacity;
    }

    /**
     * Puts elements in the next available index in the array.
     * If the array is full the oldest element is replaced with
     * the new value.
     * @param element
     */
    public synchronized void write(T element) {
        int newSize = numberOfElements.incrementAndGet();
        if (newSize > capacity) {
            buffer[oldest] = element;
            oldest = ++oldest % capacity;
            numberOfElements.decrementAndGet();
        } else {
            int index = (oldest + numberOfElements.get() - 1) % capacity;
            buffer[index] = element;
        }
    }

    /**
     * Reads from the buffer in a FIFO manner.
     * Returns the oldest element in the buffer if the buffer is not empty
     * Returns null if the buffer is empty
     * @return the oldest element in the buffer
     */
    public synchronized T take() {
        int newSize = numberOfElements.decrementAndGet();
        if (newSize < 0) {
            numberOfElements.incrementAndGet();
            return null;
        }
        T polled = buffer[oldest];
        oldest = ++oldest % capacity;
        return polled;
    }

    public synchronized T peek() {
        if (numberOfElements.get() <= 0) {
            return null;
        }
        return buffer[oldest];
    }

    public int size() {
        return numberOfElements.get();
    }

    public boolean isEmpty() {
        return numberOfElements.get() <= 0;
    }

    public boolean isFull() {
        return numberOfElements.get() >= capacity;
    }

    public synchronized void  reset() {
        numberOfElements.set(0);
    }
}
