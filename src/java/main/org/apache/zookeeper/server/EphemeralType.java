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

import org.apache.zookeeper.CreateMode;

public enum EphemeralType {
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
    public static final long MAX_TTL = 0x00ffffffffffffffL;
    public static final long TTL_MASK = 0xff00000000000000L;
    public static final long MAX_TTL_SERVER_ID = 0xfe;  // 254

    public static final String EXTENDED_TYPES_ENABLED_PROPERTY = "zookeeper.extendedTypesEnabled";

    public static boolean extendedEphemeralTypesEnabled() {
        return Boolean.getBoolean(EXTENDED_TYPES_ENABLED_PROPERTY);
    }

    public static EphemeralType get(long ephemeralOwner) {
        if ( extendedEphemeralTypesEnabled() ) {
            if ((ephemeralOwner & TTL_MASK) == TTL_MASK) {
                return TTL;
            }
        }
        if (ephemeralOwner == CONTAINER_EPHEMERAL_OWNER) {
            return CONTAINER;
        }
        return (ephemeralOwner == 0) ? VOID : NORMAL;
    }

    public static void validateServerId(long serverId) {
        // TODO: in the future, serverId should be validated for all cases, not just the extendedEphemeralTypesEnabled case
        // TODO: however, for now, it would be too disruptive

        if ( extendedEphemeralTypesEnabled() ) {
            if (serverId > EphemeralType.MAX_TTL_SERVER_ID) {
                throw new RuntimeException("extendedTypesEnabled is true but Server ID is too large. Cannot be larger than " + EphemeralType.MAX_TTL_SERVER_ID);
            }
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void validateTTL(CreateMode mode, long ttl) {
        if (mode.isTTL()) {
            ttlToEphemeralOwner(ttl);
        } else if (ttl >= 0) {
            throw new IllegalArgumentException("ttl not valid for mode: " + mode);
        }
    }

    public static long getTTL(long ephemeralOwner) {
        if (get(ephemeralOwner) == TTL) {
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
