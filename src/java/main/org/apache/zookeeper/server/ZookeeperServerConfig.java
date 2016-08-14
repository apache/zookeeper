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
package org.apache.zookeeper.server;

import java.io.File;

import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;

public class ZookeeperServerConfig extends ZKConfig {
    public static final String JUTE_MAXBUFFER = "jute.maxbuffer";
    /**
     * Path to a kinit binary: {@value}. Defaults to
     * <code>"/usr/bin/kinit"</code>
     */
    public static final String KINIT_COMMAND = "zookeeper.kinit";
    public static final String JGSS_NATIVE = "sun.security.jgss.native";
    private final ZookeeperServerSslConfig sslConfig =
            new ZookeeperServerSslConfig();

    public ZookeeperServerConfig() {
        super(new ZookeeperServerSslConfig());
    }

    public ZookeeperServerConfig(File configFile)
            throws QuorumPeerConfig.ConfigException {
        super(configFile, new ZookeeperServerSslConfig());
    }

    public ZookeeperServerConfig(String configPath)
            throws QuorumPeerConfig.ConfigException {
        super(configPath, new ZookeeperServerSslConfig());
    }

    /**
     * Now onwards client code will use properties from this class but older
     * clients still be setting properties through system properties. So to make
     * this change backward compatible we should set old system properties in
     * this configuration.
     */
    @Override
    protected void handleBackwardCompatibility() {
        super.handleBackwardCompatibility();
        properties.put(JUTE_MAXBUFFER, System.getProperty(JUTE_MAXBUFFER));
        properties.put(KINIT_COMMAND, System.getProperty(KINIT_COMMAND));
        properties.put(JGSS_NATIVE, System.getProperty(JGSS_NATIVE));
    }
}
