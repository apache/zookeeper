/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import org.apache.zookeeper.CreateMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class EphemeralTypeTest {

    @BeforeEach
    public void setUp() {
        System.setProperty(EphemeralType.EXTENDED_TYPES_ENABLED_PROPERTY, "true");
    }

    @AfterEach
    public void tearDown() {
        System.clearProperty(EphemeralType.EXTENDED_TYPES_ENABLED_PROPERTY);
    }

    @Test
    public void testTtls() {
        long[] ttls = {100, 1, EphemeralType.TTL.maxValue()};
        for (long ttl : ttls) {
            long ephemeralOwner = EphemeralType.TTL.toEphemeralOwner(ttl);
            assertEquals(EphemeralType.TTL, EphemeralType.get(ephemeralOwner));
            assertEquals(ttl, EphemeralType.TTL.getValue(ephemeralOwner));
        }

        EphemeralType.validateTTL(CreateMode.PERSISTENT_WITH_TTL, 100);
        EphemeralType.validateTTL(CreateMode.PERSISTENT_SEQUENTIAL_WITH_TTL, 100);

        try {
            EphemeralType.validateTTL(CreateMode.EPHEMERAL, 100);
            fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException dummy) {
            // expected
        }
    }

    @Test
    public void testContainerValue() {
        assertEquals(Long.MIN_VALUE, EphemeralType.CONTAINER_EPHEMERAL_OWNER);
        assertEquals(EphemeralType.CONTAINER, EphemeralType.get(EphemeralType.CONTAINER_EPHEMERAL_OWNER));
    }

    @Test
    public void testNonSpecial() {
        assertEquals(EphemeralType.VOID, EphemeralType.get(0));
        assertEquals(EphemeralType.NORMAL, EphemeralType.get(1));
        assertEquals(EphemeralType.NORMAL, EphemeralType.get(Long.MAX_VALUE));
    }

    @Test
    public void testServerIds() {
        for (int i = 0; i <= EphemeralType.MAX_EXTENDED_SERVER_ID; ++i) {
            EphemeralType.validateServerId(i);
        }
        try {
            EphemeralType.validateServerId(EphemeralType.MAX_EXTENDED_SERVER_ID + 1);
            fail("Should have thrown RuntimeException");
        } catch (RuntimeException e) {
            // expected
        }
    }

    @Test
    public void testEphemeralOwner_extendedFeature_TTL() {
        // 0xff = Extended feature is ON
        // 0x0000 = Extended type id TTL (0)
        assertThat(EphemeralType.get(0xff00000000000000L), equalTo(EphemeralType.TTL));
    }

    @Test
    public void testEphemeralOwner_extendedFeature_extendedTypeUnsupported() {
        assertThrows(IllegalArgumentException.class, () -> {
            // 0xff = Extended feature is ON
            // 0x0001 = Unsupported extended type id (1)
            EphemeralType.get(0xff00010000000000L);
        });
    }

}
