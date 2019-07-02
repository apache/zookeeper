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

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.test.TestUtils;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.Assert;
import org.junit.Test;

public class LearnerTest extends ZKTestCase {
    private static final File testData = new File(
        System.getProperty("test.data.dir", "src/test/resources/data"));

    static class SimpleLearnerZooKeeperServer extends LearnerZooKeeperServer {

        Learner learner;

        public SimpleLearnerZooKeeperServer(FileTxnSnapLog ftsl, QuorumPeer self)
                throws IOException {
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

    static class TimeoutLearner extends Learner {
        int passSocketConnectOnAttempt = 10;
        int socketConnectAttempt = 0;
        long timeMultiplier = 0;

        public void setTimeMultiplier(long multiplier) {
            timeMultiplier = multiplier;
        }

        public void setPassConnectAttempt(int num) {
            passSocketConnectOnAttempt = num;
        }

        protected long nanoTime() {
            return socketConnectAttempt * timeMultiplier;
        }
        
        protected int getSockConnectAttempt() {
            return socketConnectAttempt;
        }

        @Override
        protected void sockConnect(Socket sock, InetSocketAddress addr, int timeout) 
        throws IOException {
            if (++socketConnectAttempt < passSocketConnectOnAttempt)    {
                throw new IOException("Test injected Socket.connect() error.");
            }
        }
    }

    @Test(expected=IOException.class)
    public void connectionRetryTimeoutTest() throws Exception {
        Learner learner = new TimeoutLearner();
        learner.self = new QuorumPeer();
        learner.self.setTickTime(2000);
        learner.self.setInitLimit(5);
        learner.self.setSyncLimit(2);

        // this addr won't even be used since we fake the Socket.connect
        InetSocketAddress addr = new InetSocketAddress(1111);

        // we expect this to throw an IOException since we're faking socket connect errors every time
        learner.connectToLeader(addr, "");
    }
    @Test
    public void connectionInitLimitTimeoutTest() throws Exception {
        TimeoutLearner learner = new TimeoutLearner();
        learner.self = new QuorumPeer();
        learner.self.setTickTime(2000);
        learner.self.setInitLimit(5);
        learner.self.setSyncLimit(2);

        // this addr won't even be used since we fake the Socket.connect
        InetSocketAddress addr = new InetSocketAddress(1111);
        
        // pretend each connect attempt takes 4000 milliseconds
        learner.setTimeMultiplier((long)4000 * 1000000);
        
        learner.setPassConnectAttempt(5);

        // we expect this to throw an IOException since we're faking socket connect errors every time
        try {
            learner.connectToLeader(addr, "");
            Assert.fail("should have thrown IOException!");
        } catch (IOException e) {
            //good, wanted to see that, let's make sure we ran out of time
            Assert.assertTrue(learner.nanoTime() > 2000*5*1000000);
            Assert.assertEquals(3, learner.getSockConnectAttempt());
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
            } catch (EOFException e) {}

            sl.zk.shutdown();
            sl = new SimpleLearner(ftsl);
            Assert.assertEquals(startZxid, sl.zk.getLastProcessedZxid());
        } finally {
            TestUtils.deleteFileRecursively(tmpFile);
        }
    }
}
