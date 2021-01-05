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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class QuorumCanonicalizeTest extends ZKTestCase {

    private static final InetSocketAddress SA_DONT_CARE = InetSocketAddress.createUnresolved("dont.care.invalid", 80);

    private static final String ZK1_ALIAS = "zookeeper.invalid";
    private static final String ZK1_FQDN = "zk1.invalid";
    private static final String ZK1_IP = "169.254.0.42";
    private static final InetAddress IA_MOCK_ZK1;

    static {
        InetAddress ia = mock(InetAddress.class);

        when(ia.getCanonicalHostName()).thenReturn(ZK1_FQDN);

        IA_MOCK_ZK1 = ia;
    }

    private static InetAddress getInetAddress(InetSocketAddress addr) {
        if (addr.getHostName().equals(ZK1_ALIAS) || addr.getHostName().equals(ZK1_IP)) {
            return IA_MOCK_ZK1;
        }

        return addr.getAddress();
    };

    @AfterEach
    public void cleanUpEnvironment() {
        System.clearProperty(QuorumPeer.CONFIG_KEY_KERBEROS_CANONICALIZE_HOST_NAMES);
    }

    private QuorumPeer.QuorumServer createQuorumServer(String hostName) throws ConfigException {
        return new QuorumPeer.QuorumServer(0, hostName + ":1234:5678", QuorumCanonicalizeTest::getInetAddress);
    }

    @Test
    public void testQuorumDefaultCanonicalization() throws ConfigException {
        QuorumPeer.QuorumServer qps = createQuorumServer(ZK1_ALIAS);

        assertEquals(ZK1_ALIAS, qps.hostname,
           "The host name has been \"changed\" (canonicalized?) despite default settings");
    }

    @Test
    public void testQuorumNoCanonicalization() throws ConfigException {
        System.setProperty(QuorumPeer.CONFIG_KEY_KERBEROS_CANONICALIZE_HOST_NAMES, Boolean.FALSE.toString());

        QuorumPeer.QuorumServer qps = createQuorumServer(ZK1_ALIAS);

        assertEquals(ZK1_ALIAS, qps.hostname,
           "The host name has been \"changed\" (canonicalized?) despite default settings");
    }

    @Test
    public void testQuorumCanonicalization() throws ConfigException {
        System.setProperty(QuorumPeer.CONFIG_KEY_KERBEROS_CANONICALIZE_HOST_NAMES, Boolean.TRUE.toString());

        QuorumPeer.QuorumServer qps = createQuorumServer(ZK1_ALIAS);

        assertEquals(ZK1_FQDN, qps.hostname,
            "The host name hasn't been correctly canonicalized");
    }

    @Test
    public void testQuorumCanonicalizationFromIp() throws ConfigException {
        System.setProperty(QuorumPeer.CONFIG_KEY_KERBEROS_CANONICALIZE_HOST_NAMES, Boolean.TRUE.toString());

        QuorumPeer.QuorumServer qps = createQuorumServer(ZK1_IP);

        assertEquals(ZK1_FQDN, qps.hostname,
            "The host name hasn't been correctly canonicalized");
    }
}
