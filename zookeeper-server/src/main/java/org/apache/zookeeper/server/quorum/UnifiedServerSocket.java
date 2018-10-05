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
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

public class UnifiedServerSocket extends ServerSocket {
    private static final Logger LOG = LoggerFactory.getLogger(UnifiedServerSocket.class);

    private X509Util x509Util;

    public UnifiedServerSocket(X509Util x509Util) throws IOException {
        super();
        this.x509Util = x509Util;
    }

    public UnifiedServerSocket(X509Util x509Util, int port) throws IOException {
        super(port);
        this.x509Util = x509Util;
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
        prependableSocket.prependToInputStream(litmus);

        if (bytesRead == 5 && SslHandler.isEncrypted(ChannelBuffers.wrappedBuffer(litmus))) {
            LOG.info(getInetAddress() + " attempting to connect over ssl");
            SSLSocket sslSocket;
            try {
                sslSocket = x509Util.createSSLSocket(prependableSocket);
            } catch (X509Exception e) {
                throw new IOException("failed to create SSL context", e);
            }
            sslSocket.setUseClientMode(false);
            return sslSocket;
        } else {
            LOG.info(getInetAddress() + " attempting to connect without ssl");
            return prependableSocket;
        }
    }
}