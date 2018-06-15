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

package org.apache.zookeeper.server;

import org.apache.zookeeper.ZKTestCase;
import org.junit.Test;

import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;

public class ZooKeeperServerTest extends ZKTestCase {

    @Test
    public void testServerCnxnFactoryGetNonSecure() {
        // Arrange
        ZooKeeperServer zks = new ZooKeeperServer();
        ServerCnxnFactory serverCnxnFactoryMock = mock(ServerCnxnFactory.class);

        // Act
        zks.setServerCnxnFactory(serverCnxnFactoryMock);

        // Assert
        assertThat("ServerCnxnFactory getter should return non-secure instance when secure is not set",
                zks.getServerCnxnFactory(), sameInstance(serverCnxnFactoryMock));
    }

    @Test
    public void testServerCnxnFactoryGetSecure() {
        // Arrange
        ZooKeeperServer zks = new ZooKeeperServer();
        ServerCnxnFactory serverCnxnFactoryMock = mock(ServerCnxnFactory.class);

        // Act
        zks.setSecureServerCnxnFactory(serverCnxnFactoryMock);

        // Assert
        assertThat("ServerCnxnFactory getter should return secure instance when it's set",
                zks.getServerCnxnFactory(), sameInstance(serverCnxnFactoryMock));
    }

    @Test
    public void testServerCnxnFactoryGetBoth() {
        // Arrange
        ZooKeeperServer zks = new ZooKeeperServer();
        ServerCnxnFactory serverCnxnFactoryMock = mock(ServerCnxnFactory.class);
        ServerCnxnFactory serverCnxnFactorySecureMock = mock(ServerCnxnFactory.class);

        // Act
        zks.setServerCnxnFactory(serverCnxnFactoryMock);
        zks.setSecureServerCnxnFactory(serverCnxnFactorySecureMock);

        // Assert
        assertThat("ServerCnxnFactory getter should return secure instance when both are set",
                zks.getServerCnxnFactory(), sameInstance(serverCnxnFactorySecureMock));
    }
}
