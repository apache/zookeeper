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

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketOptions;
import java.security.Security;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.common.ClientX509Util;
import org.apache.zookeeper.common.KeyStoreFileType;
import org.apache.zookeeper.common.X509KeyType;
import org.apache.zookeeper.common.X509TestContext;
import org.apache.zookeeper.common.X509Util;
import org.apache.zookeeper.test.ClientBase;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This test makes sure that certain operations on a UnifiedServerSocket do not
 * trigger blocking mode detection. This is necessary to ensure that the
 * Leader's accept() thread doesn't get blocked.
 */
@RunWith(Parameterized.class)
public class UnifiedServerSocketModeDetectionTest extends ZKTestCase {
    private static final Logger LOG = LoggerFactory.getLogger(
            UnifiedServerSocketModeDetectionTest.class);

    @Parameterized.Parameters
    public static Collection<Object[]> params() {
        ArrayList<Object[]> result = new ArrayList<>();
        result.add(new Object[] { true });
        result.add(new Object[] { false });
        return result;
    }

    private static File tempDir;
    private static X509TestContext x509TestContext;

    private boolean useSecureClient;
    private X509Util x509Util;
    private UnifiedServerSocket listeningSocket;
    private UnifiedServerSocket.UnifiedSocket serverSideSocket;
    private Socket clientSocket;
    private ExecutorService workerPool;
    private int port;
    private InetSocketAddress localServerAddress;

    @BeforeClass
    public static void setUpClass() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        tempDir = ClientBase.createEmptyTestDir();
        x509TestContext = X509TestContext.newBuilder()
                .setTempDir(tempDir)
                .setKeyStoreKeyType(X509KeyType.EC)
                .setTrustStoreKeyType(X509KeyType.EC)
                .build();
    }

    @AfterClass
    public static void tearDownClass() {
        try {
            FileUtils.deleteDirectory(tempDir);
        } catch (IOException e) {
            // ignore
        }
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
    }

    private static void forceClose(Socket s) {
        if (s == null || s.isClosed()) {
            return;
        }
        try {
            s.close();
        } catch (IOException e) {
        }
    }

    private static void forceClose(ServerSocket s) {
        if (s == null || s.isClosed()) {
            return;
        }
        try {
            s.close();
        } catch (IOException e) {
        }
    }

    public UnifiedServerSocketModeDetectionTest(Boolean useSecureClient) {
        this.useSecureClient = useSecureClient;
    }

    @Before
    public void setUp() throws Exception {
        x509Util = new ClientX509Util();
        x509TestContext.setSystemProperties(x509Util, KeyStoreFileType.JKS, KeyStoreFileType.JKS);
        System.setProperty(x509Util.getSslHandshakeDetectionTimeoutMillisProperty(), "100");
        workerPool = Executors.newCachedThreadPool();
        port = PortAssignment.unique();
        localServerAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
        listeningSocket = new UnifiedServerSocket(x509Util, true);
        listeningSocket.bind(localServerAddress);
        Future<UnifiedServerSocket.UnifiedSocket> acceptFuture;
        acceptFuture = workerPool.submit(new Callable<UnifiedServerSocket.UnifiedSocket>() {
            @Override
            public UnifiedServerSocket.UnifiedSocket call() throws Exception {
                try {
                    return (UnifiedServerSocket.UnifiedSocket) listeningSocket.accept();
                } catch (IOException e) {
                    LOG.error("Error in accept(): ", e);
                    throw e;
                }
            }
        });
        if (useSecureClient) {
            clientSocket = x509Util.createSSLSocket();
            clientSocket.connect(localServerAddress);
        } else {
            clientSocket = new Socket();
            clientSocket.connect(localServerAddress);
            clientSocket.getOutputStream().write(new byte[] { 1, 2, 3, 4, 5 });
        }
        serverSideSocket = acceptFuture.get();
    }

    @After
    public void tearDown() throws Exception {
        x509TestContext.clearSystemProperties(x509Util);
        System.clearProperty(x509Util.getSslHandshakeDetectionTimeoutMillisProperty());
        forceClose(listeningSocket);
        forceClose(serverSideSocket);
        forceClose(clientSocket);
        workerPool.shutdown();
        workerPool.awaitTermination(1000, TimeUnit.MILLISECONDS);
        x509Util.close();
    }

    @Test
    public void testGetInetAddress() {
        serverSideSocket.getInetAddress();
        Assert.assertFalse(serverSideSocket.isModeKnown());
    }

    @Test
    public void testGetLocalAddress() {
        serverSideSocket.getLocalAddress();
        Assert.assertFalse(serverSideSocket.isModeKnown());
    }

    @Test
    public void testGetPort() {
        serverSideSocket.getPort();
        Assert.assertFalse(serverSideSocket.isModeKnown());
    }

    @Test
    public void testGetLocalPort() {
        serverSideSocket.getLocalPort();
        Assert.assertFalse(serverSideSocket.isModeKnown());
    }

    @Test
    public void testGetRemoteSocketAddress() {
        serverSideSocket.getRemoteSocketAddress();
        Assert.assertFalse(serverSideSocket.isModeKnown());
    }

    @Test
    public void testGetLocalSocketAddress() {
        serverSideSocket.getLocalSocketAddress();
        Assert.assertFalse(serverSideSocket.isModeKnown());
    }

    @Test
    public void testGetInputStream() throws IOException {
        serverSideSocket.getInputStream();
        Assert.assertFalse(serverSideSocket.isModeKnown());
    }

    @Test
    public void testGetOutputStream() throws IOException {
        serverSideSocket.getOutputStream();
        Assert.assertFalse(serverSideSocket.isModeKnown());
    }

    @Test
    public void testGetTcpNoDelay() throws IOException {
        serverSideSocket.getTcpNoDelay();
        Assert.assertFalse(serverSideSocket.isModeKnown());
    }

    @Test
    public void testSetTcpNoDelay() throws IOException {
        boolean tcpNoDelay = serverSideSocket.getTcpNoDelay();
        tcpNoDelay = !tcpNoDelay;
        serverSideSocket.setTcpNoDelay(tcpNoDelay);
        Assert.assertFalse(serverSideSocket.isModeKnown());
        Assert.assertEquals(tcpNoDelay, serverSideSocket.getTcpNoDelay());
    }

    @Test
    public void testGetSoLinger() throws IOException {
        serverSideSocket.getSoLinger();
        Assert.assertFalse(serverSideSocket.isModeKnown());
    }

    @Test
    public void testSetSoLinger() throws IOException {
        int soLinger = serverSideSocket.getSoLinger();
        if (soLinger == -1) {
            // enable it if disabled
            serverSideSocket.setSoLinger(true, 1);
            Assert.assertFalse(serverSideSocket.isModeKnown());
            Assert.assertEquals(1, serverSideSocket.getSoLinger());
        } else {
            // disable it if enabled
            serverSideSocket.setSoLinger(false, -1);
            Assert.assertFalse(serverSideSocket.isModeKnown());
            Assert.assertEquals(-1, serverSideSocket.getSoLinger());
        }
    }

    @Test
    public void testGetSoTimeout() throws IOException {
        serverSideSocket.getSoTimeout();
        Assert.assertFalse(serverSideSocket.isModeKnown());
    }

    @Test
    public void testSetSoTimeout() throws IOException {
        int timeout = serverSideSocket.getSoTimeout();
        timeout = timeout + 10;
        serverSideSocket.setSoTimeout(timeout);
        Assert.assertFalse(serverSideSocket.isModeKnown());
        Assert.assertEquals(timeout, serverSideSocket.getSoTimeout());
    }

    @Test
    public void testGetSendBufferSize() throws IOException {
        serverSideSocket.getSendBufferSize();
        Assert.assertFalse(serverSideSocket.isModeKnown());
    }

    @Test
    public void testSetSendBufferSize() throws IOException {
        serverSideSocket.setSendBufferSize(serverSideSocket.getSendBufferSize() + 1024);
        Assert.assertFalse(serverSideSocket.isModeKnown());
        // Note: the new buffer size is a hint and socket implementation
        // is free to ignore it, so we don't verify that we get back the
        // same value.

    }

    @Test
    public void testGetReceiveBufferSize() throws IOException {
        serverSideSocket.getReceiveBufferSize();
        Assert.assertFalse(serverSideSocket.isModeKnown());
    }

    @Test
    public void testSetReceiveBufferSize() throws IOException {
        serverSideSocket.setReceiveBufferSize(serverSideSocket.getReceiveBufferSize() + 1024);
        Assert.assertFalse(serverSideSocket.isModeKnown());
        // Note: the new buffer size is a hint and socket implementation
        // is free to ignore it, so we don't verify that we get back the
        // same value.

    }

    @Test
    public void testGetKeepAlive() throws IOException {
        serverSideSocket.getKeepAlive();
        Assert.assertFalse(serverSideSocket.isModeKnown());
    }

    @Test
    public void testSetKeepAlive() throws IOException {
        boolean keepAlive = serverSideSocket.getKeepAlive();
        keepAlive = !keepAlive;
        serverSideSocket.setKeepAlive(keepAlive);
        Assert.assertFalse(serverSideSocket.isModeKnown());
        Assert.assertEquals(keepAlive, serverSideSocket.getKeepAlive());
    }

    @Test
    public void testGetTrafficClass() throws IOException {
        serverSideSocket.getTrafficClass();
        Assert.assertFalse(serverSideSocket.isModeKnown());
    }

    @Test
    public void testSetTrafficClass() throws IOException {
        serverSideSocket.setTrafficClass(SocketOptions.IP_TOS);
        Assert.assertFalse(serverSideSocket.isModeKnown());
        // Note: according to the Socket javadocs, setTrafficClass() may be
        // ignored by socket implementations, so we don't check that the value
        // we set is returned.
    }

    @Test
    public void testGetReuseAddress() throws IOException {
        serverSideSocket.getReuseAddress();
        Assert.assertFalse(serverSideSocket.isModeKnown());
    }

    @Test
    public void testSetReuseAddress() throws IOException {
        boolean reuseAddress = serverSideSocket.getReuseAddress();
        reuseAddress = !reuseAddress;
        serverSideSocket.setReuseAddress(reuseAddress);
        Assert.assertFalse(serverSideSocket.isModeKnown());
        Assert.assertEquals(reuseAddress, serverSideSocket.getReuseAddress());
    }

    @Test
    public void testClose() throws IOException {
        serverSideSocket.close();
        Assert.assertFalse(serverSideSocket.isModeKnown());
    }

    @Test
    public void testShutdownInput() throws IOException {
        serverSideSocket.shutdownInput();
        Assert.assertFalse(serverSideSocket.isModeKnown());
    }

    @Test
    public void testShutdownOutput() throws IOException {
        serverSideSocket.shutdownOutput();
        Assert.assertFalse(serverSideSocket.isModeKnown());
    }

    @Test
    public void testIsConnected() {
        serverSideSocket.isConnected();
        Assert.assertFalse(serverSideSocket.isModeKnown());
    }

    @Test
    public void testIsBound() {
        serverSideSocket.isBound();
        Assert.assertFalse(serverSideSocket.isModeKnown());
    }

    @Test
    public void testIsClosed() {
        serverSideSocket.isClosed();
        Assert.assertFalse(serverSideSocket.isModeKnown());
    }

    @Test
    public void testIsInputShutdown() throws IOException {
        serverSideSocket.isInputShutdown();
        Assert.assertFalse(serverSideSocket.isModeKnown());
        serverSideSocket.shutdownInput();
        Assert.assertTrue(serverSideSocket.isInputShutdown());
    }

    @Test
    public void testIsOutputShutdown() throws IOException {
        serverSideSocket.isOutputShutdown();
        Assert.assertFalse(serverSideSocket.isModeKnown());
        serverSideSocket.shutdownOutput();
        Assert.assertTrue(serverSideSocket.isOutputShutdown());
    }
}
