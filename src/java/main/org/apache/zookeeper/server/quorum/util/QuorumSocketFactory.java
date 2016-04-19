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
package org.apache.zookeeper.server.quorum.util;

import org.apache.zookeeper.SSLCertCfg;
import org.apache.zookeeper.common.X509Exception;
import org.apache.zookeeper.common.X509Util;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLServerSocket;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.NoSuchAlgorithmException;

/**
 * Helps with abstracting away SSL gory details for consumers.
 * details.
 */
public class QuorumSocketFactory {
    private static final Logger LOG =
            LoggerFactory.getLogger(QuorumSocketFactory.class.getName());
    public static final String SSL_ENABLED_PROP = "quorum.ssl.enabled";
    private static final int LISTEN_BACKLOG = 20;
    private final boolean sslEnabled;

    private QuorumSocketFactory(boolean sslEnabled) {
        this.sslEnabled = sslEnabled;
    }

    public static QuorumSocketFactory createDefault()
            throws NoSuchAlgorithmException, X509Exception.KeyManagerException,
            X509Exception.TrustManagerException {
        String propValue = System.getProperty(
                QuorumSocketFactory.SSL_ENABLED_PROP);
        if (propValue != null &&
                propValue.compareToIgnoreCase("true") == 0) {
            return QuorumSocketFactory.createForSSL();
        } else {
            return QuorumSocketFactory.createWithoutSSL();
        }
    }

    public static QuorumSocketFactory createWithoutSSL() {
        return new QuorumSocketFactory(false);
    }

    public static QuorumSocketFactory createForSSL() throws NoSuchAlgorithmException, X509Exception.KeyManagerException,
            X509Exception.TrustManagerException {
        return new QuorumSocketFactory(true);
    }

    public ServerSocket buildForServer(final QuorumPeer quorumPeer,
                                       final int listenPort,
                                       final InetAddress bindAddr)
            throws X509Exception, IOException {
        return buildForServer(quorumPeer, listenPort, LISTEN_BACKLOG, bindAddr);
    }

    public ServerSocket buildForServer(final QuorumPeer quorumPeer,
                                       final int port)
            throws X509Exception, IOException {
        return buildForServer(quorumPeer, port, LISTEN_BACKLOG, null);
    }

    public ServerSocket buildForServer(final QuorumPeer quorumPeer,
                                       final int listenPort,
                                       final int backlog,
                                       final InetAddress bindAddr)
            throws X509Exception, IOException {
        ServerSocket s = null;
        if (this.sslEnabled) {
            s = newSslServerSocket(quorumPeer, listenPort, backlog, bindAddr);
        } else {
            s = newServerSocket(listenPort, backlog, bindAddr);
        }
        s.setReuseAddress(true);
        return s;
    }

    public Socket buildForClient(final InetSocketAddress peerAddr,
                                 final SSLCertCfg sslCertCfg) throws
            X509Exception,
            IOException {
        if (this.sslEnabled) {
            return newSslSocket(peerAddr, sslCertCfg);
        } else {
            return newSocket();
        }
    }

    private Socket newSocket() throws IOException {
        return new Socket();
    }

    private Socket newSslSocket(final InetSocketAddress peerAddr,
                                final SSLCertCfg sslCertCfg)
            throws X509Exception, IOException {
        Socket clientSocket = null;
        try {
            clientSocket = X509Util.createSSLContext(peerAddr, sslCertCfg)
                    .getSocketFactory()
                    .createSocket();
        } catch (X509Exception.SSLContextException exp) {
            LOG.error("failed creating ssl client socket, exp: " + exp);
            throw new X509Exception(exp);
        } catch (IOException exp) {
            LOG.error("failed creating ssl client socket, exp: " + exp);
            throw exp;
        }

        return clientSocket;
    }

    private ServerSocket newServerSocket(final int port, final int backlog,
                                         final InetAddress listenAddr)
            throws IOException {
        if (listenAddr != null) {
            return new ServerSocket(port, backlog, listenAddr);
        } else {
            return new ServerSocket(port, backlog);
        }
    }

    private ServerSocket newSslServerSocket(
            final QuorumPeer quorumPeer, final int port,
            final int backlog, final InetAddress listenAddr)
            throws X509Exception {
        SSLServerSocket serverSocket = null;
        try {
            if (listenAddr != null) {
                serverSocket =
                        (SSLServerSocket)X509Util.createSSLContext(quorumPeer)
                                .getServerSocketFactory()
                                .createServerSocket(port, backlog, listenAddr);
            } else {
                // bind to any address
                serverSocket =
                        (SSLServerSocket)X509Util.createSSLContext(quorumPeer)
                                .getServerSocketFactory()
                                .createServerSocket(port, backlog);
            }
        } catch (X509Exception.SSLContextException | IOException exp) {
            LOG.error("creating server socket, exp: " + exp);
            throw new X509Exception(exp);
        }

        // Fail if client does not provide credentials.
        serverSocket.setNeedClientAuth(true);
        return serverSocket;
    }

}
