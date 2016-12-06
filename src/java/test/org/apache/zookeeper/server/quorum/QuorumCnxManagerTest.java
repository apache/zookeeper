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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.security.sasl.SaslException;

import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.auth.NullQuorumAuthLearner;
import org.apache.zookeeper.server.quorum.auth.NullQuorumAuthServer;
import org.apache.zookeeper.server.quorum.auth.QuorumAuth;
import org.apache.zookeeper.server.quorum.auth.QuorumAuthLearner;
import org.apache.zookeeper.server.quorum.auth.QuorumAuthServer;
import org.apache.zookeeper.server.quorum.auth.SaslQuorumAuthLearner;
import org.apache.zookeeper.server.quorum.auth.SaslQuorumAuthServer;
import org.apache.zookeeper.server.quorum.flexible.QuorumMaj;
import org.apache.zookeeper.test.ClientBase;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuorumCnxManagerTest extends ZKTestCase {
    private static final Logger LOG = LoggerFactory.getLogger(QuorumCnxManagerTest.class);
    private int count;
    private HashMap<Long,QuorumServer> peers;
    private int peerQuorumPort[];
    private int peerClientPort[];
    private ThreadPoolExecutor executor;
    /**
     * The maximum number of threads to allow in the connectionExecutors thread
     * pool which will be used to initiate quorum server connections. Defaulting to 20.
     * TODO: Need to tune this param.
     */
    private final int quorumCnxnThreadsSize = 20;
    private Set<String> authzHosts;

    private static File saslConfigFile = null;

    @BeforeClass
    public static void setupSasl() throws Exception {
        String jaasEntries = new String(""
                + "QuorumServer {\n"
                + "       org.apache.zookeeper.server.auth.DigestLoginModule required\n"
                + "       user_test=\"mypassword\";\n"
                + "};\n"
                + "QuorumLearner {\n"
                + "       org.apache.zookeeper.server.auth.DigestLoginModule required\n"
                + "       username=\"test\"\n"
                + "       password=\"mypassword\";\n"
                + "};\n"
                + "QuorumLearnerInvalid {\n"
                + "       org.apache.zookeeper.server.auth.DigestLoginModule required\n"
                + "       username=\"test\"\n"
                + "       password=\"invalid\";\n"
                + "};\n");

        saslConfigFile = File.createTempFile("jaas.", ".conf");
        FileWriter fwriter = new FileWriter(saslConfigFile);
        fwriter.write(jaasEntries);
        fwriter.close();
        System.setProperty("java.security.auth.login.config",
                           saslConfigFile.getAbsolutePath());
    }

    @AfterClass
    public static void cleanupSasl() throws Exception {
        if (saslConfigFile != null) {
            saslConfigFile.delete();
        }
    }

    @Before
    public void setUp() throws Exception {
        this.count = 3;
        this.peers = new HashMap<Long,QuorumServer>(count);
        peerQuorumPort = new int[count];
        peerClientPort = new int[count];
        authzHosts = new HashSet<String>();
        for(int i = 0; i < count; i++) {
            peerQuorumPort[i] = PortAssignment.unique();
            peerClientPort[i] = PortAssignment.unique();
            QuorumServer qs = new QuorumServer(i, "0.0.0.0",
                    peerQuorumPort[i], PortAssignment.unique(), null);
            peers.put(Long.valueOf(i), qs);
            authzHosts.add(qs.hostname);
        }
        executor = new ThreadPoolExecutor(3, 10,
                60, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
    }

    @After
    public void tearDown() throws Exception {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test(timeout = 30000)
    public void testNoAuthConnection() throws Exception {
        QuorumCnxManager peer0 = createAndStartManager(0);
        QuorumCnxManager peer1 = createAndStartManager(1);

        peer0.connectOne(1);
        assertEventuallyConnected(peer0, 1);
    }

    @Test(timeout = 30000)
    public void testAuthConnection() throws Exception {
        QuorumCnxManager peer0 = createAndStartManager(0, "QuorumServer",
                                                       "QuorumLearner", true, true);
        QuorumCnxManager peer1 = createAndStartManager(1, "QuorumServer",
                                                       "QuorumLearner", true, true);
        peer0.connectOne(1);
        assertEventuallyConnected(peer0, 1);
    }

    /**
     * Peer0 has no auth configured, Peer1 has auth configured.
     * Peer1 connects to peer0, because null auth server sees an auth packet and connection succeeds.
     * Peer0 connects to peer1, but connection isn't initiated because
     * peer0's sid is lower than peer1's
     */
    @Test(timeout = 30000)
    public void testClientAuthAgainstNoAuthServerWithLowerSid()
            throws Exception {
        QuorumCnxManager peer0 = createAndStartManager(0);
        QuorumCnxManager peer1 = createAndStartManager(1, "QuorumServer",
                                                       "QuorumLearner", false, false);
        peer1.connectOne(0);
        peer0.connectOne(1);
        assertEventuallyConnected(peer0, 1);
    }

    /**
     * Peer0 has auth configured, Peer1 has no auth configured.
     * Peer1 connects to peer0, but is disconnected, because peer1's sid is
     * higher than peer0.
     * Peer0 connects to peer1, but is disconnected, because peer1 cannot
     * handle auth.
     */
    @Test(timeout = 30000)
    public void testClientAuthAgainstNoAuthServerWithHigherSid()
            throws Exception {
        QuorumCnxManager peer0 = createAndStartManager(0, "QuorumServer",
                                                       "QuorumLearner", false, false);
        QuorumCnxManager peer1 = createAndStartManager(1);
        peer0.connectOne(1);
        peer1.connectOne(0);
        assertEventuallyConnected(peer0, 1);
    }

    /**
     * No auth learner connects to a server that requires auth, when the server
     * has a higher sid.
     * The connection should fail in both directions.
     */
    @Test(timeout = 30000)
    public void testNoAuthLearnerConnectToAuthRequiredServerWithLowerSid()
            throws Exception {
        QuorumCnxManager peer0 = createAndStartManager(0, "QuorumServer",
                                                       "QuorumLearner", true, true);
        QuorumCnxManager peer1 = createAndStartManager(1);
        peer0.connectOne(1);
        peer1.connectOne(0);
        assertEventuallyNotConnected(peer0, 1);
    }

    /**
     * No auth learner connects to a server that requires auth, when the server
     * has a higher sid.
     * The connection should fail in both directions.
     */
    @Test(timeout = 30000)
    public void testNoAuthLearnerConnectToAuthRequiredServerWithHigherSid()
            throws Exception {
        QuorumCnxManager peer0 = createAndStartManager(0);
        QuorumCnxManager peer1 = createAndStartManager(1, "QuorumServer",
                                                       "QuorumLearner", true, true);
        peer0.connectOne(1);
        peer1.connectOne(0);
        assertEventuallyNotConnected(peer0, 1);
    }

    /**
     * An auth learner connects to a auth server, but the credentials are bad.
     * The peer with the higher sid has the bad credentials.
     * The connection will be denied.
     */
    @Test(timeout = 30000)
    public void testAuthLearnerBadCredToAuthRequiredServerWithLowerSid()
            throws Exception {
        QuorumCnxManager peer0 = createAndStartManager(0,  "QuorumServer",
                                                       "QuorumLearner", true, true);
        QuorumCnxManager peer1 = createAndStartManager(1, "QuorumServer",
                                                       "QuorumLearnerInvalid", true, true);
        peer0.connectOne(1);
        peer1.connectOne(0);

        assertEventuallyNotConnected(peer0, 1);
    }

    /**
     * An auth learner connects to a auth server, but the credentials are bad.
     * The peer with the lower sid has the bad credentials.
     * The connection will work, because peer1 is connecting to peer0.
     */
    @Test(timeout = 30000)
    public void testAuthLearnerBadCredToAuthRequiredServerWithHigherSid()
            throws Exception {
        QuorumCnxManager peer0 = createAndStartManager(0,  "QuorumServer",
                                                       "QuorumLearnerInvalid", true, true);
        QuorumCnxManager peer1 = createAndStartManager(1, "QuorumServer",
                                                       "QuorumLearner", true, true);
        peer0.connectOne(1);
        peer1.connectOne(0);
        assertEventuallyConnected(peer0, 1);
        assertEventuallyConnected(peer1, 0);
    }

    /**
     * An auth learner connects to a auth server, but the credentials are bad.
     * The connection should fail in both directions.
     */
    @Test(timeout = 30000)
    public void testAuthLearnerBadCredToNoAuthServerWithHigherSid() throws Exception {
        QuorumCnxManager peer0 = createAndStartManager(0, "QuorumServer",
                "QuorumLearner", false, false);
        QuorumCnxManager peer1 = createAndStartManager(1, "QuorumServer",
                "QuorumLearnerInvalid", true, true);
        peer1.connectOne(0);
        assertEventuallyNotConnected(peer1, 0);
    }

    /**
     * An auth learner connects to a auth server, but the credentials are bad.
     * The peer with the lower sid has the bad credentials.
     * The connection will work, because peer0 is connecting to peer1 and peer1
     * server doesn't require sasl
     */
    @Test(timeout = 30000)
    public void testAuthLearnerBadCredToNoAuthServerWithLowerSid() throws Exception {
        QuorumCnxManager peer0 = createAndStartManager(0, "QuorumServer",
                "QuorumLearnerInvalid", true, true);
        QuorumCnxManager peer1 = createAndStartManager(1, "QuorumServer",
                "QuorumLearner", false, true);
        peer0.connectOne(1);
        assertEventuallyConnected(peer0, 1);
        assertEventuallyConnected(peer1, 0);
    }

    /**
     * Test verifies that the LearnerHandler should authenticate the connecting
     * quorumpeer. Here its simulating authentication failure and it should throw
     * SaslException
     */
    @Test(timeout = 30000)
    public void testLearnerHandlerAuthFailed() throws Exception {
        File testData = ClientBase.createTmpDir();
        Socket leaderSocket = getSocketPair();
        File tmpDir = File.createTempFile("test", ".dir", testData);
        tmpDir.delete();
        tmpDir.mkdir();
        Leader leader = null;
        QuorumPeer peer = createQuorumPeer(tmpDir, true, false, true,
                "QuorumLearner", "QuorumServer",
                QuorumAuth.QUORUM_KERBEROS_SERVICE_PRINCIPAL_DEFAULT_VALUE);
        leader = createLeader(tmpDir, peer);
        peer.leader = leader;

        // authentication failed as qpserver didn't get auth packet from qpclient.
        try {
            new LearnerHandler(leaderSocket,
                    new BufferedInputStream(leaderSocket.getInputStream()), leader);
            Assert.fail("Must throw exception as there is an authentication failure");
        } catch (SaslException e){
            Assert.assertEquals("Mistakely added to learners", 0,
                    leader.getLearners().size());
        }
        ClientBase.recursiveDelete(testData);
    }

    /**
     * Test verifies that the Leader should authenticate the connecting learner
     * quorumpeer. After the successful authentication it should add this
     * learner to the learnerHandler list.
     */
    @Test(timeout = 30000)
    public void testAuthLearnerConnectsToServerWithAuthRequired()
            throws Exception {
        File testDataLearner = ClientBase.createTmpDir();
        File tmpDir = File.createTempFile("test", ".dir", testDataLearner);
        tmpDir.delete();
        FileTxnSnapLog ftsl = new FileTxnSnapLog(tmpDir, tmpDir);
        QuorumPeer learnerPeer = createQuorumPeer(tmpDir, true, true, true,
                "QuorumLearner", "QuorumServer",
                QuorumAuth.QUORUM_KERBEROS_SERVICE_PRINCIPAL_DEFAULT_VALUE);
        SimpleLearner sl = new SimpleLearner(ftsl, learnerPeer);

        File testDataLeader = ClientBase.createTmpDir();
        tmpDir = File.createTempFile("test", ".dir", testDataLeader);
        tmpDir.delete();
        tmpDir.mkdir();
        Leader leader = null;
        QuorumPeer peer = createQuorumPeer(tmpDir, true, true, true, "QuorumLearner",
                "QuorumServer",
                QuorumAuth.QUORUM_KERBEROS_SERVICE_PRINCIPAL_DEFAULT_VALUE);
        CountDownLatch learnerLatch = new CountDownLatch(1);
        leader = createSimpleLeader(tmpDir, peer, learnerLatch);
        peer.leader = leader;

        startLearnerCnxAcceptorThread(leader);
        LOG.info("Start establishing a connection with the Leader");
        String hostname = getLeaderHostname(peer);
        sl.connectToLeader(peer.getQuorumAddress(), hostname);
        // wait till leader socket soTimeout period
        Assert.assertTrue("Leader should accept the auth learner connection",
                learnerLatch.await(leader.self.tickTime * leader.self.initLimit + 1000,
                        TimeUnit.MILLISECONDS));
        Assert.assertEquals("Failed to added the learner", 1,
                leader.getLearners().size());
        ClientBase.recursiveDelete(testDataLearner);
        ClientBase.recursiveDelete(testDataLeader);
    }

    private String getLeaderHostname(QuorumPeer peer) {
        String hostname = null;
        for (QuorumServer p : peer.getView().values()) {
            if (p.id == peer.getId()) {
                hostname = p.hostname;
                break;
            }
        }
        Assert.assertNotNull("Didn't find leader", hostname);
        return hostname;
    }

    /**
     * Test verifies that the Leader should authenticate the connecting learner
     * quorumpeer. After the successful authentication it should add this
     * learner to the learnerHandler list.
     */
    @Test(timeout = 30000)
    public void testAuthLearnerConnectsToServerWithAuthNotRequired()
            throws Exception {
        File testDataLearner = ClientBase.createTmpDir();
        File tmpDir = File.createTempFile("test", ".dir", testDataLearner);
        tmpDir.delete();
        FileTxnSnapLog ftsl = new FileTxnSnapLog(tmpDir, tmpDir);
        QuorumPeer learnerPeer = createQuorumPeer(tmpDir, true, true, true,
                "QuorumLearner", "QuorumServer",
                QuorumAuth.QUORUM_KERBEROS_SERVICE_PRINCIPAL_DEFAULT_VALUE);
        SimpleLearner sl = new SimpleLearner(ftsl, learnerPeer);

        File testDataLeader = ClientBase.createTmpDir();
        tmpDir = File.createTempFile("test", ".dir", testDataLeader);
        tmpDir.delete();
        tmpDir.mkdir();
        Leader leader = null;
        QuorumPeer peer = createQuorumPeer(tmpDir, true, true, false, "QuorumLearner",
                "QuorumServer",
                QuorumAuth.QUORUM_KERBEROS_SERVICE_PRINCIPAL_DEFAULT_VALUE);
        CountDownLatch learnerLatch = new CountDownLatch(1);
        leader = createSimpleLeader(tmpDir, peer, learnerLatch);
        peer.leader = leader;

        startLearnerCnxAcceptorThread(leader);
        LOG.info("Start establishing a connection with the Leader");
        String hostname = getLeaderHostname(peer);
        sl.connectToLeader(peer.getQuorumAddress(), hostname);
        // wait till leader socket soTimeout period
        Assert.assertTrue("Leader should accept the auth learner connection",
                learnerLatch.await(leader.self.tickTime * leader.self.initLimit + 1000,
                        TimeUnit.MILLISECONDS));
        Assert.assertEquals("Failed to added the learner", 1,
                leader.getLearners().size());
        ClientBase.recursiveDelete(testDataLearner);
        ClientBase.recursiveDelete(testDataLeader);
    }

    private void startLearnerCnxAcceptorThread(Leader leader)
            throws InterruptedException {
        final CountDownLatch cnxAcceptorWatcher = new CountDownLatch(1);
        leader.cnxAcceptor = leader.new LearnerCnxAcceptor(){
            @Override
            public void run() {
                cnxAcceptorWatcher.countDown();
                super.run();
            }
        };
        leader.cnxAcceptor.start();
        // waiting to start the thread
        Assert.assertTrue("Failed to start leader.cnxAcceptor thread!",
                cnxAcceptorWatcher.await(15, TimeUnit.SECONDS));
        LOG.info("Started leader.cnxAcceptor:{} thread, state:{}",
                leader.cnxAcceptor.getName(), leader.cnxAcceptor.getState());
    }

    /**
     * Test verifies that the Auth enabled Learner is connecting to a Null Auth
     * Leader server. Learner is failing to get an auth response from Null Auth
     * Leader and fails the connection establishment.
     */
    @Test(timeout = 30000)
    public void testAuthLearnerConnectsToNullAuthServer()
            throws Exception {
        File testDataLearner = ClientBase.createTmpDir();
        File tmpDir = File.createTempFile("test", ".dir", testDataLearner);
        tmpDir.delete();
        FileTxnSnapLog ftsl = new FileTxnSnapLog(tmpDir, tmpDir);
        QuorumPeer learnerPeer = createQuorumPeer(tmpDir, true, true, true,
                "QuorumLearner", "QuorumServer",
                QuorumAuth.QUORUM_KERBEROS_SERVICE_PRINCIPAL_DEFAULT_VALUE);
        SimpleLearner sl = new SimpleLearner(ftsl, learnerPeer);

        File testDataLeader = ClientBase.createTmpDir();
        tmpDir = File.createTempFile("test", ".dir", testDataLeader);
        tmpDir.delete();
        tmpDir.mkdir();
        Leader leader = null;
        QuorumPeer peer = createQuorumPeer(tmpDir, false, false, false,
                "QuorumLearner", "QuorumServer",
                QuorumAuth.QUORUM_KERBEROS_SERVICE_PRINCIPAL_DEFAULT_VALUE);
        CountDownLatch learnerLatch = new CountDownLatch(1);
        leader = createSimpleLeader(tmpDir, peer, learnerLatch);
        peer.leader = leader;

        startLearnerCnxAcceptorThread(leader);
        LOG.info("Start establishing a connection with the Leader");

        try {
            String hostname = getLeaderHostname(peer);
            sl.connectToLeader(peer.getQuorumAddress(), hostname);
            Assert.fail("Must throw exception as server doesn't supports authentication");
        } catch (IOException e) {
            // expected
            Assert.assertTrue("Leader should accept the auth learner connection",
                    learnerLatch.await(leader.self.tickTime * leader.self.initLimit + 500,
                            TimeUnit.MILLISECONDS));
        }

        ClientBase.recursiveDelete(testDataLearner);
        ClientBase.recursiveDelete(testDataLeader);
    }

    /**
     * Test verifies that the No Auth enabled Learner is connecting to a No Auth
     * Leader server. Learner should be able to establish a connection with
     * Leader as auth is not required.
     */
    @Test(timeout = 30000)
    public void testNoAuthLearnerConnectsToServerWithAuthNotRequired()
            throws Exception {
        File testDataLearner = ClientBase.createTmpDir();
        File tmpDir = File.createTempFile("test", ".dir", testDataLearner);
        tmpDir.delete();
        FileTxnSnapLog ftsl = new FileTxnSnapLog(tmpDir, tmpDir);
        QuorumPeer learnerPeer = createQuorumPeer(tmpDir, true, false, false,
                "QuorumLearner", "QuorumServer", "");
        SimpleLearner sl = new SimpleLearner(ftsl, learnerPeer);

        File testDataLeader = ClientBase.createTmpDir();
        tmpDir = File.createTempFile("test", ".dir", testDataLeader);
        tmpDir.delete();
        tmpDir.mkdir();
        Leader leader = null;
        QuorumPeer peer = createQuorumPeer(tmpDir, true, false, false, "QuorumLearner",
                "QuorumServer",
                QuorumAuth.QUORUM_KERBEROS_SERVICE_PRINCIPAL_DEFAULT_VALUE);
        CountDownLatch learnerLatch = new CountDownLatch(1);
        leader = createSimpleLeader(tmpDir, peer, learnerLatch);
        peer.leader = leader;

        startLearnerCnxAcceptorThread(leader);
        LOG.info("Start establishing a connection with the Leader");
        String hostname = getLeaderHostname(peer);
        sl.connectToLeader(peer.getQuorumAddress(), hostname);

        Assert.assertTrue("Leader should accept no auth learner connection",
                learnerLatch.await(leader.self.tickTime * leader.self.initLimit + 1000,
                        TimeUnit.MILLISECONDS));
        ClientBase.recursiveDelete(testDataLearner);
        ClientBase.recursiveDelete(testDataLeader);
    }

    /**
     * Test verifies that the No Auth enabled Learner is connecting to a No Auth
     * Leader server. Learner shouldn't be able to establish a connection with
     * Leader as auth as auth is required.
     */
    @Test(timeout = 30000)
    public void testNoAuthLearnerConnectsToServerWithAuthRequired()
            throws Exception {
        File testDataLearner = ClientBase.createTmpDir();
        File tmpDir = File.createTempFile("test", ".dir", testDataLearner);
        tmpDir.delete();
        FileTxnSnapLog ftsl = new FileTxnSnapLog(tmpDir, tmpDir);
        QuorumPeer learnerPeer = createQuorumPeer(tmpDir, true, false, false,
                "QuorumLearner", "QuorumServer", "");
        SimpleLearner sl = new SimpleLearner(ftsl, learnerPeer);

        File testDataLeader = ClientBase.createTmpDir();
        tmpDir = File.createTempFile("test", ".dir", testDataLeader);
        tmpDir.delete();
        tmpDir.mkdir();
        Leader leader = null;
        QuorumPeer peer = createQuorumPeer(tmpDir, true, true, true, "QuorumLearner",
                "QuorumServer",
                QuorumAuth.QUORUM_KERBEROS_SERVICE_PRINCIPAL_DEFAULT_VALUE);
        CountDownLatch learnerLatch = new CountDownLatch(1);
        leader = createSimpleLeader(tmpDir, peer, learnerLatch);
        peer.leader = leader;

        startLearnerCnxAcceptorThread(leader);
        LOG.info("Start establishing a connection with the Leader");
        String hostname = getLeaderHostname(peer);
        sl.connectToLeader(peer.getQuorumAddress(), hostname);
        Assert.assertFalse("Leader shouldn't accept no auth learner connection",
                learnerLatch.await(leader.self.tickTime * leader.self.initLimit + 1000,
                        TimeUnit.MILLISECONDS));
        ClientBase.recursiveDelete(testDataLearner);
        ClientBase.recursiveDelete(testDataLeader);
    }

    /**
     * Test verifies that the No Auth enabled Learner is connecting to a No Auth
     * Leader server. Learner should be able to establish a connection with
     * Leader as auth is not required.
     */
    @Test(timeout = 30000)
    public void testNoAuthLearnerConnectsToNullAuthServer()
            throws Exception {
        File testDataLearner = ClientBase.createTmpDir();
        File tmpDir = File.createTempFile("test", ".dir", testDataLearner);
        tmpDir.delete();
        FileTxnSnapLog ftsl = new FileTxnSnapLog(tmpDir, tmpDir);
        QuorumPeer learnerPeer = createQuorumPeer(tmpDir, true, false, false,
                "QuorumLearner", "QuorumServer", "");
        SimpleLearner sl = new SimpleLearner(ftsl, learnerPeer);

        File testDataLeader = ClientBase.createTmpDir();
        tmpDir = File.createTempFile("test", ".dir", testDataLeader);
        tmpDir.delete();
        tmpDir.mkdir();
        Leader leader = null;
        QuorumPeer peer = createQuorumPeer(tmpDir, false, false, false, "", "",
                "");
        CountDownLatch learnerLatch = new CountDownLatch(1);
        leader = createSimpleLeader(tmpDir, peer, learnerLatch);
        peer.leader = leader;

        startLearnerCnxAcceptorThread(leader);
        LOG.info("Start establishing a connection with the Leader");
        String hostname = getLeaderHostname(peer);
        sl.connectToLeader(peer.getQuorumAddress(), hostname);

        Assert.assertTrue("Leader should accept no auth learner connection",
                learnerLatch.await(leader.self.tickTime * leader.self.initLimit + 1000,
                        TimeUnit.MILLISECONDS));
        ClientBase.recursiveDelete(testDataLearner);
        ClientBase.recursiveDelete(testDataLeader);
    }

    /**
     * SaslQuorumAuthServer throws exception on receiving an invalid quorum
     * auth packet.
     */
    @Test(timeout = 30000)
    public void testSaslQuorumAuthServerWithInvalidQuorumAuthPacket()
            throws Exception {
        Socket socket = getSocketPair();
        DataOutputStream dout = new DataOutputStream(socket.getOutputStream());
        BufferedOutputStream bufferedOutput = new BufferedOutputStream(dout);
        BinaryOutputArchive boa = BinaryOutputArchive
                .getArchive(bufferedOutput);
        QuorumAuthPacket authPacket = QuorumAuth
                .createPacket(QuorumAuth.Status.IN_PROGRESS, null);
        authPacket.setMagic(Long.MAX_VALUE); // invalid magic number
        boa.writeRecord(authPacket, null);
        bufferedOutput.flush();
        QuorumAuthServer authServer = new SaslQuorumAuthServer(true,
                "QuorumServer", authzHosts);
        BufferedInputStream is = new BufferedInputStream(
                socket.getInputStream());
        try {
            authServer.authenticate(socket, new DataInputStream(is));
            Assert.fail("Must throw exception as QuorumAuthPacket is invalid");
        } catch (SaslException e) {
            // expected
        }
    }

    /**
     * NullQuorumAuthServer should return true when no auth quorum packet
     * received and timed out.
     */
    @Test(timeout = 30000)
    public void testNullQuorumAuthServerShouldReturnTrue()
            throws Exception {
        Socket socket = getSocketPair();
        QuorumAuthServer authServer = new NullQuorumAuthServer();
        BufferedInputStream is = new BufferedInputStream(
                socket.getInputStream());
        // It will throw exception and fail the
        // test if any unexpected error. Not adding any extra assertion.
        authServer.authenticate(socket, new DataInputStream(is));
    }

    /**
     * NullQuorumAuthServer should return true on receiving a valid quorum auth
     * packet.
     */
    @Test(timeout = 30000)
    public void testNullQuorumAuthServerWithValidQuorumAuthPacket()
            throws Exception {
        Socket socket = getSocketPair();
        DataOutputStream dout = new DataOutputStream(socket.getOutputStream());
        BufferedOutputStream bufferedOutput = new BufferedOutputStream(dout);
        BinaryOutputArchive boa = BinaryOutputArchive
                .getArchive(bufferedOutput);
        QuorumAuthPacket authPacket = QuorumAuth
                .createPacket(QuorumAuth.Status.IN_PROGRESS, null);
        boa.writeRecord(authPacket, null);
        bufferedOutput.flush();
        QuorumAuthServer authServer = new NullQuorumAuthServer();
        BufferedInputStream is = new BufferedInputStream(
                socket.getInputStream());
        // It will throw exception and fail the
        // test if any unexpected error. Not adding any extra assertion.
        authServer.authenticate(socket, new DataInputStream(is));
    }

    private QuorumCnxManager createAndStartManager(long sid) {
        QuorumCnxManager peer = new QuorumCnxManager(sid, peers,
                new NullQuorumAuthServer(), new NullQuorumAuthLearner(), 10000,
                false, quorumCnxnThreadsSize, false);
        executor.submit(peer.listener);
        InetSocketAddress electionAddr = peer.view.get(sid).electionAddr;
        waitForElectionAddrBinding(electionAddr, 15);
        return peer;
    }

    private QuorumCnxManager createAndStartManager(long sid,
                                                   String serverLoginContext,
                                                   String learnerLoginContext,
                                                   boolean serverRequireSasl,
                                                   boolean learnerRequireSasl)
            throws Exception {
        QuorumAuthLearner authClient = new SaslQuorumAuthLearner(learnerRequireSasl,
                "NOT_USING_KRB_PRINCIPAL", learnerLoginContext);
        QuorumAuthServer authServer = new SaslQuorumAuthServer(serverRequireSasl,
                serverLoginContext, authzHosts);
        QuorumCnxManager peer = new QuorumCnxManager(sid, peers,
                authServer, authClient, 10000, false, quorumCnxnThreadsSize, true);
        executor.submit(peer.listener);
        InetSocketAddress electionAddr = peer.view.get(sid).electionAddr;
        waitForElectionAddrBinding(electionAddr, 15);
        return peer;
    }

    private void waitForElectionAddrBinding(InetSocketAddress electionAddr,
            int retries) {
        boolean success = false;
        while (retries > 0) {
            Socket sock = new Socket();
            try {
                sock.setTcpNoDelay(true);
                sock.setSoTimeout(5000);
                sock.connect(electionAddr, 5000);
                success = true;
            } catch (IOException e) {
                LOG.error("IOException while checking election addr", e);
            } finally {
                cleanup(sock);
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                // ignore
            }
            retries--;
        }
        Assert.assertTrue("Did not connect to election port", success);
    }

    private void cleanup(Socket sock) {
        try {
            sock.close();
        } catch (IOException ie) {
            LOG.error("Exception while closing socket", ie);
        }
    }

    private void assertEventuallyConnected(QuorumCnxManager peer, long sid)
            throws Exception {
        for (int i = 0; i < 20 && !peer.connectedToPeer(sid); i++) {
            Thread.sleep(1000);
        }
        Assert.assertTrue("Not connected to peer", peer.connectedToPeer(sid));
    }

    private void assertEventuallyNotConnected(QuorumCnxManager peer, long sid)
            throws Exception {
        for (int i = 0; i < 3 && !peer.connectedToPeer(sid); i++) {
            Thread.sleep(1000);
        }
        Assert.assertFalse("Connected to peer (shouldn't be)",
                           peer.connectedToPeer(sid));
    }

    private QuorumPeer createQuorumPeer(File tmpDir,
            boolean isQuorumAuthEnabled, boolean isQuorumLearnerAuthRequired,
            boolean isQuorumServerAuthRequired, String quorumLearnerLoginContext,
            String quorumServerLoginContext, String quorumServicePrincipal)
                    throws IOException, FileNotFoundException {
        QuorumPeer peer = QuorumPeer.testingQuorumPeer();
        peer.syncLimit = 2;
        peer.initLimit = 2;
        peer.tickTime = 2000;
        peer.quorumPeers = new HashMap<Long, QuorumServer>();
        peer.quorumPeers.put(0L,
                new QuorumServer(0, "0.0.0.0", PortAssignment.unique(), null, null));
        peer.quorumPeers.put(1L,
                new QuorumServer(1, "0.0.0.0", PortAssignment.unique(), null, null));
        peer.setQuorumVerifier(new QuorumMaj(3));
        peer.setCnxnFactory(new NullServerCnxnFactory());
        // auth
        if (isQuorumAuthEnabled) {
            peer.authServer = new SaslQuorumAuthServer(
                    isQuorumServerAuthRequired, quorumServerLoginContext, authzHosts);
            peer.authLearner = new SaslQuorumAuthLearner(
                    isQuorumLearnerAuthRequired, quorumServicePrincipal,
                    quorumLearnerLoginContext);
        }
        File version2 = new File(tmpDir, "version-2");
        version2.mkdir();
        FileOutputStream fos;
        fos = new FileOutputStream(new File(version2, "currentEpoch"));
        fos.write("0\n".getBytes());
        fos.close();
        fos = new FileOutputStream(new File(version2, "acceptedEpoch"));
        fos.write("0\n".getBytes());
        fos.close();
        return peer;
    }

    private static final class NullServerCnxnFactory extends ServerCnxnFactory {
        public void startup(ZooKeeperServer zkServer)
                throws IOException, InterruptedException {
        }

        public void start() {
        }

        public void shutdown() {
        }

        public void setMaxClientCnxnsPerHost(int max) {
        }

        public void join() throws InterruptedException {
        }

        public int getMaxClientCnxnsPerHost() {
            return 0;
        }

        public int getLocalPort() {
            return 0;
        }

        public InetSocketAddress getLocalAddress() {
            return null;
        }

        public Iterable<ServerCnxn> getConnections() {
            return null;
        }

        public void configure(InetSocketAddress addr, int maxClientCnxns)
                throws IOException {
        }

        public void closeSession(long sessionId) {
        }

        public void closeAll() {
        }

        @Override
        public int getNumAliveConnections() {
            return 0;
        }
    }

    private static Socket getSocketPair() throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.bind(null);
        InetSocketAddress endPoint = (InetSocketAddress) ss
                .getLocalSocketAddress();
        Socket s = new Socket(endPoint.getAddress(), endPoint.getPort());
        s.setSoTimeout(5000);
        return s;
    }

    private Leader createLeader(File tmpDir, QuorumPeer peer) throws IOException,
                    NoSuchFieldException, IllegalAccessException {
        LeaderZooKeeperServer zk = prepareLeader(tmpDir, peer);
        return new Leader(peer, zk);
    }

    private Leader createSimpleLeader(File tmpDir, QuorumPeer peer,
            CountDownLatch learnerLatch) throws IOException,
                    NoSuchFieldException, IllegalAccessException {
        LeaderZooKeeperServer zk = prepareLeader(tmpDir, peer);
        return new SimpleLeader(peer, zk, learnerLatch);
    }

    class SimpleLeader extends Leader {
        final CountDownLatch learnerLatch;

        SimpleLeader(QuorumPeer self, LeaderZooKeeperServer zk,
                CountDownLatch latch) throws IOException {
            super(self, zk);
            this.learnerLatch = latch;
        }

        @Override
        void addLearnerHandler(LearnerHandler learner) {
            super.addLearnerHandler(learner);
            learnerLatch.countDown();
        }
    }

    private LeaderZooKeeperServer prepareLeader(File tmpDir, QuorumPeer peer)
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileTxnSnapLog logFactory = new FileTxnSnapLog(tmpDir, tmpDir);
        peer.setTxnFactory(logFactory);
        Field addrField = peer.getClass().getDeclaredField("myQuorumAddr");
        addrField.setAccessible(true);
        addrField.set(peer, new InetSocketAddress(PortAssignment.unique()));
        ZKDatabase zkDb = new ZKDatabase(logFactory);
        LeaderZooKeeperServer zk = new LeaderZooKeeperServer(logFactory, peer,
                new ZooKeeperServer.BasicDataTreeBuilder(), zkDb);
        return zk;
    }

    class SimpleLearnerZooKeeperServer extends LearnerZooKeeperServer {
        boolean startupCalled;

        public SimpleLearnerZooKeeperServer(FileTxnSnapLog ftsl,
                QuorumPeer self) throws IOException {
            super(ftsl, 2000, 2000, 2000, null, new ZKDatabase(ftsl), self);
        }

        Learner learner;

        @Override
        public Learner getLearner() {
            return learner;
        }

        @Override
        public void startup() {
            startupCalled = true;
        }
    }

    class SimpleLearner extends Learner {
        SimpleLearner(FileTxnSnapLog ftsl, QuorumPeer learner)
                throws IOException {
            self = learner;
            zk = new SimpleLearnerZooKeeperServer(ftsl, self);
            ((SimpleLearnerZooKeeperServer) zk).learner = this;
        }
    }
}
