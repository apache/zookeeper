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

import io.netty.buffer.Unpooled;
import io.netty.handler.ssl.SslHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.channels.SocketChannel;
import javax.net.ssl.SSLSocket;
import org.apache.zookeeper.common.X509Exception;
import org.apache.zookeeper.common.X509Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    public UnifiedServerSocket(X509Util x509Util, boolean allowInsecureConnection, int port, int backlog) throws IOException {
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
    public UnifiedServerSocket(X509Util x509Util, boolean allowInsecureConnection, int port, int backlog, InetAddress bindAddr) throws IOException {
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
        return new UnifiedSocket(x509Util, allowInsecureConnection, prependableSocket);
    }

    /**
     * The result of calling accept() on a UnifiedServerSocket. This is a Socket that doesn't know if it's
     * using plaintext or SSL/TLS at the time when it is created. Calling a method that indicates a desire to
     * read or write from the socket will cause the socket to detect if the connected client is attempting
     * to establish a TLS or plaintext connection. This is done by doing a blocking read of 5 bytes off the
     * socket and checking if the bytes look like the start of a TLS ClientHello message. If it looks like
     * the client is attempting to connect with TLS, the internal socket is upgraded to a SSLSocket. If not,
     * any bytes read from the socket are pushed back to the input stream, and the socket continues
     * to be treated as a plaintext socket.
     *
     * The methods that trigger this behavior are:
     * <ul>
     *     <li>{@link UnifiedSocket#getInputStream()}</li>
     *     <li>{@link UnifiedSocket#getOutputStream()}</li>
     *     <li>{@link UnifiedSocket#sendUrgentData(int)}</li>
     * </ul>
     *
     * Calling other socket methods (i.e option setters such as {@link Socket#setTcpNoDelay(boolean)}) does
     * not trigger mode detection.
     *
     * Because detecting the mode is a potentially blocking operation, it should not be done in the
     * accepting thread. Attempting to read from or write to the socket in the accepting thread opens the
     * caller up to a denial-of-service attack, in which a client connects and then does nothing. This would
     * prevent any other clients from connecting. Passing the socket returned by accept() to a separate
     * thread which handles all read and write operations protects against this DoS attack.
     *
     * Callers can check if the socket has been upgraded to TLS by calling {@link UnifiedSocket#isSecureSocket()},
     * and can get the underlying SSLSocket by calling {@link UnifiedSocket#getSslSocket()}.
     */
    public static class UnifiedSocket extends Socket {

        private enum Mode {
            UNKNOWN,
            PLAINTEXT,
            TLS
        }

        private final X509Util x509Util;
        private final boolean allowInsecureConnection;
        private PrependableSocket prependableSocket;
        private SSLSocket sslSocket;
        private Mode mode;

        /**
         * Note: this constructor is intentionally private. The only intended caller is
         * {@link UnifiedServerSocket#accept()}.
         *
         * @param x509Util
         * @param allowInsecureConnection
         * @param prependableSocket
         */
        private UnifiedSocket(X509Util x509Util, boolean allowInsecureConnection, PrependableSocket prependableSocket) {
            this.x509Util = x509Util;
            this.allowInsecureConnection = allowInsecureConnection;
            this.prependableSocket = prependableSocket;
            this.sslSocket = null;
            this.mode = Mode.UNKNOWN;
        }

        /**
         * Returns true if the socket mode has been determined to be TLS.
         * @return true if the mode is TLS, false if it is UNKNOWN or PLAINTEXT.
         */
        public boolean isSecureSocket() {
            return mode == Mode.TLS;
        }

        /**
         * Returns true if the socket mode has been determined to be PLAINTEXT.
         * @return true if the mode is PLAINTEXT, false if it is UNKNOWN or TLS.
         */
        public boolean isPlaintextSocket() {
            return mode == Mode.PLAINTEXT;
        }

        /**
         * Returns true if the socket mode is not yet known.
         * @return true if the mode is UNKNOWN, false if it is PLAINTEXT or TLS.
         */
        public boolean isModeKnown() {
            return mode != Mode.UNKNOWN;
        }

        /**
         * Detects the socket mode, see comments at the top of the class for more details. This operation will block
         * for up to {@link X509Util#getSslHandshakeTimeoutMillis()} milliseconds and should not be called in the
         * accept() thread if possible.
         * @throws IOException
         */
        private void detectMode() throws IOException {
            byte[] litmus = new byte[5];
            int oldTimeout = -1;
            int bytesRead = 0;
            int newTimeout = x509Util.getSslHandshakeTimeoutMillis();
            try {
                oldTimeout = prependableSocket.getSoTimeout();
                prependableSocket.setSoTimeout(newTimeout);
                bytesRead = prependableSocket.getInputStream().read(litmus, 0, litmus.length);
            } catch (SocketTimeoutException e) {
                // Didn't read anything within the timeout, fallthrough and assume the connection is plaintext.
                LOG.warn("Socket mode detection timed out after {} ms, assuming PLAINTEXT", newTimeout);
            } finally {
                // restore socket timeout to the old value
                try {
                    if (oldTimeout != -1) {
                        prependableSocket.setSoTimeout(oldTimeout);
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to restore old socket timeout value of {} ms", oldTimeout, e);
                }
            }
            if (bytesRead < 0) { // Got a EOF right away, definitely not using TLS. Fallthrough.
                bytesRead = 0;
            }

            if (bytesRead == litmus.length && SslHandler.isEncrypted(Unpooled.wrappedBuffer(litmus))) {
                try {
                    sslSocket = x509Util.createSSLSocket(prependableSocket, litmus);
                } catch (X509Exception e) {
                    throw new IOException("failed to create SSL context", e);
                }
                prependableSocket = null;
                mode = Mode.TLS;
                LOG.info(
                    "Accepted TLS connection from {} - {} - {}",
                    sslSocket.getRemoteSocketAddress(),
                    sslSocket.getSession().getProtocol(),
                    sslSocket.getSession().getCipherSuite());
            } else if (allowInsecureConnection) {
                prependableSocket.prependToInputStream(litmus, 0, bytesRead);
                mode = Mode.PLAINTEXT;
                LOG.info("Accepted plaintext connection from {}", prependableSocket.getRemoteSocketAddress());
            } else {
                prependableSocket.close();
                mode = Mode.PLAINTEXT;
                throw new IOException("Blocked insecure connection attempt");
            }
        }

        private Socket getSocketAllowUnknownMode() {
            if (isSecureSocket()) {
                return sslSocket;
            } else { // Note: mode is UNKNOWN or PLAINTEXT
                return prependableSocket;
            }
        }

        /**
         * Returns the underlying socket, detecting the socket mode if it is not yet known. This is a potentially
         * blocking operation and should not be called in the accept() thread.
         * @return the underlying socket, after the socket mode has been determined.
         * @throws IOException
         */
        private Socket getSocket() throws IOException {
            if (!isModeKnown()) {
                detectMode();
            }
            if (mode == Mode.TLS) {
                return sslSocket;
            } else {
                return prependableSocket;
            }
        }

        /**
         * Returns the underlying SSLSocket if the mode is TLS. If the mode is UNKNOWN, causes mode detection which is a
         * potentially blocking operation. If the mode ends up being PLAINTEXT, this will throw a SocketException, so
         * callers are advised to only call this method after checking that {@link UnifiedSocket#isSecureSocket()}
         * returned true.
         * @return the underlying SSLSocket if the mode is known to be TLS.
         * @throws IOException if detecting the socket mode fails
         * @throws SocketException if the mode is PLAINTEXT.
         */
        public SSLSocket getSslSocket() throws IOException {
            if (!isModeKnown()) {
                detectMode();
            }
            if (!isSecureSocket()) {
                throw new SocketException("Socket mode is not TLS");
            }
            return sslSocket;
        }

        /**
         * See {@link Socket#connect(SocketAddress)}. Calling this method does not trigger mode detection.
         */
        @Override
        public void connect(SocketAddress endpoint) throws IOException {
            getSocketAllowUnknownMode().connect(endpoint);
        }

        /**
         * See {@link Socket#connect(SocketAddress, int)}. Calling this method does not trigger mode detection.
         */
        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            getSocketAllowUnknownMode().connect(endpoint, timeout);
        }

        /**
         * See {@link Socket#bind(SocketAddress)}. Calling this method does not trigger mode detection.
         */
        @Override
        public void bind(SocketAddress bindpoint) throws IOException {
            getSocketAllowUnknownMode().bind(bindpoint);
        }

        /**
         * See {@link Socket#getInetAddress()}. Calling this method does not trigger mode detection.
         */
        @Override
        public InetAddress getInetAddress() {
            return getSocketAllowUnknownMode().getInetAddress();
        }

        /**
         * See {@link Socket#getLocalAddress()}. Calling this method does not trigger mode detection.
         */
        @Override
        public InetAddress getLocalAddress() {
            return getSocketAllowUnknownMode().getLocalAddress();
        }

        /**
         * See {@link Socket#getPort()}. Calling this method does not trigger mode detection.
         */
        @Override
        public int getPort() {
            return getSocketAllowUnknownMode().getPort();
        }

        /**
         * See {@link Socket#getLocalPort()}. Calling this method does not trigger mode detection.
         */
        @Override
        public int getLocalPort() {
            return getSocketAllowUnknownMode().getLocalPort();
        }

        /**
         * See {@link Socket#getRemoteSocketAddress()}. Calling this method does not trigger mode detection.
         */
        @Override
        public SocketAddress getRemoteSocketAddress() {
            return getSocketAllowUnknownMode().getRemoteSocketAddress();
        }

        /**
         * See {@link Socket#getLocalSocketAddress()}. Calling this method does not trigger mode detection.
         */
        @Override
        public SocketAddress getLocalSocketAddress() {
            return getSocketAllowUnknownMode().getLocalSocketAddress();
        }

        /**
         * See {@link Socket#getChannel()}. Calling this method does not trigger mode detection.
         */
        @Override
        public SocketChannel getChannel() {
            return getSocketAllowUnknownMode().getChannel();
        }

        /**
         * See {@link Socket#getInputStream()}. If the socket mode has not yet been detected, the first read from the
         * returned input stream will trigger mode detection, which is a potentially blocking operation. This means
         * the accept() thread should avoid reading from this input stream if possible.
         */
        @Override
        public InputStream getInputStream() throws IOException {
            return new UnifiedInputStream(this);
        }

        /**
         * See {@link Socket#getOutputStream()}. If the socket mode has not yet been detected, the first read from the
         * returned input stream will trigger mode detection, which is a potentially blocking operation. This means
         * the accept() thread should avoid reading from this input stream if possible.
         */
        @Override
        public OutputStream getOutputStream() throws IOException {
            return new UnifiedOutputStream(this);
        }

        /**
         * See {@link Socket#setTcpNoDelay(boolean)}. Calling this method does not trigger mode detection.
         */
        @Override
        public void setTcpNoDelay(boolean on) throws SocketException {
            getSocketAllowUnknownMode().setTcpNoDelay(on);
        }

        /**
         * See {@link Socket#getTcpNoDelay()}. Calling this method does not trigger mode detection.
         */
        @Override
        public boolean getTcpNoDelay() throws SocketException {
            return getSocketAllowUnknownMode().getTcpNoDelay();
        }

        /**
         * See {@link Socket#setSoLinger(boolean, int)}. Calling this method does not trigger mode detection.
         */
        @Override
        public void setSoLinger(boolean on, int linger) throws SocketException {
            getSocketAllowUnknownMode().setSoLinger(on, linger);
        }

        /**
         * See {@link Socket#getSoLinger()}. Calling this method does not trigger mode detection.
         */
        @Override
        public int getSoLinger() throws SocketException {
            return getSocketAllowUnknownMode().getSoLinger();
        }

        /**
         * See {@link Socket#sendUrgentData(int)}. Calling this method triggers mode detection, which is a potentially
         * blocking operation, so it should not be done in the accept() thread.
         */
        @Override
        public void sendUrgentData(int data) throws IOException {
            getSocket().sendUrgentData(data);
        }

        /**
         * See {@link Socket#setOOBInline(boolean)}. Calling this method does not trigger mode detection.
         */
        @Override
        public void setOOBInline(boolean on) throws SocketException {
            getSocketAllowUnknownMode().setOOBInline(on);
        }

        /**
         * See {@link Socket#getOOBInline()}. Calling this method does not trigger mode detection.
         */
        @Override
        public boolean getOOBInline() throws SocketException {
            return getSocketAllowUnknownMode().getOOBInline();
        }

        /**
         * See {@link Socket#setSoTimeout(int)}. Calling this method does not trigger mode detection.
         */
        @Override
        public synchronized void setSoTimeout(int timeout) throws SocketException {
            getSocketAllowUnknownMode().setSoTimeout(timeout);
        }

        /**
         * See {@link Socket#getSoTimeout()}. Calling this method does not trigger mode detection.
         */
        @Override
        public synchronized int getSoTimeout() throws SocketException {
            return getSocketAllowUnknownMode().getSoTimeout();
        }

        /**
         * See {@link Socket#setSendBufferSize(int)}. Calling this method does not trigger mode detection.
         */
        @Override
        public synchronized void setSendBufferSize(int size) throws SocketException {
            getSocketAllowUnknownMode().setSendBufferSize(size);
        }

        /**
         * See {@link Socket#getSendBufferSize()}. Calling this method does not trigger mode detection.
         */
        @Override
        public synchronized int getSendBufferSize() throws SocketException {
            return getSocketAllowUnknownMode().getSendBufferSize();
        }

        /**
         * See {@link Socket#setReceiveBufferSize(int)}. Calling this method does not trigger mode detection.
         */
        @Override
        public synchronized void setReceiveBufferSize(int size) throws SocketException {
            getSocketAllowUnknownMode().setReceiveBufferSize(size);
        }

        /**
         * See {@link Socket#getReceiveBufferSize()}. Calling this method does not trigger mode detection.
         */
        @Override
        public synchronized int getReceiveBufferSize() throws SocketException {
            return getSocketAllowUnknownMode().getReceiveBufferSize();
        }

        /**
         * See {@link Socket#setKeepAlive(boolean)}. Calling this method does not trigger mode detection.
         */
        @Override
        public void setKeepAlive(boolean on) throws SocketException {
            getSocketAllowUnknownMode().setKeepAlive(on);
        }

        /**
         * See {@link Socket#getKeepAlive()}. Calling this method does not trigger mode detection.
         */
        @Override
        public boolean getKeepAlive() throws SocketException {
            return getSocketAllowUnknownMode().getKeepAlive();
        }

        /**
         * See {@link Socket#setTrafficClass(int)}. Calling this method does not trigger mode detection.
         */
        @Override
        public void setTrafficClass(int tc) throws SocketException {
            getSocketAllowUnknownMode().setTrafficClass(tc);
        }

        /**
         * See {@link Socket#getTrafficClass()}. Calling this method does not trigger mode detection.
         */
        @Override
        public int getTrafficClass() throws SocketException {
            return getSocketAllowUnknownMode().getTrafficClass();
        }

        /**
         * See {@link Socket#setReuseAddress(boolean)}. Calling this method does not trigger mode detection.
         */
        @Override
        public void setReuseAddress(boolean on) throws SocketException {
            getSocketAllowUnknownMode().setReuseAddress(on);
        }

        /**
         * See {@link Socket#getReuseAddress()}. Calling this method does not trigger mode detection.
         */
        @Override
        public boolean getReuseAddress() throws SocketException {
            return getSocketAllowUnknownMode().getReuseAddress();
        }

        /**
         * See {@link Socket#close()}. Calling this method does not trigger mode detection.
         */
        @Override
        public synchronized void close() throws IOException {
            getSocketAllowUnknownMode().close();
        }

        /**
         * See {@link Socket#shutdownInput()}. Calling this method does not trigger mode detection.
         */
        @Override
        public void shutdownInput() throws IOException {
            getSocketAllowUnknownMode().shutdownInput();
        }

        /**
         * See {@link Socket#shutdownOutput()}. Calling this method does not trigger mode detection.
         */
        @Override
        public void shutdownOutput() throws IOException {
            getSocketAllowUnknownMode().shutdownOutput();
        }

        /**
         * See {@link Socket#toString()}. Calling this method does not trigger mode detection.
         */
        @Override
        public String toString() {
            return "UnifiedSocket[mode=" + mode.toString() + "socket=" + getSocketAllowUnknownMode().toString() + "]";
        }

        /**
         * See {@link Socket#isConnected()}. Calling this method does not trigger mode detection.
         */
        @Override
        public boolean isConnected() {
            return getSocketAllowUnknownMode().isConnected();
        }

        /**
         * See {@link Socket#isBound()}. Calling this method does not trigger mode detection.
         */
        @Override
        public boolean isBound() {
            return getSocketAllowUnknownMode().isBound();
        }

        /**
         * See {@link Socket#isClosed()}. Calling this method does not trigger mode detection.
         */
        @Override
        public boolean isClosed() {
            return getSocketAllowUnknownMode().isClosed();
        }

        /**
         * See {@link Socket#isInputShutdown()}. Calling this method does not trigger mode detection.
         */
        @Override
        public boolean isInputShutdown() {
            return getSocketAllowUnknownMode().isInputShutdown();
        }

        /**
         * See {@link Socket#isOutputShutdown()}. Calling this method does not trigger mode detection.
         */
        @Override
        public boolean isOutputShutdown() {
            return getSocketAllowUnknownMode().isOutputShutdown();
        }

        /**
         * See {@link Socket#setPerformancePreferences(int, int, int)}. Calling this method does not trigger
         * mode detection.
         */
        @Override
        public void setPerformancePreferences(int connectionTime, int latency, int bandwidth) {
            getSocketAllowUnknownMode().setPerformancePreferences(connectionTime, latency, bandwidth);
        }

    }

    /**
     * An input stream for a UnifiedSocket. The first read from this stream will trigger mode detection on the
     * underlying UnifiedSocket.
     */
    private static class UnifiedInputStream extends InputStream {

        private final UnifiedSocket unifiedSocket;
        private InputStream realInputStream;

        private UnifiedInputStream(UnifiedSocket unifiedSocket) {
            this.unifiedSocket = unifiedSocket;
            this.realInputStream = null;
        }

        @Override
        public int read() throws IOException {
            return getRealInputStream().read();
        }

        /**
         * Note: SocketInputStream has optimized implementations of bulk-read operations, so we need to call them
         * directly instead of relying on the base-class implementation which just calls the single-byte read() over
         * and over. Not implementing these results in awful performance.
         */
        @Override
        public int read(byte[] b) throws IOException {
            return getRealInputStream().read(b);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return getRealInputStream().read(b, off, len);
        }

        private InputStream getRealInputStream() throws IOException {
            if (realInputStream == null) {
                // Note: The first call to getSocket() triggers mode detection which can block
                realInputStream = unifiedSocket.getSocket().getInputStream();
            }
            return realInputStream;
        }

        @Override
        public long skip(long n) throws IOException {
            return getRealInputStream().skip(n);
        }

        @Override
        public int available() throws IOException {
            return getRealInputStream().available();
        }

        @Override
        public void close() throws IOException {
            getRealInputStream().close();
        }

        @Override
        public synchronized void mark(int readlimit) {
            try {
                getRealInputStream().mark(readlimit);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public synchronized void reset() throws IOException {
            getRealInputStream().reset();
        }

        @Override
        public boolean markSupported() {
            try {
                return getRealInputStream().markSupported();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

    }

    private static class UnifiedOutputStream extends OutputStream {

        private final UnifiedSocket unifiedSocket;
        private OutputStream realOutputStream;

        private UnifiedOutputStream(UnifiedSocket unifiedSocket) {
            this.unifiedSocket = unifiedSocket;
            this.realOutputStream = null;
        }

        @Override
        public void write(int b) throws IOException {
            getRealOutputStream().write(b);
        }

        @Override
        public void write(byte[] b) throws IOException {
            getRealOutputStream().write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            getRealOutputStream().write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            getRealOutputStream().flush();
        }

        @Override
        public void close() throws IOException {
            getRealOutputStream().close();
        }

        private OutputStream getRealOutputStream() throws IOException {
            if (realOutputStream == null) {
                // Note: The first call to getSocket() triggers mode detection which can block
                realOutputStream = unifiedSocket.getSocket().getOutputStream();
            }
            return realOutputStream;
        }

    }

}
