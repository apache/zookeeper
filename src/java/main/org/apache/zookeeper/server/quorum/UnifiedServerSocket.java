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

  public UnifiedServerSocket() throws IOException {
    super();
  }

  public UnifiedServerSocket(int port) throws IOException {
    super(port);
  }

  @Override
  public Socket accept() throws IOException {
    if (isClosed()) {
      throw new SocketException("Socket is closed");
    }
    if (!isBound()) {
      throw new SocketException("Socket is not bound yet");
    }
    final Socket bufferedSocket = new BufferedSocket(null);
    implAccept(bufferedSocket);

    bufferedSocket.getInputStream().mark(6);

    byte[] litmus = new byte[5];
    bufferedSocket.getInputStream().read(litmus, 0, 5);

    bufferedSocket.getInputStream().reset();

    boolean isSsl = SslHandler.isEncrypted(ChannelBuffers.wrappedBuffer(litmus));
    if (isSsl) {
      LOG.info(getInetAddress() + " attempting to connect over ssl");
      SSLSocket sslSocket;
      try {
        sslSocket = (SSLSocket) X509Util.QUORUM_X509UTIL.getDefaultSSLContext().getSocketFactory().createSocket(bufferedSocket, null, bufferedSocket.getPort(), false);
      } catch (X509Exception.SSLContextException e) {
        throw new IOException("failed to create SSL context", e);
      }
      sslSocket.setUseClientMode(false);
      return sslSocket;
    } else {
      LOG.info(getInetAddress() + " attempting to connect without ssl");
      return bufferedSocket;
    }
  }
}