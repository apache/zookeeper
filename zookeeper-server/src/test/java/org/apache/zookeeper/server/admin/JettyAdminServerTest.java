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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.security.cert.X509Certificate;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.common.KeyStoreFileType;
import org.apache.zookeeper.common.X509Exception.SSLContextException;
import org.apache.zookeeper.common.X509KeyType;
import org.apache.zookeeper.common.X509TestContext;
import org.apache.zookeeper.server.ZooKeeperServerMainTest;
import org.apache.zookeeper.server.admin.AdminServer.AdminServerException;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.apache.zookeeper.test.ClientBase;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JettyAdminServerTest extends ZKTestCase {

    protected static final Logger LOG = LoggerFactory.getLogger(JettyAdminServerTest.class);

    private static final String URL_FORMAT = "http://localhost:%d/commands";
    private static final String HTTPS_URL_FORMAT = "https://localhost:%d/commands";
    private static final int jettyAdminPort = PortAssignment.unique();

    @BeforeEach
    public void enableServer() {
        // Override setting in ZKTestCase
        System.setProperty("zookeeper.admin.enableServer", "true");
        System.setProperty("zookeeper.admin.serverPort", "" + jettyAdminPort);
    }

    @BeforeEach
    public void setupEncryption() {
        Security.addProvider(new BouncyCastleProvider());
        File tmpDir = null;
        X509TestContext x509TestContext = null;
        try {
            tmpDir = ClientBase.createEmptyTestDir();
            x509TestContext = X509TestContext.newBuilder()
                                             .setTempDir(tmpDir)
                                             .setKeyStorePassword("")
                                             .setKeyStoreKeyType(X509KeyType.EC)
                                             .setTrustStorePassword("")
                                             .setTrustStoreKeyType(X509KeyType.EC)
                                             .build();
            System.setProperty(
                "zookeeper.ssl.quorum.keyStore.location",
                x509TestContext.getKeyStoreFile(KeyStoreFileType.PEM).getAbsolutePath());
            System.setProperty(
                "zookeeper.ssl.quorum.trustStore.location",
                x509TestContext.getTrustStoreFile(KeyStoreFileType.PEM).getAbsolutePath());
        } catch (Exception e) {
            LOG.info("Problems encountered while setting up encryption for Jetty admin server test", e);
        }
        System.setProperty("zookeeper.ssl.quorum.keyStore.password", "");
        System.setProperty("zookeeper.ssl.quorum.keyStore.type", "PEM");
        System.setProperty("zookeeper.ssl.quorum.trustStore.password", "");
        System.setProperty("zookeeper.ssl.quorum.trustStore.type", "PEM");
        System.setProperty("zookeeper.admin.portUnification", "true");

        // Create a trust manager that does not validate certificate chains
        TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return null;
            }
            public void checkClientTrusted(X509Certificate[] certs, String authType) {
            }
            public void checkServerTrusted(X509Certificate[] certs, String authType) {
            }
        }};

        // Create all-trusting trust manager
        SSLContext sc = null;
        try {
            sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
        } catch (Exception e) {
            LOG.error("Failed to customize encryption for HTTPS", e);
        }

        // Create all-trusting hostname verifier
        HostnameVerifier allValid = new HostnameVerifier() {
            public boolean verify(String hostname, SSLSession session) {
                return true;
            }
        };

        // This is a temporary fix while we do not yet have certificates set up to make
        // HTTPS requests correctly. This is equivalent to the "-k" option in curl.
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier(allValid);
    }

    @AfterEach
    public void cleanUp() {
        Security.removeProvider("BC");

        System.clearProperty("zookeeper.admin.enableServer");
        System.clearProperty("zookeeper.admin.serverPort");

        System.clearProperty("zookeeper.ssl.quorum.keyStore.location");
        System.clearProperty("zookeeper.ssl.quorum.keyStore.password");
        System.clearProperty("zookeeper.ssl.quorum.keyStore.type");
        System.clearProperty("zookeeper.ssl.quorum.trustStore.location");
        System.clearProperty("zookeeper.ssl.quorum.trustStore.password");
        System.clearProperty("zookeeper.ssl.quorum.trustStore.type");
        System.clearProperty("zookeeper.admin.portUnification");
        System.clearProperty("zookeeper.admin.forceHttps");
    }

    /**
     * Tests that we can start and query a JettyAdminServer.
     */
    @Test
    public void testJettyAdminServer() throws AdminServerException, IOException, SSLContextException, GeneralSecurityException {
        JettyAdminServer server = new JettyAdminServer();
        try {
            server.start();
            queryAdminServer(jettyAdminPort);
            traceAdminServer(jettyAdminPort);
        } finally {
            server.shutdown();
        }
    }

    /**
     * Starts a standalone server and tests that we can query its AdminServer.
     */
    @Test
    public void testStandalone() throws Exception {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT = PortAssignment.unique();

        ZooKeeperServerMainTest.MainThread main = new ZooKeeperServerMainTest.MainThread(CLIENT_PORT, false, null);
        main.start();

        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT, ClientBase.CONNECTION_TIMEOUT),
                "waiting for server being up");

        queryAdminServer(jettyAdminPort);

        main.shutdown();

        assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT, ClientBase.CONNECTION_TIMEOUT),
                "waiting for server down");
    }

    /**
     * Starts a quorum of two servers and tests that we can query both AdminServers.
     */
    @Test
    public void testQuorum() throws Exception {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT_QP1 = PortAssignment.unique();
        final int CLIENT_PORT_QP2 = PortAssignment.unique();

        final int ADMIN_SERVER_PORT1 = PortAssignment.unique();
        final int ADMIN_SERVER_PORT2 = PortAssignment.unique();

        String quorumCfgSection = String.format(
            "server.1=127.0.0.1:%d:%d;%d\nserver.2=127.0.0.1:%d:%d;%d",
            PortAssignment.unique(),
            PortAssignment.unique(),
            CLIENT_PORT_QP1,
            PortAssignment.unique(),
            PortAssignment.unique(),
            CLIENT_PORT_QP2);
        QuorumPeerTestBase.MainThread q1 = new QuorumPeerTestBase.MainThread(1, CLIENT_PORT_QP1, ADMIN_SERVER_PORT1, quorumCfgSection, null);
        q1.start();

        // Since JettyAdminServer reads a system property to determine its port,
        // make sure it initializes itself before setting the system property
        // again with the second port number
        Thread.sleep(500);

        QuorumPeerTestBase.MainThread q2 = new QuorumPeerTestBase.MainThread(2, CLIENT_PORT_QP2, ADMIN_SERVER_PORT2, quorumCfgSection, null);
        q2.start();

        Thread.sleep(500);

        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP1, ClientBase.CONNECTION_TIMEOUT),
                "waiting for server 1 being up");
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP2, ClientBase.CONNECTION_TIMEOUT),
                "waiting for server 2 being up");

        queryAdminServer(ADMIN_SERVER_PORT1);
        queryAdminServer(ADMIN_SERVER_PORT2);

        q1.shutdown();
        q2.shutdown();

        assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP1, ClientBase.CONNECTION_TIMEOUT),
                "waiting for server 1 down");
        assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP2, ClientBase.CONNECTION_TIMEOUT),
                "waiting for server 2 down");
    }

    @Test
    public void testForceHttpsPortUnificationEnabled() throws Exception {
        testForceHttps(true);
    }

    @Test
    public void testForceHttpsPortUnificationDisabled() throws Exception {
        testForceHttps(false);
    }

    private void testForceHttps(boolean portUnification) throws Exception {
        System.setProperty("zookeeper.admin.forceHttps", "true");
        System.setProperty("zookeeper.admin.portUnification", String.valueOf(portUnification));
        boolean httpsPassed = false;

        JettyAdminServer server = new JettyAdminServer();
        try {
            server.start();
            queryAdminServer(String.format(HTTPS_URL_FORMAT, jettyAdminPort), true);
            httpsPassed = true;
            queryAdminServer(String.format(URL_FORMAT, jettyAdminPort), false);
            fail("http call should have failed since forceHttps=true");
        } catch (SocketException se) {
            //good
        } finally {
            server.shutdown();
        }
        assertTrue(httpsPassed);
    }

    /**
     * Check that we can load the commands page of an AdminServer running at
     * localhost:port. (Note that this should work even if no zk server is set.)
     */
    private void queryAdminServer(int port) throws IOException, SSLContextException {
        queryAdminServer(String.format(URL_FORMAT, port), false);
        queryAdminServer(String.format(HTTPS_URL_FORMAT, port), true);
    }

    /**
     * Check that loading urlStr results in a non-zero length response.
     */
    private void queryAdminServer(String urlStr, boolean encrypted) throws IOException, SSLContextException {
        URL url = new URL(urlStr);
        BufferedReader dis;
        if (!encrypted) {
            dis = new BufferedReader(new InputStreamReader((url.openStream())));
        } else {
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            dis = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        }
        String line = dis.readLine();
        assertTrue(line.length() > 0);
    }

    /**
     * Using TRACE method to visit admin server
     */
    private void traceAdminServer(int port) throws IOException {
      traceAdminServer(String.format(URL_FORMAT, port));
      traceAdminServer(String.format(HTTPS_URL_FORMAT, port));
    }

    /**
     * Using TRACE method to visit admin server, the response should be 403 forbidden
     */
    private void traceAdminServer(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("TRACE");
        conn.connect();
        assertEquals(HttpURLConnection.HTTP_FORBIDDEN, conn.getResponseCode());
    }
}
