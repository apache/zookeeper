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

package org.apache.zookeeper.common;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * This class contains common utilities for netstuff. Like printing IPv6 literals correctly
 */
public class NetUtils {

    public static String formatInetAddr(InetSocketAddress addr) {
        InetAddress ia = addr.getAddress();

        if (ia == null) {
            return String.format("%s:%s", addr.getHostString(), addr.getPort());
        }

        if (ia instanceof Inet6Address) {
            return String.format("[%s]:%s", ia.getHostAddress(), addr.getPort());
        } else {
            return String.format("%s:%s", ia.getHostAddress(), addr.getPort());
        }
    }

    /**
     * Separates host and port from given host port string if host port string is enclosed
     * within square bracket.
     *
     * @param hostPort host port string
     * @return String[]{host, port} if host port string is host:port
     * or String[] {host, port:port} if host port string is host:port:port
     * or String[] {host} if host port string is host
     * or String[]{} if not a ipv6 host port string.
     */
    public static String[] getIPV6HostAndPort(String hostPort) {
        if (hostPort.startsWith("[")) {
            int i = hostPort.lastIndexOf(']');
            if (i < 0) {
                throw new IllegalArgumentException(
                    hostPort + " starts with '[' but has no matching ']'");
            }
            String host = hostPort.substring(1, i);
            if (host.isEmpty()) {
                throw new IllegalArgumentException(host + " is empty.");
            }
            if (hostPort.length() > i + 1) {
                return getHostPort(hostPort, i, host);
            }
            return new String[] { host };
        } else {
            //Not an IPV6 host port string
            return new String[] {};
        }
    }

    private static String[] getHostPort(String hostPort, int indexOfClosingBracket, String host) {
        // [127::1]:2181 , check separator : exits
        if (hostPort.charAt(indexOfClosingBracket + 1) != ':') {
            throw new IllegalArgumentException(hostPort + " does not have : after ]");
        }
        // [127::1]: scenario
        if (indexOfClosingBracket + 2 == hostPort.length()) {
            throw new IllegalArgumentException(hostPort + " doesn't have a port after colon.");
        }
        //do not include
        String port = hostPort.substring(indexOfClosingBracket + 2);
        return new String[] { host, port };
    }
}
