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

package org.apache.zookeeper.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.client.HostProvider;
import org.apache.zookeeper.client.StaticHostProvider;
import org.junit.Test;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;

public class StaticHostProviderTest extends ZKTestCase {

    @Test
    public void testNextGoesRound() throws UnknownHostException {
        HostProvider hostProvider = getHostProvider(2);
        InetSocketAddress first = hostProvider.next(0);
        assertTrue(first instanceof InetSocketAddress);
        hostProvider.next(0);
        assertEquals(first, hostProvider.next(0));
    }

    @Test
    public void testNextGoesRoundAndSleeps() throws UnknownHostException {
        int size = 2;
        HostProvider hostProvider = getHostProvider(size);
        while (size > 0) {
            hostProvider.next(0);
            --size;
        }
        long start = System.currentTimeMillis();
        hostProvider.next(1000);
        long stop = System.currentTimeMillis();
        assertTrue(900 <= stop - start);
    }

    @Test
    public void testNextDoesNotSleepForZero() throws UnknownHostException {
        int size = 2;
        HostProvider hostProvider = getHostProvider(size);
        while (size > 0) {
            hostProvider.next(0);
            --size;
        }
        long start = System.currentTimeMillis();
        hostProvider.next(0);
        long stop = System.currentTimeMillis();
        assertTrue(5 > stop - start);
    }

    @Test
    public void testTwoConsequitiveCallsToNextReturnDifferentElement()
            throws UnknownHostException {
        HostProvider hostProvider = getHostProvider(2);
        assertNotSame(hostProvider.next(0), hostProvider.next(0));
    }

    @Test
    public void testOnConnectDoesNotReset() throws UnknownHostException {
        HostProvider hostProvider = getHostProvider(2);
        InetSocketAddress first = hostProvider.next(0);
        hostProvider.onConnected();
        InetSocketAddress second = hostProvider.next(0);
        assertNotSame(first, second);
    }

    private StaticHostProvider getHostProvider(int size)
            throws UnknownHostException {
        ArrayList<InetSocketAddress> list = new ArrayList<InetSocketAddress>(
                size);
        while (size > 0) {
            list.add(new InetSocketAddress("10.10.10." + size, 1234));
            --size;
        }
        return new StaticHostProvider(list);
    }
}
