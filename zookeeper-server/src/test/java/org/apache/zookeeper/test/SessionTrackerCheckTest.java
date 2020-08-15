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

package org.apache.zookeeper.test;

import static org.junit.jupiter.api.Assertions.fail;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.zookeeper.KeeperException.SessionExpiredException;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.SessionTracker.Session;
import org.apache.zookeeper.server.SessionTracker.SessionExpirer;
import org.apache.zookeeper.server.ZooKeeperServerListener;
import org.apache.zookeeper.server.quorum.LeaderSessionTracker;
import org.apache.zookeeper.server.quorum.LearnerSessionTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validate various type of sessions against leader session tracker and learner
 * session tracker
 */
public class SessionTrackerCheckTest extends ZKTestCase {

    protected static final Logger LOG = LoggerFactory.getLogger(SessionTrackerCheckTest.class);
    public static final int TICK_TIME = 1000;
    public static final int CONNECTION_TIMEOUT = TICK_TIME * 10;

    private ConcurrentHashMap<Long, Integer> sessionsWithTimeouts = new ConcurrentHashMap<Long, Integer>();

    private class Expirer implements SessionExpirer {

        long sid;

        public Expirer(long sid) {
            this.sid = sid;
        }

        public void expire(Session session) {
        }

        public long getServerId() {
            return sid;
        }

    }

    @BeforeEach
    public void setUp() throws Exception {
        sessionsWithTimeouts.clear();
    }

    @AfterEach
    public void tearDown() throws Exception {
    }

    @Test
    public void testLearnerSessionTracker() throws Exception {
        Expirer expirer = new Expirer(1);
        // With local session on
        LearnerSessionTracker tracker = new LearnerSessionTracker(expirer, sessionsWithTimeouts, TICK_TIME, expirer.sid, true, testZKSListener());

        // Unknown session
        long sessionId = 0xb100ded;
        try {
            tracker.checkSession(sessionId, null);
            fail("Unknown session should have failed");
        } catch (SessionExpiredException e) {
            // Get expected exception
        }

        // Global session
        sessionsWithTimeouts.put(sessionId, CONNECTION_TIMEOUT);
        try {
            tracker.checkSession(sessionId, null);
        } catch (Exception e) {
            fail("Global session should not fail");
        }

        // Local session
        sessionId = tracker.createSession(CONNECTION_TIMEOUT);
        try {
            tracker.checkSession(sessionId, null);
        } catch (Exception e) {
            fail("Local session should not fail");
        }

        // During session upgrade
        sessionsWithTimeouts.put(sessionId, CONNECTION_TIMEOUT);
        try {
            tracker.checkSession(sessionId, null);
        } catch (Exception e) {
            fail("Session during upgrade should not fail");
        }

        // With local session off
        tracker = new LearnerSessionTracker(expirer, sessionsWithTimeouts, TICK_TIME, expirer.sid, false, testZKSListener());

        // Should be noop
        sessionId = 0xdeadbeef;
        try {
            tracker.checkSession(sessionId, null);
        } catch (Exception e) {
            fail("Should not get any exception");
        }

    }

    @Test
    public void testLeaderSessionTracker() throws Exception {
        Expirer expirer = new Expirer(2);
        // With local session on
        LeaderSessionTracker tracker = new LeaderSessionTracker(expirer, sessionsWithTimeouts, TICK_TIME, expirer.sid, true, testZKSListener());

        // Local session from other server
        long sessionId = ((expirer.sid + 1) << 56) + 1;
        try {
            tracker.checkSession(sessionId, null);
        } catch (Exception e) {
            fail("local session from other server should not fail");
        }

        // Track global session
        tracker.trackSession(sessionId, CONNECTION_TIMEOUT);
        try {
            tracker.checkSession(sessionId, null);
        } catch (Exception e) {
            fail("Global session should not fail");
        }
        try {
            tracker.checkGlobalSession(sessionId, null);
        } catch (Exception e) {
            fail("Global session should not fail " + e);
        }

        // Local session from the leader
        sessionId = tracker.createSession(CONNECTION_TIMEOUT);
        try {
            tracker.checkSession(sessionId, null);
        } catch (Exception e) {
            fail("Local session on the leader should not fail");
        }

        // During session upgrade
        tracker.trackSession(sessionId, CONNECTION_TIMEOUT);
        try {
            tracker.checkSession(sessionId, null);
        } catch (Exception e) {
            fail("Session during upgrade should not fail");
        }
        try {
            tracker.checkGlobalSession(sessionId, null);
        } catch (Exception e) {
            fail("Global session should not fail " + e);
        }

        // With local session off
        tracker = new LeaderSessionTracker(expirer, sessionsWithTimeouts, TICK_TIME, expirer.sid, false, testZKSListener());

        // Global session
        sessionId = 0xdeadbeef;
        tracker.trackSession(sessionId, CONNECTION_TIMEOUT);
        try {
            tracker.checkSession(sessionId, null);
        } catch (Exception e) {
            fail("Global session should not fail");
        }
        try {
            tracker.checkGlobalSession(sessionId, null);
        } catch (Exception e) {
            fail("Global session should not fail");
        }

        // Local session from other server
        sessionId = ((expirer.sid + 1) << 56) + 2;
        try {
            tracker.checkSession(sessionId, null);
            fail("local session from other server should fail");
        } catch (SessionExpiredException e) {
            // Got expected exception
        }

        // Local session from the leader
        sessionId = ((expirer.sid) << 56) + 2;
        try {
            tracker.checkSession(sessionId, null);
            fail("local session from the leader should fail");
        } catch (SessionExpiredException e) {
            // Got expected exception
        }

    }

    ZooKeeperServerListener testZKSListener() {
        return new ZooKeeperServerListener() {

            @Override
            public void notifyStopping(String errMsg, int exitCode) {

            }
        };
    }

}
