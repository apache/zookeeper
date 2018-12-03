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

import org.apache.zookeeper.ZKTestCase;
import org.junit.Test;
import org.junit.Assert;

public class BitMapTest extends ZKTestCase {

    @Test
    public void testAddAndRemove() {
        BitMap<String> bitMap = new BitMap<String>();
        String v1 = new String("v1");
        Integer bit = bitMap.add(v1);

        Assert.assertEquals(1, bitMap.size());
        Assert.assertTrue(bit >= 0);
        Assert.assertEquals(v1, bitMap.get(bit));
        Assert.assertEquals(bit, bitMap.getBit(v1));

        // add the same value again
        Integer newBit = bitMap.add(v1);
        Assert.assertEquals(bit, newBit);
        Assert.assertEquals(1, bitMap.size());

        String v2 = new String("v2");
        Integer v2Bit = bitMap.add(v2);
        Assert.assertEquals(2, bitMap.size());
        Assert.assertNotEquals(v2Bit, bit);

        // remove by value
        bitMap.remove(v1);
        Assert.assertEquals(1, bitMap.size());
        Assert.assertNull(bitMap.get(bit));
        Assert.assertNull(bitMap.getBit(v1));

        // remove by bit
        bitMap.remove(v2Bit);
        Assert.assertEquals(0, bitMap.size());
        Assert.assertNull(bitMap.get(v2Bit));
        Assert.assertNull(bitMap.getBit(v2));
    }

    @Test
    public void testBitReuse() {
        BitMap<String> bitMap = new BitMap<String>();
        int v1Bit = bitMap.add("v1");
        int v2Bit = bitMap.add("v2");
        int v3Bit = bitMap.add("v3");
        bitMap.remove(v2Bit);

        int v4Bit = bitMap.add("v4");

        Assert.assertEquals(v4Bit, v2Bit);
    }
}
