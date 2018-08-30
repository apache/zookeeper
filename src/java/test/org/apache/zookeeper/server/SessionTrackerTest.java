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
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.server.SessionTrackerImpl.SessionImpl;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Testing zk client session logic in sessiontracker
 */
public class SessionTrackerTest extends ZKTestCase {

    private final long sessionId = 339900;
    private final int sessionTimeout = 3000;
    private FirstProcessor firstProcessor;
    private CountDownLatch latch;

    /**
     * Verify the create session call in the Leader.FinalRequestProcessor after
     * the session expiration.
     */
    @Test(timeout = 20000)
    public void testAddSessionAfterSessionExpiry() throws Exception {
        ZooKeeperServer zks = setupSessionTracker();

        latch = new CountDownLatch(1);
        zks.sessionTracker.trackSession(sessionId, sessionTimeout);
        SessionTrackerImpl sessionTrackerImpl = (SessionTrackerImpl) zks.sessionTracker;
        SessionImpl sessionImpl = sessionTrackerImpl.sessionsById
                .get(sessionId);
        Assert.assertNotNull("Sessionid:" + sessionId
                + " doesn't exists in sessiontracker", sessionImpl);

        // verify the session existence
        Object sessionOwner = new Object();
        sessionTrackerImpl.checkSession(sessionId, sessionOwner);

        // waiting for the session expiry
        latch.await(sessionTimeout * 2, TimeUnit.MILLISECONDS);

        // Simulating FinalRequestProcessor logic: create session request has
        // delayed and now reaches FinalRequestProcessor. Here the leader zk
        // will do sessionTracker.addSession(id, timeout)
        sessionTrackerImpl.trackSession(sessionId, sessionTimeout);
        try {
            sessionTrackerImpl.checkSession(sessionId, sessionOwner);
            Assert.fail("Should throw session expiry exception "
                    + "as the session has expired and closed");
        } catch (KeeperException.SessionExpiredException e) {
            // expected behaviour
        }
        Assert.assertTrue("Session didn't expired", sessionImpl.isClosing());
        Assert.assertFalse("Session didn't expired", sessionTrackerImpl
                .touchSession(sessionId, sessionTimeout));
        Assert.assertEquals(
                "Duplicate session expiry request has been generated", 1,
                firstProcessor.getCountOfCloseSessionReq());
    }

    /**
     * Verify the session closure request has reached PrepRequestProcessor soon
     * after session expiration by the session tracker
     */
    @Test(timeout = 20000)
    public void testCloseSessionRequestAfterSessionExpiry() throws Exception {
        ZooKeeperServer zks = setupSessionTracker();

        latch = new CountDownLatch(1);
        zks.sessionTracker.trackSession(sessionId, sessionTimeout);
        SessionTrackerImpl sessionTrackerImpl = (SessionTrackerImpl) zks.sessionTracker;
        SessionImpl sessionImpl = sessionTrackerImpl.sessionsById
                .get(sessionId);
        Assert.assertNotNull("Sessionid:" + sessionId
                + " doesn't exists in sessiontracker", sessionImpl);

        // verify the session existence
        Object sessionOwner = new Object();
        sessionTrackerImpl.checkSession(sessionId, sessionOwner);

        // waiting for the session expiry
        latch.await(sessionTimeout * 2, TimeUnit.MILLISECONDS);

        // Simulating close session request: removeSession() will be executed
        // while OpCode.closeSession
        sessionTrackerImpl.removeSession(sessionId);
        SessionImpl actualSession = sessionTrackerImpl.sessionsById
                .get(sessionId);
        Assert.assertNull("Session:" + sessionId
                + " still exists after removal", actualSession);
    }

    private ZooKeeperServer setupSessionTracker() throws IOException {
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        zks.setupRequestProcessors();
        firstProcessor = new FirstProcessor(zks, null);
        zks.firstProcessor = firstProcessor;

        // setup session tracker
        zks.createSessionTracker();
        zks.startSessionTracker();
        return zks;
    }

    // Mock processor used in zookeeper server
    private class FirstProcessor extends PrepRequestProcessor {
        private volatile int countOfCloseSessionReq = 0;

        public FirstProcessor(ZooKeeperServer zks,
                RequestProcessor nextProcessor) {
            super(zks, nextProcessor);
        }

        @Override
        public void processRequest(Request request) {
            // check session close request
            if (request.type == OpCode.closeSession) {
                countOfCloseSessionReq++;
                latch.countDown();
            }
        }

        // return number of session expiry calls
        int getCountOfCloseSessionReq() {
            return countOfCloseSessionReq;
        }
    }
}
