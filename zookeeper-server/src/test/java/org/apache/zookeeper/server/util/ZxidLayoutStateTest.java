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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.apache.zookeeper.ZKTestCase;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ZxidLayoutState}: layout selection for new and historic
 * zxids around the switch epoch, and the switch/adopt/clear life cycle.
 *
 * <p>Introduced by Benedict Jin (asdf2014) for ZOOKEEPER-2789.
 */
public class ZxidLayoutStateTest extends ZKTestCase {

    @Test
    public void testNotSwitchedDefaults() {
        ZxidLayoutState state = new ZxidLayoutState();
        assertFalse(state.isSwitched());
        assertEquals(ZxidLayoutState.NOT_SWITCHED, state.getSwitchEpoch());
        assertSame(ZxidLayout.LEGACY, state.current());
        // Without a switch everything is legacy, however large the zxid.
        assertSame(ZxidLayout.LEGACY, state.layoutFor(0L));
        assertSame(ZxidLayout.LEGACY, state.layoutFor(ZxidUtils.makeZxid(5, 1000)));
        assertSame(ZxidLayout.LEGACY, state.layoutFor(Long.MAX_VALUE));
    }

    @Test
    public void testSwitchAt() {
        ZxidLayoutState state = new ZxidLayoutState();
        state.switchAt(6);
        assertTrue(state.isSwitched());
        assertEquals(6, state.getSwitchEpoch());
        assertSame(ZxidLayout.WIDE_COUNTER, state.current());
        // Re-recording the same switch epoch is fine.
        state.switchAt(6);
        assertEquals(6, state.getSwitchEpoch());
    }

    @Test
    public void testLayoutForAroundSwitchPoint() {
        ZxidLayoutState state = new ZxidLayoutState();
        state.switchAt(6);
        long firstWide = ZxidLayout.WIDE_COUNTER.makeZxid(6, 0);
        // Everything below the first wide-counter zxid is legacy data.
        assertSame(ZxidLayout.LEGACY, state.layoutFor(0L));
        assertSame(ZxidLayout.LEGACY, state.layoutFor(ZxidUtils.makeZxid(5, 1000)));
        assertSame(ZxidLayout.LEGACY, state.layoutFor(ZxidUtils.makeZxid(5, 0xffffffffL)));
        assertSame(ZxidLayout.LEGACY, state.layoutFor(firstWide - 1));
        // The switch point itself and everything above is wide-counter.
        assertSame(ZxidLayout.WIDE_COUNTER, state.layoutFor(firstWide));
        assertSame(ZxidLayout.WIDE_COUNTER, state.layoutFor(ZxidLayout.WIDE_COUNTER.makeZxid(6, 1)));
        assertSame(ZxidLayout.WIDE_COUNTER, state.layoutFor(ZxidLayout.WIDE_COUNTER.makeZxid(7, 42)));
    }

    @Test
    public void testLegacyNewEpochZxidNotMistakenForWide() {
        // A legacy new-epoch zxid (counter == 0 in the legacy layout) below
        // the switch point must be decomposed with the legacy layout: with
        // the wide layout its counter part would read as non-zero and the
        // DIFF/TRUNC decisions in LearnerHandler.syncFollower() would break.
        ZxidLayoutState state = new ZxidLayoutState();
        state.switchAt(6);
        long legacyNewEpoch = ZxidUtils.makeZxid(5, 0);
        assertEquals(0, state.layoutFor(legacyNewEpoch).getCounterFromZxid(legacyNewEpoch));
        assertEquals(5, state.layoutFor(legacyNewEpoch).getEpochFromZxid(legacyNewEpoch));
    }

    @Test
    public void testSwitchAtRejectsConflict() {
        ZxidLayoutState state = new ZxidLayoutState();
        state.switchAt(6);
        assertThrows(IllegalStateException.class, () -> state.switchAt(7));
        assertEquals(6, state.getSwitchEpoch());
    }

    @Test
    public void testSwitchAtRejectsOutOfRange() {
        ZxidLayoutState state = new ZxidLayoutState();
        assertThrows(IllegalArgumentException.class, () -> state.switchAt(-1));
        assertThrows(IllegalArgumentException.class, () -> state.switchAt(ZxidLayout.WIDE_COUNTER.getMaxEpoch() + 1));
        assertFalse(state.isSwitched());
    }

    @Test
    public void testForceSwitchAt() {
        ZxidLayoutState state = new ZxidLayoutState();
        state.switchAt(6);
        // An unused switch may be moved when the next leader announces a
        // different switch epoch (the previous announcing leader failed
        // before its epoch committed anything).
        state.forceSwitchAt(7);
        assertEquals(7, state.getSwitchEpoch());
    }

    @Test
    public void testClear() {
        ZxidLayoutState state = new ZxidLayoutState();
        state.switchAt(6);
        state.clear();
        assertFalse(state.isSwitched());
        assertSame(ZxidLayout.LEGACY, state.current());
        assertSame(ZxidLayout.LEGACY, state.layoutFor(Long.MAX_VALUE));
    }

    @Test
    public void testLegacyOnlyIsImmutable() {
        ZxidLayoutState state = ZxidLayoutState.legacyOnly();
        assertFalse(state.isSwitched());
        assertSame(ZxidLayout.LEGACY, state.current());
        assertThrows(UnsupportedOperationException.class, () -> state.switchAt(1));
        assertThrows(UnsupportedOperationException.class, () -> state.forceSwitchAt(1));
        assertThrows(UnsupportedOperationException.class, state::clear);
    }

}
