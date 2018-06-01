/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server;

/**
 * See https://issues.apache.org/jira/browse/ZOOKEEPER-2901
 *
 * version 3.5.3 introduced bugs associated with how TTL nodes were implemented. version 3.5.4
 * fixes the problems but makes TTL nodes created in 3.5.3 invalid. OldEphemeralType is a copy
 * of the old - bad - implementation that is provided as a workaround. {@link EphemeralType#TTL_3_5_3_EMULATION_PROPERTY}
 * can be used to emulate support of the badly specified TTL nodes.
 */
public enum OldEphemeralType {
    /**
     * Not ephemeral
     */
    VOID,
    /**
     * Standard, pre-3.5.x EPHEMERAL
     */
    NORMAL,
    /**
     * Container node
     */
    CONTAINER,
    /**
     * TTL node
     */
    TTL;

    public static final long CONTAINER_EPHEMERAL_OWNER = Long.MIN_VALUE;
    public static final long MAX_TTL = 0x0fffffffffffffffL;
    public static final long TTL_MASK = 0x8000000000000000L;

    public static OldEphemeralType get(long ephemeralOwner) {
        if (ephemeralOwner == CONTAINER_EPHEMERAL_OWNER) {
            return CONTAINER;
        }
        if (ephemeralOwner < 0) {
            return TTL;
        }
        return (ephemeralOwner == 0) ? VOID : NORMAL;
    }

    public static long getTTL(long ephemeralOwner) {
        if ((ephemeralOwner < 0) && (ephemeralOwner != CONTAINER_EPHEMERAL_OWNER)) {
            return (ephemeralOwner & MAX_TTL);
        }
        return 0;
    }

    public static long ttlToEphemeralOwner(long ttl) {
        if ((ttl > MAX_TTL) || (ttl <= 0)) {
            throw new IllegalArgumentException("ttl must be positive and cannot be larger than: " + MAX_TTL);
        }
        return TTL_MASK | ttl;
    }
}
