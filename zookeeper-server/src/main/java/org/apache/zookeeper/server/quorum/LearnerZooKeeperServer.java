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
import java.util.Collections;
import java.util.Map;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.server.DataTreeBean;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServerBean;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;

/**
 * Parent class for all ZooKeeperServers for Learners
 */
public abstract class LearnerZooKeeperServer extends QuorumZooKeeperServer {

    /*
     * Request processors
     */
    protected CommitProcessor commitProcessor;
    protected SyncRequestProcessor syncProcessor;

    public LearnerZooKeeperServer(FileTxnSnapLog logFactory, int tickTime, int minSessionTimeout, int maxSessionTimeout, int listenBacklog, ZKDatabase zkDb, QuorumPeer self) throws IOException {
        super(logFactory, tickTime, minSessionTimeout, maxSessionTimeout, listenBacklog, zkDb, self);
    }

    /**
     * Abstract method to return the learner associated with this server.
     * Since the Learner may change under our feet (when QuorumPeer reassigns
     * it) we can't simply take a reference here. Instead, we need the
     * subclasses to implement this.
     */
    public abstract Learner getLearner();

    /**
     * Returns the current state of the session tracker. This is only currently
     * used by a Learner to build a ping response packet.
     *
     */
    protected Map<Long, Integer> getTouchSnapshot() {
        if (sessionTracker != null) {
            return ((LearnerSessionTracker) sessionTracker).snapshot();
        }
        Map<Long, Integer> map = Collections.emptyMap();
        return map;
    }

    /**
     * Returns the id of the associated QuorumPeer, which will do for a unique
     * id of this server.
     */
    @Override
    public long getServerId() {
        return self.getMyId();
    }

    @Override
    public void createSessionTracker() {
        sessionTracker = new LearnerSessionTracker(
            this,
            getZKDatabase().getSessionWithTimeOuts(),
            this.tickTime,
            self.getMyId(),
            self.areLocalSessionsEnabled(),
            getZooKeeperServerListener());
    }

    @Override
    protected void revalidateSession(ServerCnxn cnxn, long sessionId, int sessionTimeout) throws IOException {
        if (upgradeableSessionTracker.isLocalSession(sessionId)) {
            super.revalidateSession(cnxn, sessionId, sessionTimeout);
        } else {
            getLearner().validateSession(cnxn, sessionId, sessionTimeout);
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
        if (self.jmxLeaderElectionBean != null) {
            try {
                MBeanRegistry.getInstance().unregister(self.jmxLeaderElectionBean);
            } catch (Exception e) {
                LOG.warn("Failed to register with JMX", e);
            }
            self.jmxLeaderElectionBean = null;
        }

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

    protected void unregisterJMX(Learner peer) {
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
    public synchronized void shutdown(boolean fullyShutDown) {
        if (!canShutdown()) {
            LOG.debug("ZooKeeper server is not running, so not proceeding to shutdown!");
        } else {
            LOG.info("Shutting down");
            try {
                if (syncProcessor != null) {
                    // Shutting down the syncProcessor here, first, ensures queued transactions here are written to
                    // permanent storage, which ensures that crash recovery data is consistent with what is used for a
                    // leader election immediately following shutdown, because of the old leader going down; and also
                    // that any state on its way to being written is also loaded in the potential call to
                    // fast-forward-from-edits, in super.shutdown(...), so we avoid getting a DIFF from the new leader
                    // that contains entries we have already written to our transaction log.
                    syncProcessor.shutdown();
                }
            } catch (Exception e) {
                LOG.warn("Ignoring unexpected exception in syncprocessor shutdown", e);
            }
        }
        try {
            super.shutdown(fullyShutDown);
        } catch (Exception e) {
            LOG.warn("Ignoring unexpected exception during shutdown", e);
        }
    }

}
