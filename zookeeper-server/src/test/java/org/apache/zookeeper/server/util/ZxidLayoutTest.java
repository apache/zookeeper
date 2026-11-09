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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.apache.zookeeper.ZKTestCase;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ZxidLayout}: the legacy 32/32 layout and the
 * wide-counter 24/40 layout introduced by ZOOKEEPER-2789.
 *
 * <p>Introduced by Benedict Jin (asdf2014) for ZOOKEEPER-2789.
 */
public class ZxidLayoutTest extends ZKTestCase {

    @Test
    public void testLegacyLayoutConstants() {
        assertEquals(32, ZxidLayout.LEGACY.getCounterBits());
        assertEquals(0xffffffffL, ZxidLayout.LEGACY.getMaxCounter());
        assertEquals((1L << 31) - 1, ZxidLayout.LEGACY.getMaxEpoch());
    }

    @Test
    public void testWideCounterLayoutConstants() {
        assertEquals(40, ZxidLayout.WIDE_COUNTER.getCounterBits());
        assertEquals(0xffffffffffL, ZxidLayout.WIDE_COUNTER.getMaxCounter());
        assertEquals((1L << 23) - 1, ZxidLayout.WIDE_COUNTER.getMaxEpoch());
    }

    @Test
    public void testLegacyMatchesZxidUtils() {
        long[] zxids = {0L, 1L, ZxidUtils.makeZxid(5, 1000), ZxidUtils.makeZxid(42, 0xffffffffL), -1L};
        for (long zxid : zxids) {
            assertEquals(ZxidUtils.getEpochFromZxid(zxid), ZxidLayout.LEGACY.getEpochFromZxid(zxid));
            assertEquals(ZxidUtils.getCounterFromZxid(zxid), ZxidLayout.LEGACY.getCounterFromZxid(zxid));
        }
        assertEquals(ZxidUtils.makeZxid(5, 1000), ZxidLayout.LEGACY.makeZxid(5, 1000));
    }

    @Test
    public void testEpochCounterRoundTrip() {
        for (ZxidLayout layout : new ZxidLayout[]{ZxidLayout.LEGACY, ZxidLayout.WIDE_COUNTER}) {
            long[] epochs = {0L, 1L, 5L, 0xabcdL, layout.getMaxEpoch()};
            long[] counters = {0L, 1L, 1000L, layout.getMaxCounter()};
            for (long epoch : epochs) {
                for (long counter : counters) {
                    long zxid = layout.makeZxid(epoch, counter);
                    assertTrue(zxid >= 0, "zxid must stay non-negative for epoch <= getMaxEpoch()");
                    assertEquals(epoch, layout.getEpochFromZxid(zxid), layout + " epoch of 0x" + Long.toHexString(zxid));
                    assertEquals(counter, layout.getCounterFromZxid(zxid), layout + " counter of 0x" + Long.toHexString(zxid));
                }
            }
        }
    }

    @Test
    public void testMakeZxidMasksCounterOverflow() {
        for (ZxidLayout layout : new ZxidLayout[]{ZxidLayout.LEGACY, ZxidLayout.WIDE_COUNTER}) {
            // A counter wider than its bits must not corrupt the epoch part.
            assertEquals(layout.makeZxid(1, 0), layout.makeZxid(1, layout.getMaxCounter() + 1));
            assertEquals(layout.makeZxid(1, 1), layout.makeZxid(1, layout.getMaxCounter() + 2));
        }
    }

    @Test
    public void testClearCounter() {
        for (ZxidLayout layout : new ZxidLayout[]{ZxidLayout.LEGACY, ZxidLayout.WIDE_COUNTER}) {
            assertEquals(0L, layout.clearCounter(0L));
            assertEquals(layout.makeZxid(5, 0), layout.clearCounter(layout.makeZxid(5, 12345)));
            // Clearing the counter and OR-ing a new one in composes a valid
            // zxid, which is how zookeeper.testingonly.initialZxid is applied.
            long zxid = layout.makeZxid(3, 777);
            assertEquals(layout.makeZxid(3, 42), layout.clearCounter(zxid) | 42L);
        }
        assertEquals(0xffffffff00000000L, ZxidLayout.LEGACY.clearCounter(-1L));
        assertEquals(0xffffff0000000000L, ZxidLayout.WIDE_COUNTER.clearCounter(-1L));
    }

    @Test
    public void testCounterRolloverBoundary() {
        // Leader.propose() forces a re-election when the counter is
        // exhausted: the check compares the counter part to getMaxCounter().
        for (ZxidLayout layout : new ZxidLayout[]{ZxidLayout.LEGACY, ZxidLayout.WIDE_COUNTER}) {
            long nearRollover = layout.makeZxid(1, layout.getMaxCounter() - 1);
            long atRollover = layout.makeZxid(1, layout.getMaxCounter());
            assertNotEquals(layout.getMaxCounter(), layout.getCounterFromZxid(nearRollover));
            assertEquals(layout.getMaxCounter(), layout.getCounterFromZxid(atRollover));
            // The exhausted counter does not leak into the epoch part.
            assertEquals(1L, layout.getEpochFromZxid(atRollover));
        }
    }

    @Test
    public void testOrderPreservedAcrossLayoutSwitch() {
        // The switch always comes with an epoch bump, so the first
        // wide-counter zxid is numerically larger than every legacy zxid of
        // the previous epochs — the total order of zxids survives the switch.
        long lastLegacy = ZxidLayout.LEGACY.makeZxid(5, ZxidLayout.LEGACY.getMaxCounter());
        long firstWide = ZxidLayout.WIDE_COUNTER.makeZxid(6, 0);
        assertTrue(firstWide > lastLegacy);
    }

    @Test
    public void testToString() {
        assertEquals("legacy", ZxidLayout.LEGACY.toString());
        assertEquals("wide-counter", ZxidLayout.WIDE_COUNTER.toString());
    }

}
