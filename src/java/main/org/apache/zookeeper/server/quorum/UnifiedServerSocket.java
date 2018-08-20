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

import org.apache.zookeeper.common.X509Exception;
import org.apache.zookeeper.common.X509Util;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.ssl.SslHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

/**
 * A ServerSocket that can act either as a regular ServerSocket, as a SSLServerSocket, or as both, depending on
 * the constructor parameters and on the type of client (TLS or plaintext) that connects to it.
 * The constructors have the same signature as constructors of ServerSocket, with the addition of two parameters
 * at the beginning:
 * <ul>
 *     <li>X509Util - provides the SSL context to construct a secure socket when a client connects with TLS.</li>
 *     <li>boolean allowInsecureConnection - when true, acts as a hybrid server socket (plaintext / TLS). When
 *         false, acts as a SSLServerSocket (rejects plaintext connections).</li>
 * </ul>
 * The <code>!allowInsecureConnection</code> mode is needed so we can update the SSLContext (in particular, the
 * key store and/or trust store) without having to re-create the server socket. By starting with a plaintext socket
 * and delaying the upgrade to TLS until after a client has connected and begins a handshake, we can keep the same
 * UnifiedServerSocket instance around, and replace the default SSLContext in the provided X509Util when the key store
 * and/or trust store file changes on disk.
 */
public class UnifiedServerSocket extends ServerSocket {
    private static final Logger LOG = LoggerFactory.getLogger(UnifiedServerSocket.class);

    private X509Util x509Util;
    private final boolean allowInsecureConnection;

    /**
     * Creates an unbound unified server socket by calling {@link ServerSocket#ServerSocket()}.
     * Secure client connections will be upgraded to TLS once this socket detects the ClientHello message (start of a
     * TLS handshake). Plaintext client connections will either be accepted or rejected depending on the value of
     * the <code>allowInsecureConnection</code> parameter.
     * @param x509Util the X509Util that provides the SSLContext to use for secure connections.
     * @param allowInsecureConnection if true, accept plaintext connections, otherwise close them.
     * @throws IOException if {@link ServerSocket#ServerSocket()} throws.
     */
    public UnifiedServerSocket(X509Util x509Util, boolean allowInsecureConnection) throws IOException {
        super();
        this.x509Util = x509Util;
        this.allowInsecureConnection = allowInsecureConnection;
    }

    /**
     * Creates a unified server socket bound to the specified port by calling {@link ServerSocket#ServerSocket(int)}.
     * Secure client connections will be upgraded to TLS once this socket detects the ClientHello message (start of a
     * TLS handshake). Plaintext client connections will either be accepted or rejected depending on the value of
     * the <code>allowInsecureConnection</code> parameter.
     * @param x509Util the X509Util that provides the SSLContext to use for secure connections.
     * @param allowInsecureConnection if true, accept plaintext connections, otherwise close them.
     * @param port the port number, or {@code 0} to use a port number that is automatically allocated.
     * @throws IOException if {@link ServerSocket#ServerSocket(int)} throws.
     */
    public UnifiedServerSocket(X509Util x509Util, boolean allowInsecureConnection, int port) throws IOException {
        super(port);
        this.x509Util = x509Util;
        this.allowInsecureConnection = allowInsecureConnection;
    }

    /**
     * Creates a unified server socket bound to the specified port, with the specified backlog, by calling
     * {@link ServerSocket#ServerSocket(int, int)}.
     * Secure client connections will be upgraded to TLS once this socket detects the ClientHello message (start of a
     * TLS handshake). Plaintext client connections will either be accepted or rejected depending on the value of
     * the <code>allowInsecureConnection</code> parameter.
     * @param x509Util the X509Util that provides the SSLContext to use for secure connections.
     * @param allowInsecureConnection if true, accept plaintext connections, otherwise close them.
     * @param port the port number, or {@code 0} to use a port number that is automatically allocated.
     * @param backlog requested maximum length of the queue of incoming connections.
     * @throws IOException if {@link ServerSocket#ServerSocket(int, int)} throws.
     */
    public UnifiedServerSocket(X509Util x509Util,
                               boolean allowInsecureConnection,
                               int port,
                               int backlog) throws IOException {
        super(port, backlog);
        this.x509Util = x509Util;
        this.allowInsecureConnection = allowInsecureConnection;
    }

    /**
     * Creates a unified server socket bound to the specified port, with the specified backlog, and local IP address
     * to bind to, by calling {@link ServerSocket#ServerSocket(int, int, InetAddress)}.
     * Secure client connections will be upgraded to TLS once this socket detects the ClientHello message (start of a
     * TLS handshake). Plaintext client connections will either be accepted or rejected depending on the value of
     * the <code>allowInsecureConnection</code> parameter.
     * @param x509Util the X509Util that provides the SSLContext to use for secure connections.
     * @param allowInsecureConnection if true, accept plaintext connections, otherwise close them.
     * @param port the port number, or {@code 0} to use a port number that is automatically allocated.
     * @param backlog requested maximum length of the queue of incoming connections.
     * @param bindAddr the local InetAddress the server will bind to.
     * @throws IOException if {@link ServerSocket#ServerSocket(int, int, InetAddress)} throws.
     */
    public UnifiedServerSocket(X509Util x509Util,
                               boolean allowInsecureConnection,
                               int port,
                               int backlog,
                               InetAddress bindAddr) throws IOException {
        super(port, backlog, bindAddr);
        this.x509Util = x509Util;
        this.allowInsecureConnection = allowInsecureConnection;
    }

    @Override
    public Socket accept() throws IOException {
        if (isClosed()) {
            throw new SocketException("Socket is closed");
        }
        if (!isBound()) {
            throw new SocketException("Socket is not bound yet");
        }
        final PrependableSocket prependableSocket = new PrependableSocket(null);
        implAccept(prependableSocket);

        byte[] litmus = new byte[5];
        int bytesRead = prependableSocket.getInputStream().read(litmus, 0, 5);

        if (bytesRead == 5 && SslHandler.isEncrypted(ChannelBuffers.wrappedBuffer(litmus))) {
            LOG.info(getInetAddress() + " attempting to connect over ssl");
            SSLSocket sslSocket;
            try {
                sslSocket = x509Util.createSSLSocket(prependableSocket, litmus);
            } catch (X509Exception e) {
                throw new IOException("failed to create SSL context", e);
            }
            sslSocket.setUseClientMode(false);
            sslSocket.setNeedClientAuth(true); // TODO: probably need to add a property for this in X509Util
            return sslSocket;
        } else {
            if (allowInsecureConnection) {
                LOG.info(getInetAddress() + " attempting to connect without ssl");
                prependableSocket.prependToInputStream(litmus);
                return prependableSocket;
            } else {
                LOG.info(getInetAddress() + " attempted to connect without ssl but allowInsecureConnection is false, " +
                    "closing socket");
                prependableSocket.close();
                throw new IOException("Blocked insecure connection attempt");
            }
        }
    }
}
