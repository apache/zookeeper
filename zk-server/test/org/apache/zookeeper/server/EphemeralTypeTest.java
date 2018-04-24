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
import org.junit.Assert;
import org.junit.Test;

public class EphemeralTypeTest {
    @Test
    public void testTtls() {
        long ttls[] = {100, 1, EphemeralType.MAX_TTL};
        for (long ttl : ttls) {
            long ephemeralOwner = EphemeralType.ttlToEphemeralOwner(ttl);
            Assert.assertEquals(EphemeralType.TTL, EphemeralType.get(ephemeralOwner));
            Assert.assertEquals(ttl, EphemeralType.getTTL(ephemeralOwner));
        }

        EphemeralType.validateTTL(CreateMode.PERSISTENT_WITH_TTL, 100);
        EphemeralType.validateTTL(CreateMode.PERSISTENT_SEQUENTIAL_WITH_TTL, 100);

        try {
            EphemeralType.validateTTL(CreateMode.EPHEMERAL, 100);
            Assert.fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException dummy) {
            // expected
        }
    }

    @Test
    public void testContainerValue() {
        Assert.assertEquals(Long.MIN_VALUE, EphemeralType.CONTAINER_EPHEMERAL_OWNER);
        Assert.assertEquals(EphemeralType.CONTAINER, EphemeralType.get(EphemeralType.CONTAINER_EPHEMERAL_OWNER));
    }

    @Test
    public void testNonSpecial() {
        Assert.assertEquals(EphemeralType.VOID, EphemeralType.get(0));
        Assert.assertEquals(EphemeralType.NORMAL, EphemeralType.get(1));
        Assert.assertEquals(EphemeralType.NORMAL, EphemeralType.get(Long.MAX_VALUE));
    }
}
