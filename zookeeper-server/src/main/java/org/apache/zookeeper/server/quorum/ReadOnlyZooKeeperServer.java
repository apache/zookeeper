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

package org.apache.zookeeper.server.quorum;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.server.DataTreeBean;
import org.apache.zookeeper.server.FinalRequestProcessor;
import org.apache.zookeeper.server.PrepRequestProcessor;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.ZooKeeperServerBean;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;

/**
 * A ZooKeeperServer which comes into play when peer is partitioned from the
 * majority. Handles read-only clients, but drops connections from not-read-only
 * ones.
 * <p>
 * The very first processor in the chain of request processors is a
 * ReadOnlyRequestProcessor which drops state-changing requests.
 */
public class ReadOnlyZooKeeperServer extends ZooKeeperServer {

    protected final QuorumPeer self;
    private volatile boolean shutdown = false;

    ReadOnlyZooKeeperServer(FileTxnSnapLog logFactory, QuorumPeer self, ZKDatabase zkDb) {
        super(
            logFactory,
            self.tickTime,
            self.minSessionTimeout,
            self.maxSessionTimeout,
            self.clientPortListenBacklog,
            zkDb,
            self.getInitialConfig(),
            self.isReconfigEnabled());
        this.self = self;
    }

    @Override
    protected void setupRequestProcessors() {
        RequestProcessor finalProcessor = new FinalRequestProcessor(this);
        RequestProcessor prepProcessor = new PrepRequestProcessor(this, finalProcessor);
        ((PrepRequestProcessor) prepProcessor).start();
        firstProcessor = new ReadOnlyRequestProcessor(this, prepProcessor);
        ((ReadOnlyRequestProcessor) firstProcessor).start();
    }

    @Override
    public synchronized void startup() {
        // check to avoid startup follows shutdown
        if (shutdown) {
            LOG.warn("Not starting Read-only server as startup follows shutdown!");
            return;
        }
        registerJMX(new ReadOnlyBean(this), self.jmxLocalPeerBean);
        super.startup();
        self.setZooKeeperServer(this);
        self.adminServer.setZooKeeperServer(this);
        LOG.info("Read-only server started");
    }

    @Override
    public void createSessionTracker() {
        sessionTracker = new LearnerSessionTracker(
                this, getZKDatabase().getSessionWithTimeOuts(),
                this.tickTime, self.getId(), self.areLocalSessionsEnabled(),
                getZooKeeperServerListener());
    }

    @Override
    protected void startSessionTracker() {
        ((LearnerSessionTracker) sessionTracker).start();
    }

    @Override
    protected void setLocalSessionFlag(Request si) {
        switch (si.type) {
            case OpCode.createSession:
                if (self.areLocalSessionsEnabled()) {
                    si.setLocalSession(true);
                }
                break;
            case OpCode.closeSession:
                if (((UpgradeableSessionTracker) sessionTracker).isLocalSession(si.sessionId)) {
                    si.setLocalSession(true);
                } else {
                    LOG.warn("Submitting global closeSession request for session 0x{} in ReadOnly mode",
                            Long.toHexString(si.sessionId));
                }
                break;
            default:
                break;
        }
    }

    @Override
    protected void validateSession(ServerCnxn cnxn, long sessionId) throws IOException {
        if (((LearnerSessionTracker) sessionTracker).isGlobalSession(sessionId)) {
            String msg = "Refusing global session reconnection in RO mode " + cnxn.getRemoteSocketAddress();
            LOG.info(msg);
            throw new ServerCnxn.CloseRequestException(msg, ServerCnxn.DisconnectReason.RENEW_GLOBAL_SESSION_IN_RO_MODE);
        }
    }

    @Override
    protected void registerJMX() {
        // register with JMX
        try {
            jmxDataTreeBean = new DataTreeBean(getZKDatabase().getDataTree());
            MBeanRegistry.getInstance().register(jmxDataTreeBean, jmxServerBean);
        } catch (Exception e) {
            LOG.warn("Failed to register with JMX", e);
            jmxDataTreeBean = null;
        }
    }

    public void registerJMX(ZooKeeperServerBean serverBean, LocalPeerBean localPeerBean) {
        // register with JMX
        try {
            jmxServerBean = serverBean;
            MBeanRegistry.getInstance().register(serverBean, localPeerBean);
        } catch (Exception e) {
            LOG.warn("Failed to register with JMX", e);
            jmxServerBean = null;
        }
    }

    @Override
    protected void unregisterJMX() {
        // unregister from JMX
        try {
            if (jmxDataTreeBean != null) {
                MBeanRegistry.getInstance().unregister(jmxDataTreeBean);
            }
        } catch (Exception e) {
            LOG.warn("Failed to unregister with JMX", e);
        }
        jmxDataTreeBean = null;
    }

    protected void unregisterJMX(ZooKeeperServer zks) {
        // unregister from JMX
        try {
            if (jmxServerBean != null) {
                MBeanRegistry.getInstance().unregister(jmxServerBean);
            }
        } catch (Exception e) {
            LOG.warn("Failed to unregister with JMX", e);
        }
        jmxServerBean = null;
    }

    @Override
    public String getState() {
        return "read-only";
    }

    /**
     * Returns the id of the associated QuorumPeer, which will do for a unique
     * id of this server.
     */
    @Override
    public long getServerId() {
        return self.getId();
    }

    @Override
    public synchronized void shutdown() {
        if (!canShutdown()) {
            LOG.debug("ZooKeeper server is not running, so not proceeding to shutdown!");
            return;
        }
        shutdown = true;
        unregisterJMX(this);

        // set peer's server to null
        self.setZooKeeperServer(null);
        // clear all the connections
        self.closeAllConnections();

        self.adminServer.setZooKeeperServer(null);

        // shutdown the server itself
        super.shutdown();
    }

    @Override
    public void dumpConf(PrintWriter pwriter) {
        super.dumpConf(pwriter);

        pwriter.print("initLimit=");
        pwriter.println(self.getInitLimit());
        pwriter.print("syncLimit=");
        pwriter.println(self.getSyncLimit());
        pwriter.print("electionAlg=");
        pwriter.println(self.getElectionType());
        pwriter.print("electionPort=");
        pwriter.println(self.getElectionAddress().getAllPorts()
                .stream().map(Objects::toString).collect(Collectors.joining("|")));
        pwriter.print("quorumPort=");
        pwriter.println(self.getQuorumAddress().getAllPorts()
                .stream().map(Objects::toString).collect(Collectors.joining("|")));
        pwriter.print("peerType=");
        pwriter.println(self.getLearnerType().ordinal());
    }

    @Override
    protected void setState(State state) {
        this.state = state;
    }

}
