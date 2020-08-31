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

package org.apache.zookeeper.server.controller;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;
import org.apache.zookeeper.server.ServerConfig;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.apache.zookeeper.server.quorum.flexible.QuorumMaj;

/**
 * Config for the ControllerService. Responsible for providing the minimum set of configurations
 * that's required to spin up a single member ensemble.
 */
public class ControllerServerConfig extends QuorumPeerConfig {
    public static final String CONTROLLER_PORT_KEY = "zookeeper.controllerPort";
    public static final String CLIENT_PORT_KEY = "zookeeper.clientPortAddress";
    private InetSocketAddress controllerAddress;
    public InetSocketAddress getControllerAddress() {
        return controllerAddress;
    }

    /**
     * Instantiate a new config via a zk config file.
     * @param configFile path to the configuration file
     * @throws ConfigException
     */
    public ControllerServerConfig(String configFile) throws ConfigException {
        parse(configFile);
    }

    /**
     * Instantiate a config object with required parameters.
     * @param hostAddress The address to bind to (likely loopback or localhost)
     * @param controllerPort Port the controller will listen for incoming control command sent from CommandClient.
     * @param zkServerPort Port the ZooKeeper server will listen on.
     * @param dataDirPath Path to the data directory that ZooKeeperServer uses.
     */
    public ControllerServerConfig(InetAddress hostAddress, int controllerPort, int zkServerPort, String dataDirPath) {
        controllerAddress = new InetSocketAddress(hostAddress, controllerPort);
        clientPortAddress = new InetSocketAddress(hostAddress, zkServerPort);
        dataDir = new File(dataDirPath);
        dataLogDir = dataDir;
        serverId = 0;
    }

    /**
     * Instantiate a config object with required parameters.
     * @param controllerPort Port the controller will listen for incoming control command sent from CommandClient.
     * @param zkServerPort Port the ZooKeeper server will listen on.
     * @param dataDirPath Path to the data directory that ZooKeeperServer uses.
     */
    public ControllerServerConfig(int controllerPort, int zkServerPort, String dataDirPath) {
        this(InetAddress.getLoopbackAddress(), controllerPort, zkServerPort, dataDirPath);
    }

    public ServerConfig getZooKeeperServerConfig() {
        ServerConfig serverConfig = new ServerConfig();
        serverConfig.readFrom(this);
        return serverConfig;
    }

    @Override
    public void parse(String configFile) throws ConfigException {
        super.parse(configFile);
        for (String key : System.getProperties().stringPropertyNames()) {
            if (CONTROLLER_PORT_KEY.equalsIgnoreCase(key)) {
                setControllerAddress(System.getProperty(key));
            }
            if (CLIENT_PORT_KEY.equals(key)) {
                setClientAddress(System.getProperty(key));
            }
        }

        if (controllerAddress == null) {
            throw new ConfigException("Missing required parameter " + CONTROLLER_PORT_KEY);
        }

        if (clientPortAddress == null) {
            throw new ConfigException("Missing required parameter " + CLIENT_PORT_KEY);
        }
    }

    private void setControllerAddress(String port) {
        try {
            controllerAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), Integer.parseInt(port));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid port", ex);
        }
    }

    private void setClientAddress(String port) {
        try {
            clientPortAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), Integer.parseInt(port));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid port", ex);
        }
    }

    /**
     * Ensure config is acceptable by filling in default values for any missing quorum configuration
     * (specifically in the case of a single machine ensemble)
     *
     * @throws IOException
     */
    public void ensureComplete() throws IOException {
        if (this.quorumVerifier != null && this.quorumVerifier.getAllMembers().size() > 0) {
            return;
        }

        // QuorumPeer requires a QuorumVerifier.
        // We will use majority strategy with only this host in the quorum.
        // We need to provide 2 more ports: one for elections and one for quorum communications.
        // We will also mark this host as the leader.
        ServerSocket randomSocket1 = new ServerSocket(0);
        int quorumPort = randomSocket1.getLocalPort();

        ServerSocket randomSocket2 = new ServerSocket(0);
        int electionPort = randomSocket2.getLocalPort();

        randomSocket1.close();
        randomSocket2.close();

        QuorumPeer.QuorumServer selfAsPeer = new QuorumPeer.QuorumServer(
                0,
                new InetSocketAddress(quorumPort),
                new InetSocketAddress(electionPort),
                this.clientPortAddress
        );
        Map<Long, QuorumPeer.QuorumServer> peers = new HashMap<>();
        peers.put(selfAsPeer.id, selfAsPeer);
        this.quorumVerifier = new QuorumMaj(peers);
    }
}
