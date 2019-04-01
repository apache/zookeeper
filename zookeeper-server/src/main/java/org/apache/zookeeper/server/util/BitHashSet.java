/**
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

package org.apache.zookeeper.server.util;

import java.util.BitSet;
import java.util.Set;
import java.util.HashSet;
import java.util.Iterator;
import java.lang.Iterable;

/**
 * Using BitSet to store all the elements, and use HashSet to cache limited
 * number of elements to find a balance between memory and time complexity.
 *
 * Without HashSet, we need to use O(N) time to get the elements, N is
 * the bit numbers in elementBits. But we need to keep the size small to make
 * sure it doesn't cost too much in memory, there is a trade off between
 * memory and time complexity.
 *
 * Previously, was deciding to dynamically switch between SparseBitSet and
 * HashSet based on the memory consumption, but it will take time to copy
 * data over and may have some herd effect of keep copying data from one
 * data structure to anther. The current solution can do a very good job
 * given most of the paths have limited number of elements.
 */
public class BitHashSet implements Iterable<Integer> {

    /**
     * Change to SparseBitSet if we we want to optimize more, the number of
     * elements on a single server is usually limited, so BitSet should be
     * fine.
     */
    private final BitSet elementBits = new BitSet();

    /**
     * HashSet is used to optimize the iterating, if there is a single 
     * element in this BitHashSet, but the bit is very large, without 
     * HashSet we need to go through all the words before return that 
     * element, which is not efficient.
     */
    private final Set<Integer> cache = new HashSet<Integer>();

    private final int cacheSize;

    // To record how many elements in this set.
    private int elementCount = 0;

    public BitHashSet() {
        this(Integer.getInteger("zookeeper.bitHashCacheSize", 10));
    }

    public BitHashSet(int cacheSize) {
        this.cacheSize = cacheSize;
    }

    public synchronized boolean add(Integer elementBit) {
        if (elementBit == null || elementBits.get(elementBit)) {
            return false;
        }
        if (cache.size() < cacheSize) {
            cache.add(elementBit);
        }
        elementBits.set(elementBit);
        elementCount++;
        return true;
    }

    /**
     * Remove the watches, and return the number of watches being removed.
     */
    public synchronized int remove(Set<Integer> bitSet, BitSet bits) {
        cache.removeAll(bitSet);
        elementBits.andNot(bits);
        int elementCountBefore = elementCount;
        elementCount = elementBits.cardinality();
        return elementCountBefore - elementCount;
    }

    public synchronized boolean remove(Integer elementBit) {
        if (elementBit == null || !elementBits.get(elementBit)) {
            return false;
        }

        cache.remove(elementBit);
        elementBits.clear(elementBit);
        elementCount--;
        return true;
    }

    public synchronized boolean contains(Integer elementBit) {
        if (elementBit == null) {
            return false;
        }
        return elementBits.get(elementBit);
    }

    public synchronized int size() {
        return elementCount;
    }

    /**
     * This function is not thread-safe, need to synchronized when
     * iterate through this set.
     */
    @Override
    public Iterator<Integer> iterator() {
        // sample current size at the beginning
        int currentSize = size();

        if (cache.size() == currentSize) {
            return cache.iterator();
        }

        return new Iterator<Integer>() {
            int returnedCount = 0;
            int bitIndex = 0;

            @Override
            public boolean hasNext() {
                return returnedCount < currentSize;
            }

            @Override
            public Integer next() {
                int bit = elementBits.nextSetBit(bitIndex);
                bitIndex = bit + 1;
                returnedCount++;
                return bit;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    // visible for test
    public synchronized int cachedSize() {
        return cache.size();
    }

    public synchronized boolean isEmpty() {
        return elementCount == 0;
    }
}
