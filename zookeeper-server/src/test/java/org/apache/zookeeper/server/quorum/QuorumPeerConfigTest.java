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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Properties;
import org.apache.zookeeper.common.ClientX509Util;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;
import org.junit.jupiter.api.Test;

public class QuorumPeerConfigTest {

    /**
     * test case for https://issues.apache.org/jira/browse/ZOOKEEPER-2264
     */
    @Test
    public void testErrorMessageWhensecureClientPortNotSetButsecureClientPortAddressSet() throws IOException, ConfigException {
        QuorumPeerConfig quorumPeerConfig = new QuorumPeerConfig();
        try {
            Properties zkProp = getDefaultZKProperties();
            zkProp.setProperty("secureClientPortAddress", "localhost");
            quorumPeerConfig.parseProperties(zkProp);
            fail("IllegalArgumentException is expected");
        } catch (IllegalArgumentException e) {
            String expectedMessage = "secureClientPortAddress is set but secureClientPort is not set";
            assertEquals(expectedMessage, e.getMessage());
        }
    }

    /**
     *
     * Test case for https://issues.apache.org/jira/browse/ZOOKEEPER-2264
     */
    @Test
    public void testErrorMessageWhenclientPortNotSetButclientPortAddressSet() throws IOException, ConfigException {
        QuorumPeerConfig quorumPeerConfig = new QuorumPeerConfig();
        try {
            Properties zkProp = getDefaultZKProperties();
            zkProp.setProperty("clientPortAddress", "localhost");
            quorumPeerConfig.parseProperties(zkProp);
            fail("IllegalArgumentException is expected");
        } catch (IllegalArgumentException e) {
            String expectedMessage = "clientPortAddress is set but clientPort is not set";
            assertEquals(expectedMessage, e.getMessage());
        }
    }

    /**
     * https://issues.apache.org/jira/browse/ZOOKEEPER-2297
     */
    @Test
    public void testConfigureSSLAuthGetsConfiguredIfSecurePortConfigured() throws IOException, ConfigException {
        String sslAuthProp = "zookeeper.authProvider.x509";
        QuorumPeerConfig quorumPeerConfig = new QuorumPeerConfig();
        Properties zkProp = getDefaultZKProperties();
        zkProp.setProperty("secureClientPort", "12345");
        quorumPeerConfig.parseProperties(zkProp);
        String expected = "org.apache.zookeeper.server.auth.X509AuthenticationProvider";
        String result = System.getProperty(sslAuthProp);
        assertEquals(expected, result);
    }

    /**
     * https://issues.apache.org/jira/browse/ZOOKEEPER-2297
     */
    @Test
    public void testCustomSSLAuth() throws IOException {
        try (ClientX509Util x509Util = new ClientX509Util()) {
            System.setProperty(x509Util.getSslAuthProviderProperty(), "y509");
            QuorumPeerConfig quorumPeerConfig = new QuorumPeerConfig();
            try {
                Properties zkProp = getDefaultZKProperties();
                zkProp.setProperty("secureClientPort", "12345");
                quorumPeerConfig.parseProperties(zkProp);
                fail("ConfigException is expected");
            } catch (ConfigException e) {
                assertNotNull(e.getMessage());
            }
        }
    }

    /**
     * Test case for https://issues.apache.org/jira/browse/ZOOKEEPER-2873
     */
    @Test
    public void testSamePortConfiguredForClientAndElection() {
        assertThrows(ConfigException.class, () -> {
            QuorumPeerConfig quorumPeerConfig = new QuorumPeerConfig();
            Properties zkProp = getDefaultZKProperties();
            zkProp.setProperty("server.1", "localhost:2888:2888");
            quorumPeerConfig.parseProperties(zkProp);
        });
    }

    /**
     * Extend the existing QuorumPeerConfig to set the server id.
     */
    public static class MockQuorumPeerConfig extends QuorumPeerConfig {

        public MockQuorumPeerConfig(long serverId) {
            this.serverId = serverId;
        }

    }

    /**
     * Test case for https://issues.apache.org/jira/browse/ZOOKEEPER-2847
     */
    @Test
    public void testClientAddrFromClientPort() throws IOException, ConfigException {
        long serverId = 1;
        QuorumPeerConfig quorumPeerConfig = new MockQuorumPeerConfig(serverId);
        Properties zkProp = getDefaultZKProperties();
        int clientPort = 12345;
        zkProp.setProperty("clientPort", Integer.toString(clientPort));
        zkProp.setProperty("server.1", "127.0.0.1:2889:3889:participant");
        quorumPeerConfig.parseProperties(zkProp);

        QuorumServer qs = quorumPeerConfig.getQuorumVerifier().getAllMembers().get(serverId);
        InetSocketAddress expectedAddress = new InetSocketAddress("0.0.0.0", clientPort);
        assertEquals(expectedAddress, quorumPeerConfig.getClientPortAddress());
        assertEquals(quorumPeerConfig.getClientPortAddress(), qs.clientAddr);
    }

    @Test
    public void testJvmPauseMonitorConfigured() throws IOException, ConfigException {
        final Long sleepTime = 444L;
        final Long warnTH = 5555L;
        final Long infoTH = 555L;

        QuorumPeerConfig quorumPeerConfig = new QuorumPeerConfig();
        Properties zkProp = getDefaultZKProperties();
        zkProp.setProperty("dataDir", new File("myDataDir").getAbsolutePath());
        zkProp.setProperty("jvm.pause.monitor", "true");
        zkProp.setProperty("jvm.pause.sleep.time.ms", sleepTime.toString());
        zkProp.setProperty("jvm.pause.warn-threshold.ms", warnTH.toString());
        zkProp.setProperty("jvm.pause.info-threshold.ms", infoTH.toString());
        quorumPeerConfig.parseProperties(zkProp);

        assertEquals(sleepTime, Long.valueOf(quorumPeerConfig.getJvmPauseSleepTimeMs()));
        assertEquals(warnTH, Long.valueOf(quorumPeerConfig.getJvmPauseWarnThresholdMs()));
        assertEquals(infoTH, Long.valueOf(quorumPeerConfig.getJvmPauseInfoThresholdMs()));
        assertTrue(quorumPeerConfig.isJvmPauseMonitorToRun());
    }

    /**
     * Test case for https://issues.apache.org/jira/browse/ZOOKEEPER-3721
     */
    @Test
    public void testParseBoolean() throws IOException, ConfigException {
        QuorumPeerConfig quorumPeerConfig = new QuorumPeerConfig();
        Properties zkProp = getDefaultZKProperties();

        zkProp.setProperty("localSessionsEnabled", "true");
        quorumPeerConfig.parseProperties(zkProp);
        assertEquals(true, quorumPeerConfig.areLocalSessionsEnabled());

        zkProp.setProperty("localSessionsEnabled", "false");
        quorumPeerConfig.parseProperties(zkProp);
        assertEquals(false, quorumPeerConfig.areLocalSessionsEnabled());

        zkProp.setProperty("localSessionsEnabled", "True");
        quorumPeerConfig.parseProperties(zkProp);
        assertEquals(true, quorumPeerConfig.areLocalSessionsEnabled());

        zkProp.setProperty("localSessionsEnabled", "False");
        quorumPeerConfig.parseProperties(zkProp);
        assertEquals(false, quorumPeerConfig.areLocalSessionsEnabled());

        zkProp.setProperty("localSessionsEnabled", "yes");
        try {
            quorumPeerConfig.parseProperties(zkProp);
            fail("Must throw exception as 'yes' is not accpetable for parseBoolean!");
        } catch (ConfigException e) {
            // expected
        }
    }

    private Properties getDefaultZKProperties() {
        Properties zkProp = new Properties();
        zkProp.setProperty("dataDir", new File("myDataDir").getAbsolutePath());
        return zkProp;
    }

}
