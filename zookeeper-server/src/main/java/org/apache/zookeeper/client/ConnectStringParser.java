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

package org.apache.zookeeper.client;

import static org.apache.zookeeper.common.StringUtils.split;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.apache.zookeeper.common.NetUtils;
import org.apache.zookeeper.common.PathUtils;
import org.apache.zookeeper.common.StringUtils;

/**
 * A parser for ZooKeeper Client connect strings.
 *
 * This class is not meant to be seen or used outside of ZooKeeper itself.
 *
 * The chrootPath member should be replaced by a Path object in issue
 * ZOOKEEPER-849.
 *
 * @see org.apache.zookeeper.ZooKeeper
 */
public final class ConnectStringParser {

    private static final int DEFAULT_PORT = 2181;
    private static final String DNS_SRV_PREFIX = "dns-srv://";

    private String chrootPath;
    private final ArrayList<InetSocketAddress> serverAddresses = new ArrayList<>();

    /**
     * Parse host and port by splitting client connectString
     * with support for IPv6 literals
     * @throws IllegalArgumentException
     *             for an invalid chroot path.
     */
    public ConnectStringParser(final String connectString) {
        if (StringUtils.isBlank(connectString)) {
            throw new IllegalArgumentException("Connect string cannot be null or empty");
        }

        if (connectString.startsWith(DNS_SRV_PREFIX)) {
            parseDnsSrvFormat(connectString);
        } else {
            parseHostPortFormat(connectString);
        }
    }

    public String getChrootPath() {
        return chrootPath;
    }

    public ArrayList<InetSocketAddress> getServerAddresses() {
        return serverAddresses;
    }


    /**
     * Gets the connection type for the given connect string.
     *
     * @param connectString the connection string to analyze
     * @return ConnectionType.DNS_SRV if it's a DNS SRV connect string, ConnectionType.HOST_PORT otherwise
     */
    public static ConnectionType getConnectionType(final String connectString) {
        if (connectString == null) {
            throw new IllegalArgumentException("connect string  cannot be null");
        }
        return connectString.startsWith(DNS_SRV_PREFIX)
                ? ConnectionType.DNS_SRV : ConnectionType.HOST_PORT;
    }

    /**
     * Parse DNS SRV connection string format: dns-srv://service.domain.com/chroot
     * @throws IllegalArgumentException for an invalid chroot path.
     */
    private void parseDnsSrvFormat(final String connectString) {
        final String dnsName = connectString.substring(DNS_SRV_PREFIX.length());

        final String[] parts = extractChrootPath(dnsName);
        final String dnsServiceName = parts[0];

        chrootPath = parts[1];
        // The DNS service name is stored as a placeholder address
        // The actual resolution will be handled by DnsSrvHostProvider
        serverAddresses.add(InetSocketAddress.createUnresolved(dnsServiceName, DEFAULT_PORT));
    }

    /**
     * Parse host and port by splitting client connectString
     * with support for IPv6 literals
     * @throws IllegalArgumentException for an invalid chroot path.
     */
    private void parseHostPortFormat(String connectString) {
        final String[] parts = extractChrootPath(connectString);
        final String serversPart = parts[0];
        chrootPath = parts[1];

        final List<String> hostsList = split(serversPart, ",");
        for (String host : hostsList) {
            int port = DEFAULT_PORT;
            final String[] hostAndPort = NetUtils.getIPV6HostAndPort(host);
            if (hostAndPort.length != 0) {
                host = hostAndPort[0];
                if (hostAndPort.length == 2) {
                    port = Integer.parseInt(hostAndPort[1]);
                }
            } else {
                int pidx = host.lastIndexOf(':');
                if (pidx >= 0) {
                    // otherwise : is at the end of the string, ignore
                    if (pidx < host.length() - 1) {
                        port = Integer.parseInt(host.substring(pidx + 1));
                    }
                    host = host.substring(0, pidx);
                }
            }
            serverAddresses.add(InetSocketAddress.createUnresolved(host, port));
        }
    }

    /**
     * Extract chroot path from a connection string.
     *
     * @param connectionString the connection string that may contain a chroot path
     * @return array where [0] is the server part (before chroot) and [1] is the chroot path (or null)
     * @throws IllegalArgumentException for an invalid chroot path
     */
    private String[] extractChrootPath(final String connectionString) {
        String serverPart = connectionString;
        String chrootPath = null;

        // parse out chroot, if any
        final int chrootIndex = connectionString.indexOf('/');
        if (chrootIndex >= 0) {
            chrootPath = connectionString.substring(chrootIndex);
            // ignore "/" chroot spec, same as null
            if (chrootPath.length() == 1) {
                chrootPath = null;
            } else {
                PathUtils.validatePath(chrootPath);
            }
            serverPart = connectionString.substring(0, chrootIndex);
        }
        return new String[]{serverPart, chrootPath};
    }

}
