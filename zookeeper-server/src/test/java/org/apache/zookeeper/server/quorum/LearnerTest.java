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

import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.common.X509Exception;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.server.ExitCode;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.TxnHeader;
import org.apache.zookeeper.util.ServiceUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LearnerTest extends ZKTestCase {

    private static final File testData = new File(System.getProperty("test.data.dir", "src/test/resources/data"));

    static class SimpleLearnerZooKeeperServer extends LearnerZooKeeperServer {

        Learner learner;

        public SimpleLearnerZooKeeperServer(FileTxnSnapLog ftsl, QuorumPeer self) throws IOException {
            super(ftsl, 2000, 2000, 2000, -1, new ZKDatabase(ftsl), self);
        }

        @Override
        public Learner getLearner() {
            return learner;
        }

    }

    static class SimpleLearner extends Learner {

        SimpleLearner(FileTxnSnapLog ftsl) throws IOException {
            self = new QuorumPeer();
            zk = new SimpleLearnerZooKeeperServer(ftsl, self);
            ((SimpleLearnerZooKeeperServer) zk).learner = this;
        }

    }

    static class TestLearner extends Learner {

        private int passSocketConnectOnAttempt = 10;
        private int socketConnectAttempt = 0;
        private long timeMultiplier = 0;
        private Socket socketToBeCreated = null;
        private Set<InetSocketAddress> unreachableAddresses = emptySet();

        private void setTimeMultiplier(long multiplier) {
            timeMultiplier = multiplier;
        }

        private void setPassConnectAttempt(int num) {
            passSocketConnectOnAttempt = num;
        }

        protected long nanoTime() {
            return socketConnectAttempt * timeMultiplier;
        }

        private int getSockConnectAttempt() {
            return socketConnectAttempt;
        }

        private void setSocketToBeCreated(Socket socketToBeCreated) {
            this.socketToBeCreated = socketToBeCreated;
        }

        private void setUnreachableAddresses(Set<InetSocketAddress> unreachableAddresses) {
            this.unreachableAddresses = unreachableAddresses;
        }

        @Override
        protected void sockConnect(Socket sock, InetSocketAddress addr, int timeout) throws IOException {
            synchronized (this) {
                if (++socketConnectAttempt < passSocketConnectOnAttempt || unreachableAddresses.contains(addr)) {
                    throw new IOException("Test injected Socket.connect() error.");
                }
            }
        }

        @Override
        protected Socket createSocket() throws X509Exception, IOException {
            if (socketToBeCreated != null) {
                return socketToBeCreated;
            }
            return super.createSocket();
        }
    }

    @AfterEach
    public void cleanup() {
        System.clearProperty(QuorumPeer.CONFIG_KEY_MULTI_ADDRESS_ENABLED);
    }

    @Test
    public void connectionRetryTimeoutTest() throws Exception {
        assertThrows(IOException.class, () -> {
            Learner learner = new TestLearner();
            learner.self = new QuorumPeer();
            learner.self.setTickTime(2000);
            learner.self.setInitLimit(5);
            learner.self.setSyncLimit(2);

            // this addr won't even be used since we fake the Socket.connect
            InetSocketAddress addr = new InetSocketAddress(1111);

            // we expect this to throw an IOException since we're faking socket connect errors every time
            learner.connectToLeader(new MultipleAddresses(addr), "");
        });
    }

    @Test
    public void connectionInitLimitTimeoutTest() throws Exception {
        TestLearner learner = new TestLearner();
        learner.self = new QuorumPeer();
        learner.self.setTickTime(2000);
        learner.self.setInitLimit(5);
        learner.self.setSyncLimit(2);

        // this addr won't even be used since we fake the Socket.connect
        InetSocketAddress addr = new InetSocketAddress(1111);

        // pretend each connect attempt takes 4000 milliseconds
        learner.setTimeMultiplier((long) 4000 * 1000_000);

        learner.setPassConnectAttempt(5);

        // we expect this to throw an IOException since we're faking socket connect errors every time
        try {
            learner.connectToLeader(new MultipleAddresses(addr), "");
            fail("should have thrown IOException!");
        } catch (IOException e) {
            //good, wanted to see that, let's make sure we ran out of time
            assertTrue(learner.nanoTime() > 2000 * 5 * 1000_000);
            assertEquals(3, learner.getSockConnectAttempt());
        }
    }

    @Test
    public void shouldTryMultipleAddresses() throws Exception {
        System.setProperty(QuorumPeer.CONFIG_KEY_MULTI_ADDRESS_ENABLED, "true");
        TestLearner learner = new TestLearner();
        learner.self = new QuorumPeer();
        learner.self.setTickTime(2000);
        learner.self.setInitLimit(5);
        learner.self.setSyncLimit(2);

        // this addr won't even be used since we fake the Socket.connect
        InetSocketAddress addrA = new InetSocketAddress(1111);
        InetSocketAddress addrB = new InetSocketAddress(2222);
        InetSocketAddress addrC = new InetSocketAddress(3333);
        InetSocketAddress addrD = new InetSocketAddress(4444);

        // we will never pass (don't allow successful socker.connect) during this test
        learner.setPassConnectAttempt(100);

        // we expect this to throw an IOException since we're faking socket connect errors every time
        try {
            learner.connectToLeader(new MultipleAddresses(asList(addrA, addrB, addrC, addrD)), "");
            fail("should have thrown IOException!");
        } catch (IOException e) {
            //good, wanted to see the IOException, let's make sure we tried each address 5 times
            assertEquals(4 * 5, learner.getSockConnectAttempt());
        }
    }

    @Test
    public void multipleAddressesSomeAreFailing() throws Exception {
        System.setProperty(QuorumPeer.CONFIG_KEY_MULTI_ADDRESS_ENABLED, "true");
        TestLearner learner = new TestLearner();
        learner.self = new QuorumPeer();
        learner.self.setTickTime(2000);
        learner.self.setInitLimit(5);
        learner.self.setSyncLimit(2);

        // these addresses won't even be used since we fake the Socket.connect
        InetSocketAddress addrWorking = new InetSocketAddress(1111);
        InetSocketAddress addrBadA = new InetSocketAddress(2222);
        InetSocketAddress addrBadB = new InetSocketAddress(3333);
        InetSocketAddress addrBadC = new InetSocketAddress(4444);

        // we will emulate socket connection error for each 'bad' address
        learner.setUnreachableAddresses(new HashSet<>(asList(addrBadA, addrBadB, addrBadC)));

        // all connection attempts should succeed (if it is not an unreachable address)
        learner.setPassConnectAttempt(0);

        // initialize a mock socket, created by the Learner
        Socket mockSocket = mock(Socket.class);
        when(mockSocket.isConnected()).thenReturn(true);
        learner.setSocketToBeCreated(mockSocket);


        // we expect this to not throw an IOException since there is a single working address
        learner.connectToLeader(new MultipleAddresses(asList(addrBadA, addrBadB, addrBadC, addrWorking)), "");

        assertEquals(learner.getSocket(), mockSocket, "Learner connected to the wrong address");
    }

    @Test
    public void connectToLearnerMasterLimitTest() throws Exception {
        TestLearner learner = new TestLearner();
        learner.self = new QuorumPeer();
        learner.self.setTickTime(2000);
        learner.self.setInitLimit(2);
        learner.self.setSyncLimit(2);
        learner.self.setConnectToLearnerMasterLimit(5);

        InetSocketAddress addr = new InetSocketAddress(1111);
        learner.setTimeMultiplier((long) 4000 * 1000_000);
        learner.setPassConnectAttempt(5);

        try {
            learner.connectToLeader(new MultipleAddresses(addr), "");
            fail("should have thrown IOException!");
        } catch (IOException e) {
            assertTrue(learner.nanoTime() > 2000 * 5 * 1000_000);
            assertEquals(3, learner.getSockConnectAttempt());
        }
    }

    @Test
    public void syncTest(@TempDir File tmpDir) throws Exception {
        File tmpFile = File.createTempFile("test", ".dir", tmpDir);
        tmpFile.delete();
        FileTxnSnapLog ftsl = new FileTxnSnapLog(tmpFile, tmpFile);
        SimpleLearner sl = new SimpleLearner(ftsl);
        long startZxid = sl.zk.getLastProcessedZxid();

        // Set up bogus streams
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive oa = BinaryOutputArchive.getArchive(baos);
        sl.leaderOs = BinaryOutputArchive.getArchive(new ByteArrayOutputStream());

        // make streams and socket do something innocuous
        sl.bufferedOutput = new BufferedOutputStream(System.out);
        sl.sock = new Socket();

        // fake messages from the server
        QuorumPacket qp = new QuorumPacket(Leader.SNAP, 0, null, null);
        oa.writeRecord(qp, null);
        sl.zk.getZKDatabase().serializeSnapshot(oa);
        oa.writeString("BenWasHere", "signature");
        TxnHeader hdr = new TxnHeader(0, 0, 0, 0, ZooDefs.OpCode.create);
        CreateTxn txn = new CreateTxn("/foo", new byte[0], new ArrayList<ACL>(), false, sl.zk.getZKDatabase().getNode("/").stat.getCversion());
        ByteArrayOutputStream tbaos = new ByteArrayOutputStream();
        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(tbaos);
        hdr.serialize(boa, "hdr");
        txn.serialize(boa, "txn");
        tbaos.close();
        qp = new QuorumPacket(Leader.PROPOSAL, 1, tbaos.toByteArray(), null);
        oa.writeRecord(qp, null);

        // setup the messages to be streamed to follower
        sl.leaderIs = BinaryInputArchive.getArchive(new ByteArrayInputStream(baos.toByteArray()));

        try {
            sl.syncWithLeader(3);
        } catch (EOFException e) {
        }

        sl.zk.shutdown();
        sl = new SimpleLearner(ftsl);
        assertEquals(startZxid, sl.zk.getLastProcessedZxid());
    }

    @Test
    public void truncFailTest(@TempDir File tmpDir) throws Exception {
        final boolean[] exitProcCalled = {false};

        ServiceUtils.setSystemExitProcedure(new Consumer<Integer>() {
            @Override
            public void accept(Integer exitCode) {
                exitProcCalled[0] = true;
                assertThat("System.exit() was called with invalid exit code", exitCode, equalTo(ExitCode.QUORUM_PACKET_ERROR.getValue()));
            }
        });

        File tmpFile = File.createTempFile("test", ".dir", tmpDir);
        tmpFile.delete();
        FileTxnSnapLog txnSnapLog = new FileTxnSnapLog(tmpFile, tmpFile);
        SimpleLearner sl = new SimpleLearner(txnSnapLog);
        long startZxid = sl.zk.getLastProcessedZxid();

        // Set up bogus streams
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive oa = BinaryOutputArchive.getArchive(baos);
        sl.leaderOs = BinaryOutputArchive.getArchive(new ByteArrayOutputStream());

        // make streams and socket do something innocuous
        sl.bufferedOutput = new BufferedOutputStream(System.out);
        sl.sock = new Socket();

        // fake messages from the server
        QuorumPacket qp = new QuorumPacket(Leader.TRUNC, 0, null, null);
        oa.writeRecord(qp, null);

        // setup the messages to be streamed to follower
        sl.leaderIs = BinaryInputArchive.getArchive(new ByteArrayInputStream(baos.toByteArray()));

        try {
            sl.syncWithLeader(3);
        } catch (EOFException e) {
        }

        sl.zk.shutdown();

        assertThat("System.exit() should have been called", exitProcCalled[0], is(true));
    }

    @Test
    public void incompleteTruncSyncDoesNotCreateTxnLogGapOnReconnect(@TempDir File tmpDir) throws Exception {
        FileTxnSnapLog txnSnapLog = new FileTxnSnapLog(tmpDir, tmpDir);
        SimpleLearner learner = new SimpleLearner(txnSnapLog);
        ZKDatabase zkDb = learner.zk.getZKDatabase();

        txnSnapLog.save(zkDb.getDataTree(), zkDb.getSessionWithTimeOuts(), false);
        appendCreate(txnSnapLog, 1, "/retained");
        appendCreate(txnSnapLog, 2, "/discarded");
        txnSnapLog.commit();
        assertEquals(2, zkDb.loadDataBase());

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BinaryOutputArchive leaderOutput = BinaryOutputArchive.getArchive(bytes);
        leaderOutput.writeRecord(new QuorumPacket(Leader.TRUNC, 1, null, null), null);

        TxnHeader replacementHeader = new TxnHeader(1, 3, 2, 3, ZooDefs.OpCode.create);
        CreateTxn replacementTxn = new CreateTxn(
            "/replacement",
            new byte[0],
            ZooDefs.Ids.OPEN_ACL_UNSAFE,
            false,
            2);
        ByteArrayOutputStream proposalBytes = new ByteArrayOutputStream();
        BinaryOutputArchive proposalOutput = BinaryOutputArchive.getArchive(proposalBytes);
        replacementHeader.serialize(proposalOutput, "hdr");
        replacementTxn.serialize(proposalOutput, "txn");
        leaderOutput.writeRecord(new QuorumPacket(Leader.PROPOSAL, 2, proposalBytes.toByteArray(), null), null);
        leaderOutput.writeRecord(new QuorumPacket(Leader.COMMIT, 2, null, null), null);

        learner.leaderIs = BinaryInputArchive.getArchive(new ByteArrayInputStream(bytes.toByteArray()));
        learner.leaderOs = BinaryOutputArchive.getArchive(new ByteArrayOutputStream());
        learner.bufferedOutput = new BufferedOutputStream(new ByteArrayOutputStream());
        learner.sock = new Socket();

        assertThrows(EOFException.class, () -> learner.syncWithLeader(3));
        assertEquals(QuorumPeer.SyncMode.TRUNC, learner.self.getSyncMode());
        assertNotNull(zkDb.getNode("/replacement"));
        assertNull(zkDb.getNode("/discarded"));

        learner.shutdown();

        // Mirror the lazy reload performed by QuorumPeer.getLastLoggedZxid() on
        // the next connection without depending on how shutdown invalidates the database.
        if (!zkDb.isInitialized()) {
            zkDb.loadDataBase();
        }
        long nextZxid = zkDb.getDataTreeLastProcessedZxid() + 1;

        // Persist the transaction the leader would send next. If the incomplete
        // in-memory state was reused, nextZxid is 3 and the on-disk log has a gap.
        appendCreate(txnSnapLog, nextZxid, "/continued");
        txnSnapLog.commit();
        zkDb.close();

        // Simulate a process restart. Replaying the log must not detect a zxid gap.
        FileTxnSnapLog restartedTxnSnapLog = new FileTxnSnapLog(tmpDir, tmpDir);
        ZKDatabase restartedDb = new ZKDatabase(restartedTxnSnapLog);
        long restoredZxid = assertDoesNotThrow(restartedDb::loadDataBase);
        assertEquals(2, restoredZxid);
        assertNotNull(restartedDb.getNode("/retained"));
        assertNotNull(restartedDb.getNode("/continued"));
        assertNull(restartedDb.getNode("/discarded"));
        assertNull(restartedDb.getNode("/replacement"));
        restartedDb.close();
    }

    private static void appendCreate(FileTxnSnapLog txnSnapLog, long zxid, String path) throws IOException {
        TxnHeader header = new TxnHeader(1, (int) zxid, zxid, zxid, ZooDefs.OpCode.create);
        CreateTxn txn = new CreateTxn(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, false, (int) zxid);
        txnSnapLog.append(new Request(header, txn, null));
    }
}
