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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.net.ssl.HandshakeCompletedEvent;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLSocket;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.common.BaseX509ParameterizedTestCase;
import org.apache.zookeeper.common.ClientX509Util;
import org.apache.zookeeper.common.KeyStoreFileType;
import org.apache.zookeeper.common.X509Exception;
import org.apache.zookeeper.common.X509KeyType;
import org.apache.zookeeper.common.X509TestContext;
import org.apache.zookeeper.common.X509Util;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class UnifiedServerSocketTest extends BaseX509ParameterizedTestCase {

    public static Stream<Arguments> data() {
        ArrayList<Arguments> result = new ArrayList<>();
        int paramIndex = 0;
        for (X509KeyType caKeyType : X509KeyType.values()) {
            for (X509KeyType certKeyType : X509KeyType.values()) {
                for (Boolean hostnameVerification : new Boolean[]{true, false}) {
                    result.add(Arguments.of(caKeyType, certKeyType, hostnameVerification, paramIndex++));
                }
            }
        }
        return result.stream();
    }

    private static final int MAX_RETRIES = 5;
    private static final int TIMEOUT = 1000;
    private static final byte[] DATA_TO_CLIENT = "hello client".getBytes();
    private static final byte[] DATA_FROM_CLIENT = "hello server".getBytes();

    private X509Util x509Util;
    private InetSocketAddress localServerAddress;
    private final Object handshakeCompletedLock = new Object();
    // access only inside synchronized(handshakeCompletedLock) { ... } blocks
    private boolean handshakeCompleted = false;

    public void init(
            final X509KeyType caKeyType, final X509KeyType certKeyType, final Boolean hostnameVerification, final Integer paramIndex) {
        super.init(paramIndex, () -> {
            try {
                return X509TestContext.newBuilder().setTempDir(tempDir).setKeyStoreKeyType(certKeyType).setTrustStoreKeyType(caKeyType).setHostnameVerification(hostnameVerification).build();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    public void setUp() throws Exception {
        localServerAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), PortAssignment.unique());
        x509Util = new ClientX509Util();
        x509TestContext.setSystemProperties(x509Util, KeyStoreFileType.JKS, KeyStoreFileType.JKS);
    }

    @AfterEach
    public void tearDown() throws Exception {
        x509TestContext.clearSystemProperties(x509Util);
        x509Util.close();
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

    private static final class UnifiedServerThread extends Thread {

        private final byte[] dataToClient;
        private List<byte[]> dataFromClients;
        private ExecutorService workerPool;
        private UnifiedServerSocket serverSocket;

        UnifiedServerThread(X509Util x509Util, InetSocketAddress bindAddress, boolean allowInsecureConnection, byte[] dataToClient) throws IOException {
            this.dataToClient = dataToClient;
            dataFromClients = new ArrayList<>();
            workerPool = Executors.newCachedThreadPool();
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
                    workerPool.submit(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                byte[] buf = new byte[1024];
                                int bytesRead = unifiedSocket.getInputStream().read(buf, 0, 1024);
                                // Make sure the settings applied above before the socket was potentially upgraded to
                                // TLS still apply.
                                assertEquals(tcpNoDelay, unifiedSocket.getTcpNoDelay());
                                assertEquals(TIMEOUT, unifiedSocket.getSoTimeout());
                                assertEquals(keepAlive, unifiedSocket.getKeepAlive());
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
                                throw new RuntimeException(e);
                            } finally {
                                forceClose(unifiedSocket);
                            }
                        }
                    });
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                forceClose(serverSocket);
                workerPool.shutdown();
            }
        }

        public void shutdown(long millis) throws InterruptedException {
            forceClose(serverSocket); // this should break the run() loop
            workerPool.awaitTermination(millis, TimeUnit.MILLISECONDS);
            this.join(millis);
        }

        synchronized byte[] getDataFromClient(int index) {
            return dataFromClients.get(index);
        }

        synchronized boolean receivedAnyDataFromClient() {
            return !dataFromClients.isEmpty();
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
                        synchronized (handshakeCompletedLock) {
                            handshakeCompleted = true;
                            handshakeCompletedLock.notifyAll();
                        }
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

        assertNotNull(sslSocket, "Failed to connect to server with SSL");
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
        assertNotNull(socket, "Failed to connect to server without SSL");
        return socket;
    }

    // In the tests below, a "Strict" server means a UnifiedServerSocket that
    // does not allow plaintext connections (in other words, it's SSL-only).
    // A "Non Strict" server means a UnifiedServerSocket that allows both
    // plaintext and SSL incoming connections.

    /**
     * Attempting to connect to a SSL-or-plaintext server with SSL should work.
     */
    @ParameterizedTest
    @MethodSource("data")
    public void testConnectWithSSLToNonStrictServer(
        final X509KeyType caKeyType, final X509KeyType certKeyType, final Boolean hostnameVerification, final Integer paramIndex
    ) throws Exception {
        init(caKeyType, certKeyType, hostnameVerification, paramIndex);
        setUp();
        UnifiedServerThread serverThread = new UnifiedServerThread(x509Util, localServerAddress, true, DATA_TO_CLIENT);
        serverThread.start();

        Socket sslSocket = connectWithSSL();
        try {
            sslSocket.getOutputStream().write(DATA_FROM_CLIENT);
            sslSocket.getOutputStream().flush();
            byte[] buf = new byte[DATA_TO_CLIENT.length];
            int bytesRead = sslSocket.getInputStream().read(buf, 0, buf.length);
            assertEquals(buf.length, bytesRead);
            assertArrayEquals(DATA_TO_CLIENT, buf);

            synchronized (handshakeCompletedLock) {
                if (!handshakeCompleted) {
                    handshakeCompletedLock.wait(TIMEOUT);
                }
                assertTrue(handshakeCompleted);
            }
            assertArrayEquals(DATA_FROM_CLIENT, serverThread.getDataFromClient(0));
        } finally {
            forceClose(sslSocket);
            serverThread.shutdown(TIMEOUT);
        }
    }

    /**
     * Attempting to connect to a SSL-only server with SSL should work.
     */
    @ParameterizedTest
    @MethodSource("data")
    public void testConnectWithSSLToStrictServer(
        final X509KeyType caKeyType, final X509KeyType certKeyType, final Boolean hostnameVerification, final Integer paramIndex
    ) throws Exception {
        init(caKeyType, certKeyType, hostnameVerification, paramIndex);
        setUp();
        UnifiedServerThread serverThread = new UnifiedServerThread(x509Util, localServerAddress, false, DATA_TO_CLIENT);
        serverThread.start();

        Socket sslSocket = connectWithSSL();
        try {
            sslSocket.getOutputStream().write(DATA_FROM_CLIENT);
            sslSocket.getOutputStream().flush();
            byte[] buf = new byte[DATA_TO_CLIENT.length];
            int bytesRead = sslSocket.getInputStream().read(buf, 0, buf.length);
            assertEquals(buf.length, bytesRead);
            assertArrayEquals(DATA_TO_CLIENT, buf);

            synchronized (handshakeCompletedLock) {
                if (!handshakeCompleted) {
                    handshakeCompletedLock.wait(TIMEOUT);
                }
                assertTrue(handshakeCompleted);
            }

            assertArrayEquals(DATA_FROM_CLIENT, serverThread.getDataFromClient(0));
        } finally {
            forceClose(sslSocket);
            serverThread.shutdown(TIMEOUT);
        }
    }

    /**
     * Attempting to connect to a SSL-or-plaintext server without SSL should work.
     */
    @ParameterizedTest
    @MethodSource("data")
    public void testConnectWithoutSSLToNonStrictServer(
        final X509KeyType caKeyType, final X509KeyType certKeyType, final Boolean hostnameVerification, final Integer paramIndex
    ) throws Exception {
        init(caKeyType, certKeyType, hostnameVerification, paramIndex);
        setUp();
        UnifiedServerThread serverThread = new UnifiedServerThread(x509Util, localServerAddress, true, DATA_TO_CLIENT);
        serverThread.start();

        Socket socket = connectWithoutSSL();
        try {
            socket.getOutputStream().write(DATA_FROM_CLIENT);
            socket.getOutputStream().flush();
            byte[] buf = new byte[DATA_TO_CLIENT.length];
            int bytesRead = socket.getInputStream().read(buf, 0, buf.length);
            assertEquals(buf.length, bytesRead);
            assertArrayEquals(DATA_TO_CLIENT, buf);
            assertArrayEquals(DATA_FROM_CLIENT, serverThread.getDataFromClient(0));
        } finally {
            forceClose(socket);
            serverThread.shutdown(TIMEOUT);
        }
    }

    /**
     * Attempting to connect to a SSL-or-plaintext server without SSL with a
     * small initial data write should work. This makes sure that sending
     * less than 5 bytes does not break the logic in the server's initial 5
     * byte read.
     */
    @ParameterizedTest
    @MethodSource("data")
    public void testConnectWithoutSSLToNonStrictServerPartialWrite(
        final X509KeyType caKeyType, final X509KeyType certKeyType, final Boolean hostnameVerification, final Integer paramIndex
    ) throws Exception {
        init(caKeyType, certKeyType, hostnameVerification, paramIndex);
        setUp();
        UnifiedServerThread serverThread = new UnifiedServerThread(x509Util, localServerAddress, true, DATA_TO_CLIENT);
        serverThread.start();

        Socket socket = connectWithoutSSL();
        try {
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
            assertEquals(buf.length, bytesRead);
            assertArrayEquals(DATA_TO_CLIENT, buf);
            assertArrayEquals(DATA_FROM_CLIENT, serverThread.getDataFromClient(0));
        } finally {
            forceClose(socket);
            serverThread.shutdown(TIMEOUT);
        }
    }

    /**
     * Attempting to connect to a SSL-only server without SSL should fail.
     */
    @ParameterizedTest
    @MethodSource("data")
    public void testConnectWithoutSSLToStrictServer(
        final X509KeyType caKeyType, final X509KeyType certKeyType, final Boolean hostnameVerification, final Integer paramIndex
    ) throws Exception {
        init(caKeyType, certKeyType, hostnameVerification, paramIndex);
        setUp();
        UnifiedServerThread serverThread = new UnifiedServerThread(x509Util, localServerAddress, false, DATA_TO_CLIENT);
        serverThread.start();

        Socket socket = connectWithoutSSL();
        socket.getOutputStream().write(DATA_FROM_CLIENT);
        socket.getOutputStream().flush();
        byte[] buf = new byte[DATA_TO_CLIENT.length];
        try {
            int bytesRead = socket.getInputStream().read(buf, 0, buf.length);
            if (bytesRead == -1) {
                // Using the NioSocketImpl after JDK 13, the expected behaviour on the client side
                // is to reach the end of the stream (bytesRead == -1), without a socket exception.
                return;
            }
        } catch (SocketException e) {
            // Using the old PlainSocketImpl (prior to JDK 13) we expect to get Socket Exception
            return;
        } finally {
            forceClose(socket);
            serverThread.shutdown(TIMEOUT);

            // independently of the client socket implementation details, we always make sure the
            // server didn't receive any data during the test
            assertFalse(serverThread.receivedAnyDataFromClient(), "The strict server accepted connection without SSL.");
        }
        fail("Expected server to hang up the connection. Read from server succeeded unexpectedly.");
    }

    /**
     * This test makes sure that UnifiedServerSocket used properly (a single
     * thread accept()-ing connections and handing the resulting sockets to
     * other threads for processing) is not vulnerable to blocking the
     * accept() thread while doing mode detection if a misbehaving client
     * connects. A misbehaving client is one that either disconnects
     * immediately, or connects but does not send any data.
     *
     * This version of the test uses a non-strict server socket (i.e. it
     * accepts both TLS and plaintext connections).
     */
    @ParameterizedTest
    @MethodSource("data")
    public void testTLSDetectionNonBlockingNonStrictServerIdleClient(
        final X509KeyType caKeyType, final X509KeyType certKeyType, final Boolean hostnameVerification, final Integer paramIndex
    ) throws Exception {
        init(caKeyType, certKeyType, hostnameVerification, paramIndex);
        setUp();
        Socket badClientSocket = null;
        Socket clientSocket = null;
        Socket secureClientSocket = null;
        UnifiedServerThread serverThread = new UnifiedServerThread(x509Util, localServerAddress, true, DATA_TO_CLIENT);
        serverThread.start();

        try {
            badClientSocket = connectWithoutSSL(); // Leave the bad client socket idle

            clientSocket = connectWithoutSSL();
            clientSocket.getOutputStream().write(DATA_FROM_CLIENT);
            clientSocket.getOutputStream().flush();
            byte[] buf = new byte[DATA_TO_CLIENT.length];
            int bytesRead = clientSocket.getInputStream().read(buf, 0, buf.length);
            assertEquals(buf.length, bytesRead);
            assertArrayEquals(DATA_TO_CLIENT, buf);
            assertArrayEquals(DATA_FROM_CLIENT, serverThread.getDataFromClient(0));

            synchronized (handshakeCompletedLock) {
                assertFalse(handshakeCompleted);
            }

            secureClientSocket = connectWithSSL();
            secureClientSocket.getOutputStream().write(DATA_FROM_CLIENT);
            secureClientSocket.getOutputStream().flush();
            buf = new byte[DATA_TO_CLIENT.length];
            bytesRead = secureClientSocket.getInputStream().read(buf, 0, buf.length);
            assertEquals(buf.length, bytesRead);
            assertArrayEquals(DATA_TO_CLIENT, buf);
            assertArrayEquals(DATA_FROM_CLIENT, serverThread.getDataFromClient(1));

            synchronized (handshakeCompletedLock) {
                if (!handshakeCompleted) {
                    handshakeCompletedLock.wait(TIMEOUT);
                }
                assertTrue(handshakeCompleted);
            }
        } finally {
            forceClose(badClientSocket);
            forceClose(clientSocket);
            forceClose(secureClientSocket);
            serverThread.shutdown(TIMEOUT);
        }
    }

    /**
     * Like the above test, but with a strict server socket (closes non-TLS
     * connections after seeing that there is no handshake).
     */
    @ParameterizedTest
    @MethodSource("data")
    public void testTLSDetectionNonBlockingStrictServerIdleClient(
        final X509KeyType caKeyType, final X509KeyType certKeyType, final Boolean hostnameVerification, final Integer paramIndex
    ) throws Exception {
        init(caKeyType, certKeyType, hostnameVerification, paramIndex);
        setUp();
        Socket badClientSocket = null;
        Socket secureClientSocket = null;
        UnifiedServerThread serverThread = new UnifiedServerThread(x509Util, localServerAddress, false, DATA_TO_CLIENT);
        serverThread.start();

        try {
            badClientSocket = connectWithoutSSL(); // Leave the bad client socket idle

            secureClientSocket = connectWithSSL();
            secureClientSocket.getOutputStream().write(DATA_FROM_CLIENT);
            secureClientSocket.getOutputStream().flush();
            byte[] buf = new byte[DATA_TO_CLIENT.length];
            int bytesRead = secureClientSocket.getInputStream().read(buf, 0, buf.length);
            assertEquals(buf.length, bytesRead);
            assertArrayEquals(DATA_TO_CLIENT, buf);

            synchronized (handshakeCompletedLock) {
                if (!handshakeCompleted) {
                    handshakeCompletedLock.wait(TIMEOUT);
                }
                assertTrue(handshakeCompleted);
            }
            assertArrayEquals(DATA_FROM_CLIENT, serverThread.getDataFromClient(0));
        } finally {
            forceClose(badClientSocket);
            forceClose(secureClientSocket);
            serverThread.shutdown(TIMEOUT);
        }
    }

    /**
     * Similar to the tests above, but the bad client disconnects immediately
     * without sending any data.
     */
    @ParameterizedTest
    @MethodSource("data")
    public void testTLSDetectionNonBlockingNonStrictServerDisconnectedClient(
        final X509KeyType caKeyType, final X509KeyType certKeyType, final Boolean hostnameVerification, final Integer paramIndex
    ) throws Exception {
        init(caKeyType, certKeyType, hostnameVerification, paramIndex);
        setUp();
        Socket clientSocket = null;
        Socket secureClientSocket = null;
        UnifiedServerThread serverThread = new UnifiedServerThread(x509Util, localServerAddress, true, DATA_TO_CLIENT);
        serverThread.start();

        try {
            Socket badClientSocket = connectWithoutSSL();
            forceClose(badClientSocket); // close the bad client socket immediately

            clientSocket = connectWithoutSSL();
            clientSocket.getOutputStream().write(DATA_FROM_CLIENT);
            clientSocket.getOutputStream().flush();
            byte[] buf = new byte[DATA_TO_CLIENT.length];
            int bytesRead = clientSocket.getInputStream().read(buf, 0, buf.length);
            assertEquals(buf.length, bytesRead);
            assertArrayEquals(DATA_TO_CLIENT, buf);
            assertArrayEquals(DATA_FROM_CLIENT, serverThread.getDataFromClient(0));

            synchronized (handshakeCompletedLock) {
                assertFalse(handshakeCompleted);
            }

            secureClientSocket = connectWithSSL();
            secureClientSocket.getOutputStream().write(DATA_FROM_CLIENT);
            secureClientSocket.getOutputStream().flush();
            buf = new byte[DATA_TO_CLIENT.length];
            bytesRead = secureClientSocket.getInputStream().read(buf, 0, buf.length);
            assertEquals(buf.length, bytesRead);
            assertArrayEquals(DATA_TO_CLIENT, buf);
            assertArrayEquals(DATA_FROM_CLIENT, serverThread.getDataFromClient(1));

            synchronized (handshakeCompletedLock) {
                if (!handshakeCompleted) {
                    handshakeCompletedLock.wait(TIMEOUT);
                }
                assertTrue(handshakeCompleted);
            }
        } finally {
            forceClose(clientSocket);
            forceClose(secureClientSocket);
            serverThread.shutdown(TIMEOUT);
        }
    }

    /**
     * Like the above test, but with a strict server socket (closes non-TLS
     * connections after seeing that there is no handshake).
     */
    @ParameterizedTest
    @MethodSource("data")
    public void testTLSDetectionNonBlockingStrictServerDisconnectedClient(
        final X509KeyType caKeyType, final X509KeyType certKeyType, final Boolean hostnameVerification, final Integer paramIndex
    ) throws Exception {
        init(caKeyType, certKeyType, hostnameVerification, paramIndex);
        setUp();
        Socket secureClientSocket = null;
        UnifiedServerThread serverThread = new UnifiedServerThread(x509Util, localServerAddress, false, DATA_TO_CLIENT);
        serverThread.start();

        try {
            Socket badClientSocket = connectWithoutSSL();
            forceClose(badClientSocket); // close the bad client socket immediately

            secureClientSocket = connectWithSSL();
            secureClientSocket.getOutputStream().write(DATA_FROM_CLIENT);
            secureClientSocket.getOutputStream().flush();
            byte[] buf = new byte[DATA_TO_CLIENT.length];
            int bytesRead = secureClientSocket.getInputStream().read(buf, 0, buf.length);
            assertEquals(buf.length, bytesRead);
            assertArrayEquals(DATA_TO_CLIENT, buf);

            synchronized (handshakeCompletedLock) {
                if (!handshakeCompleted) {
                    handshakeCompletedLock.wait(TIMEOUT);
                }
                assertTrue(handshakeCompleted);
            }
            assertArrayEquals(DATA_FROM_CLIENT, serverThread.getDataFromClient(0));
        } finally {
            forceClose(secureClientSocket);
            serverThread.shutdown(TIMEOUT);
        }
    }

}
