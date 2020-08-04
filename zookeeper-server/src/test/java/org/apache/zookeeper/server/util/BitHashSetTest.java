/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.List;
import org.apache.zookeeper.ZKTestCase;
import org.junit.jupiter.api.Test;

public class BitHashSetTest extends ZKTestCase {

    @Test
    public void testAddWatchBit() {
        int watcherCacheSize = 1;
        BitHashSet ws = new BitHashSet(watcherCacheSize);
        assertTrue(ws.add(1));
        assertEquals(1, ws.size());
        assertEquals(1, ws.cachedSize());

        List<Integer> actualBits = new ArrayList<Integer>();

        for (int bit : ws) {
            actualBits.add(bit);
        }
        assertArrayEquals(new Integer[]{1}, actualBits.toArray(new Integer[actualBits.size()]));

        // add the same bit again
        assertFalse(ws.add(1));
        assertEquals(1, ws.size());
        assertEquals(1, ws.cachedSize());

        // add another bit, make sure there there is only 1 bit cached
        assertTrue(ws.add(2));
        assertEquals(2, ws.size());
        assertEquals(1, ws.cachedSize());

        assertTrue(ws.contains(1));

        actualBits.clear();
        for (int bit : ws) {
            actualBits.add(bit);
        }
        assertArrayEquals(new Integer[]{1, 2}, actualBits.toArray(new Integer[actualBits.size()]));
    }

    @Test
    public void testRemoveWatchBit() {
        int watcherCacheSize = 1;
        BitHashSet ws = new BitHashSet(watcherCacheSize);
        ws.add(1);
        ws.add(2);

        assertTrue(ws.contains(1));
        assertTrue(ws.contains(2));

        ws.remove(1);
        assertFalse(ws.contains(1));
        assertEquals(1, ws.size());
        assertEquals(0, ws.cachedSize());

        List<Integer> actualBits = new ArrayList<Integer>();

        for (int bit : ws) {
            actualBits.add(bit);
        }
        assertArrayEquals(new Integer[]{2}, actualBits.toArray(new Integer[actualBits.size()]));

        ws.add(3);
        assertEquals(2, ws.size());
        assertEquals(1, ws.cachedSize());

        actualBits.clear();
        for (int bit : ws) {
            actualBits.add(bit);
        }
        assertArrayEquals(new Integer[]{2, 3}, actualBits.toArray(new Integer[actualBits.size()]));

        ws.remove(2);
        ws.remove(3);

        assertEquals(0, ws.size());
        assertEquals(0, ws.cachedSize());
    }

}
