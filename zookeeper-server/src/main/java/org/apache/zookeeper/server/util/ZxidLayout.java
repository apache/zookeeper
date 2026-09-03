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
 * A bit layout of a zxid: how the 64 bits are split between the epoch part
 * (high bits) and the counter part (low bits).
 *
 * <p>Two layouts exist:
 * <ul>
 * <li>{@link #LEGACY}: 32-bit epoch / 32-bit counter, the layout ZooKeeper
 * has always used and the default.</li>
 * <li>{@link #WIDE_COUNTER}: 24-bit epoch / 40-bit counter. With a 32-bit
 * counter, a cluster sustaining 1k writes/s exhausts the counter in about
 * 49.7 days and every exhaustion forces a leader re-election (see
 * ZOOKEEPER-1277 and ZOOKEEPER-2789). With a 40-bit counter the same load
 * lasts about 34.9 years, while 24 bits of epoch still allow one leader
 * election per hour for roughly 1915 years.</li>
 * </ul>
 *
 * <p>Which layout applies to a given zxid is decided by
 * {@link ZxidLayoutState}: an ensemble starts with {@link #LEGACY} and may
 * switch to {@link #WIDE_COUNTER} from a specific epoch on. Instances are
 * immutable; the static {@link ZxidUtils} helpers are equivalent to
 * {@link #LEGACY} and remain for the fixed-layout uses (such as the epoch
 * carrier zxids of the FOLLOWERINFO / LEADERINFO handshake packets, which
 * stay in the legacy layout on the wire regardless of the data layout).
 *
 * <p>Introduced by Benedict Jin (asdf2014) for ZOOKEEPER-2789.
 */
public final class ZxidLayout {

    public static final ZxidLayout LEGACY = new ZxidLayout("legacy", 32);
    public static final ZxidLayout WIDE_COUNTER = new ZxidLayout("wide-counter", 40);

    private final String name;
    private final int counterBits;
    private final long counterMask;
    private final long maxEpoch;

    private ZxidLayout(String name, int counterBits) {
        this.name = name;
        this.counterBits = counterBits;
        this.counterMask = (1L << counterBits) - 1;
        // The largest epoch that keeps a zxid non-negative, so that numeric
        // (signed) zxid comparisons all over the code base stay valid.
        this.maxEpoch = (1L << (63 - counterBits)) - 1;
    }

    public long getEpochFromZxid(long zxid) {
        return zxid >> counterBits;
    }

    public long getCounterFromZxid(long zxid) {
        return zxid & counterMask;
    }

    public long makeZxid(long epoch, long counter) {
        return (epoch << counterBits) | (counter & counterMask);
    }

    /** Returns the given zxid with its counter part zeroed out, keeping only the epoch part. */
    public long clearCounter(long zxid) {
        return zxid & ~counterMask;
    }

    /** Returns the maximum value the counter part of a zxid can hold before it rolls over. */
    public long getMaxCounter() {
        return counterMask;
    }

    /** Returns the maximum epoch this layout can hold without producing a negative zxid. */
    public long getMaxEpoch() {
        return maxEpoch;
    }

    public int getCounterBits() {
        return counterBits;
    }

    @Override
    public String toString() {
        return name;
    }

}
