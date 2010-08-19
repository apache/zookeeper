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
package org.apache.hedwig.util;

import java.net.InetSocketAddress;

import junit.framework.TestCase;

import org.junit.Test;

public class TestHedwigSocketAddress extends TestCase {

    // Common values used by tests
    private String hostname = "localhost";
    private int port = 4080;
    private int sslPort = 9876;
    private int invalidPort = -9999;
    private String COLON = ":";
    
    @Test
    public void testCreateWithSSLPort() throws Exception {
        HedwigSocketAddress addr = new HedwigSocketAddress(hostname, port, sslPort);
        assertTrue(addr.getSocketAddress().equals(new InetSocketAddress(hostname, port)));
        assertTrue(addr.getSSLSocketAddress().equals(new InetSocketAddress(hostname, sslPort)));
    }

    @Test
    public void testCreateWithNoSSLPort() throws Exception {
        HedwigSocketAddress addr = new HedwigSocketAddress(hostname, port);
        assertTrue(addr.getSocketAddress().equals(new InetSocketAddress(hostname, port)));
        assertTrue(addr.getSSLSocketAddress() == null);
    }

    @Test
    public void testCreateFromStringWithSSLPort() throws Exception {
        HedwigSocketAddress addr = new HedwigSocketAddress(hostname+COLON+port+COLON+sslPort);
        assertTrue(addr.getSocketAddress().equals(new InetSocketAddress(hostname, port)));
        assertTrue(addr.getSSLSocketAddress().equals(new InetSocketAddress(hostname, sslPort)));
    }    

    @Test
    public void testCreateFromStringWithNoSSLPort() throws Exception {
        HedwigSocketAddress addr = new HedwigSocketAddress(hostname+COLON+port);
        assertTrue(addr.getSocketAddress().equals(new InetSocketAddress(hostname, port)));
        assertTrue(addr.getSSLSocketAddress() == null);
    }
    
    @Test
    public void testCreateWithInvalidRegularPort() throws Exception {
        boolean success = false;
        try {
            new HedwigSocketAddress(hostname+COLON+invalidPort);
        }
        catch (IllegalArgumentException e) {
            success = true;
        }
        assertTrue(success);
    }    

    @Test
    public void testCreateWithInvalidSSLPort() throws Exception {
        boolean success = false;
        try {
            new HedwigSocketAddress(hostname, port, invalidPort);
        }
        catch (IllegalArgumentException e) {
            success = true;
        }
        assertTrue(success);
    }    

    @Test
    public void testToStringConversion() throws Exception {
        HedwigSocketAddress addr = new HedwigSocketAddress(hostname, port, sslPort);
        HedwigSocketAddress addr2 = new HedwigSocketAddress(addr.toString());
        assertTrue(addr.getSocketAddress().equals(addr2.getSocketAddress()));
        assertTrue(addr.getSSLSocketAddress().equals(addr2.getSSLSocketAddress()));
        addr.toString().equals(addr2.toString());
    }

    @Test
    public void testIsSSLEnabledFlag() throws Exception {
        HedwigSocketAddress sslAddr = new HedwigSocketAddress(hostname, port, sslPort);
        assertTrue(sslAddr.isSSLEnabled());
        HedwigSocketAddress addr = new HedwigSocketAddress(hostname, port);
        assertFalse(addr.isSSLEnabled());               
    }
    
}
