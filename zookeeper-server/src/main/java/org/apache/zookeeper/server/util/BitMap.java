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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import java.util.HashMap;
import java.util.BitSet;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * This is a helper class to maintain the bit to specific value and the
 * reversed value to bit mapping.
 */
public class BitMap<T> {

    private final Map<T, Integer> value2Bit = new HashMap<T, Integer>();
    private final Map<Integer, T> bit2Value = new HashMap<Integer, T>();

    private final BitSet freedBitSet = new BitSet();
    private Integer nextBit = Integer.valueOf(0);

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    @SuppressFBWarnings(value = "DLS_DEAD_LOCAL_STORE",
            justification = "SpotBugs false positive")
    public Integer add(T value) {
        /*
         * Optimized for code which will add the same value again and again,
         * more specifically this is used to add new bit for watcher, and
         * the same watcher may watching thousands or even millions of nodes,
         * which will call add the same value of this function, check exist
         * using read lock will optimize the performance here.
         */
        Integer bit = getBit(value);
        if (bit != null) {
            return bit;
        }

        rwLock.writeLock().lock();
        try {
            bit = value2Bit.get(value);
            if (bit != null) {
                return bit;
            }
            bit = freedBitSet.nextSetBit(0);
            if (bit > -1) {
                freedBitSet.clear(bit);
            } else {
                bit = nextBit++;
            }

            value2Bit.put(value, bit);
            bit2Value.put(bit, value);
            return bit;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public T get(int bit) {
        rwLock.readLock().lock();
        try {
            return bit2Value.get(bit);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public Integer getBit(T value) {
        rwLock.readLock().lock();
        try {
            return value2Bit.get(value);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public int remove(T value) {
        /*
         * remove only called once when the session is closed, so use write 
         * lock directly without checking read lock.
         */
        rwLock.writeLock().lock();
        try {
            Integer bit = value2Bit.get(value);
            if (bit == null) {
                return -1;
            }
            value2Bit.remove(value);
            bit2Value.remove(bit);
            freedBitSet.set(bit);
            return bit;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public T remove(int bit) {
        rwLock.writeLock().lock();
        try {
            T value = bit2Value.get(bit);
            if (value == null) {
                return null;
            }
            value2Bit.remove(value);
            bit2Value.remove(bit);
            freedBitSet.set(bit);
            return value;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public int size() {
        rwLock.readLock().lock();
        try {
            return value2Bit.size();
        } finally {
            rwLock.readLock().unlock();
        }
    }
}
