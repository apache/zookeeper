/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import org.apache.zookeeper.server.ExitCode;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.apache.zookeeper.util.ServiceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class which accepts commands to modify ZooKeeperServer state or Connection state at runtime for the purpose of
 * single machine integration testing. Not meant to be used in production. It is recommended to use this in conjunction
 * with the CommandListener HttpServer and CommandClient.
 *
 */
@SuppressFBWarnings(value = "IS2_INCONSISTENT_SYNC", justification = "quorum peer is internally synchronized.")
public class ZooKeeperServerController {

    private static final Logger LOG = LoggerFactory.getLogger(ZooKeeperServerController.class);
    private static final long DEFAULT_DELAY_MS = 1000;

    private QuorumPeer quorumPeer;
    private ControllableConnectionFactory cnxnFactory;

    public ZooKeeperServerController(QuorumPeerConfig config) throws IOException {
        if (config == null) {
            throw new IllegalArgumentException("ZooKeeperServerController requires a valid config!");
        }

        cnxnFactory = new ControllableConnectionFactory();
        cnxnFactory.configure(config.getClientPortAddress(), config.getMaxClientCnxns(), config.getClientPortListenBacklog());
        quorumPeer = QuorumPeer.createFromConfig(config);
        quorumPeer.setCnxnFactory(cnxnFactory);
    }

    public void run() {
        try {
            quorumPeer.start();
            quorumPeer.join();
        } catch (Exception ex) {
            LOG.error("Fatal error starting quorum peer", ex);
            ServiceUtils.requestSystemExit(ExitCode.UNEXPECTED_ERROR.getValue());
        }
    }

    protected ServerCnxnFactory getCnxnFactory() {
        return cnxnFactory;
    }

    public synchronized void shutdown() {
        if (this.cnxnFactory != null) {
            this.cnxnFactory.shutdown();
            this.cnxnFactory = null;
        }

        if (this.quorumPeer != null && this.quorumPeer.isRunning()) {
            this.quorumPeer.shutdown();
            this.quorumPeer = null;
        }
    }

    public synchronized boolean isReady() {
        return this.cnxnFactory != null
                && this.quorumPeer != null
                && this.quorumPeer.isRunning()
                && this.quorumPeer.getActiveServer() != null;
    }

    /**
     * Process the command. An exception indicates errors. No exception indicates success.
     */
    public void processCommand(ControlCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Invalid command parameter!");
        }

        LOG.info("processing command {}{}", command.getAction(),
                command.getParameter() == null ? "" : "[" + command.getParameter() + "]");

        // Don't process command if we are shutting down or still initializing.
        if (!isReady()) {
            throw new IllegalStateException("Service is not ready. It has already been shutdown or is still initializing.");
        }

        switch (command.getAction()) {
            case PING:
                // NO-OP
                break;
            case SHUTDOWN:
                shutdown();
                break;
            case CLOSECONNECTION:
                if (command.getParameter() == null) {
                    cnxnFactory.closeAll(ServerCnxn.DisconnectReason.CLOSE_ALL_CONNECTIONS_FORCED);
                } else {
                    // A single parameter should be a session id as long.
                    // Parse failure exceptions will be sent to the caller.
                    cnxnFactory.closeSession(Long.decode(command.getParameter()),
                            ServerCnxn.DisconnectReason.CONNECTION_CLOSE_FORCED);
                }
                break;
            case EXPIRESESSION:
                if (command.getParameter() == null) {
                    expireAllSessions();
                } else {
                    // A single parameter should be a session id as long.
                    // Parse failure exceptions will be sent to the caller
                    expireSession(Long.decode(command.getParameter()));
                }
                break;
            case REJECTCONNECTIONS:
                // TODO: (hanm) implement once dependent feature is ready.
                //cnxnFactory.rejectNewConnections();
                break;
            case ADDDELAY:
                cnxnFactory.delayResponses(command.getParameter() == null
                        ? DEFAULT_DELAY_MS : Long.decode(command.getParameter()));
                break;
            case NORESPONSE:
                if (command.getParameter() == null) {
                    cnxnFactory.holdAllFutureResponses();
                } else {
                    cnxnFactory.holdFutureResponses(Long.decode(command.getParameter()));
                }
                break;
            case FAILREQUESTS:
                if (command.getParameter() == null) {
                    cnxnFactory.failAllFutureRequests();
                } else {
                    cnxnFactory.failFutureRequests(Long.decode(command.getParameter()));
                }
                break;
            case RESET:
                cnxnFactory.resetBadBehavior();
                break;
            case ELECTNEWLEADER:
                quorumPeer.startLeaderElection();
                break;
            default:
                throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    private ZooKeeperServer getServer() {
        return quorumPeer.getActiveServer();
    }

    private void expireSession(long sessionId) {
        getServer().expire(sessionId);
    }

    private void expireAllSessions() {
        for (Long sessionId : getServer().getSessionTracker().localSessions()) {
            expireSession(sessionId);
        }

        for (Long sessionId : getServer().getSessionTracker().globalSessions()) {
            expireSession(sessionId);
        }
    }

}

