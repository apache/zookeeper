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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import javax.net.ssl.SSLHandshakeException;

import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKParameterized;
import org.apache.zookeeper.common.X509Exception;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.util.QuorumSocketFactory;
import org.apache.zookeeper.server.quorum.utils.AsyncClientSocket;
import org.apache.zookeeper.server.quorum.utils.AsyncServerSocket;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.zookeeper.common.ZKConfig.SSL_KEYSTORE_LOCATION;
import static org.apache.zookeeper.common.ZKConfig.SSL_KEYSTORE_PASSWD;
import static org.apache.zookeeper.common.ZKConfig.SSL_TRUSTSTORE_LOCATION;
import static org.apache.zookeeper.common.ZKConfig.SSL_TRUSTSTORE_PASSWD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;


@RunWith(Parameterized.class)
@Parameterized.UseParametersRunnerFactory(ZKParameterized.RunnerFactory.class)
public class QuorumSocketFactoryTest {
    private static final Logger LOG
            = LoggerFactory.getLogger(QuorumSocketFactory.class);
    private final QuorumServer listenServer
            = new QuorumServer(1, new InetSocketAddress("localhost", PortAssignment.unique()));
    private final QuorumServer client1
            = new QuorumServer(2, new InetSocketAddress("localhost", PortAssignment.unique()));
    private final QuorumServer client2
            = new QuorumServer(3, new InetSocketAddress("localhost", PortAssignment.unique()));

    private final boolean sslEnabled;
    private final boolean isSelfSigned;
    private X509ClusterBase x509ClusterBase;
    private List<QuorumPeerConfig> quorumPeerConfigs;
    private ServerSocket serverSocket = null;
    private Socket clientSocket1 = null;
    private Socket clientSocket2 = null;

    public QuorumSocketFactoryTest(final boolean sslEnabled,
                                   final boolean isSelfSigned) {
        this.sslEnabled = sslEnabled;
        this.isSelfSigned = isSelfSigned;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {false, false}, {true, false}, {true, true}});
    }

    @Rule
    public TemporaryFolder tmpFolder = new TemporaryFolder();

    @Before
    public void setup() throws IOException {
        if (!isSelfSigned) {
            this.x509ClusterBase = new X509ClusterCASigned("x509ca",
                    this.tmpFolder.newFolder("ssl").toPath(), 3);
        } else {
            this.x509ClusterBase = new X509ClusterSelfSigned("x509ca",
                    this.tmpFolder.newFolder("ssl").toPath(), 3);
        }
        quorumPeerConfigs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            quorumPeerConfigs.add(new QuorumPeerConfig());
            setQuorumPeerSslConfig(quorumPeerConfigs.get(i), i);
        }
    }

    @After
    public void cleanUp() {
        if (clientSocket1 != null) {
            close(clientSocket1);
        }

        if (clientSocket2 != null) {
            close(clientSocket2);
        }

        if (serverSocket != null) {
            close(serverSocket);
        }
    }

    @Test
    public void testListener() throws Exception {
        serverSocket = newServerAndBindTest(listenServer, 0);
        serverSocket.close();
        serverSocket = null;
    }

    @Test
    public void testAccept() throws Exception {
        final int serverIndex = 0;
        final int clientIndex = 1;

        Collection<Socket> sockets = connectOneClientToServerTest(
                client1, listenServer, clientIndex, serverIndex);
        Iterator<Socket> it = sockets.iterator();
        it.next();
        it.next().close();
        clientSocket1.close();
        clientSocket1 = null;
        serverSocket.close();
        serverSocket = null;
    }

    @Test(expected=SSLHandshakeException.class)
    public void testBadClient() throws X509Exception, InterruptedException,
            ExecutionException, IOException, NoSuchAlgorithmException {
        readWriteTestHelper(connectOneBadClientToServerTest(
                client1, listenServer, 0, 1), "HelloWorld!");
        if (!sslEnabled) {
            // need to throw to make test case pass.
            throw new SSLHandshakeException(
                    "ssl disabled, so test should pass");
        }
    }

    @Test
    public void testReadWrite() throws X509Exception, InterruptedException,
            ExecutionException, IOException, NoSuchAlgorithmException {
        readWriteTestHelper(connectOneClientToServerTest(
                client1, listenServer, 0, 1), "HelloWorld!");
    }

    private void readWriteTestHelper(final Collection<Socket> sockets,
                                     final String testStr)
            throws InterruptedException, ExecutionException, IOException {
        Iterator<Socket> it = sockets.iterator();
        // Write from client
        FutureTask<Void> writeFuture
                = new AsyncClientSocket(it.next()).write(testStr);
        FutureTask<String> readFuture
                = new AsyncClientSocket(it.next()).read();

        while(!writeFuture.isDone()
                || !readFuture.isDone()) {
            Thread.sleep(2);
        }

        String str;
        try {
            str = readFuture.get();
        } catch (ExecutionException exp) {
            if (exp.getCause() != null &&
                    exp.getCause() instanceof IOException) {
                if (exp.getCause() instanceof SSLHandshakeException) {
                    throw (SSLHandshakeException) exp.getCause();
                } else {
                    throw (IOException) exp.getCause();
                }
            } else {
                throw exp;
            }
        }

        assertEquals("data txrx", testStr, str);
        it = sockets.iterator();
        it.next();
        it.next().close();
        clientSocket1.close();
        clientSocket1 = null;
        serverSocket.close();
        serverSocket = null;
    }

    private Collection<Socket> connectOneClientToServerTest(
            final QuorumServer from, final QuorumServer to,
            int clientIndex, int serverIndex)
            throws X509Exception, IOException, InterruptedException,
            ExecutionException, NoSuchAlgorithmException {

        return connectOneClientToServerTest(newClient(from, clientIndex),
                to, serverIndex);
    }

    private Collection<Socket> connectOneBadClientToServerTest(
            final QuorumServer from, final QuorumServer to,
            int clientIndex, int serverIndex)
            throws X509Exception, IOException, InterruptedException,
            ExecutionException, NoSuchAlgorithmException {

        return connectOneClientToServerTest(newBadClient(from, clientIndex),
                to, serverIndex);
    }

    private Collection<Socket> connectOneClientToServerTest(
            final Socket client, final QuorumServer to, int serverIndex)
            throws X509Exception, IOException, InterruptedException,
            ExecutionException, NoSuchAlgorithmException {
        serverSocket = newServerAndBindTest(to, serverIndex);

        clientSocket1 = client;
        FutureTask<Socket> clientSocketFuture
                = new AsyncClientSocket(clientSocket1).connect(to);
        FutureTask<Socket> serverSocketFuture
                = new AsyncServerSocket(serverSocket).accept();

        while (!clientSocketFuture.isDone()
                || !serverSocketFuture.isDone()) {
            Thread.sleep(2);
        }

        assertTrue("connected", clientSocketFuture.get().isConnected());
        assertTrue("accepted", serverSocketFuture.get().isConnected());
        return Collections.unmodifiableList(
                Arrays.asList(clientSocketFuture.get(),
                        serverSocketFuture.get()));
    }

    private Socket newClient(final QuorumServer from, int index)
            throws X509Exception, IOException, NoSuchAlgorithmException {
        return startClient(from, index);
    }

    private Socket newBadClient(final QuorumServer from, int index)
            throws X509Exception, IOException, NoSuchAlgorithmException {
        setBadQuorumPeerSslConfig(quorumPeerConfigs.get(index), index);
        return startClient(from, index);
    }

    private Socket startClient(final QuorumServer from,
                               final int index)
            throws X509Exception, IOException, NoSuchAlgorithmException {
        if (this.sslEnabled) {
            return QuorumSocketFactory.createForSSL().buildForClient
                    (quorumPeerConfigs.get(index));
        } else {
            return QuorumSocketFactory.createWithoutSSL().buildForClient(
                    quorumPeerConfigs.get(index));
        }
    }

    private ServerSocket newServerAndBindTest(final QuorumServer server,
                                              int index)
            throws X509Exception, IOException, NoSuchAlgorithmException {
        ServerSocket s = startListener(server, index);
        assertTrue("bind worked", s.isBound());
        return s;
    }

    private ServerSocket startListener(final QuorumServer server, int index)
            throws X509Exception, IOException, NoSuchAlgorithmException {
        QuorumSocketFactory quorumSocketFactory = null;
        if (this.sslEnabled) {
            quorumSocketFactory = QuorumSocketFactory.createForSSL();
        } else {
            quorumSocketFactory = QuorumSocketFactory.createWithoutSSL();
        }

        return quorumSocketFactory.buildForServer(quorumPeerConfigs.get(index),
                server.addr.getPort(), server.addr.getAddress());
    }

    private void close(ServerSocket s) {
        try {
            s.close();
        } catch (IOException e) {
        }
    }

    private void close(Socket s) {
        try {
            s.close();
        } catch (IOException e) {}
    }

    private void setQuorumPeerSslConfig(final QuorumPeerConfig zkConfig,
                                        final int index) {
        setQuorumPeerSslConfig(x509ClusterBase, zkConfig, index);
    }

    public static void setQuorumPeerSslConfig(
            final X509ClusterBase x509ClusterBase,
            final QuorumPeerConfig zkConfig, final int index) {
        zkConfig.setProperty(SSL_KEYSTORE_LOCATION,
                x509ClusterBase.getKeyStoreList().get(index).toString());
        zkConfig.setProperty(SSL_KEYSTORE_PASSWD,
                x509ClusterBase.getKeyStorePasswordList().get(index));
        zkConfig.setProperty(SSL_TRUSTSTORE_LOCATION,
                x509ClusterBase.getTrustStoreList().get(index).toString());
        zkConfig.setProperty(SSL_TRUSTSTORE_PASSWD,
                x509ClusterBase.getTrustStorePasswordList().get(index));
    }

    private void setBadQuorumPeerSslConfig(final QuorumPeerConfig zkConfig,
                                           final int index) {
        setBadQuorumPeerSslConfig(x509ClusterBase, zkConfig, index);
    }

    public void setBadQuorumPeerSslConfig(
            final X509ClusterBase x509ClusterBase,
            final QuorumPeerConfig zkConfig, final int index) {
        zkConfig.setProperty(SSL_KEYSTORE_LOCATION,
                x509ClusterBase.getBadKeyStoreList().get(index).toString());
        zkConfig.setProperty(SSL_KEYSTORE_PASSWD,
                x509ClusterBase.getBadKeyStorePasswordList().get(index));
        zkConfig.setProperty(SSL_TRUSTSTORE_LOCATION,
                x509ClusterBase.getBadTrustStoreList().get(index).
                        toString());
        zkConfig.setProperty(SSL_TRUSTSTORE_PASSWD,
                x509ClusterBase.getBadTrustStorePasswordList().get(index));
    }
}
