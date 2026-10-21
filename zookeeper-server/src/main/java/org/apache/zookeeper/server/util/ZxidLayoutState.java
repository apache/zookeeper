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

/**
 * Tracks which {@link ZxidLayout} an ensemble member uses, and from which
 * epoch on.
 *
 * <p>An ensemble starts in the {@link ZxidLayout#LEGACY} layout. When the
 * wide-counter layout is enabled (see {@code zookeeper.wideCounterZxidEnabled})
 * and a new leader is elected, the leader switches the ensemble to
 * {@link ZxidLayout#WIDE_COUNTER} starting from its own new epoch (the
 * "switch epoch"), and announces that epoch to the learners during the
 * LEADERINFO handshake. Every member persists the switch epoch next to
 * {@code currentEpoch}, so that after a restart it can still parse its
 * on-disk data correctly.
 *
 * <p>Because the switch always happens together with an epoch bump, the
 * numeric order of zxids is preserved across the switch: the first
 * wide-counter zxid {@code (switchEpoch << 40)} is numerically larger than
 * every legacy zxid of the previous epochs. This has two consequences:
 * <ul>
 * <li>Pure numeric zxid comparisons (leader election, DIFF/TRUNC ranges,
 * min/max committed log) remain valid without any layout knowledge.</li>
 * <li>For the places that need to <em>decompose</em> a zxid into epoch and
 * counter, {@link #layoutFor(long)} picks the correct layout for any zxid,
 * old or new, by comparing it against the first wide-counter zxid.</li>
 * </ul>
 *
 * <p>The switch is one-way: once a member has recorded a switch epoch it
 * must never go back to the legacy layout (an old-layout leader elected
 * afterwards would generate zxids that sort below already-committed ones).
 * {@link #adopt(long)} therefore rejects any attempt to move or clear the
 * switch epoch.
 *
 * <p>Introduced by Benedict Jin (asdf2014) for ZOOKEEPER-2789.
 */
public class ZxidLayoutState {

    /** Sentinel: the ensemble has not switched, everything is in the legacy layout. */
    public static final long NOT_SWITCHED = Long.MAX_VALUE;

    private static final ZxidLayoutState LEGACY_ONLY = new ZxidLayoutState() {
        @Override
        public void switchAt(long epoch) {
            throw new UnsupportedOperationException("this ZxidLayoutState is immutable");
        }
        @Override
        public void forceSwitchAt(long epoch) {
            throw new UnsupportedOperationException("this ZxidLayoutState is immutable");
        }
        @Override
        public void clear() {
            throw new UnsupportedOperationException("this ZxidLayoutState is immutable");
        }
    };

    private volatile long switchEpoch = NOT_SWITCHED;

    /**
     * Returns a shared immutable state that never switches. Used as the
     * default wherever no quorum context exists (e.g. standalone servers),
     * keeping the legacy behavior.
     */
    public static ZxidLayoutState legacyOnly() {
        return LEGACY_ONLY;
    }

    public boolean isSwitched() {
        return switchEpoch != NOT_SWITCHED;
    }

    public long getSwitchEpoch() {
        return switchEpoch;
    }

    /** The layout for newly generated zxids. */
    public ZxidLayout current() {
        return isSwitched() ? ZxidLayout.WIDE_COUNTER : ZxidLayout.LEGACY;
    }

    /**
     * The layout the given zxid was generated with. Correct for any zxid,
     * old or new: zxids from epochs before the switch epoch are numerically
     * smaller than the first wide-counter zxid.
     */
    public ZxidLayout layoutFor(long zxid) {
        long epoch = switchEpoch;
        if (epoch == NOT_SWITCHED) {
            return ZxidLayout.LEGACY;
        }
        long firstWideZxid = ZxidLayout.WIDE_COUNTER.makeZxid(epoch, 0);
        return Long.compareUnsigned(zxid, firstWideZxid) >= 0 ? ZxidLayout.WIDE_COUNTER : ZxidLayout.LEGACY;
    }

    /**
     * Switches to the wide-counter layout starting from the given epoch.
     * Called by the leader when it establishes a new epoch with the feature
     * enabled, and when loading a previously persisted switch epoch.
     *
     * @throws IllegalStateException if already switched at a different epoch
     * @throws IllegalArgumentException if the epoch cannot be represented
     */
    public void switchAt(long epoch) {
        checkRange(epoch);
        synchronized (this) {
            if (isSwitched() && switchEpoch != epoch) {
                throw new IllegalStateException(
                    "already switched to " + ZxidLayout.WIDE_COUNTER + " at epoch " + switchEpoch + ", cannot re-switch at " + epoch);
            }
            switchEpoch = epoch;
        }
    }

    /**
     * Moves the switch epoch unconditionally. Only safe while no zxid has
     * been generated in the wide-counter layout yet — e.g. when the leader
     * that announced the previous switch epoch failed before its epoch
     * committed anything, and the next leader announces a different one.
     * Callers must verify that (see QuorumPeer#adoptZxidLayoutSwitch).
     */
    public void forceSwitchAt(long epoch) {
        checkRange(epoch);
        synchronized (this) {
            switchEpoch = epoch;
        }
    }

    /**
     * Forgets a recorded switch, returning to the legacy-only state. Like
     * {@link #forceSwitchAt(long)} this is only safe while no wide-counter
     * zxid exists yet.
     */
    public void clear() {
        synchronized (this) {
            switchEpoch = NOT_SWITCHED;
        }
    }

    private static void checkRange(long epoch) {
        if (epoch < 0 || epoch > ZxidLayout.WIDE_COUNTER.getMaxEpoch()) {
            throw new IllegalArgumentException("switch epoch " + epoch + " out of range for " + ZxidLayout.WIDE_COUNTER);
        }
    }

}
