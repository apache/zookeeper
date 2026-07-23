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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.Test;

public class HostProviderSelectionTest extends ZKTestCase {
    @Test
    public void testStaticHostProviderSelection() throws Exception {
        final String[] staticFormats = {
                "localhost:2181",
                "zk1:2181,zk2:2181,zk3:2181",
                "zk1:2181,zk2:2181/myapp",
                "[::1]:2181,[2001:db8::1]:2181"
        };

        for (final String connectString : staticFormats) {
            // Test without config
            try (final ZooKeeper zk = new ZooKeeper(connectString,
                    ClientBase.CONNECTION_TIMEOUT, DummyWatcher.INSTANCE)) {
                assertNotNull(zk);
            }

            // Test with config
            final ZKClientConfig config = new ZKClientConfig();
            try (final ZooKeeper zk = new ZooKeeper(connectString,
                    ClientBase.CONNECTION_TIMEOUT, DummyWatcher.INSTANCE, config)) {
                assertNotNull(zk);
            }
        }
    }

    @Test
    public void testDnsSrvHostProviderSelection() {
        final String[] dnsSrvFormats = {
                "dns-srv://nonexistent.test.local",
                "dns-srv://nonexistent.test.local/myapp"
        };

        for (final String connectString : dnsSrvFormats) {
            // Test without config
            final IllegalArgumentException exception1 = assertThrows(IllegalArgumentException.class, () ->
                    new ZooKeeper(connectString, ClientBase.CONNECTION_TIMEOUT, DummyWatcher.INSTANCE));
            validateDnsSrvError(exception1);

            // Test with config
            final ZKClientConfig config = new ZKClientConfig();
            final IllegalArgumentException exception2 = assertThrows(IllegalArgumentException.class, () ->
                    new ZooKeeper(connectString, ClientBase.CONNECTION_TIMEOUT, DummyWatcher.INSTANCE, config));
            validateDnsSrvError(exception2);
        }
    }

    @Test
    public void testInvalidFormats() {
        final String[] invalidFormats = {
                "",
                "dns-srv://"
        };

        for (final String connectString : invalidFormats) {
            final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                    new ZooKeeper(connectString, ClientBase.CONNECTION_TIMEOUT, DummyWatcher.INSTANCE));
            final String message = exception.getMessage();
            assertTrue(message.contains("Connect string cannot be null or empty")
                || message.contains("DNS name cannot be null or empty"));
        }
    }

    private void validateDnsSrvError(final IllegalArgumentException exception) {
        final String message = exception.getMessage();
        assertTrue(message.contains("Failed to initialize DnsSrvHostProvider for DNS name:"));
    }
}
