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
package org.apache.zookeeper.server.quorum;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.server.SessionTracker;
import org.apache.zookeeper.server.ZooKeeperServerListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A session tracker that supports upgradeable local sessions.
 */
public abstract class UpgradeableSessionTracker implements SessionTracker {
    private static final Logger LOG = LoggerFactory.getLogger(UpgradeableSessionTracker.class);

    private ConcurrentMap<Long, Integer> localSessionsWithTimeouts;
    private ConcurrentMap<Long, Integer> upgradingSessions;
    protected LocalSessionTracker localSessionTracker;
    protected boolean localSessionsEnabled;

    public void start() {}

    public void createLocalSessionTracker(SessionExpirer expirer,
            int tickTime, long id, ZooKeeperServerListener listener) {
        this.localSessionsWithTimeouts =
            new ConcurrentHashMap<Long, Integer>();
        this.localSessionTracker = new LocalSessionTracker(
            expirer, this.localSessionsWithTimeouts, tickTime, id, listener);
        this.upgradingSessions = new ConcurrentHashMap<Long, Integer>();
    }

    public boolean isTrackingSession(long sessionId) {
        return isLocalSession(sessionId) || isGlobalSession(sessionId);
    }

    public boolean isLocalSession(long sessionId) {
        return localSessionTracker != null &&
            localSessionTracker.isTrackingSession(sessionId);
    }

    public boolean isUpgradingSession(long sessionId) {
        return upgradingSessions != null &&
            upgradingSessions.containsKey(sessionId);
    }

    public void finishedUpgrading(long sessionId) {
        if (upgradingSessions != null) {
            upgradingSessions.remove(sessionId);
        }
    }

    abstract public boolean isGlobalSession(long sessionId);

    /**
     * Upgrades the session to a global session.
     * This simply removes the session from the local tracker and marks
     * it as global.  It is up to the caller to actually
     * queue up a transaction for the session.
     *
     * @param sessionId
     * @return session timeout (-1 if not a local session)
     */
    public int upgradeSession(long sessionId) {
        if (localSessionsWithTimeouts == null) {
            return -1;
        }
        // We won't race another upgrade attempt because only one thread
        // will get the timeout from the map
        Integer timeout = localSessionsWithTimeouts.remove(sessionId);
        if (timeout != null) {
            LOG.info("Upgrading session 0x" + Long.toHexString(sessionId));
            // Track global session, which will add to global session tracker
            // on leader and do nothing on learner. Need to start track global
            // session in leader now to update the session expire between
            // LeaderRequestProcessor and PrepRequestProcessor.
            trackSession(sessionId, timeout);
            // Track ongoing upgrading sessions, learner will use it to find
            // other sessions it has which are not in local and global sessions
            upgradingSessions.put(sessionId, timeout);
            localSessionTracker.removeSession(sessionId);
            return timeout;
        }
        return -1;
    }

    protected void removeLocalSession(long sessionId) {
         if (localSessionTracker == null) {
            return;
        }
        localSessionTracker.removeSession(sessionId);
    }

    public void checkGlobalSession(long sessionId, Object owner)
            throws KeeperException.SessionExpiredException,
            KeeperException.SessionMovedException {
        throw new UnsupportedOperationException();
    }

    public long getLocalSessionCount() {
        if (localSessionsWithTimeouts == null) {
            return 0;
        }
        return localSessionsWithTimeouts.size();
    }
}
