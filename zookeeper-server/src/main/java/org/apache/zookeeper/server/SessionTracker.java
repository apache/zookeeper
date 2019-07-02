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

import java.io.PrintWriter;
import java.util.Map;
import java.util.Set;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.SessionExpiredException;
import org.apache.zookeeper.KeeperException.SessionMovedException;

/**
 * This is the basic interface that ZooKeeperServer uses to track sessions. The
 * standalone and leader ZooKeeperServer use the same SessionTracker. The
 * FollowerZooKeeperServer uses a SessionTracker which is basically a simple
 * shell to track information to be forwarded to the leader.
 */
public interface SessionTracker {
    public static interface Session {
        long getSessionId();
        int getTimeout();
        boolean isClosing();
    }
    public static interface SessionExpirer {
        void expire(Session session);

        long getServerId();
    }

    long createSession(int sessionTimeout);

    /**
     * Track the session expire, not add to ZkDb.
     * @param id sessionId
     * @param to sessionTimeout
     * @return whether the session was newly tracked (if false, already tracked)
     */
    boolean trackSession(long id, int to);

    /**
     * Add the session to the local session map or global one in zkDB.
     * @param id sessionId
     * @param to sessionTimeout
     * @return whether the session was newly added (if false, already existed)
     */
    boolean commitSession(long id, int to);

    /**
     * @param sessionId
     * @param sessionTimeout
     * @return false if session is no longer active
     */
    boolean touchSession(long sessionId, int sessionTimeout);

    /**
     * Mark that the session is in the process of closing.
     * @param sessionId
     */
    void setSessionClosing(long sessionId);

    /**
     *
     */
    void shutdown();

    /**
     * @param sessionId
     */
    void removeSession(long sessionId);

    /**
     * @param sessionId
     * @return whether or not the SessionTracker is aware of this session
     */
    boolean isTrackingSession(long sessionId);

    /**
     * Checks whether the SessionTracker is aware of this session, the session
     * is still active, and the owner matches. If the owner wasn't previously
     * set, this sets the owner of the session.
     *
     * UnknownSessionException should never been thrown to the client. It is
     * only used internally to deal with possible local session from other
     * machine
     *
     * @param sessionId
     * @param owner
     */
    public void checkSession(long sessionId, Object owner)
            throws KeeperException.SessionExpiredException,
            KeeperException.SessionMovedException,
            KeeperException.UnknownSessionException;

    /**
     * Strictly check that a given session is a global session or not
     * @param sessionId
     * @param owner
     * @throws KeeperException.SessionExpiredException
     * @throws KeeperException.SessionMovedException
     */
    public void checkGlobalSession(long sessionId, Object owner)
            throws KeeperException.SessionExpiredException,
            KeeperException.SessionMovedException;

    void setOwner(long id, Object owner) throws SessionExpiredException;

    /**
     * Text dump of session information, suitable for debugging.
     * @param pwriter the output writer
     */
    void dumpSessions(PrintWriter pwriter);

    /**
     * Returns a mapping of time to session IDs that expire at that time.
     */
    Map<Long, Set<Long>> getSessionExpiryMap();

    /**
     * If this session tracker supports local sessions, return how many.
     * otherwise returns 0;
     */
    public long getLocalSessionCount();
}
