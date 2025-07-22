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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

  @Test
  public void testParsingOfIPv6Address() {
    //Full IPv6 address
    String ipv6Full = "2001:0db8:85a3:0000:0000:8a2e:0370:7334";
    byte[] expectedFull = {
            (byte) 0x20, (byte) 0x01, (byte) 0x0d, (byte) 0xb8,
            (byte) 0x85, (byte) 0xa3, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x8a, (byte) 0x2e,
            (byte) 0x03, (byte) 0x70, (byte) 0x73, (byte) 0x34
    };
    byte[] actualFull = IPAuthenticationProvider.v6addr2Bytes(ipv6Full);
    assertNotNull(actualFull, "Full IPv6 address should not return null");
    assertArrayEquals(expectedFull, actualFull, "Full IPv6 address conversion mismatch");

    //Compressed IPv6 address (double colon)
    String ipv6Compressed = "2001:db8::8a2e:370:7334";
    byte[] expectedCompressed = {
            (byte) 0x20, (byte) 0x01, (byte) 0x0d, (byte) 0xb8,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x8a, (byte) 0x2e,
            (byte) 0x03, (byte) 0x70, (byte) 0x73, (byte) 0x34
    };
    byte[] actualCompressed = IPAuthenticationProvider.v6addr2Bytes(ipv6Compressed);
    assertNotNull(actualCompressed, "Compressed IPv6 address should not return null");
    assertArrayEquals(expectedCompressed, actualCompressed, "Compressed IPv6 address conversion mismatch");

    //Shortened IPv6 address
    String ipv6Shortened = "2001:db8::1";
    byte[] expectedShortened = {
            (byte) 0x20, (byte) 0x01, (byte) 0x0d, (byte) 0xb8,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01
    };
    byte[] actualShortened = IPAuthenticationProvider.v6addr2Bytes(ipv6Shortened);
    assertNotNull(actualShortened, "Shortened IPv6 address should not return null");
    assertArrayEquals(expectedShortened, actualShortened, "Shortened IPv6 address conversion mismatch");

    //Loopback address
    String ipv6Loopback = "::1";
    byte[] expectedLoopback = {
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01
    };
    byte[] actualLoopback = IPAuthenticationProvider.v6addr2Bytes(ipv6Loopback);
    assertNotNull(actualLoopback, "Loopback IPv6 address should not return null");
    assertArrayEquals(expectedLoopback, actualLoopback, "Loopback IPv6 address conversion mismatch");
  }
}
