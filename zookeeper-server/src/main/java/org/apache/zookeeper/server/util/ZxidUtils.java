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

public class ZxidUtils {

    private static final long EPOCH_HIGH_POSITION = 40L;
    private static final long COUNTER_LOW_POSITION = 0xffffffffffL;
    private static final long CLEAR_EPOCH = 0x000000ffffffffffL;
    private static final long CLEAR_COUNTER = 0xffffff0000000000L;

    static public long getEpochFromZxid(long zxid) {
        return zxid >> EPOCH_HIGH_POSITION;
    }

    static public long getCounterFromZxid(long zxid) {
        return zxid & COUNTER_LOW_POSITION;
    }

    static public long makeZxid(long epoch, long counter) {
        return (epoch << EPOCH_HIGH_POSITION) | (counter & COUNTER_LOW_POSITION);
    }

    static public long clearEpoch(long zxid) {
        return zxid & CLEAR_EPOCH;
    }

    static public long clearCounter(long zxid) {
        return zxid & CLEAR_COUNTER;
    }

    static public String zxidToString(long zxid) {
        return Long.toHexString(zxid);
    }

    public static long getEpochHighPosition() {
        return EPOCH_HIGH_POSITION;
    }

    public static long getCounterLowPosition() {
        return COUNTER_LOW_POSITION;
    }
}
