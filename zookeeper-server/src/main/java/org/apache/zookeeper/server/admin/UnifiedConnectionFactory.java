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

import java.nio.ByteBuffer;
import org.apache.zookeeper.server.ServerMetrics;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.DetectorConnectionFactory;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/**
 * Sniffs the first bytes of an incoming connection and serves it over TLS or
 * plaintext accordingly, so that the admin port accepts both.
 *
 * <p>The detection itself is done by Jetty's {@link DetectorConnectionFactory}.
 * This class only adds the {@code insecure_admin_count} metric on the plaintext
 * path.
 */
public class UnifiedConnectionFactory extends DetectorConnectionFactory {

    public UnifiedConnectionFactory(SslContextFactory.Server sslContextFactory, String nextProtocol) {
        super(new SslConnectionFactory(sslContextFactory, nextProtocol));
    }

    /**
     * Called when none of the detecting factories recognised the bytes, which
     * means the connection is plaintext. A connection that sends no data at all
     * also ends up here.
     */
    @Override
    protected void nextProtocol(Connector connector, EndPoint endPoint, ByteBuffer buffer) {
        ServerMetrics.getMetrics().INSECURE_ADMIN.add(1);
        super.nextProtocol(connector, endPoint, buffer);
    }

}
