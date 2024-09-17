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

package org.apache.zookeeper.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import java.util.Arrays;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.server.auth.IPAuthenticationProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class IPAuthTest {
    @Before
    public void setUp() {
        System.setProperty(IPAuthenticationProvider.USE_X_FORWARDED_FOR_KEY, "true");
    }

    @After
    public void tearDown() {
        System.clearProperty(IPAuthenticationProvider.USE_X_FORWARDED_FOR_KEY);
    }

    @Test
    public void testHandleAuthentication_Forwarded() {
        final IPAuthenticationProvider provider = new IPAuthenticationProvider();

        final HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        final String forwardedForHeader = "fc00:0:0:0:0:0:0:4, 192.168.0.6, 10.0.0.8, 172.16.0.9";
        Mockito.doReturn(forwardedForHeader).when(mockRequest).getHeader(IPAuthenticationProvider.X_FORWARDED_FOR_HEADER_NAME);
        Mockito.doReturn("192.168.0.5").when(mockRequest).getRemoteAddr();

        // validate it returns the leftmost IP from the X-Forwarded-For header
        final List<Id> expectedIds = Arrays.asList(new Id(provider.getScheme(), "fc00:0:0:0:0:0:0:4"));
        assertEquals(expectedIds, provider.handleAuthentication(mockRequest, null));
    }

    @Test
    public void testHandleAuthentication_NoForwarded() {
        final IPAuthenticationProvider provider = new IPAuthenticationProvider();

        final HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        Mockito.doReturn(null).when(mockRequest).getHeader(IPAuthenticationProvider.X_FORWARDED_FOR_HEADER_NAME);
        Mockito.doReturn("192.168.0.6").when(mockRequest).getRemoteAddr();

        // validate it returns the remote address
        final List<Id> expectedIds = Arrays.asList(new Id(provider.getScheme(), "192.168.0.6"));
        assertEquals(expectedIds, provider.handleAuthentication(mockRequest, null));
    }
}
