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

import static org.apache.zookeeper.ZooDefs.Ids.OPEN_ACL_UNSAFE;
import static org.apache.zookeeper.server.admin.Commands.AUTH_INFO_SEPARATOR;
import static org.apache.zookeeper.server.admin.Commands.ROOT_PATH;
import static org.apache.zookeeper.server.admin.JettyAdminServerTest.HTTPS_URL_FORMAT;
import static org.junit.Assert.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import javax.servlet.http.HttpServletResponse;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.ClientX509Util;
import org.apache.zookeeper.common.QuorumX509Util;
import org.apache.zookeeper.common.X509Exception;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.server.NettyServerCnxnFactory;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.auth.DigestAuthenticationProvider;
import org.apache.zookeeper.server.auth.ProviderRegistry;
import org.apache.zookeeper.server.auth.X509AuthenticationProvider;
import org.apache.zookeeper.test.ClientBase;
import org.eclipse.jetty.http.HttpHeader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CommandAuthTest extends ZKTestCase {
    private static final String DIGEST_SCHEMA = "digest";
    private static final String X509_SCHEMA = "x509";
    private static final String IP_SCHEMA = "ip";
    private static final String ROOT_USER = "root";
    private static final String ROOT_PASSWORD = "root_passwd";
    private static final String AUTH_TEST_COMMAND_NAME = "authtest";
    private static final String X509_SUBJECT_PRINCIPAL = "CN=localhost,OU=ZooKeeper,O=Apache,L=Unknown,ST=Unknown,C=Unknown";

    public enum AuthSchema {
        DIGEST,
        X509,
        IP
    }

    private final int jettyAdminPort = PortAssignment.unique();
    private final String hostPort = "127.0.0.1:" + PortAssignment.unique();
    private final ClientX509Util clientX509Util = new ClientX509Util();
    private final QuorumX509Util quorumX509Util = new QuorumX509Util();
    private ZooKeeperServer zks;
    private ServerCnxnFactory cnxnFactory;
    private JettyAdminServer adminServer;
    private ZooKeeper zk;

    @TempDir
    static File dataDir;

    @TempDir
    static File logDir;

    @BeforeAll
    public void setup() throws Exception {
        Commands.registerCommand(new AuthTestCommand(true, ZooDefs.Perms.ALL, ROOT_PATH));

        setupTLS();

        // start ZookeeperServer
        System.setProperty("zookeeper.4lw.commands.whitelist", "*");
        zks = new ZooKeeperServer(dataDir, logDir, 3000);
        final int port = Integer.parseInt(hostPort.split(":")[1]);
        cnxnFactory = ServerCnxnFactory.createFactory(port, -1);
        cnxnFactory.startup(zks);
        assertTrue(ClientBase.waitForServerUp(hostPort, 120000));

        // start AdminServer
        System.setProperty("zookeeper.admin.enableServer", "true");
        System.setProperty("zookeeper.admin.serverPort", String.valueOf(jettyAdminPort));
        adminServer = new JettyAdminServer();
        adminServer.setZooKeeperServer(zks);
        adminServer.start();
    }

    @AfterAll
    public void tearDown() throws Exception {
        clearTLS();

        System.clearProperty("zookeeper.4lw.commands.whitelist");
        System.clearProperty("zookeeper.admin.enableServer");
        System.clearProperty("zookeeper.admin.serverPort");

        if (adminServer != null) {
            adminServer.shutdown();
        }

        if (cnxnFactory != null) {
            cnxnFactory.shutdown();
        }

        if (zks != null) {
            zks.shutdown();
        }
    }

    @BeforeEach
    public void setupEach() throws Exception {
        zk = ClientBase.createZKClient(hostPort);
    }

    @AfterEach
    public void tearDownEach() throws Exception {
        if (zk != null) {
            zk.close();
        }
    }

    @ParameterizedTest
    @EnumSource(AuthSchema.class)
    public void testAuthCheck_authorized(final AuthSchema authSchema) throws Exception {
        setupRootACL(authSchema);
        try {
            final HttpURLConnection authTestConn = sendAuthTestCommandRequest(authSchema, true);
            assertEquals(HttpURLConnection.HTTP_OK, authTestConn.getResponseCode());
        } finally {
            addAuthInfo(zk, authSchema);
            resetRootACL(zk);
        }
    }

    @ParameterizedTest
    @EnumSource(value = AuthSchema.class, names = {"DIGEST"})
    public void testAuthCheck_notAuthorized(final AuthSchema authSchema) throws Exception {
        setupRootACL(authSchema);
        try {
            final HttpURLConnection authTestConn = sendAuthTestCommandRequest(authSchema, false);
            assertEquals(HttpURLConnection.HTTP_FORBIDDEN, authTestConn.getResponseCode());
        } finally {
            addAuthInfo(zk, authSchema);
            resetRootACL(zk);
        }
    }

    @ParameterizedTest
    @EnumSource(AuthSchema.class)
    public void testAuthCheck_noACL(final AuthSchema authSchema) throws Exception {
        final HttpURLConnection authTestConn = sendAuthTestCommandRequest(authSchema, false);
        assertEquals(HttpURLConnection.HTTP_OK, authTestConn.getResponseCode());
    }

    @ParameterizedTest
    @EnumSource(value = AuthSchema.class, names = {"DIGEST"})
    public void testAuthCheck_noPerms(final AuthSchema authSchema) throws Exception {
        // The extra ACL entry gives Perms.READ perms to the "invalid"
        // DIGEST authInfo---but that should not permit access, as
        // AuthTestCommand requires Perms.ADMIN.
        setupRootACL(authSchema, ZooDefs.Ids.READ_ACL_UNSAFE);
        try {
            final HttpURLConnection authTestConn = sendAuthTestCommandRequest(authSchema, false);
            assertEquals(HttpURLConnection.HTTP_FORBIDDEN, authTestConn.getResponseCode());
        } finally {
            addAuthInfo(zk, authSchema);
            resetRootACL(zk);
        }
    }

    @Test
    public void testAuthCheck_invalidServerRequiredConfig() {
        assertThrows("An active server is required for auth check",
        IllegalArgumentException.class,
        () -> new AuthTestCommand(false, ZooDefs.Perms.ALL, ROOT_PATH));
    }

    @Test
    public void testAuthCheck_noAuthInfo() {
        testAuthCheck_invalidAuthInfo(null);
    }

    @Test
    public void testAuthCheck_noAuthInfoSeparator() {
        final String invalidAuthInfo =  String.format("%s%s%s:%s", DIGEST_SCHEMA, "", ROOT_USER, ROOT_PASSWORD);
        testAuthCheck_invalidAuthInfo(invalidAuthInfo);
    }

    @Test
    public void testAuthCheck_invalidAuthInfoSeparator() {
        final String invalidAuthInfo =  String.format("%s%s%s:%s", DIGEST_SCHEMA, ":", ROOT_USER, ROOT_PASSWORD);
        testAuthCheck_invalidAuthInfo(invalidAuthInfo);
    }

    @Test
    public void testAuthCheck_invalidAuthSchema() {
        final String invalidAuthInfo =  String.format("%s%s%s:%s", "InvalidAuthSchema", AUTH_INFO_SEPARATOR, ROOT_USER, ROOT_PASSWORD);
        testAuthCheck_invalidAuthInfo(invalidAuthInfo);
    }

    @Test
    public void testAuthCheck_authProviderNotFound() {
        final String invalidAuthInfo =  String.format("%s%s%s:%s", "sasl", AUTH_INFO_SEPARATOR, ROOT_USER, ROOT_PASSWORD);
        testAuthCheck_invalidAuthInfo(invalidAuthInfo);
    }

    private void testAuthCheck_invalidAuthInfo(final String invalidAuthInfo) {
        final CommandResponse commandResponse = Commands.runGetCommand(AUTH_TEST_COMMAND_NAME, zks, new HashMap<>(), invalidAuthInfo, null);
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, commandResponse.getStatusCode());
    }

    private static class AuthTestCommand extends GetCommand {
        public AuthTestCommand(final boolean serverRequired, final int perm, final String path) {
            super(Arrays.asList(AUTH_TEST_COMMAND_NAME, "at"), serverRequired, new AuthRequest(perm, path));
        }

        @Override
        public CommandResponse runGet(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            return initializeResponse();
        }
    }

    private void setupTLS() throws Exception {
        System.setProperty("zookeeper.authProvider.x509", "org.apache.zookeeper.server.auth.X509AuthenticationProvider");
        String testDataPath = System.getProperty("test.data.dir", "src/test/resources/data");

        System.setProperty(clientX509Util.getSslKeystoreLocationProperty(), testDataPath + "/ssl/testKeyStore.jks");
        System.setProperty(clientX509Util.getSslKeystorePasswdProperty(), "testpass");
        System.setProperty(clientX509Util.getSslTruststoreLocationProperty(), testDataPath + "/ssl/testTrustStore.jks");
        System.setProperty(clientX509Util.getSslTruststorePasswdProperty(), "testpass");

        // client
        System.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET, "org.apache.zookeeper.ClientCnxnSocketNetty");
        System.setProperty(ZKClientConfig.SECURE_CLIENT, "true");

        // server
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, "org.apache.zookeeper.server.NettyServerCnxnFactory");
        System.setProperty(NettyServerCnxnFactory.PORT_UNIFICATION_KEY, Boolean.TRUE.toString());

        // admin server
        System.setProperty(quorumX509Util.getSslKeystoreLocationProperty(), testDataPath + "/ssl/testKeyStore.jks");
        System.setProperty(quorumX509Util.getSslKeystorePasswdProperty(), "testpass");
        System.setProperty(quorumX509Util.getSslTruststoreLocationProperty(), testDataPath + "/ssl/testTrustStore.jks");
        System.setProperty(quorumX509Util.getSslTruststorePasswdProperty(), "testpass");
        System.setProperty("zookeeper.admin.forceHttps", "true");
        System.setProperty("zookeeper.admin.needClientAuth", "true");

        // create SSLContext
        final SSLContext sslContext = SSLContext.getInstance(ClientX509Util.DEFAULT_PROTOCOL);
        final X509AuthenticationProvider authProvider = (X509AuthenticationProvider) ProviderRegistry.getProvider("x509");
        if (authProvider == null) {
            throw new X509Exception.SSLContextException("Could not create SSLContext with x509 auth provider");
        }
        sslContext.init(new X509KeyManager[]{authProvider.getKeyManager()}, new X509TrustManager[]{authProvider.getTrustManager()}, null);

        // set SSLSocketFactory
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
    }

    public void clearTLS() {
        System.clearProperty("zookeeper.authProvider.x509");

        System.clearProperty(clientX509Util.getSslKeystoreLocationProperty());
        System.clearProperty(clientX509Util.getSslKeystorePasswdProperty());
        System.clearProperty(clientX509Util.getSslTruststoreLocationProperty());
        System.clearProperty(clientX509Util.getSslTruststorePasswdProperty());

        // client side
        System.clearProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET);
        System.clearProperty(ZKClientConfig.SECURE_CLIENT);

        // server side
        System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
        System.clearProperty(NettyServerCnxnFactory.PORT_UNIFICATION_KEY);

        // admin server
        System.clearProperty(quorumX509Util.getSslKeystoreLocationProperty());
        System.clearProperty(quorumX509Util.getSslKeystorePasswdProperty());
        System.clearProperty(quorumX509Util.getSslTruststoreLocationProperty());
        System.clearProperty(quorumX509Util.getSslTruststorePasswdProperty());
        System.clearProperty("zookeeper.admin.forceHttps");
        System.clearProperty("zookeeper.admin.needClientAuth");
    }

    private void setupRootACL(final AuthSchema authSchema) throws Exception {
        setupRootACL(authSchema, Collections.<ACL>emptyList());
    }

    private void setupRootACL(final AuthSchema authSchema, final List<ACL> extraEntries) throws Exception {
        final List<ACL> aclEntries = new ArrayList<>();

        switch (authSchema) {
            case DIGEST:
                aclEntries.addAll(genACLForDigest());
                break;
            case X509:
                aclEntries.addAll(genACLForX509());
                break;
            case IP:
                aclEntries.addAll(genACLForIP());
                break;
            default:
                throw new IllegalArgumentException("Unknown auth schema");
        }

        aclEntries.addAll(extraEntries);

        zk.setACL(Commands.ROOT_PATH, aclEntries, -1);
    }

    private HttpURLConnection sendAuthTestCommandRequest(final AuthSchema authSchema, final boolean validAuthInfo) throws Exception  {
        final URL authTestURL = new URL(String.format(HTTPS_URL_FORMAT + "/" + AUTH_TEST_COMMAND_NAME, jettyAdminPort));
        final HttpURLConnection authTestConn = (HttpURLConnection) authTestURL.openConnection();
        addAuthHeader(authTestConn, authSchema, validAuthInfo);
        authTestConn.setRequestMethod("GET");
        return authTestConn;
    }

    private void addAuthInfo(final ZooKeeper zk, final AuthSchema authSchema) {
        switch (authSchema) {
            case DIGEST:
                addAuthInfoForDigest(zk);
                break;
            case X509:
                addAuthInfoForX509(zk);
                break;
            case IP:
                addAuthInfoForIP(zk);
                break;
            default:
                throw new IllegalArgumentException("Unknown auth schema");
        }
    }

    public static void resetRootACL(final ZooKeeper zk) throws Exception {
        zk.setACL(Commands.ROOT_PATH, OPEN_ACL_UNSAFE, -1);
    }

    public static List<ACL> genACLForDigest() throws Exception  {
        final String idPassword = String.format("%s:%s", ROOT_USER, ROOT_PASSWORD);
        final String digest = DigestAuthenticationProvider.generateDigest(idPassword);

        final ACL acl = new ACL(ZooDefs.Perms.ALL, new Id(DIGEST_SCHEMA, digest));
        return Collections.singletonList(acl);
    }

    private static List<ACL> genACLForX509() throws Exception  {
        final ACL acl = new ACL(ZooDefs.Perms.ALL, new Id(X509_SCHEMA, X509_SUBJECT_PRINCIPAL));
        return Collections.singletonList(acl);
    }

    private static List<ACL> genACLForIP() throws Exception  {
        final ACL acl = new ACL(ZooDefs.Perms.ALL, new Id(IP_SCHEMA, "127.0.0.1"));
        return Collections.singletonList(acl);
    }

    public static void addAuthInfoForDigest(final ZooKeeper zk) {
        final String idPassword = String.format("%s:%s", ROOT_USER, ROOT_PASSWORD);
        zk.addAuthInfo(DIGEST_SCHEMA, idPassword.getBytes(StandardCharsets.UTF_8));
    }

    public static void addAuthInfoForX509(final ZooKeeper zk) {
        zk.addAuthInfo(X509_SCHEMA, X509_SUBJECT_PRINCIPAL.getBytes(StandardCharsets.UTF_8));
    }

    private void addAuthInfoForIP(final ZooKeeper zk) {
        zk.addAuthInfo(IP_SCHEMA, "127.0.0.1".getBytes(StandardCharsets.UTF_8));
    }

    public static void addAuthHeader(final HttpURLConnection conn, final AuthSchema authSchema, final boolean validAuthInfo) {
        String authInfo;
        switch (authSchema) {
            case DIGEST:
                authInfo = validAuthInfo ? buildAuthorizationForDigest() : buildInvalidAuthorizationForDigest();
                break;
            case X509:
                authInfo = buildAuthorizationForX509();
                break;
            case IP:
                authInfo = buildAuthorizationForIP();
                break;
            default:
                throw new IllegalArgumentException("Unknown auth schema");
        }
        conn.setRequestProperty(HttpHeader.AUTHORIZATION.asString(), authInfo);
    }

    public static String buildAuthorizationForDigest() {
        return  String.format("%s%s%s:%s", DIGEST_SCHEMA, Commands.AUTH_INFO_SEPARATOR, ROOT_USER, ROOT_PASSWORD);
    }

    private static String buildInvalidAuthorizationForDigest() {
        return  String.format("%s%s%s:%s", DIGEST_SCHEMA, Commands.AUTH_INFO_SEPARATOR, "InvalidUser", "InvalidPassword");
    }

    private static String buildAuthorizationForX509() {
        return  String.format("%s%s", X509_SCHEMA, Commands.AUTH_INFO_SEPARATOR);
    }

    private static String buildAuthorizationForIP() {
        return  String.format("%s%s", IP_SCHEMA, Commands.AUTH_INFO_SEPARATOR);
    }
}
