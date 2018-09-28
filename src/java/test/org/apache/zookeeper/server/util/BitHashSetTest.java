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

import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;

import org.apache.zookeeper.ZKTestCase;
import org.junit.Test;
import org.junit.Assert;

public class BitHashSetTest extends ZKTestCase {

    @Test
    public void testAddWatchBit() {
        int watcherCacheSize = 1;
        BitHashSet ws = new BitHashSet(watcherCacheSize);
        Assert.assertTrue(ws.add(1));
        Assert.assertEquals(1, ws.size());
        Assert.assertEquals(1, ws.cachedSize());

        List<Integer> actualBits = new ArrayList<Integer>();

        for (int bit: ws) {
            actualBits.add(bit);
        }
        Assert.assertArrayEquals(
            new Integer[] {1},
            actualBits.toArray(new Integer[actualBits.size()]));

        // add the same bit again
        Assert.assertFalse(ws.add(1));
        Assert.assertEquals(1, ws.size());
        Assert.assertEquals(1, ws.cachedSize());

        // add another bit, make sure there there is only 1 bit cached
        Assert.assertTrue(ws.add(2));
        Assert.assertEquals(2, ws.size());
        Assert.assertEquals(1, ws.cachedSize());

        Assert.assertTrue(ws.contains(1));

        actualBits.clear();
        for (int bit: ws) {
            actualBits.add(bit);
        }
        Assert.assertArrayEquals(
            new Integer[] {1, 2},
            actualBits.toArray(new Integer[actualBits.size()]));
    }

    @Test
    public void testRemoveWatchBit() {
        int watcherCacheSize = 1;
        BitHashSet ws = new BitHashSet(watcherCacheSize);
        ws.add(1);
        ws.add(2);

        Assert.assertTrue(ws.contains(1));
        Assert.assertTrue(ws.contains(2));

        ws.remove(1);
        Assert.assertFalse(ws.contains(1));
        Assert.assertEquals(1, ws.size());
        Assert.assertEquals(0, ws.cachedSize());

        List<Integer> actualBits = new ArrayList<Integer>();

        for (int bit: ws) {
            actualBits.add(bit);
        }
        Assert.assertArrayEquals(
            new Integer[] {2},
            actualBits.toArray(new Integer[actualBits.size()]));

        ws.add(3);
        Assert.assertEquals(2, ws.size());
        Assert.assertEquals(1, ws.cachedSize());

        actualBits.clear();
        for (int bit: ws) {
            actualBits.add(bit);
        }
        Assert.assertArrayEquals(
            new Integer[] {2, 3},
            actualBits.toArray(new Integer[actualBits.size()]));

        ws.remove(2);
        ws.remove(3);

        Assert.assertEquals(0, ws.size());
        Assert.assertEquals(0, ws.cachedSize());
    }
}
