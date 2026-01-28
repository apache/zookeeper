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

package org.apache.zookeeper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import java.io.IOException;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

public class ServerCnxnFactoryTest {
    public enum FactoryType {
        NIO, NETTY
    }

    private ServerCnxnFactory factory;

    @AfterEach
    public void tearDown() {
        if (factory != null) {
            try {
                factory.shutdown();
            } catch (Exception e) {
                // Ignore all shutdown exceptions in tests since factories may not be fully initialized
                // This includes NullPointerException when ServerSocketChannel is null
                // and any other exceptions from uninitialized factory state
            }
        }
    }

    @ParameterizedTest
    @EnumSource(FactoryType.class)
    public void testShedConnections_InvalidPercentage(final FactoryType factoryType) throws IOException {
        factory = createFactory(factoryType);
        assertThrows(IllegalArgumentException.class, () -> factory.shedConnections(-1));
        assertThrows(IllegalArgumentException.class, () -> factory.shedConnections(101));
    }

    @ParameterizedTest
    @EnumSource(FactoryType.class)
    public void testShedConnections_ValidPercentages(final FactoryType factoryType) throws IOException {
        factory = createFactory(factoryType);
        assertEquals(0, factory.shedConnections(0));
        assertEquals(0, factory.shedConnections(50));
        assertEquals(0, factory.shedConnections(100));
    }

    @ParameterizedTest
    @EnumSource(FactoryType.class)
    public void testShedConnections_DeterministicBehavior(final FactoryType factoryType) throws Exception {
        factory = createFactory(factoryType);

        // Create 4 mock connections for testing deterministic edge cases
        final ServerCnxn[] mockCnxns = new ServerCnxn[4];
        for (int i = 0; i < 4; i++) {
            mockCnxns[i] = mock(ServerCnxn.class);
            factory.cnxns.add(mockCnxns[i]);
        }

        // Test 0% shedding - should shed exactly 0 connections (deterministic)
        int shedCount = factory.shedConnections(0);
        assertEquals(0, shedCount, "0% shedding should shed exactly 0 connections");

        // Verify no connections were actually closed
        int actualClosedCount = countConnectionsShed(mockCnxns);
        assertEquals(0, actualClosedCount, "No connections should be closed for 0% shedding");

        // Test 100% shedding - should shed exactly all connections (deterministic)
        shedCount = factory.shedConnections(100);
        assertEquals(4, shedCount, "100% shedding should shed exactly all 4 connections");

        // Verify all connections were actually closed with correct reason
        actualClosedCount = countConnectionsShed(mockCnxns);
        assertEquals(4, actualClosedCount, "All 4 connections should be closed for 100% shedding");
    }

    @ParameterizedTest
    @EnumSource(FactoryType.class)
    public void testShedConnections_SmallPercentageRoundsToZero(final FactoryType factoryType) throws Exception {
        factory = createFactory(factoryType);

        // Add single mock connection
        final ServerCnxn mockCnxn = mock(ServerCnxn.class);
        factory.cnxns.add(mockCnxn);

        // Test critical edge case: small percentage rounds to 0
        assertEquals(0, factory.shedConnections(1), "1% of 1 connection should round to 0");
    }

    @ParameterizedTest
    @EnumSource(FactoryType.class)
    public void testShedConnections_ErrorHandling(final FactoryType factoryType) throws Exception {
        factory = createFactory(factoryType);

        // Create mock connections where one will fail to close
        final ServerCnxn[] mockCnxns = new ServerCnxn[4];
        for (int i = 0; i < 4; i++) {
            mockCnxns[i] = mock(ServerCnxn.class);
            factory.cnxns.add(mockCnxns[i]);
        }

        // Make the second connection throw an exception when closed
        doThrow(new RuntimeException("Connection close failed"))
                .when(mockCnxns[1]).close(ServerCnxn.DisconnectReason.SHED_CONNECTIONS_COMMAND);

        // Test 100% shedding to ensure error handling works deterministically
        final int shedCount = factory.shedConnections(100);

        // With 100% shedding, all 4 connections should be attempted to close
        // The method returns the count of connections successfully closed
        // Since one connection throws an exception, only 3 should be successfully closed
        assertEquals(3, shedCount, "Should successfully close 3 connections, 1 should fail");

        // Verify that 3 connections were actually closed (excluding the one that threw exception)
        int actualClosedCount = countConnectionsShed(mockCnxns);
        assertEquals(4, actualClosedCount, "All 4 connections should have close() called, even if one throws exception");
    }

    private ServerCnxnFactory createFactory(final FactoryType type) {
        switch (type) {
            case NIO:
                return new NIOServerCnxnFactory();
            case NETTY:
                return new NettyServerCnxnFactory();
            default:
                throw new IllegalArgumentException("Unknown factory type: " + type);
        }
    }

    private int countConnectionsShed(final ServerCnxn[] connections) {
        return (int) Arrays.stream(connections)
                .filter(cnxn -> mockingDetails(cnxn).getInvocations().stream()
                        .anyMatch(invocation ->
                                invocation.getMethod().getName().equals("close")
                                        && invocation.getArguments().length == 1
                                        && invocation.getArguments()[0].equals(ServerCnxn.DisconnectReason.SHED_CONNECTIONS_COMMAND)
                        ))
                .count();
    }
}
