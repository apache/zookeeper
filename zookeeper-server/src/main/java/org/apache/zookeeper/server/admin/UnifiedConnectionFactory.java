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

package org.apache.zookeeper.server.admin;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import org.apache.zookeeper.server.ServerMetrics;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The purpose of this class is to dynamically determine whether to create
 * a plaintext or SSL connection whenever newConnection() is called. It works
 * in conjunction with ReadAheadEndpoint to inspect bytes on the incoming
 * connection.
 */
public class UnifiedConnectionFactory extends AbstractConnectionFactory {

    private static final Logger LOG = LoggerFactory.getLogger(UnifiedConnectionFactory.class);

    private final SslContextFactory sslContextFactory;
    private final String nextProtocol;

    public UnifiedConnectionFactory(String nextProtocol) {
        this(null, nextProtocol);
    }

    public UnifiedConnectionFactory(SslContextFactory factory, String nextProtocol) {
        super("SSL");
        this.sslContextFactory = (factory == null) ? new SslContextFactory.Server() : factory;
        this.nextProtocol = nextProtocol;
        this.addBean(this.sslContextFactory);
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();
        SSLEngine engine = this.sslContextFactory.newSSLEngine();
        SSLSession session = engine.getSession();
        engine.setUseClientMode(false);
        if (session.getPacketBufferSize() > this.getInputBufferSize()) {
            this.setInputBufferSize(session.getPacketBufferSize());
        }
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint realEndPoint) {
        ReadAheadEndpoint aheadEndpoint = new ReadAheadEndpoint(realEndPoint, 1);
        byte[] bytes = aheadEndpoint.getBytes();
        boolean isSSL;

        if (bytes == null || bytes.length == 0) {
            isSSL = false;
            LOG.warn("Incoming connection has no data");
        } else {
            byte b = bytes[0]; // TLS first byte is 0x16, let's not support SSLv3 and below
            isSSL = b == 0x16; // matches SSL detection in NettyServerCnxnFactory.java
        }

        LOG.debug(String.format("UnifiedConnectionFactory: newConnection() with SSL = %b", isSSL));

        EndPoint plainEndpoint;
        SslConnection sslConnection;

        if (isSSL) {
            SSLEngine engine = this.sslContextFactory.newSSLEngine(aheadEndpoint.getRemoteAddress());
            engine.setUseClientMode(false);
            sslConnection = this.newSslConnection(connector, aheadEndpoint, engine);
            sslConnection.setRenegotiationAllowed(this.sslContextFactory.isRenegotiationAllowed());
            this.configure(sslConnection, connector, aheadEndpoint);
            plainEndpoint = sslConnection.getDecryptedEndPoint();
        } else {
            sslConnection = null;
            plainEndpoint = aheadEndpoint;
            ServerMetrics.getMetrics().INSECURE_ADMIN.add(1);
        }

        ConnectionFactory next = connector.getConnectionFactory(nextProtocol);
        Connection connection = next.newConnection(connector, plainEndpoint);
        plainEndpoint.setConnection(connection);

        return (sslConnection == null) ? connection : sslConnection;
    }

    protected SslConnection newSslConnection(
        final Connector connector,
        final EndPoint endPoint,
        final SSLEngine engine) {
        return new SslConnection(connector.getByteBufferPool(), connector.getExecutor(), endPoint, engine);
    }

    @Override
    public String toString() {
        return String.format(
            "%s@%x{%s->%s}",
            this.getClass().getSimpleName(),
            this.hashCode(),
            this.getProtocol(),
            this.nextProtocol);
    }

}
