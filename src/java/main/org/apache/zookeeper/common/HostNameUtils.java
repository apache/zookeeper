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

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * A class with hostname related utility methods.
 */
public class HostNameUtils {

    private HostNameUtils() {
        // non instantiable and non inheritable
    }

    /**
     * Returns the hostname or IP address of {@link java.net.InetSocketAddress}.
     *
     * This method returns the IP address if the
     * {@link java.net.InetSocketAddress} was created with a literal IP address,
     * and it doesn't perform a reverse DNS lookup. The goal of this method is
     * to substitute {@link java.net.InetSocketAddress#getHostString()}, which
     * is only available since Java 7.
     *
     * This method checks if the input InetSocketAddress was constructed with a
     * literal IP address by calling toString() on the underlying
     * {@link java.net.InetAddress}. It returns a string with the form
     * "hostname/literal IP address", and the hostname part is empty if the
     * input {@link java.net.InetSocketAddress} was created with an IP address.
     * There are 2 implementations of {@link java.net.InetAddress},
     * {@link java.net.Inet4Address} and {@link java.net.Inet6Address}, and both
     * classes are final, so we can trust the return value of the toString()
     * method.
     *
     * @return the hostname or IP address of {@link java.net.InetSocketAddress}.
     * @see java.net.InetSocketAddress#getHostString()
     */
    public static String getHostString(InetSocketAddress socketAddress) {
        InetAddress address = socketAddress.getAddress();
        return (address != null && address.toString().startsWith("/")) ?
               address.getHostAddress() :
               socketAddress.getHostName();
    }
}
