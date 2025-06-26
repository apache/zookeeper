/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.quorum;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.lang.reflect.Method;
import java.util.Properties;
import org.apache.zookeeper.server.quorum.auth.MockSSLQuorumAuthLearner;
import org.apache.zookeeper.server.quorum.auth.MockSslQuorumAuthServer;
import org.apache.zookeeper.server.quorum.auth.NullQuorumAuthLearner;
import org.apache.zookeeper.server.quorum.auth.NullQuorumAuthServer;
import org.apache.zookeeper.server.quorum.auth.QuorumAuth;
import org.apache.zookeeper.server.quorum.auth.QuorumAuthLearner;
import org.apache.zookeeper.server.quorum.auth.QuorumAuthServer;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for pluggable SSL quorum auth providers in {@link QuorumPeer}.
 */
public class QuorumPeerAuthProviderTest {

    /**
     * When sslAuthServerProvider is set to a custom provider, ensure it instantiates correctly.
     */
    @Test
    public void testCustomSslAuthServerProvider() throws Exception {
        // Prepare config with custom server auth provider
        QuorumPeerConfig config = new QuorumPeerConfig();
        Properties zkProp = getDefaultZKProperties();
        zkProp.setProperty("sslQuorum", "true");
        zkProp.setProperty(QuorumAuth.QUORUM_SSL_AUTHPROVIDER,
                MockSslQuorumAuthServer.class.getName());
        config.parseProperties(zkProp);

        // Set on peer and invoke private method
        QuorumPeer peer = new QuorumPeer();
        peer.setSslAuthServerProvider(config.getSslAuthServerProvider());
        QuorumAuthServer authServer = invokeGetSslQuorumAuthServer(peer);

        assertTrue(authServer instanceof MockSslQuorumAuthServer,
                "Expected MockSSLQuorumAuthServer when provider is configured");
    }

    /**
     * Without any provider configured, default should be NullQuorumAuthServer.
     */
    @Test
    public void testDefaultSslAuthServerProvider() throws Exception {
        QuorumPeer peer = new QuorumPeer();
        QuorumAuthServer authServer = invokeGetSslQuorumAuthServer(peer);
        assertTrue(authServer instanceof NullQuorumAuthServer,
                "Expected NullQuorumAuthServer when no provider is configured");
    }

    /**
     * When sslAuthLearnerProvider is set to a custom provider, ensure it instantiates correctly.
     */
    @Test
    public void testCustomSslAuthLearnerProvider() throws Exception {
        QuorumPeerConfig config = new QuorumPeerConfig();
        Properties zkProp = getDefaultZKProperties();
        zkProp.setProperty("sslQuorum", "true");
        zkProp.setProperty(QuorumAuth.QUORUM_SSL_LEARNER_AUTHPROVIDER,
                MockSSLQuorumAuthLearner.class.getName());
        config.parseProperties(zkProp);

        QuorumPeer peer = new QuorumPeer();
        peer.setSslAuthLearnerProvider(config.getSslAuthLearnerProvider());
        QuorumAuthLearner authLearner = invokeGetSslQuorumAuthLearner(peer);

        assertTrue(authLearner instanceof MockSSLQuorumAuthLearner,
                "Expected MockSSLQuorumAuthLearner when learner provider is configured");
    }

    /**
     * Without any learner provider configured, default should be NullQuorumAuthLearner.
     */
    @Test
    public void testDefaultSslAuthLearnerProvider() throws Exception {
        QuorumPeer peer = new QuorumPeer();
        QuorumAuthLearner authLearner = invokeGetSslQuorumAuthLearner(peer);
        assertTrue(authLearner instanceof NullQuorumAuthLearner,
                "Expected NullQuorumAuthLearner when no learner provider is configured");
    }

    // Reflection helpers to access private methods

    private QuorumAuthServer invokeGetSslQuorumAuthServer(QuorumPeer peer) throws Exception {
        Method m = QuorumPeer.class.getDeclaredMethod("getSslQuorumAuthServer");
        m.setAccessible(true);
        return (QuorumAuthServer) m.invoke(peer);
    }

    private QuorumAuthLearner invokeGetSslQuorumAuthLearner(QuorumPeer peer) throws Exception {
        Method m = QuorumPeer.class.getDeclaredMethod("getSslQuorumAuthLearner");
        m.setAccessible(true);
        return (QuorumAuthLearner) m.invoke(peer);
    }
    private Properties getDefaultZKProperties() {
        Properties zkProp = new Properties();
        zkProp.setProperty("dataDir", new File("myDataDir").getAbsolutePath());
        zkProp.setProperty("oraclePath", new File("mastership").getAbsolutePath());
        return zkProp;
    }
}
