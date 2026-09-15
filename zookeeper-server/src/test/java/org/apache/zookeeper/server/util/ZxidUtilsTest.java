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

package org.apache.zookeeper.server.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.apache.zookeeper.ZKTestCase;
import org.junit.jupiter.api.Test;

/**
 * Tests for the static {@link ZxidUtils} helpers, which are fixed to the
 * legacy 32-bit epoch / 32-bit counter layout (see {@link ZxidLayout}).
 *
 * <p>Introduced by Benedict Jin (asdf2014) for ZOOKEEPER-2789.
 */
public class ZxidUtilsTest extends ZKTestCase {

    @Test
    public void testMakeZxid() {
        assertEquals(0L, ZxidUtils.makeZxid(0, 0));
        assertEquals(1L, ZxidUtils.makeZxid(0, 1));
        assertEquals(0x100000000L, ZxidUtils.makeZxid(1, 0));
        assertEquals(0x100000001L, ZxidUtils.makeZxid(1, 1));
        assertEquals(0x5000003e8L, ZxidUtils.makeZxid(5, 1000));
        // A counter wider than its 32 bits must not corrupt the epoch part.
        assertEquals(ZxidUtils.makeZxid(1, 0), ZxidUtils.makeZxid(1, 0x100000000L));
    }

    @Test
    public void testGetEpochFromZxid() {
        assertEquals(0L, ZxidUtils.getEpochFromZxid(0L));
        assertEquals(0L, ZxidUtils.getEpochFromZxid(0xffffffffL));
        assertEquals(1L, ZxidUtils.getEpochFromZxid(ZxidUtils.makeZxid(1, 0)));
        assertEquals(5L, ZxidUtils.getEpochFromZxid(ZxidUtils.makeZxid(5, 1000)));
    }

    @Test
    public void testGetCounterFromZxid() {
        assertEquals(0L, ZxidUtils.getCounterFromZxid(0L));
        assertEquals(0L, ZxidUtils.getCounterFromZxid(ZxidUtils.makeZxid(7, 0)));
        assertEquals(1000L, ZxidUtils.getCounterFromZxid(ZxidUtils.makeZxid(5, 1000)));
        assertEquals(0xffffffffL, ZxidUtils.getCounterFromZxid(-1L));
    }

    @Test
    public void testEpochCounterRoundTrip() {
        long[] epochs = {0L, 1L, 5L, 0xabcdL};
        long[] counters = {0L, 1L, 1000L, 0xffffffffL};
        for (long epoch : epochs) {
            for (long counter : counters) {
                long zxid = ZxidUtils.makeZxid(epoch, counter);
                assertEquals(epoch, ZxidUtils.getEpochFromZxid(zxid), "epoch of zxid " + Long.toHexString(zxid));
                assertEquals(counter, ZxidUtils.getCounterFromZxid(zxid), "counter of zxid " + Long.toHexString(zxid));
            }
        }
    }

    @Test
    public void testZxidToString() {
        assertEquals("0", ZxidUtils.zxidToString(0L));
        assertEquals("ff", ZxidUtils.zxidToString(255L));
        assertEquals("100000000", ZxidUtils.zxidToString(ZxidUtils.makeZxid(1, 0)));
    }

}
