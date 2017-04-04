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
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.common.X509Util;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import javax.net.ssl.HandshakeCompletedEvent;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public class UnifiedServerSocketTest {

    private X509Util x509Util;
    private int port;
    private volatile boolean handshakeCompleted;

    @Before
    public void setUp() throws Exception {
        handshakeCompleted = false;

        port = PortAssignment.unique();

        String testDataPath = System.getProperty("test.data.dir", "build/test/data");
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, "org.apache.zookeeper.server.NettyServerCnxnFactory");
        System.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET, "org.apache.zookeeper.ClientCnxnSocketNetty");
        System.setProperty(ZKClientConfig.SECURE_CLIENT, "true");

        x509Util = new ClientX509Util();

        System.setProperty(x509Util.getSslKeystoreLocationProperty(), testDataPath + "/ssl/testKeyStore.jks");
        System.setProperty(x509Util.getSslKeystorePasswdProperty(), "testpass");
        System.setProperty(x509Util.getSslTruststoreLocationProperty(), testDataPath + "/ssl/testTrustStore.jks");
        System.setProperty(x509Util.getSslTruststorePasswdProperty(), "testpass");
    }

    @Test
    public void testConnectWithSSL() throws Exception {
        class ServerThread extends Thread {
            public void run() {
                try {
                    Socket unifiedSocket = new UnifiedServerSocket(x509Util, port).accept();
                    ((SSLSocket)unifiedSocket).getSession(); // block until handshake completes
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        ServerThread serverThread = new ServerThread();
        serverThread.start();

        SSLSocket sslSocket = x509Util.createSSLSocket();
        sslSocket.connect(new InetSocketAddress(port), 1000);
        sslSocket.addHandshakeCompletedListener(new HandshakeCompletedListener() {
            @Override
            public void handshakeCompleted(HandshakeCompletedEvent handshakeCompletedEvent) {
                completeHandshake();
            }
        });
        sslSocket.startHandshake();

        serverThread.join(1000);

        long start = Time.currentElapsedTime();
        while (Time.currentElapsedTime() < start + 10000) {
            if (handshakeCompleted) {
                return;
            }
        }

        Assert.fail("failed to complete handshake");
    }

    private void completeHandshake() {
        handshakeCompleted = true;
    }

    @Test
    public void testConnectWithoutSSL() throws Exception {
        final byte[] testData = "hello there".getBytes();

        class ServerThread extends Thread {
            public void run() {
                try {
                    Socket unifiedSocket = new UnifiedServerSocket(x509Util, port).accept();
                    unifiedSocket.getOutputStream().write(testData);
                    unifiedSocket.getOutputStream().flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        ServerThread serverThread = new ServerThread();
        serverThread.start();

        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(port), 1000);
        socket.getOutputStream().write("hello".getBytes());
        socket.getOutputStream().flush();

        byte[] readBytes = new byte[testData.length];
        socket.getInputStream().read(readBytes, 0, testData.length);

        serverThread.join(1000);

        Assert.assertArrayEquals(testData, readBytes);
    }
}
