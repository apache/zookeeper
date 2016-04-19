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

package org.apache.zookeeper.client;

import org.apache.zookeeper.SSLCertCfg;
import org.apache.zookeeper.ServerCfg;
import org.apache.zookeeper.common.PathUtils;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.apache.zookeeper.common.StringUtils.split;

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

    private final String chrootPath;

    private final ArrayList<ServerCfg> serverCfgList = new ArrayList<>();

    /**
     * 
     * @throws IllegalArgumentException
     *             for an invalid chroot path.
     */
    public ConnectStringParser(String connectString) {
        // parse out chroot, if any
        int off = connectString.indexOf('/');
        if (off >= 0) {
            String chrootPath = connectString.substring(off);
            // ignore "/" chroot spec, same as null
            if (chrootPath.length() == 1) {
                this.chrootPath = null;
            } else {
                PathUtils.validatePath(chrootPath);
                this.chrootPath = chrootPath;
            }
            connectString = connectString.substring(0, off);
        } else {
            this.chrootPath = null;
        }


        List<String> hostsList = split(connectString,",");
        for (String host : hostsList) {
            final String[] hostStrParts = host.split(":");
            int port = DEFAULT_PORT;
            boolean noPort = false;
            if (hostStrParts.length > 1) {
                try {
                    port = Integer.parseInt(hostStrParts[1]);
                } catch (NumberFormatException exp) {
                    // ok nothing to do here!.
                    noPort = true;
                }
            }

            try {
                if (hostStrParts.length > 2 || noPort) {
                    serverCfgList.add(
                            new ServerCfg(hostStrParts[0],
                                    InetSocketAddress.createUnresolved(host,
                                            port),
                                    SSLCertCfg.parseCertCfgStr(host)));
                } else {
                    serverCfgList.add(
                            new ServerCfg(hostStrParts[0],
                                    InetSocketAddress.createUnresolved(host,
                                            port)));
                }
            } catch (QuorumPeerConfig.ConfigException exp) {
                throw new IllegalArgumentException(exp);
            }
        }
    }

    public String getChrootPath() {
        return chrootPath;
    }

    public Collection<ServerCfg> getServersCfg() {
        return Collections.unmodifiableCollection(serverCfgList);
    }
}
