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

import java.util.concurrent.atomic.AtomicLong;

public class ZxidUtils {
    // 40L
    private static final long EPOCH_HIGH_POSITION32 = 32L;
    private static final long EPOCH_HIGH_POSITION40 = 40L;

    private static final long COUNTER_LOW_POSITION32 = 0xffffffffL;
    private static final long CLEAR_EPOCH32 = 0x00000000ffffffffL;
    private static final long CLEAR_COUNTER32 = 0xffffffff00000000L;

    private static final long COUNTER_LOW_POSITION40 = 0xffffffffffL;
    private static final long CLEAR_EPOCH40 = 0x000000ffffffffffL;
    private static final long CLEAR_COUNTER40 = 0xffffff0000000000L;

    private static AtomicLong EPOCH_HIGH_POSITION = new AtomicLong(EPOCH_HIGH_POSITION32);

    private static AtomicLong COUNTER_LOW_POSITION = new AtomicLong(COUNTER_LOW_POSITION32);
    private static AtomicLong CLEAR_EPOCH = new AtomicLong(CLEAR_EPOCH32);
    private static AtomicLong CLEAR_COUNTER = new AtomicLong(CLEAR_COUNTER32);

    static public long getEpochFromZxid(long zxid) {
        return zxid >> EPOCH_HIGH_POSITION.get();
    }

    static public long getCounterFromZxid(long zxid) {
        return zxid & COUNTER_LOW_POSITION.get();
    }

    static public long makeZxid(long epoch, long counter) {
        return (epoch << EPOCH_HIGH_POSITION.get()) | (counter & COUNTER_LOW_POSITION.get());
    }

    static public long clearEpoch(long zxid) {
        return zxid & CLEAR_EPOCH.get();
    }

    static public long clearCounter(long zxid) {
        return zxid & CLEAR_COUNTER.get();
    }

    static public String zxidToString(long zxid) {
        return Long.toHexString(zxid);
    }

    public static long getEpochHighPosition() {
        return EPOCH_HIGH_POSITION.get();
    }

    public static long getCounterLowPosition() {
        return COUNTER_LOW_POSITION.get();
    }

    public static void setEpochHighPosition40() {
        EPOCH_HIGH_POSITION.set(EPOCH_HIGH_POSITION40);

        COUNTER_LOW_POSITION.set(COUNTER_LOW_POSITION40);
        CLEAR_EPOCH.set(CLEAR_EPOCH40);
        CLEAR_COUNTER.set(CLEAR_COUNTER40);
    }

    public static void setEpochHighPosition32() {
        EPOCH_HIGH_POSITION.set(EPOCH_HIGH_POSITION32);

        COUNTER_LOW_POSITION.set(COUNTER_LOW_POSITION32);
        CLEAR_EPOCH.set(CLEAR_EPOCH32);
        CLEAR_COUNTER.set(CLEAR_COUNTER32);
    }

    public static void setEpochHighPosition(long position) {
        if (position == 32) {
            setEpochHighPosition32();
        } else if (position == 40) {
            setEpochHighPosition40();
        } else {
            throw new IllegalArgumentException("Invalid epoch high position:" + position + ", should is 32 or 40.");
        }
    }

}
