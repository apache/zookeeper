/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.zookeeper.common;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import org.junit.Assert;
import org.junit.Test;

public class HostNameUtilsTest {

    private String validName = "example.com";
    private String invalidName = "example.com_invalid";
    private int port = 123;

    @Test
    public void testWildcard() {
        InetSocketAddress socketAddress = new InetSocketAddress(port);
        Assert.assertEquals("InetSocketAddress with no host. " +
                            "Expecting 0.0.0.0.",
                            socketAddress.getAddress().getHostAddress(),
                            HostNameUtils.getHostString(socketAddress));
    }

    @Test
    public void testHostName() {
        InetSocketAddress socketAddress =
            new InetSocketAddress(validName, port);
        Assert.assertEquals("InetSocketAddress with a valid hostname",
                            validName,
                            HostNameUtils.getHostString(socketAddress));

        socketAddress = new InetSocketAddress(invalidName, port);
        Assert.assertEquals("InetSocketAddress with an invalid hostname",
                            invalidName,
                            HostNameUtils.getHostString(socketAddress));
    }

    @Test
    public void testGetByAddress() {
        try {
            byte[] byteAddress = new byte[]{1, 2, 3, 4};
            InetAddress address = InetAddress.getByAddress(byteAddress);
            InetSocketAddress socketAddress =
                new InetSocketAddress(address, port);
            Assert.assertEquals("getByAddress with byte address only.",
                                address.getHostAddress(),
                                HostNameUtils.getHostString(socketAddress));

            address = InetAddress.getByAddress(validName, byteAddress);
            socketAddress = new InetSocketAddress(address, port);
            Assert.assertEquals("getByAddress with a valid hostname and byte " +
                                "address.",
                                validName,
                                HostNameUtils.getHostString(socketAddress));

            address = InetAddress.getByAddress(invalidName, byteAddress);
            socketAddress = new InetSocketAddress(address, port);
            Assert.assertEquals("getByAddress with an invalid hostname and " +
                                "byte address.",
                                invalidName,
                                HostNameUtils.getHostString(socketAddress));
        } catch (UnknownHostException ex) {
            Assert.fail(ex.toString());
        }
    }

    @Test
    public void testGetByName() {
        try {
            InetAddress address = InetAddress.getByName(validName);
            InetSocketAddress socketAddress =
                new InetSocketAddress(address, port);
            Assert.assertEquals("getByName with a valid hostname.",
                                validName,
                                HostNameUtils.getHostString(socketAddress));
        } catch (UnknownHostException ex) {
            Assert.fail(ex.toString());
        }
    }
}