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

/**
 * This is a data wrapper class that is basically an InetSocketAddress with one
 * extra piece of information for the SSL port (optional). This is used by
 * Hedwig so we can encapsulate both regular and SSL port information in one
 * data structure. Hedwig hub servers can be configured to listen on the
 * standard regular port and additionally on an optional SSL port. The String
 * representation of a HedwigSocketAddress is: <hostname>:<port>:<SSL
 * port(optional)>
 */
public class HedwigSocketAddress {

    // Member fields that make up this class.
    private final String hostname;
    private final int port;
    private final int sslPort;

    private final InetSocketAddress socketAddress;
    private final InetSocketAddress sslSocketAddress;

    // Constants used by this class.
    public static final String COLON = ":";
    private static final int NO_SSL_PORT = -1;

    // Constructor that takes in both a regular and SSL port.
    public HedwigSocketAddress(String hostname, int port, int sslPort) {
        this.hostname = hostname;
        this.port = port;
        this.sslPort = sslPort;
        socketAddress = new InetSocketAddress(hostname, port);
        if (sslPort != NO_SSL_PORT)
            sslSocketAddress = new InetSocketAddress(hostname, sslPort);
        else
            sslSocketAddress = null;
    }

    // Constructor that only takes in a regular port.
    public HedwigSocketAddress(String hostname, int port) {
        this(hostname, port, NO_SSL_PORT);
    }

    // Constructor from a String "serialized" version of this class.
    public HedwigSocketAddress(String addr) {
        String[] parts = addr.split(COLON);
        this.hostname = parts[0];
        this.port = Integer.parseInt(parts[1]);
        if (parts.length > 2)
            this.sslPort = Integer.parseInt(parts[2]);
        else
            this.sslPort = NO_SSL_PORT;
        socketAddress = new InetSocketAddress(hostname, port);
        if (sslPort != NO_SSL_PORT)
            sslSocketAddress = new InetSocketAddress(hostname, sslPort);
        else
            sslSocketAddress = null;
    }

    // Public getters
    public String getHostname() {
        return hostname;
    }

    public int getPort() {
        return port;
    }

    public int getSSLPort() {
        return sslPort;
    }

    // Method to return an InetSocketAddress for the regular port.
    public InetSocketAddress getSocketAddress() {
        return socketAddress;
    }

    // Method to return an InetSocketAddress for the SSL port.
    // Note that if no SSL port (or an invalid value) was passed
    // during object creation, this call will throw an IllegalArgumentException
    // (runtime exception).
    public InetSocketAddress getSSLSocketAddress() {
        return sslSocketAddress;
    }

    // Method to determine if this object instance is SSL enabled or not
    // (contains a valid SSL port).
    public boolean isSSLEnabled() {
        return sslPort != NO_SSL_PORT;
    }

    // Return the String "serialized" version of this object.
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(hostname).append(COLON).append(port).append(COLON).append(sslPort);
        return sb.toString();
    }

    // Implement an equals method comparing two HedwigSocketAddress objects.
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof HedwigSocketAddress))
            return false;
        HedwigSocketAddress that = (HedwigSocketAddress) obj;
        return (this.hostname.equals(that.hostname) && (this.port == that.port) && (this.sslPort == that.sslPort));
    }

    // Static helper method to return the string representation for an
    // InetSocketAddress. The HedwigClient can only operate in SSL or non-SSL
    // mode. So the server hosts it connects to will just be an
    // InetSocketAddress instead of a HedwigSocketAddress. This utility method
    // can be used so we can store these server hosts as strings (ByteStrings)
    // in various places (e.g. list of server hosts we've connected to
    // or wrote to unsuccessfully).
    public static String sockAddrStr(InetSocketAddress addr) {
        return addr.getAddress().getHostAddress() + ":" + addr.getPort();
    }

}
