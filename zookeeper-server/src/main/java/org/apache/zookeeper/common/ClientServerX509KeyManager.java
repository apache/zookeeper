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

package org.apache.zookeeper.common;

import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;

/**
 * An {@link X509ExtendedKeyManager} that delegates client-mode and server-mode
 * key selection to separate underlying key managers. This allows a ZooKeeper
 * node to use different keystores (and therefore different certificates) for
 * its client role (outgoing connections) and server role (incoming connections),
 * enabling the use of certificates with a single Extended Key Usage (EKU).
 */
public class ClientServerX509KeyManager extends X509ExtendedKeyManager {

    private static final String CLIENT_PREFIX = "client:";
    private static final String SERVER_PREFIX = "server:";

    private final X509KeyManager clientKeyManager;
    private final X509KeyManager serverKeyManager;

    public ClientServerX509KeyManager(X509KeyManager clientKeyManager, X509KeyManager serverKeyManager) {
        this.clientKeyManager = clientKeyManager;
        this.serverKeyManager = serverKeyManager;
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
        String alias = clientKeyManager.chooseClientAlias(keyType, issuers, socket);
        return alias != null ? CLIENT_PREFIX + alias : null;
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
        String alias = serverKeyManager.chooseServerAlias(keyType, issuers, socket);
        return alias != null ? SERVER_PREFIX + alias : null;
    }

    @Override
    public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
        if (clientKeyManager instanceof X509ExtendedKeyManager) {
            String alias = ((X509ExtendedKeyManager) clientKeyManager)
                .chooseEngineClientAlias(keyType, issuers, engine);
            return alias != null ? CLIENT_PREFIX + alias : null;
        }
        return chooseClientAlias(keyType, issuers, null);
    }

    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
        if (serverKeyManager instanceof X509ExtendedKeyManager) {
            String alias = ((X509ExtendedKeyManager) serverKeyManager)
                .chooseEngineServerAlias(keyType, issuers, engine);
            return alias != null ? SERVER_PREFIX + alias : null;
        }
        return chooseServerAlias(keyType, issuers, null);
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
        if (alias == null) {
            return null;
        }
        if (alias.startsWith(CLIENT_PREFIX)) {
            return clientKeyManager.getCertificateChain(alias.substring(CLIENT_PREFIX.length()));
        }
        if (alias.startsWith(SERVER_PREFIX)) {
            return serverKeyManager.getCertificateChain(alias.substring(SERVER_PREFIX.length()));
        }
        return serverKeyManager.getCertificateChain(alias);
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
        if (alias == null) {
            return null;
        }
        if (alias.startsWith(CLIENT_PREFIX)) {
            return clientKeyManager.getPrivateKey(alias.substring(CLIENT_PREFIX.length()));
        }
        if (alias.startsWith(SERVER_PREFIX)) {
            return serverKeyManager.getPrivateKey(alias.substring(SERVER_PREFIX.length()));
        }
        return serverKeyManager.getPrivateKey(alias);
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
        String[] aliases = clientKeyManager.getClientAliases(keyType, issuers);
        return prefixAliases(aliases, CLIENT_PREFIX);
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
        String[] aliases = serverKeyManager.getServerAliases(keyType, issuers);
        return prefixAliases(aliases, SERVER_PREFIX);
    }

    private static String[] prefixAliases(String[] aliases, String prefix) {
        if (aliases == null) {
            return null;
        }
        String[] prefixed = new String[aliases.length];
        for (int i = 0; i < aliases.length; i++) {
            prefixed[i] = prefix + aliases[i];
        }
        return prefixed;
    }
}
