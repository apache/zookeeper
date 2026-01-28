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

package org.apache.zookeeper.server.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.jupiter.api.Test;

public class ShedConnectionsCommandTest {

    private static final String VALID_JSON_25_PERCENT = "{\"percentage\": 25}";
    private static final String VALID_JSON_100_PERCENT = "{\"percentage\": 100}";
    private static final String VALID_JSON_1_PERCENT = "{\"percentage\": 1}";
    private static final String VALID_JSON_0_PERCENT = "{\"percentage\": 0}";

    private static final String INVALID_JSON_OVER_100_PERCENT = "{\"percentage\": 101}";

    private static final String INVALID_JSON_MISSING_PARAM = "{\"other\": 25}";
    private static final String INVALID_JSON_MALFORMED = "{\"percentage\": }";
    private static final String INVALID_JSON_EMPTY = "{}";

    @Test
    public void testValidPercentage25() {
        validateSuccessfulShedCommand(25, 50, 30, VALID_JSON_25_PERCENT, true, true);
    }

    @Test
    public void testValidPercentage100() {
        validateSuccessfulShedCommand(100, 20, 10, VALID_JSON_100_PERCENT, true, true);
    }

    @Test
    public void testValidPercentage1() {
        validateSuccessfulShedCommand(1, 100, 0, VALID_JSON_1_PERCENT, true, false);
    }

    @Test
    public void testValidPercentage0() {
        validateSuccessfulShedCommand(0, 100, 50, VALID_JSON_0_PERCENT, false, false);
    }

    @Test
    public void testInvalidPercentage101() {
        validateFailedShedCommand(INVALID_JSON_OVER_100_PERCENT, HttpServletResponse.SC_BAD_REQUEST, "Percentage must be between 0 and 100", true);
    }

    @Test
    public void testInvalidNullInputStream() {
        validateFailedShedCommand(null, HttpServletResponse.SC_BAD_REQUEST, "Request body is required", true);
    }

    @Test
    public void testEmptyJson() {
        validateFailedShedCommand(INVALID_JSON_EMPTY, HttpServletResponse.SC_BAD_REQUEST, "Missing required parameter: percentage", true);
    }

    @Test
    public void testMissingPercentageParameter() {
        validateFailedShedCommand(INVALID_JSON_MISSING_PARAM, HttpServletResponse.SC_BAD_REQUEST, "Missing required parameter: percentage", true);
    }

    @Test
    public void testMalformedJson() {
        validateFailedShedCommand(INVALID_JSON_MALFORMED, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON or failed to read request body", false);
    }

    @Test
    public void testOnlyInsecureConnections() {
        validateSuccessfulShedCommand(25, 40, 0, VALID_JSON_25_PERCENT, true, false);
    }

    @Test
    public void testOnlySecureConnections() {
        validateSuccessfulShedCommand(25, 0, 60, VALID_JSON_25_PERCENT, false, true);
    }

    @Test
    public void testNoConnections() {
        validateSuccessfulShedCommand(25, 0, 0, VALID_JSON_25_PERCENT, false, false);
    }

    @Test
    public void testMixedConnections() {
        validateSuccessfulShedCommand(25, 30, 20, VALID_JSON_25_PERCENT, true, true);
    }

    @Test
    public void testCommandNames() {
        final Commands.ShedConnectionsCommand command = new Commands.ShedConnectionsCommand();
        assertEquals(2, command.getNames().size());
        assertTrue(command.getNames().contains("shed"));
        assertTrue(command.getNames().contains("shed_connections"));
    }

    @Test
    public void testAuthorizationRequired() {
        final Commands.ShedConnectionsCommand command = new Commands.ShedConnectionsCommand();
        final AuthRequest authRequest = command.getAuthRequest();

        assertNotNull(authRequest);
        assertEquals(org.apache.zookeeper.ZooDefs.Perms.ALL, authRequest.getPermission());
        assertEquals(Commands.ROOT_PATH, authRequest.getPath());
    }

    private void validateSuccessfulShedCommand(
            final int expectedPercentage,
            final int insecureConnections,
            final int secureConnections,
            final String jsonInput,
            final boolean shouldCallInsecureFactory,
            final boolean shouldCallSecureFactory) {

        final Commands.ShedConnectionsCommand command = new Commands.ShedConnectionsCommand();
        final ZooKeeperServer zkServer = createMockZooKeeperServer(insecureConnections, secureConnections);
        final InputStream inputStream = new ByteArrayInputStream(jsonInput.getBytes());
        final int totalConnections = insecureConnections + secureConnections;

        final CommandResponse response = command.runPost(zkServer, inputStream);
        assertSuccessfulResponse(response, expectedPercentage, totalConnections);
        assertFactoryCalls(zkServer, expectedPercentage, shouldCallInsecureFactory, shouldCallSecureFactory);
    }

    private void validateFailedShedCommand(
            final String jsonInput,
            final int expectedStatusCode,
            final String expectedError,
            final boolean exactMatch) {

        final Commands.ShedConnectionsCommand command = new Commands.ShedConnectionsCommand();
        final ZooKeeperServer zkServer = createMockZooKeeperServer(10, 10);
        final InputStream inputStream = jsonInput != null ? new ByteArrayInputStream(jsonInput.getBytes()) : null;

        final CommandResponse response = command.runPost(zkServer, inputStream);

        assertNotNull(response);
        assertEquals(expectedStatusCode, response.getStatusCode());

        final Map<String, Object> result = response.toMap();
        final String actualError = (String) result.get("error");

        if (exactMatch) {
            assertEquals(expectedError, actualError);
        } else {
            assertTrue(actualError.contains(expectedError),
                    String.format("Expected error message to contain '%s', but was '%s'", expectedError, actualError));
        }
    }

    private void assertSuccessfulResponse(
            final CommandResponse response,
            final int expectedPercentage,
            final int totalConnections) {

        assertNotNull(response);
        assertEquals(HttpServletResponse.SC_OK, response.getStatusCode());

        final Map<String, Object> result = response.toMap();
        assertEquals(expectedPercentage, result.get("percentage_requested"));

        assertTrue(result.containsKey("connections_shed"));

        final int actualShed = (Integer) result.get("connections_shed");
        assertTrue(actualShed >= 0, "Shed count should be non-negative");
        assertTrue(actualShed <= totalConnections, "Cannot shed more than total connections");

        // For 0% and 100%, we can make exact assertions
        if (expectedPercentage == 0) {
            assertEquals(0, actualShed, "0% should shed exactly 0 connections");
        } else if (expectedPercentage == 100) {
            assertEquals(totalConnections, actualShed, "100% should shed all connections");
        }
    }

    private void assertFactoryCalls(
            final ZooKeeperServer zkServer,
            final int percentage,
            final boolean shouldCallInsecureFactory,
            final boolean shouldCallSecureFactory) {

        final ServerCnxnFactory factory = zkServer.getServerCnxnFactory();
        final ServerCnxnFactory secureFactory = zkServer.getSecureServerCnxnFactory();

        if (factory != null) {
            if (shouldCallInsecureFactory) {
                verify(factory, times(1)).shedConnections(percentage);
            } else {
                verify(factory, never()).shedConnections(anyInt());
            }
        }

        if (secureFactory != null) {
            if (shouldCallSecureFactory) {
                verify(secureFactory, times(1)).shedConnections(percentage);
            } else {
                verify(secureFactory, never()).shedConnections(anyInt());
            }
        }
    }

    private ZooKeeperServer createMockZooKeeperServer(int insecureConnections, int secureConnections) {
        final ZooKeeperServer zkServer = mock(ZooKeeperServer.class);
        final int totalConnections = insecureConnections + secureConnections;

        when(zkServer.getNumAliveConnections()).thenReturn(totalConnections);

        // Mock insecure factory
        ServerCnxnFactory factory = null;
        if (insecureConnections > 0) {
            factory = mock(ServerCnxnFactory.class);
            when(factory.getNumAliveConnections()).thenReturn(insecureConnections);
            // Mock shedConnections to match the actual implementation behavior
            when(factory.shedConnections(anyInt())).thenAnswer(invocation -> {
                int percentage = invocation.getArgument(0);
                if (percentage == 0) {
                    return 0; // 0% is deterministic - shed nothing
                } else if (percentage == 100) {
                    return insecureConnections; // 100% is deterministic - shed all
                } else {
                    // For other percentages, use probabilistic approximation
                    return (int) Math.ceil(insecureConnections * percentage / 100.0);
                }
            });
        }
        when(zkServer.getServerCnxnFactory()).thenReturn(factory);

        // Mock secure factory
        ServerCnxnFactory secureFactory = null;
        if (secureConnections > 0) {
            secureFactory = mock(ServerCnxnFactory.class);
            when(secureFactory.getNumAliveConnections()).thenReturn(secureConnections);
            // Mock shedConnections to match the actual implementation behavior
            when(secureFactory.shedConnections(anyInt())).thenAnswer(invocation -> {
                int percentage = invocation.getArgument(0);
                if (percentage == 0) {
                    return 0; // 0% is deterministic - shed nothing
                } else if (percentage == 100) {
                    return secureConnections; // 100% is deterministic - shed all
                } else {
                    // For other percentages, use probabilistic approximation
                    return (int) Math.ceil(secureConnections * percentage / 100.0);
                }
            });
        }
        when(zkServer.getSecureServerCnxnFactory()).thenReturn(secureFactory);

        return zkServer;
    }
}
