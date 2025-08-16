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

package org.apache.zookeeper.common.ssl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.Properties;
import java.util.UUID;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.X509TestHelpers;

public class Cert {
    public final String name;
    public final KeyPair key;
    public final X509Certificate cert;
    public final Path dir;
    public final Path crl;

    Cert(String name, KeyPair key, X509Certificate cert, Path dir, Path crl) {
        this.name = name;
        this.key = key;
        this.cert = cert;
        this.dir = dir;
        this.crl = crl;
    }

    public PemFile writePem() throws Exception {
        String password = UUID.randomUUID().toString();
        String pem = X509TestHelpers.pemEncodeCertAndPrivateKey(cert, key.getPrivate(), password);
        Path file = Files.createTempFile(dir, name, ".pem");
        Files.write(file, pem.getBytes());
        return new PemFile(file, password);
    }

    public Properties buildServerProperties(Ca ca) throws Exception {
        final Properties config = new Properties();
        config.put("clientPort", "0");
        config.put("secureClientPort", "0");

        // explicitly ipv4 to avoid dns lookup issue
        config.put("clientPortAddress", "127.0.0.1");
        config.put("secureClientPortAddress", "127.0.0.1");

        config.put("admin.enableServer", "false");
        config.put("admin.rateLimiterIntervalInMS", "0");

        PemFile serverPem = writePem();

        // TLS config fields
        config.put("ssl.keyStore.location", serverPem.file.toString());
        config.put("ssl.keyStore.password", serverPem.password);
        config.put("ssl.trustStore.location", ca.pemFile.file.toString());

        // Netty is required for TLS
        config.put("serverCnxnFactory", org.apache.zookeeper.server.NettyServerCnxnFactory.class.getName());
        config.put("4lw.commands.whitelist", "*");
        return config;
    }

    public ZKClientConfig buildClientConfig(Ca ca) throws Exception {
        PemFile pemFile = writePem();

        ZKClientConfig config = new ZKClientConfig();
        config.setProperty("zookeeper.client.secure", "true");
        config.setProperty("zookeeper.ssl.keyStore.password", pemFile.password);
        config.setProperty("zookeeper.ssl.keyStore.location", pemFile.file.toString());
        config.setProperty("zookeeper.ssl.trustStore.location", ca.pemFile.file.toString());

        // only netty supports TLS
        config.setProperty("zookeeper.clientCnxnSocket", org.apache.zookeeper.ClientCnxnSocketNetty.class.getName());
        return config;
    }
}
