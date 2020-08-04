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

package org.apache.zookeeper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.SessionExpiredException;
import org.apache.zookeeper.KeeperException.SessionMovedException;
import org.apache.zookeeper.MultiOperationRecord;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.proto.CreateRequest;
import org.apache.zookeeper.proto.ReconfigRequest;
import org.apache.zookeeper.proto.RequestHeader;
import org.apache.zookeeper.proto.SetDataRequest;
import org.apache.zookeeper.server.ZooKeeperServer.ChangeRecord;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.Leader;
import org.apache.zookeeper.server.quorum.LeaderBeanTest;
import org.apache.zookeeper.server.quorum.LeaderZooKeeperServer;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.txn.ErrorTxn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class PrepRequestProcessorTest extends ClientBase {

    private static final Logger LOG = LoggerFactory.getLogger(PrepRequestProcessorTest.class);
    private static final int CONNECTION_TIMEOUT = 3000;
    private static String HOSTPORT = "127.0.0.1:" + PortAssignment.unique();
    private CountDownLatch pLatch;

    private ZooKeeperServer zks;
    private ServerCnxnFactory servcnxnf;
    private PrepRequestProcessor processor;
    private Request outcome;

    private boolean isReconfigEnabledPreviously;
    private boolean isStandaloneEnabledPreviously;

    @BeforeEach
    public void setup() throws Exception {
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        SyncRequestProcessor.setSnapCount(100);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);

        servcnxnf = ServerCnxnFactory.createFactory(PORT, -1);
        servcnxnf.startup(zks);
        assertTrue(ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server being up ");
        zks.sessionTracker = new MySessionTracker();

        isReconfigEnabledPreviously = QuorumPeerConfig.isReconfigEnabled();
        isStandaloneEnabledPreviously = QuorumPeerConfig.isStandaloneEnabled();
    }

    @AfterEach
    public void teardown() throws Exception {
        if (servcnxnf != null) {
            servcnxnf.shutdown();
        }
        if (zks != null) {
            zks.shutdown();
        }

        // reset the reconfig option
        QuorumPeerConfig.setReconfigEnabled(isReconfigEnabledPreviously);
        QuorumPeerConfig.setStandaloneEnabled(isStandaloneEnabledPreviously);
    }

    @Test
    public void testPRequest() throws Exception {
        pLatch = new CountDownLatch(1);
        processor = new PrepRequestProcessor(zks, new MyRequestProcessor());
        Request foo = new Request(null, 1L, 1, OpCode.create, ByteBuffer.allocate(3), null);
        processor.pRequest(foo);

        assertEquals(new ErrorTxn(KeeperException.Code.MARSHALLINGERROR.intValue()), outcome.getTxn(), "Request should have marshalling error");
        assertTrue(pLatch.await(5, TimeUnit.SECONDS), "request hasn't been processed in chain");
    }

    private Request createRequest(Record record, int opCode) throws IOException {
        return createRequest(record, opCode, 1L);
    }

    private Request createRequest(Record record, int opCode, long sessionId) throws IOException {
        return createRequest(record, opCode, sessionId, false);
    }

    private Request createRequest(Record record, int opCode, boolean admin) throws IOException {
        return createRequest(record, opCode, 1L, admin);
    }

    private Request createRequest(Record record, int opCode, long sessionId, boolean admin) throws IOException {
        // encoding
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
        record.serialize(boa, "request");
        baos.close();
        // Id
        List<Id> ids = Arrays.asList(admin ? new Id("super", "super user") : Ids.ANYONE_ID_UNSAFE);
        return new Request(null, sessionId, 0, opCode, ByteBuffer.wrap(baos.toByteArray()), ids);
    }

    private void process(List<Op> ops) throws Exception {
        pLatch = new CountDownLatch(1);
        processor = new PrepRequestProcessor(zks, new MyRequestProcessor());

        Record record = new MultiOperationRecord(ops);
        Request req = createRequest(record, OpCode.multi, false);

        processor.pRequest(req);
        assertTrue(pLatch.await(5, TimeUnit.SECONDS), "request hasn't been processed in chain");
    }

    /**
     * This test checks that a successful multi will change outstanding record
     * and failed multi shouldn't change outstanding record.
     */
    @Test
    public void testMultiOutstandingChange() throws Exception {
        zks.getZKDatabase().dataTree.createNode("/foo", new byte[0], Ids.OPEN_ACL_UNSAFE, 0, 0, 0, 0);

        assertNull(zks.outstandingChangesForPath.get("/foo"));

        process(Arrays.asList(Op.setData("/foo", new byte[0], -1)));

        ChangeRecord cr = zks.outstandingChangesForPath.get("/foo");
        assertNotNull(cr, "Change record wasn't set");
        assertEquals(1, cr.zxid, "Record zxid wasn't set correctly");

        process(Arrays.asList(Op.delete("/foo", -1)));
        cr = zks.outstandingChangesForPath.get("/foo");
        assertEquals(2, cr.zxid, "Record zxid wasn't set correctly");

        // It should fail and shouldn't change outstanding record.
        process(Arrays.asList(Op.delete("/foo", -1)));
        cr = zks.outstandingChangesForPath.get("/foo");
        // zxid should still be previous result because record's not changed.
        assertEquals(2, cr.zxid, "Record zxid wasn't set correctly");
    }

    @Test
    public void testReconfigWithAnotherOutstandingChange() throws Exception {
        QuorumPeerConfig.setReconfigEnabled(true);
        QuorumPeerConfig.setStandaloneEnabled(false);

        QuorumPeer qp = new QuorumPeer();
        QuorumVerifier quorumVerifierMock = mock(QuorumVerifier.class);
        when(quorumVerifierMock.getAllMembers()).thenReturn(LeaderBeanTest.getMockedPeerViews(qp.getId()));

        qp.setQuorumVerifier(quorumVerifierMock, false);
        FileTxnSnapLog snapLog = new FileTxnSnapLog(tmpDir, tmpDir);
        LeaderZooKeeperServer lzks = new LeaderZooKeeperServer(snapLog, qp, new ZKDatabase(snapLog));
        qp.leader = new Leader(qp, lzks);
        lzks.sessionTracker = new MySessionTracker();
        ZooKeeperServer.setDigestEnabled(true);
        processor = new PrepRequestProcessor(lzks, new MyRequestProcessor());

        Record record = new CreateRequest("/foo", "data".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT.toFlag());
        pLatch = new CountDownLatch(1);
        processor.pRequest(createRequest(record, OpCode.create, false));
        assertTrue(pLatch.await(5, TimeUnit.SECONDS), "request hasn't been processed in chain");

        String newMember = "server.0=localhost:" + PortAssignment.unique()  + ":" + PortAssignment.unique() + ":participant";
        record = new ReconfigRequest(null, null, newMember, 0);
        pLatch = new CountDownLatch(1);
        processor.pRequest(createRequest(record, OpCode.reconfig, true));
        assertTrue(pLatch.await(5, TimeUnit.SECONDS), "request hasn't been processed in chain");
        assertEquals(outcome.getHdr().getType(), OpCode.reconfig);   // Verifies that there was no error.
    }

    /**
     * ZOOKEEPER-2052:
     * This test checks that if a multi operation aborted, and during the multi there is side effect
     * that changed outstandingChangesForPath, after aborted the side effect should be removed and
     * everything should be restored correctly.
     */
    @Test
    public void testMultiRollbackNoLastChange() throws Exception {
        zks.getZKDatabase().dataTree.createNode("/foo", new byte[0], Ids.OPEN_ACL_UNSAFE, 0, 0, 0, 0);
        zks.getZKDatabase().dataTree.createNode("/foo/bar", new byte[0], Ids.OPEN_ACL_UNSAFE, 0, 0, 0, 0);

        assertNull(zks.outstandingChangesForPath.get("/foo"));

        // multi record:
        //   set "/foo" => succeed, leave a outstanding change
        //   delete "/foo" => fail, roll back change
        process(Arrays.asList(Op.setData("/foo", new byte[0], -1), Op.delete("/foo", -1)));

        // aborting multi shouldn't leave any record.
        assertNull(zks.outstandingChangesForPath.get("/foo"));
    }

    /**
     * Test ephemerals are deleted when the session is closed with
     * the newly added CloseSessionTxn in ZOOKEEPER-3145.
     */
    @Test
    public void testCloseSessionTxn() throws Exception {
        boolean before = ZooKeeperServer.isCloseSessionTxnEnabled();

        ZooKeeperServer.setCloseSessionTxnEnabled(true);
        try {
            // create a few ephemerals
            long ephemeralOwner = 1;
            DataTree dt = zks.getZKDatabase().dataTree;
            dt.createNode("/foo", new byte[0], Ids.OPEN_ACL_UNSAFE, ephemeralOwner, 0, 0, 0);
            dt.createNode("/bar", new byte[0], Ids.OPEN_ACL_UNSAFE, ephemeralOwner, 0, 0, 0);

            // close session
            RequestHeader header = new RequestHeader();
            header.setType(OpCode.closeSession);

            final FinalRequestProcessor frq = new FinalRequestProcessor(zks);
            final CountDownLatch latch = new CountDownLatch(1);
            processor = new PrepRequestProcessor(zks, new RequestProcessor() {
                @Override
                public void processRequest(Request request) {
                    frq.processRequest(request);
                    latch.countDown();
                }

                @Override
                public void shutdown() {
                    // TODO Auto-generated method stub
                }
            });
            processor.pRequest(createRequest(header, OpCode.closeSession, ephemeralOwner));

            assertTrue(latch.await(3, TimeUnit.SECONDS));

            // assert ephemerals are deleted
            assertEquals(null, dt.getNode("/foo"));
            assertEquals(null, dt.getNode("/bar"));
        } finally {
            ZooKeeperServer.setCloseSessionTxnEnabled(before);
        }
    }

    /**
     * It tests that PrepRequestProcessor will return BadArgument KeeperException
     * if the request path (if it exists) is not valid, e.g. empty string.
     */
    @Test
    public void testInvalidPath() throws Exception {
        pLatch = new CountDownLatch(1);
        processor = new PrepRequestProcessor(zks, new MyRequestProcessor());

        SetDataRequest record = new SetDataRequest("", new byte[0], -1);
        Request req = createRequest(record, OpCode.setData, false);
        processor.pRequest(req);
        pLatch.await();
        assertEquals(outcome.getHdr().getType(), OpCode.error);
        assertEquals(outcome.getException().code(), KeeperException.Code.BADARGUMENTS);
    }

    private class MyRequestProcessor implements RequestProcessor {

        @Override
        public void processRequest(Request request) {
            // getting called by PrepRequestProcessor
            outcome = request;
            pLatch.countDown();
        }
        @Override
        public void shutdown() {
            // TODO Auto-generated method stub

        }

    }

    private class MySessionTracker implements SessionTracker {

        @Override
        public boolean trackSession(long id, int to) {
            // TODO Auto-generated method stub
            return false;
        }
        @Override
        public boolean commitSession(long id, int to) {
            // TODO Auto-generated method stub
            return false;
        }
        @Override
        public void checkSession(long sessionId, Object owner) throws SessionExpiredException, SessionMovedException {
            // TODO Auto-generated method stub
        }
        @Override
        public long createSession(int sessionTimeout) {
            // TODO Auto-generated method stub
            return 0;
        }
        @Override
        public void dumpSessions(PrintWriter pwriter) {
            // TODO Auto-generated method stub

        }
        @Override
        public void removeSession(long sessionId) {
            // TODO Auto-generated method stub

        }
        public int upgradeSession(long sessionId) {
            // TODO Auto-generated method stub
            return 0;
        }
        @Override
        public void setOwner(long id, Object owner) throws SessionExpiredException {
            // TODO Auto-generated method stub

        }
        @Override
        public void shutdown() {
            // TODO Auto-generated method stub

        }
        @Override
        public boolean touchSession(long sessionId, int sessionTimeout) {
            // TODO Auto-generated method stub
            return false;
        }
        @Override
        public void setSessionClosing(long sessionId) {
            // TODO Auto-generated method stub
        }
        @Override
        public boolean isTrackingSession(long sessionId) {
            // TODO Auto-generated method stub
            return false;
        }
        @Override
        public void checkGlobalSession(long sessionId, Object owner) throws SessionExpiredException, SessionMovedException {
            // TODO Auto-generated method stub
        }
        @Override
        public Map<Long, Set<Long>> getSessionExpiryMap() {
            return new HashMap<Long, Set<Long>>();
        }
        @Override
        public long getLocalSessionCount() {
            return 0;
        }

        @Override
        public boolean isLocalSessionsEnabled() {
            return false;
        }
    }

}
