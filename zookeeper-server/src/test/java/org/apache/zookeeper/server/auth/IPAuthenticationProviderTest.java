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
package org.apache.zookeeper.server.auth;

import static org.apache.zookeeper.server.auth.IPAuthenticationProvider.USE_X_FORWARDED_FOR_KEY;
import static org.apache.zookeeper.server.auth.IPAuthenticationProvider.X_FORWARDED_FOR_HEADER_NAME;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import javax.servlet.http.HttpServletRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class IPAuthenticationProviderTest {

  private HttpServletRequest request;

  @Before
  public void setUp() throws Exception {
    System.clearProperty(USE_X_FORWARDED_FOR_KEY);
    request = mock(HttpServletRequest.class);
  }

  @After
  public void tearDown() {
    System.clearProperty(USE_X_FORWARDED_FOR_KEY);
  }

  @Test
  public void testGetClientIPAddressSkipXForwardedFor() {
    // Arrange
    System.setProperty(USE_X_FORWARDED_FOR_KEY, "false");
    doReturn("192.168.1.1").when(request).getRemoteAddr();
    doReturn("192.168.1.2,192.168.1.3,192.168.1.4").when(request).getHeader(X_FORWARDED_FOR_HEADER_NAME);

    // Act
    String clientIp = IPAuthenticationProvider.getClientIPAddress(request);

    // Assert
    assertEquals("192.168.1.1", clientIp);
  }

  @Test
  public void testGetClientIPAddressDefaultBehaviour() {
    // Arrange
    System.clearProperty(USE_X_FORWARDED_FOR_KEY);
    doReturn("192.168.1.1").when(request).getRemoteAddr();
    doReturn("192.168.1.2,192.168.1.3,192.168.1.4").when(request).getHeader(X_FORWARDED_FOR_HEADER_NAME);

    // Act
    String clientIp = IPAuthenticationProvider.getClientIPAddress(request);

    // Assert
    assertEquals("192.168.1.1", clientIp);
  }

  @Test
  public void testGetClientIPAddressWithXForwardedFor() {
    // Arrange
    System.setProperty(USE_X_FORWARDED_FOR_KEY, "true");
    doReturn("192.168.1.1").when(request).getRemoteAddr();
    doReturn("192.168.1.2,192.168.1.3,192.168.1.4").when(request).getHeader(X_FORWARDED_FOR_HEADER_NAME);

    // Act
    String clientIp = IPAuthenticationProvider.getClientIPAddress(request);

    // Assert
    assertEquals("192.168.1.2", clientIp);
  }

  @Test
  public void testGetClientIPAddressMissingXForwardedFor() {
    // Arrange
    System.setProperty(USE_X_FORWARDED_FOR_KEY, "false");
    doReturn("192.168.1.1").when(request).getRemoteAddr();

    // Act
    String clientIp = IPAuthenticationProvider.getClientIPAddress(request);

    // Assert
    assertEquals("192.168.1.1", clientIp);
  }
}
