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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.security.sasl.SaslException;

import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.auth.NullQuorumAuthClient;
import org.apache.zookeeper.server.quorum.auth.NullQuorumAuthServer;
import org.apache.zookeeper.server.quorum.auth.QuorumAuth;
import org.apache.zookeeper.server.quorum.auth.QuorumAuthClient;
import org.apache.zookeeper.server.quorum.auth.QuorumAuthServer;
import org.apache.zookeeper.server.quorum.auth.SaslQuorumAuthClient;
import org.apache.zookeeper.server.quorum.auth.SaslQuorumAuthServer;
import org.apache.zookeeper.server.quorum.flexible.QuorumMaj;
import org.apache.zookeeper.test.ClientBase;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class QuorumCnxManagerTest {
    int count;
    HashMap<Long,QuorumServer> peers;
    int peerQuorumPort[];
    int peerClientPort[];
    ThreadPoolExecutor executor;

    static File saslConfigFile = null;

    @BeforeClass
    public static void setupSasl() throws Exception {
        String jaasEntries = new String(""
                + "QuorumServer {\n"
                + "       org.apache.zookeeper.server.auth.DigestLoginModule required\n"
                + "       user_test=\"mypassword\";\n"
                + "};\n"
                + "QuorumClient {\n"
                + "       org.apache.zookeeper.server.auth.DigestLoginModule required\n"
                + "       username=\"test\"\n"
                + "       password=\"mypassword\";\n"
                + "};\n"
                + "QuorumClientInvalid {\n"
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

        for(int i = 0; i < count; i++) {
            peerQuorumPort[i] = PortAssignment.unique();
            peerClientPort[i] = PortAssignment.unique();
            peers.put(Long.valueOf(i), new QuorumServer(i, "0.0.0.0",
                    peerQuorumPort[i], PortAssignment.unique(), null));
        }
        executor = new ThreadPoolExecutor(3, 10,
                60, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
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
                                                       "QuorumClient", true);
        QuorumCnxManager peer1 = createAndStartManager(1, "QuorumServer",
                                                       "QuorumClient", true);
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
                                                       "QuorumClient", false);
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
                                                       "QuorumClient", false);
        QuorumCnxManager peer1 = createAndStartManager(1);
        peer0.connectOne(1);
        peer1.connectOne(0);
        assertEventuallyConnected(peer0, 1);
    }

    /**
     * No auth client connects to a server that requires auth, when the server
     * has a higher sid.
     * The connection should fail in both directions.
     */
    @Test(timeout = 30000)
    public void testNoAuthClientConnectToAuthRequiredServerWithLowerSid()
            throws Exception {
        QuorumCnxManager peer0 = createAndStartManager(0, "QuorumServer",
                                                       "QuorumClient", true);
        QuorumCnxManager peer1 = createAndStartManager(1);
        peer0.connectOne(1);
        peer1.connectOne(0);
        assertEventuallyNotConnected(peer0, 1);
    }

    /**
     * No auth client connects to a server that requires auth, when the server
     * has a higher sid.
     * The connection should fail in both directions.
     */
    @Test(timeout = 30000)
    public void testNoAuthClientConnectToAuthRequiredServerWithHigherSid()
            throws Exception {
        QuorumCnxManager peer0 = createAndStartManager(0);
        QuorumCnxManager peer1 = createAndStartManager(1, "QuorumServer",
                                                       "QuorumClient", true);
        peer0.connectOne(1);
        peer1.connectOne(0);
        assertEventuallyNotConnected(peer0, 1);
    }

    /**
     * An auth client connects to a auth server, but the credentials are bad.
     * The peer with the higher sid has the bad credentials.
     * The connection will be denied.
     */
    @Test(timeout = 30000)
    public void testAuthClientBadCredToAuthRequiredServerWithLowerSid()
            throws Exception {
        QuorumCnxManager peer0 = createAndStartManager(0,  "QuorumServer",
                                                       "QuorumClient", true);
        QuorumCnxManager peer1 = createAndStartManager(1, "QuorumServer",
                                                       "QuorumClientInvalid", true);
        peer0.connectOne(1);
        peer1.connectOne(0);

        assertEventuallyNotConnected(peer0, 1);
    }

    /**
     * An auth client connects to a auth server, but the credentials are bad.
     * The peer with the lower sid has the bad credentials.
     * The connection will work, because peer1 is connecting to peer0.
     */
    @Test(timeout = 30000)
    public void testAuthClientBadCredToAuthRequiredServerWithHigherSid()
            throws Exception {
        QuorumCnxManager peer0 = createAndStartManager(0,  "QuorumServer",
                                                       "QuorumClientInvalid", true);
        QuorumCnxManager peer1 = createAndStartManager(1, "QuorumServer",
                                                       "QuorumClient", true);
        peer0.connectOne(1);
        peer1.connectOne(0);
        assertEventuallyConnected(peer0, 1);
        assertEventuallyConnected(peer1, 0);
    }

    /**
     * An auth client connects to a auth server, but the credentials are bad.
     * The connection should fail in both directions.
     */
    @Test(timeout = 30000)
    public void testAuthClientBadCredToNoAuthServerWithHigherSid() throws Exception {
        QuorumCnxManager peer0 = createAndStartManager(0, "QuorumServer",
                "QuorumClient", false);
        QuorumCnxManager peer1 = createAndStartManager(1, "QuorumServer",
                "QuorumClientInvalid", true);
        peer1.connectOne(0);
        assertEventuallyNotConnected(peer1, 0);
    }

    /**
     * An auth client connects to a auth server, but the credentials are bad.
     * The peer with the lower sid has the bad credentials.
     * The connection will work, because peer0 is connecting to peer1 and peer1
     * server doesn't require sasl
     */
    @Test(timeout = 30000)
    public void testAuthClientBadCredToNoAuthServerWithLowerSid() throws Exception {
        QuorumCnxManager peer0 = createAndStartManager(0, "QuorumServer",
                "QuorumClientInvalid", true);
        QuorumCnxManager peer1 = createAndStartManager(1, "QuorumServer",
                "QuorumClient", false);
        peer0.connectOne(1);
        assertEventuallyConnected(peer0, 1);
        assertEventuallyConnected(peer1, 0);
    }

    @Test(timeout = 30000)
    public void testLearnerHandlerAuthFailed() throws Exception {
        File testData = ClientBase.createTmpDir();
        Socket leaderSocket = getSocketPair();
        File tmpDir = File.createTempFile("test", ".dir", testData);
        tmpDir.delete();
        tmpDir.mkdir();
        Leader leader = null;
        QuorumPeer peer = createQuorumPeer(tmpDir, false, false, "", "", "");
        leader = createLeader(tmpDir, peer);
        peer.leader = leader;

        LearnerHandler lh = new LearnerHandler(leaderSocket,
                new BufferedInputStream(leaderSocket.getInputStream()), leader);
        Assert.assertFalse("Wrong authentication", lh.isAuthSuccess());
        Assert.assertEquals("Mistakely added to learners", 0,
                leader.getLearners().size());
        ClientBase.recursiveDelete(testData);
    }

    @Test(timeout = 30000)
    public void testAuthLearnerConnectsToServerWithAuthRequired()
            throws Exception {
        File testDataLearner = ClientBase.createTmpDir();
        File tmpDir = File.createTempFile("test", ".dir", testDataLearner);
        tmpDir.delete();
        FileTxnSnapLog ftsl = new FileTxnSnapLog(tmpDir, tmpDir);
        QuorumPeer learnerPeer = createQuorumPeer(tmpDir, true, true,
                "QuorumClient", "QuorumServer",
                QuorumAuth.QUORUM_KERBEROS_SERVICE_PRINCIPAL_DEFAULT_VALUE);
        SimpleLearner sl = new SimpleLearner(ftsl, learnerPeer);

        File testDataLeader = ClientBase.createTmpDir();
        tmpDir = File.createTempFile("test", ".dir", testDataLeader);
        tmpDir.delete();
        tmpDir.mkdir();
        Leader leader = null;
        QuorumPeer peer = createQuorumPeer(tmpDir, true, true, "QuorumClient",
                "QuorumServer",
                QuorumAuth.QUORUM_KERBEROS_SERVICE_PRINCIPAL_DEFAULT_VALUE);
        CountDownLatch learnerLatch = new CountDownLatch(1);
        leader = createSimpleLeader(tmpDir, peer, learnerLatch);
        peer.leader = leader;
        leader.cnxAcceptor = leader.new LearnerCnxAcceptor();
        leader.cnxAcceptor.start();
        Thread.sleep(2000); // waiting to start the thread

        sl.connectToLeader(peer.getQuorumAddress());
        // wait till leader socket soTimeout period
        Assert.assertTrue("Leader should accept the auth learner connection",
                learnerLatch.await(leader.self.tickTime * leader.self.initLimit + 1000,
                        TimeUnit.MILLISECONDS));
        ClientBase.recursiveDelete(testDataLearner);
        ClientBase.recursiveDelete(testDataLeader);
    }

    @Test(timeout = 30000)
    public void testAuthLearnerConnectsToNoAuthServer()
            throws Exception {
        File testDataLearner = ClientBase.createTmpDir();
        File tmpDir = File.createTempFile("test", ".dir", testDataLearner);
        tmpDir.delete();
        FileTxnSnapLog ftsl = new FileTxnSnapLog(tmpDir, tmpDir);
        QuorumPeer learnerPeer = createQuorumPeer(tmpDir, true, true,
                "QuorumClient", "QuorumServer",
                QuorumAuth.QUORUM_KERBEROS_SERVICE_PRINCIPAL_DEFAULT_VALUE);
        SimpleLearner sl = new SimpleLearner(ftsl, learnerPeer);

        File testDataLeader = ClientBase.createTmpDir();
        tmpDir = File.createTempFile("test", ".dir", testDataLeader);
        tmpDir.delete();
        tmpDir.mkdir();
        Leader leader = null;
        QuorumPeer peer = createQuorumPeer(tmpDir, false, false, "", "", "");
        CountDownLatch learnerLatch = new CountDownLatch(1);
        leader = createSimpleLeader(tmpDir, peer, learnerLatch);
        peer.leader = leader;
        leader.cnxAcceptor = leader.new LearnerCnxAcceptor();
        leader.cnxAcceptor.start();
        Thread.sleep(2000); // waiting to start the thread

        try {
            sl.connectToLeader(peer.getQuorumAddress());
            Assert.fail("Must throw exception as server doesn't supports authentication");
        } catch (SaslException e) {
            // expected
            Assert.assertTrue("Leader should accept the auth learner connection",
                    learnerLatch.await(leader.self.tickTime * leader.self.initLimit + 500,
                            TimeUnit.MILLISECONDS));
        }

        ClientBase.recursiveDelete(testDataLearner);
        ClientBase.recursiveDelete(testDataLeader);
    }

    @Test(timeout = 30000)
    public void testNoAuthLearnerConnectsToServerWithAuthNotRequired()
            throws Exception {
        File testDataLearner = ClientBase.createTmpDir();
        File tmpDir = File.createTempFile("test", ".dir", testDataLearner);
        tmpDir.delete();
        FileTxnSnapLog ftsl = new FileTxnSnapLog(tmpDir, tmpDir);
        QuorumPeer learnerPeer = createQuorumPeer(tmpDir, false, false, "", "",
                "");
        SimpleLearner sl = new SimpleLearner(ftsl, learnerPeer);

        File testDataLeader = ClientBase.createTmpDir();
        tmpDir = File.createTempFile("test", ".dir", testDataLeader);
        tmpDir.delete();
        tmpDir.mkdir();
        Leader leader = null;
        QuorumPeer peer = createQuorumPeer(tmpDir, true, false, "QuorumClient",
                "QuorumServer",
                QuorumAuth.QUORUM_KERBEROS_SERVICE_PRINCIPAL_DEFAULT_VALUE);
        CountDownLatch learnerLatch = new CountDownLatch(1);
        leader = createSimpleLeader(tmpDir, peer, learnerLatch);
        peer.leader = leader;
        leader.cnxAcceptor = leader.new LearnerCnxAcceptor();
        leader.cnxAcceptor.start();
        Thread.sleep(2000); // waiting to start the thread

        sl.connectToLeader(peer.getQuorumAddress());

        Assert.assertTrue("Leader should accept no auth learner connection",
                learnerLatch.await(leader.self.tickTime * leader.self.initLimit + 1000,
                        TimeUnit.MILLISECONDS));
        ClientBase.recursiveDelete(testDataLearner);
        ClientBase.recursiveDelete(testDataLeader);
    }

    @Test(timeout = 30000)
    public void testNoAuthLearnerConnectsToServerWithAuthRequired()
            throws Exception {
        File testDataLearner = ClientBase.createTmpDir();
        File tmpDir = File.createTempFile("test", ".dir", testDataLearner);
        tmpDir.delete();
        FileTxnSnapLog ftsl = new FileTxnSnapLog(tmpDir, tmpDir);
        QuorumPeer learnerPeer = createQuorumPeer(tmpDir, false, false, "", "",
                "");
        SimpleLearner sl = new SimpleLearner(ftsl, learnerPeer);

        File testDataLeader = ClientBase.createTmpDir();
        tmpDir = File.createTempFile("test", ".dir", testDataLeader);
        tmpDir.delete();
        tmpDir.mkdir();
        Leader leader = null;
        QuorumPeer peer = createQuorumPeer(tmpDir, true, true, "QuorumClient",
                "QuorumServer",
                QuorumAuth.QUORUM_KERBEROS_SERVICE_PRINCIPAL_DEFAULT_VALUE);
        CountDownLatch learnerLatch = new CountDownLatch(1);
        leader = createSimpleLeader(tmpDir, peer, learnerLatch);
        peer.leader = leader;
        leader.cnxAcceptor = leader.new LearnerCnxAcceptor();
        leader.cnxAcceptor.start();
        Thread.sleep(2000); // waiting to start the thread

        sl.connectToLeader(peer.getQuorumAddress());
        Assert.assertFalse("Leader shouldn't accept no auth learner connection",
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
                "QuorumServer");
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
     * NullQuorumAuthServer should return false when no auth quorum packet
     * received and timed out.
     */
    @Test(timeout = 30000)
    public void testNullQuorumAuthServerShouldReturnFalseOnIOException()
            throws Exception {
        Socket socket = getSocketPair();
        QuorumAuthServer authServer = new NullQuorumAuthServer();
        BufferedInputStream is = new BufferedInputStream(
                socket.getInputStream());
        Assert.assertFalse("Wrongly authenticated",
                authServer.authenticate(socket, new DataInputStream(is)));
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
        Assert.assertTrue("Failed to authenticate",
                authServer.authenticate(socket, new DataInputStream(is)));
    }

    private QuorumCnxManager createAndStartManager(long sid) {
        QuorumCnxManager peer = new QuorumCnxManager(sid, peers, executor,
                Collections.synchronizedSet(new HashSet<Long>()),
                new NullQuorumAuthServer(), new NullQuorumAuthClient(), 10000,
                false);
        executor.submit(peer.listener);
        return peer;
    }

    private QuorumCnxManager createAndStartManager(long sid,
                                                   String serverLoginContext,
                                                   String clientLoginContext,
                                                   boolean serverRequireSasl)
            throws Exception {
        QuorumAuthClient authClient = new SaslQuorumAuthClient(serverRequireSasl,
                "NOT_USING_KRB_PRINCIPAL", clientLoginContext);
        QuorumAuthServer authServer = new SaslQuorumAuthServer(serverRequireSasl,
                serverLoginContext);
        QuorumCnxManager peer = new QuorumCnxManager(sid, peers,
                executor, Collections.synchronizedSet(new HashSet<Long>()),
                authServer, authClient, 10000, false);
        executor.submit(peer.listener);
        return peer;
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
            boolean isQuorumClientAuthEnabled,
            boolean isQuorumServerAuthRequired, String quorumClientLoginContext,
            String quorumServerLoginContext, String quorumServicePrincipal)
                    throws IOException, FileNotFoundException {
        QuorumPeer peer = QuorumPeer.testingQuorumPeer();
        peer.syncLimit = 2;
        peer.initLimit = 2;
        peer.tickTime = 2000;
        peer.quorumPeers = new HashMap<Long, QuorumServer>();
        peer.quorumPeers.put(1L,
                new QuorumServer(0, "0.0.0.0", PortAssignment.unique(), null, null));
        peer.quorumPeers.put(1L,
                new QuorumServer(1, "0.0.0.0", PortAssignment.unique(), null, null));
        peer.setQuorumVerifier(new QuorumMaj(3));
        peer.setCnxnFactory(new NullServerCnxnFactory());
        // auth
        if (isQuorumClientAuthEnabled) {
            peer.authServer = new SaslQuorumAuthServer(
                    isQuorumServerAuthRequired, quorumServerLoginContext);
            peer.authClient = new SaslQuorumAuthClient(
                    isQuorumServerAuthRequired, quorumServicePrincipal,
                    quorumClientLoginContext);
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
        Socket s = new Socket(endPoint.getAddress(), endPoint.getPort() + 1);
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
