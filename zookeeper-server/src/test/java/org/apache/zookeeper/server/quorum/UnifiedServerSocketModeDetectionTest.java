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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketOptions;
import java.security.Security;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This test makes sure that certain operations on a UnifiedServerSocket do not
 * trigger blocking mode detection. This is necessary to ensure that the
 * Leader's accept() thread doesn't get blocked.
 */
public class UnifiedServerSocketModeDetectionTest extends ZKTestCase {

    private static final Logger LOG = LoggerFactory.getLogger(UnifiedServerSocketModeDetectionTest.class);

    private static File tempDir;
    private static X509TestContext x509TestContext;

    private X509Util x509Util;
    private UnifiedServerSocket listeningSocket;
    private UnifiedServerSocket.UnifiedSocket serverSideSocket;
    private Socket clientSocket;
    private ExecutorService workerPool;
    private int port;
    private InetSocketAddress localServerAddress;

    @BeforeAll
    public static void setUpClass() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        tempDir = ClientBase.createEmptyTestDir();
        x509TestContext = X509TestContext.newBuilder().setTempDir(tempDir).setKeyStoreKeyType(X509KeyType.EC).setTrustStoreKeyType(X509KeyType.EC).build();
    }

    @AfterAll
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

    public void init(boolean useSecureClient) throws Exception {
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
                    LOG.error("Error in accept()", e);
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
            clientSocket.getOutputStream().write(new byte[]{1, 2, 3, 4, 5});
        }
        serverSideSocket = acceptFuture.get();
    }

    @AfterEach
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGetInetAddress(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.getInetAddress();
        assertFalse(serverSideSocket.isModeKnown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGetLocalAddress(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.getLocalAddress();
        assertFalse(serverSideSocket.isModeKnown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGetPort(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.getPort();
        assertFalse(serverSideSocket.isModeKnown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGetLocalPort(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.getLocalPort();
        assertFalse(serverSideSocket.isModeKnown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGetRemoteSocketAddress(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.getRemoteSocketAddress();
        assertFalse(serverSideSocket.isModeKnown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGetLocalSocketAddress(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.getLocalSocketAddress();
        assertFalse(serverSideSocket.isModeKnown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGetInputStream(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.getInputStream();
        assertFalse(serverSideSocket.isModeKnown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGetOutputStream(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.getOutputStream();
        assertFalse(serverSideSocket.isModeKnown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGetTcpNoDelay(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.getTcpNoDelay();
        assertFalse(serverSideSocket.isModeKnown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testSetTcpNoDelay(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        boolean tcpNoDelay = serverSideSocket.getTcpNoDelay();
        tcpNoDelay = !tcpNoDelay;
        serverSideSocket.setTcpNoDelay(tcpNoDelay);
        assertFalse(serverSideSocket.isModeKnown());
        assertEquals(tcpNoDelay, serverSideSocket.getTcpNoDelay());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGetSoLinger(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.getSoLinger();
        assertFalse(serverSideSocket.isModeKnown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testSetSoLinger(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        int soLinger = serverSideSocket.getSoLinger();
        if (soLinger == -1) {
            // enable it if disabled
            serverSideSocket.setSoLinger(true, 1);
            assertFalse(serverSideSocket.isModeKnown());
            assertEquals(1, serverSideSocket.getSoLinger());
        } else {
            // disable it if enabled
            serverSideSocket.setSoLinger(false, -1);
            assertFalse(serverSideSocket.isModeKnown());
            assertEquals(-1, serverSideSocket.getSoLinger());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGetSoTimeout(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.getSoTimeout();
        assertFalse(serverSideSocket.isModeKnown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testSetSoTimeout(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        int timeout = serverSideSocket.getSoTimeout();
        timeout = timeout + 10;
        serverSideSocket.setSoTimeout(timeout);
        assertFalse(serverSideSocket.isModeKnown());
        assertEquals(timeout, serverSideSocket.getSoTimeout());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGetSendBufferSize(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.getSendBufferSize();
        assertFalse(serverSideSocket.isModeKnown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testSetSendBufferSize(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.setSendBufferSize(serverSideSocket.getSendBufferSize() + 1024);
        assertFalse(serverSideSocket.isModeKnown());
        // Note: the new buffer size is a hint and socket implementation
        // is free to ignore it, so we don't verify that we get back the
        // same value.

    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGetReceiveBufferSize(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.getReceiveBufferSize();
        assertFalse(serverSideSocket.isModeKnown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testSetReceiveBufferSize(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.setReceiveBufferSize(serverSideSocket.getReceiveBufferSize() + 1024);
        assertFalse(serverSideSocket.isModeKnown());
        // Note: the new buffer size is a hint and socket implementation
        // is free to ignore it, so we don't verify that we get back the
        // same value.

    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGetKeepAlive(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.getKeepAlive();
        assertFalse(serverSideSocket.isModeKnown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testSetKeepAlive(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        boolean keepAlive = serverSideSocket.getKeepAlive();
        keepAlive = !keepAlive;
        serverSideSocket.setKeepAlive(keepAlive);
        assertFalse(serverSideSocket.isModeKnown());
        assertEquals(keepAlive, serverSideSocket.getKeepAlive());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGetTrafficClass(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.getTrafficClass();
        assertFalse(serverSideSocket.isModeKnown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testSetTrafficClass(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.setTrafficClass(SocketOptions.IP_TOS);
        assertFalse(serverSideSocket.isModeKnown());
        // Note: according to the Socket javadocs, setTrafficClass() may be
        // ignored by socket implementations, so we don't check that the value
        // we set is returned.
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGetReuseAddress(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.getReuseAddress();
        assertFalse(serverSideSocket.isModeKnown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testSetReuseAddress(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        boolean reuseAddress = serverSideSocket.getReuseAddress();
        reuseAddress = !reuseAddress;
        serverSideSocket.setReuseAddress(reuseAddress);
        assertFalse(serverSideSocket.isModeKnown());
        assertEquals(reuseAddress, serverSideSocket.getReuseAddress());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testClose(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.close();
        assertFalse(serverSideSocket.isModeKnown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testShutdownInput(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.shutdownInput();
        assertFalse(serverSideSocket.isModeKnown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testShutdownOutput(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.shutdownOutput();
        assertFalse(serverSideSocket.isModeKnown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testIsConnected(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.isConnected();
        assertFalse(serverSideSocket.isModeKnown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testIsBound(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.isBound();
        assertFalse(serverSideSocket.isModeKnown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testIsClosed(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.isClosed();
        assertFalse(serverSideSocket.isModeKnown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testIsInputShutdown(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.isInputShutdown();
        assertFalse(serverSideSocket.isModeKnown());
        serverSideSocket.shutdownInput();
        assertTrue(serverSideSocket.isInputShutdown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testIsOutputShutdown(boolean useSecureClient) throws Exception {
        init(useSecureClient);
        serverSideSocket.isOutputShutdown();
        assertFalse(serverSideSocket.isModeKnown());
        serverSideSocket.shutdownOutput();
        assertTrue(serverSideSocket.isOutputShutdown());
    }

}
