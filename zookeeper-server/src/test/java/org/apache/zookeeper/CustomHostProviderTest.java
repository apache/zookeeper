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

package org.apache.zookeeper;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.zookeeper.client.HostProvider;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.Test;

public class CustomHostProviderTest extends ZKTestCase {

    private AtomicInteger counter = new AtomicInteger(3);

    private class SpecialHostProvider implements HostProvider {

        // ignores its connectstring, and next() always returns localhost:2181
        // it will count down when updateServerList() is called
        @Override
        public int size() {
            return 1;
        }
        @Override
        public InetSocketAddress next(long spinDelay) {
            return new InetSocketAddress("127.0.0.1", 2181);
        }
        @Override
        public void onConnected() {
        }
        @Override
        public boolean updateServerList(Collection<InetSocketAddress> serverAddresses, InetSocketAddress currentHost) {
            counter.decrementAndGet();
            return false;
        }

    }

    @Test
    public void testZooKeeperWithCustomHostProvider() throws IOException, InterruptedException {
        final int CLIENT_PORT = PortAssignment.unique();
        final HostProvider specialHostProvider = new SpecialHostProvider();
        int expectedCounter = 3;
        counter.set(expectedCounter);

        ZooKeeper zkDefaults = new ZooKeeper(
            "127.0.0.1:" + CLIENT_PORT,
            ClientBase.CONNECTION_TIMEOUT,
            DummyWatcher.INSTANCE,
            false);

        ZooKeeper zkSpecial = new ZooKeeper(
                "127.0.0.1:" + CLIENT_PORT,
                ClientBase.CONNECTION_TIMEOUT,
                DummyWatcher.INSTANCE,
                false,
                specialHostProvider);

        assertTrue(counter.get() == expectedCounter);
        zkDefaults.updateServerList("127.0.0.1:" + PortAssignment.unique());
        assertTrue(counter.get() == expectedCounter);

        zkSpecial.updateServerList("127.0.0.1:" + PortAssignment.unique());
        expectedCounter--;
        assertTrue(counter.get() == expectedCounter);
    }

}
