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

    public enum ConnectionType {
        DNS_SRV,
        HOST_PORT
    }

    private String chrootPath;
    private final ArrayList<InetSocketAddress> serverAddresses = new ArrayList<>();
    private final ConnectionType connectionType;
    private final String connectString;

    /**
     * Constructs a ConnectStringParser with given connect string.
     *
     * <p>Supports two connection string formats:</p>
     * <ul>
     * <li><strong>Host:Port format:</strong> "host1:2181,host2:2181/chroot"</li>
     * <li><strong>DNS SRV format:</strong> "dns-srv://service.domain.com/chroot"</li>
     * </ul>
     *
     * @param connectString the connect string to parse
     * @throws IllegalArgumentException if connectString is null/empty or contains invalid chroot path
     */
    public ConnectStringParser(final String connectString) {
        if (StringUtils.isBlank(connectString)) {
            throw new IllegalArgumentException("Connect string cannot be null or empty");
        }

        if (connectString.startsWith(DNS_SRV_PREFIX)) {
            connectionType = ConnectionType.DNS_SRV;
            parseDnsSrvFormat(connectString);
        } else {
            connectionType = ConnectionType.HOST_PORT;
            parseHostPortFormat(connectString);
        }
        this.connectString = connectString;
    }

    public String getChrootPath() {
        return chrootPath;
    }

    public ArrayList<InetSocketAddress> getServerAddresses() {
        return serverAddresses;
    }

    public ConnectionType getConnectionType() {
        return connectionType;
    }

    public String getConnectString() {
        return connectString;
    }

    private String[] parseConnectString(final String connectString) {
        String serverPart = connectString;
        String chrootPath = null;

        // parse out chroot, if any
        final int off = connectString.indexOf('/');
        if (off >= 0) {
            chrootPath = connectString.substring(off);
            // ignore "/" chroot spec, same as null
            if (chrootPath.length() == 1) {
                chrootPath = null;
            } else {
                PathUtils.validatePath(chrootPath);
            }
            serverPart = connectString.substring(0, off);
        }
        return new String[]{serverPart, chrootPath};
    }

    private void parseHostPortFormat(final String connectString) {
        final String[] parts = parseConnectString(connectString);
        chrootPath = parts[1];

        final List<String> hostsList = split(parts[0], ",");
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

    private void parseDnsSrvFormat(final String connectString) {
        final String[] parts = parseConnectString(connectString.substring(DNS_SRV_PREFIX.length()));
        chrootPath = parts[1];
        // The DNS service name is stored as a placeholder address
        // The actual resolution will be handled by DnsSrvHostProvider
        serverAddresses.add(InetSocketAddress.createUnresolved(parts[0], DEFAULT_PORT));
    }
}
