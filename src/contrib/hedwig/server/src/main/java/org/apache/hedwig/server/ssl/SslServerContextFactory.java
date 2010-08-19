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
package org.apache.hedwig.server.ssl;

import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.apache.hedwig.client.ssl.SslContextFactory;
import org.apache.hedwig.server.common.ServerConfiguration;

public class SslServerContextFactory extends SslContextFactory {

    public SslServerContextFactory(ServerConfiguration cfg) {
        try {
            // Load our Java key store.
            KeyStore ks = KeyStore.getInstance("pkcs12");
            ks.load(cfg.getCertStream(), cfg.getPassword().toCharArray());

            // Like ssh-agent.
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, cfg.getPassword().toCharArray());

            // Create the SSL context.
            ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), getTrustManagers(), null);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    protected boolean isClient() {
        return false;
    }

}
