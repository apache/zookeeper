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
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.test.TestUtils;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.TxnHeader;
import org.apache.zookeeper.util.ServiceUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
    public void syncTest() throws Exception {
        File tmpFile = File.createTempFile("test", ".dir", testData);
        tmpFile.delete();
        try {
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
        } finally {
            TestUtils.deleteFileRecursively(tmpFile);
        }
    }

    @Test
    public void truncFailTest() throws Exception {
        final boolean[] exitProcCalled = {false};

        ServiceUtils.setSystemExitProcedure(new Consumer<Integer>() {
            @Override
            public void accept(Integer exitCode) {
                exitProcCalled[0] = true;
                assertThat("System.exit() was called with invalid exit code", exitCode, equalTo(ExitCode.QUORUM_PACKET_ERROR.getValue()));
            }
        });

        File tmpFile = File.createTempFile("test", ".dir", testData);
        tmpFile.delete();
        try {
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
        } finally {
            TestUtils.deleteFileRecursively(tmpFile);
        }
    }
}
