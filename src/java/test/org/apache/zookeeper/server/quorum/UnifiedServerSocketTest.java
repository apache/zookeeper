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

import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.ClientX509Util;
import org.apache.zookeeper.common.X509Exception;
import org.apache.zookeeper.common.X509KeyType;
import org.apache.zookeeper.common.X509TestContext;
import org.apache.zookeeper.common.X509Util;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.test.ClientBase;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.net.ssl.HandshakeCompletedEvent;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLSocket;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.security.Security;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@RunWith(Parameterized.class)
public class UnifiedServerSocketTest {

    @Parameterized.Parameters
    public static Collection<Object[]> params() {
        ArrayList<Object[]> result = new ArrayList<>();
        int paramIndex = 0;
        for (X509KeyType caKeyType : X509KeyType.values()) {
            for (X509KeyType certKeyType : X509KeyType.values()) {
                for (Boolean hostnameVerification : new Boolean[] { true, false  }) {
                    result.add(new Object[]{
                            caKeyType,
                            certKeyType,
                            hostnameVerification,
                            paramIndex++
                    });
                }
            }
        }
        return result;
    }

    /**
     * Because key generation and writing / deleting files is kind of expensive, we cache the certs and on-disk files
     * between test cases. None of the test cases modify any of this data so it's safe to reuse between tests. This
     * caching makes all test cases after the first one for a given parameter combination complete almost instantly.
     */
    private static Map<Integer, X509TestContext> cachedTestContexts;
    private static File tempDir;

    private static final int MAX_RETRIES = 5;
    private static final int TIMEOUT = 1000;
    private static final byte[] DATA_TO_CLIENT = "hello client".getBytes();
    private static final byte[] DATA_FROM_CLIENT = "hello server".getBytes();

    private X509Util x509Util;
    private int port;
    private InetSocketAddress localServerAddress;
    private volatile boolean handshakeCompleted;
    private X509TestContext x509TestContext;

    @BeforeClass
    public static void setUpClass() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        cachedTestContexts = new HashMap<>();
        tempDir = ClientBase.createEmptyTestDir();
    }

    @AfterClass
    public static void cleanUpClass() {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
        cachedTestContexts.clear();
        cachedTestContexts = null;
    }

    public UnifiedServerSocketTest(
            X509KeyType caKeyType,
            X509KeyType certKeyType,
            Boolean hostnameVerification,
            Integer paramIndex) throws Exception {
        if (cachedTestContexts.containsKey(paramIndex)) {
            x509TestContext = cachedTestContexts.get(paramIndex);
        } else {
            x509TestContext = X509TestContext.newBuilder()
                    .setTempDir(tempDir)
                    .setKeyStoreKeyType(certKeyType)
                    .setTrustStoreKeyType(caKeyType)
                    .setHostnameVerification(hostnameVerification)
                    .build();
            cachedTestContexts.put(paramIndex, x509TestContext);
        }
    }

    @Before
    public void setUp() throws Exception {
        handshakeCompleted = false;

        port = PortAssignment.unique();
        localServerAddress = new InetSocketAddress("localhost", port);

        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, "org.apache.zookeeper.server.NettyServerCnxnFactory");
        System.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET, "org.apache.zookeeper.ClientCnxnSocketNetty");
        System.setProperty(ZKClientConfig.SECURE_CLIENT, "true");

        x509Util = new ClientX509Util();

        x509TestContext.setSystemProperties(x509Util, X509Util.StoreFileType.JKS, X509Util.StoreFileType.JKS);
    }

    private static void forceClose(java.io.Closeable s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static final class UnifiedServerThread extends Thread {
        private final byte[] dataToClient;
        private List<byte[]> dataFromClients;
        private List<Thread> workerThreads;
        private UnifiedServerSocket serverSocket;

        UnifiedServerThread(X509Util x509Util,
                            InetSocketAddress bindAddress,
                            boolean allowInsecureConnection,
                            byte[] dataToClient) throws IOException {
            this.dataToClient = dataToClient;
            dataFromClients = new ArrayList<>();
            workerThreads = new ArrayList<>();
            serverSocket = new UnifiedServerSocket(x509Util, allowInsecureConnection);
            serverSocket.bind(bindAddress);
        }

        @Override
        public void run() {
            try {
                Random rnd = new Random();
                while (true) {
                    final Socket unifiedSocket = serverSocket.accept();
                    final boolean tcpNoDelay = rnd.nextBoolean();
                    unifiedSocket.setTcpNoDelay(tcpNoDelay);
                    unifiedSocket.setSoTimeout(TIMEOUT);
                    final boolean keepAlive = rnd.nextBoolean();
                    unifiedSocket.setKeepAlive(keepAlive);
                    // Note: getting the input stream should not block the thread or trigger mode detection.
                    BufferedInputStream bis = new BufferedInputStream(unifiedSocket.getInputStream());
                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                byte[] buf = new byte[1024];
                                int bytesRead = unifiedSocket.getInputStream().read(buf, 0, 1024);
                                // Make sure the settings applied above before the socket was potentially upgraded to
                                // TLS still apply.
                                Assert.assertEquals(tcpNoDelay, unifiedSocket.getTcpNoDelay());
                                Assert.assertEquals(TIMEOUT, unifiedSocket.getSoTimeout());
                                Assert.assertEquals(keepAlive, unifiedSocket.getKeepAlive());
                                if (bytesRead > 0) {
                                    byte[] dataFromClient = new byte[bytesRead];
                                    System.arraycopy(buf, 0, dataFromClient, 0, bytesRead);
                                    synchronized (dataFromClients) {
                                        dataFromClients.add(dataFromClient);
                                    }
                                }
                                unifiedSocket.getOutputStream().write(dataToClient);
                                unifiedSocket.getOutputStream().flush();
                            } catch (IOException e) {
                                e.printStackTrace();
                                throw new RuntimeException(e);
                            } finally {
                                forceClose(unifiedSocket);
                            }
                        }
                    });
                    workerThreads.add(t);
                    t.start();
                }
            } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            } finally {
                forceClose(serverSocket);
            }
        }

        public void shutdown(long millis) throws InterruptedException {
            forceClose(serverSocket); // this should break the run() loop
            for (Thread t : workerThreads) {
                t.join(millis);
            }
            this.join(millis);
        }

        synchronized byte[] getDataFromClient(int index) {
            return dataFromClients.get(index);
        }
    }

    private SSLSocket connectWithSSL() throws IOException, X509Exception, InterruptedException {
        SSLSocket sslSocket = null;
        int retries = 0;
        while (retries < MAX_RETRIES) {
            try {
                sslSocket = x509Util.createSSLSocket();
                sslSocket.addHandshakeCompletedListener(new HandshakeCompletedListener() {
                    @Override
                    public void handshakeCompleted(HandshakeCompletedEvent handshakeCompletedEvent) {
                        handshakeCompleted = true;
                    }
                });
                sslSocket.setSoTimeout(TIMEOUT);
                sslSocket.connect(localServerAddress, TIMEOUT);
                break;
            } catch (ConnectException connectException) {
                connectException.printStackTrace();
                forceClose(sslSocket);
                sslSocket = null;
                Thread.sleep(TIMEOUT);
            }
            retries++;
        }

        Assert.assertNotNull("Failed to connect to server with SSL", sslSocket);
        return sslSocket;
    }

    private Socket connectWithoutSSL() throws IOException, InterruptedException {
        Socket socket = null;
        int retries = 0;
        while (retries < MAX_RETRIES) {
            try {
                socket = new Socket();
                socket.setSoTimeout(TIMEOUT);
                socket.connect(localServerAddress, TIMEOUT);
                break;
            } catch (ConnectException connectException) {
                connectException.printStackTrace();
                forceClose(socket);
                socket = null;
                Thread.sleep(TIMEOUT);
            }
            retries++;
        }
        Assert.assertNotNull("Failed to connect to server without SSL", socket);
        return socket;
    }

    @Test
    public void testConnectWithSSLToNonStrictServer() throws Exception {
        UnifiedServerThread serverThread = new UnifiedServerThread(
                x509Util, localServerAddress, true, DATA_TO_CLIENT);
        serverThread.start();

        Socket sslSocket = connectWithSSL();
        sslSocket.getOutputStream().write(DATA_FROM_CLIENT);
        sslSocket.getOutputStream().flush();
        byte[] buf = new byte[DATA_TO_CLIENT.length];
        int bytesRead = sslSocket.getInputStream().read(buf, 0, buf.length);
        Assert.assertEquals(buf.length, bytesRead);
        Assert.assertArrayEquals(DATA_TO_CLIENT, buf);

        serverThread.shutdown(TIMEOUT);
        forceClose(sslSocket);

        Assert.assertTrue(handshakeCompleted);
        Assert.assertArrayEquals(DATA_FROM_CLIENT, serverThread.getDataFromClient(0));
    }

    @Test
    public void testConnectWithSSLToStrictServer() throws Exception {
        UnifiedServerThread serverThread = new UnifiedServerThread(
                x509Util, localServerAddress, false, DATA_TO_CLIENT);
        serverThread.start();

        Socket sslSocket = connectWithSSL();
        sslSocket.getOutputStream().write(DATA_FROM_CLIENT);
        sslSocket.getOutputStream().flush();
        byte[] buf = new byte[DATA_TO_CLIENT.length];
        int bytesRead = sslSocket.getInputStream().read(buf, 0, buf.length);
        Assert.assertEquals(buf.length, bytesRead);
        Assert.assertArrayEquals(DATA_TO_CLIENT, buf);

        serverThread.shutdown(TIMEOUT);
        forceClose(sslSocket);

        Assert.assertTrue(handshakeCompleted);

        Assert.assertArrayEquals(DATA_FROM_CLIENT, serverThread.getDataFromClient(0));
    }

    @Test
    public void testConnectWithoutSSLToNonStrictServer() throws Exception {
        UnifiedServerThread serverThread = new UnifiedServerThread(
                x509Util, localServerAddress, true, DATA_TO_CLIENT);
        serverThread.start();

        Socket socket = connectWithoutSSL();
        socket.getOutputStream().write(DATA_FROM_CLIENT);
        socket.getOutputStream().flush();
        byte[] buf = new byte[DATA_TO_CLIENT.length];
        int bytesRead = socket.getInputStream().read(buf, 0, buf.length);
        Assert.assertEquals(buf.length, bytesRead);
        Assert.assertArrayEquals(DATA_TO_CLIENT, buf);

        serverThread.shutdown(TIMEOUT);
        forceClose(socket);

        Assert.assertArrayEquals(DATA_FROM_CLIENT, serverThread.getDataFromClient(0));
    }

    @Test
    public void testConnectWithoutSSLToNonStrictServerPartialWrite() throws Exception {
        UnifiedServerThread serverThread = new UnifiedServerThread(
                x509Util, localServerAddress, true, DATA_TO_CLIENT);
        serverThread.start();

        Socket socket = connectWithoutSSL();
        // Write only 2 bytes of the message, wait a bit, then write the rest.
        // This makes sure that writes smaller than 5 bytes don't break the plaintext mode on the server
        // once it decides that the input doesn't look like a TLS handshake.
        socket.getOutputStream().write(DATA_FROM_CLIENT, 0, 2);
        socket.getOutputStream().flush();
        Thread.sleep(TIMEOUT / 2);
        socket.getOutputStream().write(DATA_FROM_CLIENT, 2, DATA_FROM_CLIENT.length - 2);
        socket.getOutputStream().flush();
        byte[] buf = new byte[DATA_TO_CLIENT.length];
        int bytesRead = socket.getInputStream().read(buf, 0, buf.length);
        Assert.assertEquals(buf.length, bytesRead);
        Assert.assertArrayEquals(DATA_TO_CLIENT, buf);

        serverThread.shutdown(TIMEOUT);
        forceClose(socket);

        Assert.assertArrayEquals(DATA_FROM_CLIENT, serverThread.getDataFromClient(0));
    }

    @Test
    public void testConnectWithoutSSLToStrictServer() throws Exception {
        UnifiedServerThread serverThread = new UnifiedServerThread(
                x509Util, localServerAddress, false, DATA_TO_CLIENT);
        serverThread.start();

        Socket socket = connectWithoutSSL();
        socket.getOutputStream().write(DATA_FROM_CLIENT);
        socket.getOutputStream().flush();
        byte[] buf = new byte[DATA_TO_CLIENT.length];
        try {
            socket.getInputStream().read(buf, 0, buf.length);
        } catch (SocketException e) {
            // We expect the other end to hang up the connection
            serverThread.shutdown(TIMEOUT);
            forceClose(socket);
            return;
        }
        Assert.fail("Expected server to hang up the connection. Read from server succeeded unexpectedly.");
    }

    /**
     * This test makes sure that UnifiedServerSocket used properly (a single thread accept()-ing connections and
     * handing the resulting sockets to other threads for processing) is not vulnerable to a simple denial-of-service
     * attack in which a client connects and never writes any bytes. This should not block the accepting thread, since
     * the read to determine if the client is sending a TLS handshake or not happens in the processing thread.
     *
     * This version of the test uses a non-strict server socket (i.e. it accepts both TLS and plaintext connections).
     */
    @Test
    public void testDenialOfServiceResistanceNonStrictServer() throws Exception {
        UnifiedServerThread serverThread = new UnifiedServerThread(
                x509Util, localServerAddress, true, DATA_TO_CLIENT);
        serverThread.start();
        final boolean[] dosThreadConnected = new boolean[] { false };

        Thread dosThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket socket = connectWithoutSSL();
                    synchronized (dosThreadConnected) {
                        dosThreadConnected[0] = true;
                        dosThreadConnected.notifyAll();
                    }
                    Thread.sleep(100000L);
                } catch (Exception e) {
                    // ...
                }
            }
        });
        dosThread.start();
        // make sure the denial-of-service thread connects first
        synchronized (dosThreadConnected) {
            while (!dosThreadConnected[0]) {
                dosThreadConnected.wait();
            }
        }

        Socket socket = connectWithoutSSL();
        socket.getOutputStream().write(DATA_FROM_CLIENT);
        socket.getOutputStream().flush();
        byte[] buf = new byte[DATA_TO_CLIENT.length];
        int bytesRead = socket.getInputStream().read(buf, 0, buf.length);
        Assert.assertEquals(buf.length, bytesRead);
        Assert.assertArrayEquals(DATA_TO_CLIENT, buf);
        Assert.assertFalse(handshakeCompleted);
        Assert.assertArrayEquals(DATA_FROM_CLIENT, serverThread.getDataFromClient(0));
        forceClose(socket);

        socket = connectWithSSL();
        socket.getOutputStream().write(DATA_FROM_CLIENT);
        socket.getOutputStream().flush();
        buf = new byte[DATA_TO_CLIENT.length];
        bytesRead = socket.getInputStream().read(buf, 0, buf.length);
        Assert.assertEquals(buf.length, bytesRead);
        Assert.assertArrayEquals(DATA_TO_CLIENT, buf);
        Assert.assertTrue(handshakeCompleted);
        Assert.assertArrayEquals(DATA_FROM_CLIENT, serverThread.getDataFromClient(1));
        forceClose(socket);

        serverThread.shutdown(TIMEOUT);
        dosThread.interrupt();
        dosThread.join(TIMEOUT);
    }

    /**
     * Like the above test, but with a strict server socket (closes non-TLS connections after seeing that there is
     * no handshake).
     */
    @Test
    public void testDenialOfServiceResistanceStrictServer() throws Exception {
        UnifiedServerThread serverThread = new UnifiedServerThread(
                x509Util, localServerAddress, false, DATA_TO_CLIENT);
        serverThread.start();
        final boolean[] dosThreadConnected = new boolean[] { false };

        Thread dosThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket socket = connectWithoutSSL();
                    synchronized (dosThreadConnected) {
                        dosThreadConnected[0] = true;
                        dosThreadConnected.notifyAll();
                    }
                    Thread.sleep(100000L);
                } catch (Exception e) {
                    // ...
                }
            }
        });
        dosThread.start();
        // make sure the denial-of-service thread connects first
        synchronized (dosThreadConnected) {
            while (!dosThreadConnected[0]) {
                dosThreadConnected.wait();
            }
        }

        Socket sslSocket = connectWithSSL();
        sslSocket.getOutputStream().write(DATA_FROM_CLIENT);
        sslSocket.getOutputStream().flush();
        byte[] buf = new byte[DATA_TO_CLIENT.length];
        int bytesRead = sslSocket.getInputStream().read(buf, 0, buf.length);
        Assert.assertEquals(buf.length, bytesRead);
        Assert.assertArrayEquals(DATA_TO_CLIENT, buf);

        serverThread.shutdown(TIMEOUT);
        forceClose(sslSocket);

        Assert.assertTrue(handshakeCompleted);
        Assert.assertArrayEquals(DATA_FROM_CLIENT, serverThread.getDataFromClient(0));
        dosThread.interrupt();
        dosThread.join(TIMEOUT);
    }

    /**
     * Similar to the DoS resistance tests above, but the bad client disconnects immediately without sending any data.
     * @throws Exception
     */
    @Test
    public void testImmediateDisconnectResistanceNonStrictServer() throws Exception {
        UnifiedServerThread serverThread = new UnifiedServerThread(
                x509Util, localServerAddress, true, DATA_TO_CLIENT);
        serverThread.start();
        final boolean[] disconnectThreadConnected = new boolean[] { false };

        Thread disconnectThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket socket = connectWithoutSSL();
                    socket.close();
                    synchronized (disconnectThreadConnected) {
                        disconnectThreadConnected[0] = true;
                        disconnectThreadConnected.notifyAll();
                    }
                } catch (Exception e) {
                    // ...
                }
            }
        });
        disconnectThread.start();
        // make sure the disconnect thread connects first
        synchronized (disconnectThreadConnected) {
            while (!disconnectThreadConnected[0]) {
                disconnectThreadConnected.wait();
            }
        }

        Socket socket = connectWithoutSSL();
        socket.getOutputStream().write(DATA_FROM_CLIENT);
        socket.getOutputStream().flush();
        byte[] buf = new byte[DATA_TO_CLIENT.length];
        int bytesRead = socket.getInputStream().read(buf, 0, buf.length);
        Assert.assertEquals(buf.length, bytesRead);
        Assert.assertArrayEquals(DATA_TO_CLIENT, buf);
        Assert.assertFalse(handshakeCompleted);
        Assert.assertArrayEquals(DATA_FROM_CLIENT, serverThread.getDataFromClient(0));
        forceClose(socket);

        socket = connectWithSSL();
        socket.getOutputStream().write(DATA_FROM_CLIENT);
        socket.getOutputStream().flush();
        buf = new byte[DATA_TO_CLIENT.length];
        bytesRead = socket.getInputStream().read(buf, 0, buf.length);
        Assert.assertEquals(buf.length, bytesRead);
        Assert.assertArrayEquals(DATA_TO_CLIENT, buf);
        Assert.assertTrue(handshakeCompleted);
        Assert.assertArrayEquals(DATA_FROM_CLIENT, serverThread.getDataFromClient(1));
        forceClose(socket);

        serverThread.shutdown(TIMEOUT);
        disconnectThread.join(TIMEOUT);
    }

    /**
     * Like the above test, but with a strict server socket (closes non-TLS connections after seeing that there is
     * no handshake).
     */
    @Test
    public void testImmediateDisconnectResistanceStrictServer() throws Exception {
        UnifiedServerThread serverThread = new UnifiedServerThread(
                x509Util, localServerAddress, false, DATA_TO_CLIENT);
        serverThread.start();
        final boolean[] disconnectThreadConnected = new boolean[] { false };

        Thread disconnectThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket socket = connectWithoutSSL();
                    socket.close();
                    synchronized (disconnectThreadConnected) {
                        disconnectThreadConnected[0] = true;
                        disconnectThreadConnected.notifyAll();
                    }
                } catch (Exception e) {
                    // ...
                }
            }
        });
        disconnectThread.start();
        // make sure the disconnect thread connects first
        synchronized (disconnectThreadConnected) {
            while (!disconnectThreadConnected[0]) {
                disconnectThreadConnected.wait();
            }
        }

        Socket sslSocket = connectWithSSL();
        sslSocket.getOutputStream().write(DATA_FROM_CLIENT);
        sslSocket.getOutputStream().flush();
        byte[] buf = new byte[DATA_TO_CLIENT.length];
        int bytesRead = sslSocket.getInputStream().read(buf, 0, buf.length);
        Assert.assertEquals(buf.length, bytesRead);
        Assert.assertArrayEquals(DATA_TO_CLIENT, buf);

        serverThread.shutdown(TIMEOUT);
        forceClose(sslSocket);

        Assert.assertTrue(handshakeCompleted);
        Assert.assertArrayEquals(DATA_FROM_CLIENT, serverThread.getDataFromClient(0));
        disconnectThread.interrupt();
        disconnectThread.join(TIMEOUT);
    }
}
