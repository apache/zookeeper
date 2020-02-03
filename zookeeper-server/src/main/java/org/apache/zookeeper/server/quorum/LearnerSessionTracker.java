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

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.zookeeper.KeeperException.SessionExpiredException;
import org.apache.zookeeper.KeeperException.SessionMovedException;
import org.apache.zookeeper.KeeperException.UnknownSessionException;
import org.apache.zookeeper.server.SessionTrackerImpl;
import org.apache.zookeeper.server.ZooKeeperServerListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The learner session tracker is used by learners (followers and observers) to
 * track zookeeper sessions which may or may not be echoed to the leader.  When
 * a new session is created it is saved locally in a wrapped
 * LocalSessionTracker.  It can subsequently be upgraded to a global session
 * as required.  If an upgrade is requested the session is removed from local
 * collections while keeping the same session ID.  It is up to the caller to
 * queue a session creation request for the leader.
 * A secondary function of the learner session tracker is to remember sessions
 * which have been touched in this service.  This information is passed along
 * to the leader with a ping.
 */
public class LearnerSessionTracker extends UpgradeableSessionTracker {

    private static final Logger LOG = LoggerFactory.getLogger(LearnerSessionTracker.class);

    private final SessionExpirer expirer;
    // Touch table for the global sessions
    private final AtomicReference<Map<Long, Integer>> touchTable = new AtomicReference<Map<Long, Integer>>();
    private final long serverId;
    private final AtomicLong nextSessionId = new AtomicLong();

    private final ConcurrentMap<Long, Integer> globalSessionsWithTimeouts;

    public LearnerSessionTracker(SessionExpirer expirer, ConcurrentMap<Long, Integer> sessionsWithTimeouts, int tickTime, long id, boolean localSessionsEnabled, ZooKeeperServerListener listener) {
        this.expirer = expirer;
        this.touchTable.set(new ConcurrentHashMap<Long, Integer>());
        this.globalSessionsWithTimeouts = sessionsWithTimeouts;
        this.serverId = id;
        nextSessionId.set(SessionTrackerImpl.initializeNextSessionId(serverId));

        this.localSessionsEnabled = localSessionsEnabled;
        if (this.localSessionsEnabled) {
            createLocalSessionTracker(expirer, tickTime, id, listener);
        }
    }

    public void removeSession(long sessionId) {
        if (localSessionTracker != null) {
            localSessionTracker.removeSession(sessionId);
        }
        globalSessionsWithTimeouts.remove(sessionId);
        touchTable.get().remove(sessionId);
    }

    public void start() {
        if (localSessionTracker != null) {
            localSessionTracker.start();
        }
    }

    public void shutdown() {
        if (localSessionTracker != null) {
            localSessionTracker.shutdown();
        }
    }

    public boolean isGlobalSession(long sessionId) {
        return globalSessionsWithTimeouts.containsKey(sessionId);
    }

    public boolean trackSession(long sessionId, int sessionTimeout) {
        // Learner doesn't track global session, do nothing here
        return false;
    }

    /**
     * Synchronized on this to avoid race condition of adding a local session
     * after committed global session, which may cause the same session being
     * tracked on this server and leader.
     */
    public synchronized boolean commitSession(long sessionId, int sessionTimeout) {
        boolean added = globalSessionsWithTimeouts.put(sessionId, sessionTimeout) == null;

        if (added) {
            // Only do extra logging so we know what kind of session this is
            // if we're supporting both kinds of sessions
            LOG.info("Committing global session 0x{}", Long.toHexString(sessionId));
        }

        // If the session moved before the session upgrade finished, it's
        // possible that the session will be added to the local session
        // again. Need to double check and remove it from local session
        // tracker when the global session is quorum committed, otherwise the
        // local session might be tracked both locally and on leader.
        //
        // This cannot totally avoid the local session being upgraded again
        // because there is still race condition between create another upgrade
        // request and process the createSession commit, and there is no way
        // to know there is a on flying createSession request because it might
        // be upgraded by other server which owns the session before move.
        if (localSessionsEnabled) {
            removeLocalSession(sessionId);
            finishedUpgrading(sessionId);
        }

        touchTable.get().put(sessionId, sessionTimeout);
        return added;
    }

    public boolean touchSession(long sessionId, int sessionTimeout) {
        if (localSessionsEnabled) {
            if (localSessionTracker.touchSession(sessionId, sessionTimeout)) {
                return true;
            }
            if (!isGlobalSession(sessionId) && !isUpgradingSession(sessionId)) {
                return false;
            }
        }
        touchTable.get().put(sessionId, sessionTimeout);
        return true;
    }

    public Map<Long, Integer> snapshot() {
        return touchTable.getAndSet(new ConcurrentHashMap<Long, Integer>());
    }

    public long createSession(int sessionTimeout) {
        if (localSessionsEnabled) {
            return localSessionTracker.createSession(sessionTimeout);
        }
        return nextSessionId.getAndIncrement();
    }

    public void checkSession(long sessionId, Object owner) throws SessionExpiredException, SessionMovedException {
        if (localSessionTracker != null) {
            try {
                localSessionTracker.checkSession(sessionId, owner);
                return;
            } catch (UnknownSessionException e) {
                // Check whether it's a global session. We can ignore those
                // because they are handled at the leader, but if not, rethrow.
                // We check local session status first to avoid race condition
                // with session upgrading.
                if (!isGlobalSession(sessionId)) {
                    throw new SessionExpiredException();
                }
            }
        }
    }

    public void setOwner(long sessionId, Object owner) throws SessionExpiredException {
        if (localSessionTracker != null) {
            try {
                localSessionTracker.setOwner(sessionId, owner);
                return;
            } catch (SessionExpiredException e) {
                // Check whether it's a global session. We can ignore those
                // because they are handled at the leader, but if not, rethrow.
                // We check local session status first to avoid race condition
                // with session upgrading.
                if (!isGlobalSession(sessionId)) {
                    throw e;
                }
            }
        }
    }

    public void dumpSessions(PrintWriter pwriter) {
        if (localSessionTracker != null) {
            pwriter.print("Local ");
            localSessionTracker.dumpSessions(pwriter);
        }
        pwriter.print("Global Sessions(");
        pwriter.print(globalSessionsWithTimeouts.size());
        pwriter.println("):");
        SortedSet<Long> sessionIds = new TreeSet<Long>(globalSessionsWithTimeouts.keySet());
        for (long sessionId : sessionIds) {
            pwriter.print("0x");
            pwriter.print(Long.toHexString(sessionId));
            pwriter.print("\t");
            pwriter.print(globalSessionsWithTimeouts.get(sessionId));
            pwriter.println("ms");
        }
    }

    public void setSessionClosing(long sessionId) {
        // Global sessions handled on the leader; this call is a no-op if
        // not tracked as a local session so safe to call in both cases.
        if (localSessionTracker != null) {
            localSessionTracker.setSessionClosing(sessionId);
        }
    }

    @Override
    public Map<Long, Set<Long>> getSessionExpiryMap() {
        return new HashMap<Long, Set<Long>>();
    }

}
