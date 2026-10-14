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

package org.apache.zookeeper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.URIParameter;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.kerberos.KerberosPrincipal;
import javax.security.auth.kerberos.KerberosTicket;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;
import org.apache.zookeeper.audit.AuditEvent;
import org.apache.zookeeper.audit.Slf4jAuditLogger;
import org.apache.zookeeper.audit.ZKAuditProvider;
import org.apache.zookeeper.common.ConfigException;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.server.auth.DigestLoginModule;
import org.apache.zookeeper.server.embedded.ZooKeeperServerEmbedded;
import org.apache.zookeeper.test.LoggerTestTool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class LoginAuditTest extends ZKTestCase {

    private static final String CONTEXT = "AuditTest";
    private static final String USER = "client@EXAMPLE.COM";
    private static final String CONFIGURED_USER = "configured@EXAMPLE.COM";
    private static final String LOGIN_STATE_OPTION = "testState";
    private Configuration previousConfiguration;
    private LoggerTestTool logger;
    private TestLogin login;
    private LoginState loginState;
    private TestAuditLogger auditLogger;

    @TempDir
    Path tempDir;

    @BeforeAll
    public static void enableAudit() {
        System.setProperty(ZKAuditProvider.AUDIT_ENABLE, "true");
        System.setProperty("zookeeper.audit.impl.class", TestAuditLogger.class.getName());
        assertTrue(ZKAuditProvider.isAuditEnabled());
    }

    @AfterAll
    public static void clearAuditProperty() {
        System.clearProperty(ZKAuditProvider.AUDIT_ENABLE);
        System.clearProperty("zookeeper.audit.impl.class");
    }

    @BeforeEach
    public void setUp() {
        previousConfiguration = Configuration.getConfiguration();
        logger = new LoggerTestTool(Slf4jAuditLogger.class);
        auditLogger = TestAuditLogger.instance;
        auditLogger.failAudit = false;
        auditLogger.failLinkage = false;
        loginState = new LoginState();
        configure(true, false);
    }

    @AfterEach
    public void tearDown() throws Exception {
        try {
            auditLogger.failAudit = false;
            auditLogger.failLinkage = false;
            if (login != null) {
                login.shutdown();
                loginState.failLogout = false;
                login.logout();
            }
        } finally {
            Configuration.setConfiguration(previousConfiguration);
            logger.close();
        }
    }

    @Test
    public void testLoginUsesAuthenticatedPrincipal() throws Exception {
        login = new TestLogin(new ZKConfig());
        assertEquals(CONFIGURED_USER, login.getUserName());
        assertMessages("user=" + USER + "\toperation=login\tresult=success");
    }

    @Test
    public void testLoginWithoutConfiguredPrincipal() throws Exception {
        configure(false, false);
        login = new TestLogin(new ZKConfig());
        assertEquals(Login.SYSTEM_USER, login.getUserName());
        assertMessages("user=" + USER + "\toperation=login\tresult=success");
    }

    @Test
    public void testDigestLoginUsesSubjectUsername() throws Exception {
        configure(CONTEXT, new AppConfigurationEntry(
            DigestLoginModule.class.getName(), AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
            Map.of("username", "digest\n\t-client", "password", "private-test-password", "principal", CONFIGURED_USER)));
        login = new TestLogin(new ZKConfig());
        assertEquals(CONFIGURED_USER, login.getUserName());
        assertMessages("user=digest-client\toperation=login\tresult=success");
    }

    @ParameterizedTest
    @ValueSource(strings = {CONTEXT, "other"})
    public void testFailedDigestLoginUsesConfiguredUsername(String contextName) throws Exception {
        configure(contextName, new AppConfigurationEntry(
            DigestLoginModule.class.getName(), AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
            Map.of("username", "digest-client", "password", 42)));
        LoginContext jaasLogin = new LoginContext(CONTEXT, callbacks -> { });
        LoginException expected = assertThrows(LoginException.class, jaasLogin::login);
        LoginException actual = assertThrows(LoginException.class, () -> new TestLogin(new ZKConfig()));
        assertEquals(expected.getClass(), actual.getClass());
        assertTrue(actual.getMessage().contains("ClassCastException"));
        assertMessages("user=digest-client\toperation=login\tresult=failure");
    }

    @Test
    public void testKerberosPrincipalTakesPrecedenceOverDigestUsername() throws Exception {
        configure(CONTEXT,
            new AppConfigurationEntry(
                DigestLoginModule.class.getName(), AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                Map.of("username", "digest-client", "password", "test-password")),
            new AppConfigurationEntry(
                TestLoginModule.class.getName(), AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                Map.of("principal", CONFIGURED_USER)));
        login = new TestLogin(new ZKConfig());
        assertMessages("user=" + USER + "\toperation=login\tresult=success");
    }

    @Test
    public void testFailedLoginUsesConfiguredPrincipalAndPreservesException() {
        loginState.failLogin = true;
        LoginException failure = assertThrows(LoginException.class, () -> new TestLogin(new ZKConfig()));
        assertSame(loginState.loginFailure, failure);
        assertMessages("user=" + CONFIGURED_USER + "\toperation=login\tresult=failure");
    }

    @Test
    public void testFailedLoginUsesPrincipalFromLaterModule() throws Exception {
        configureFailingKerberosChain(CONTEXT);
        assertThrows(LoginException.class, () -> new TestLogin(new ZKConfig()));
        assertMessages("user=" + CONFIGURED_USER + "\toperation=login\tresult=failure");
    }

    @Test
    public void testNonStringPrincipalPreservesJaasFailure() throws Exception {
        configure(CONTEXT,
            new AppConfigurationEntry(
                DigestLoginModule.class.getName(), AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                Map.of("username", "audit-test", "password", "test-password", "principal", 42)),
            new AppConfigurationEntry(
                "com.sun.security.auth.module.Krb5LoginModule", AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                Map.of("principal", CONFIGURED_USER, "useTicketCache", "true",
                       "ticketCache", tempDir.resolve("missing-cache").toString(),
                       "useKeyTab", "false", "doNotPrompt", "true")));
        LoginContext jaasLogin = new LoginContext(CONTEXT, callbacks -> { });
        LoginException expected = assertThrows(LoginException.class, jaasLogin::login);
        LoginException actual = assertThrows(LoginException.class, () -> new TestLogin(new ZKConfig()));
        assertEquals(expected.getClass(), actual.getClass());
        assertEquals(expected.getMessage(), actual.getMessage());
        assertMessages("user=" + CONFIGURED_USER + "\toperation=login\tresult=failure");
    }

    @Test
    public void testFailedLoginUsesOtherFallbackPrincipal() throws Exception {
        configureFailingKerberosChain("other");
        assertThrows(LoginException.class, () -> new TestLogin(new ZKConfig()));
        assertMessages("user=" + CONFIGURED_USER + "\toperation=login\tresult=failure");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testAuditFailureDoesNotInterruptSuccessfulLogin(boolean linkageFailure) throws Exception {
        auditLogger.failAudit = true;
        auditLogger.failLinkage = linkageFailure;
        try (LoggerTestTool loginLogger = new LoggerTestTool(Login.class)) {
            login = new TestLogin(new ZKConfig());
            assertTrue(login.getSubject().getPrincipals().contains(new KerberosPrincipal(USER)));
            assertTrue(loginLogger.getOutputStream().toString(StandardCharsets.UTF_8).contains("Unable to audit login"));
        }
    }

    @Test
    public void testAuditFailurePreservesFailedLogin() {
        auditLogger.failAudit = true;
        loginState.failLogin = true;
        assertSame(loginState.loginFailure, assertThrows(LoginException.class, () -> new TestLogin(new ZKConfig())));
    }

    @Test
    public void testMissingLoginContextIsAudited() {
        assertThrows(LoginException.class, () -> new Login("Missing", () -> callbacks -> { }, new ZKConfig()));
        assertMessages("user=" + Login.SYSTEM_USER + "\toperation=login\tresult=failure");
    }

    @Test
    public void testNullLoginContextIsAudited() {
        assertThrows(LoginException.class, () -> new Login(null, () -> callbacks -> { }, new ZKConfig()));
        assertMessages("user=" + Login.SYSTEM_USER + "\toperation=login\tresult=failure");
    }

    @Test
    public void testLogoutIsAuditedOnlyWhenPerformed() throws Exception {
        login = new TestLogin(new ZKConfig());
        clearMessages();
        login.logout();
        login.logout();
        assertMessages("user=" + USER + "\toperation=logout\tresult=success");
    }

    @Test
    public void testFailedLogoutPreservesException() throws Exception {
        login = new TestLogin(new ZKConfig());
        clearMessages();
        loginState.failLogout = true;
        assertSame(loginState.logoutFailure, assertThrows(LoginException.class, () -> login.logout()));
        assertMessages("user=" + USER + "\toperation=logout\tresult=failure");
    }

    @Test
    public void testAuditFailurePreservesFailedLogout() throws Exception {
        login = new TestLogin(new ZKConfig());
        auditLogger.failAudit = true;
        loginState.failLogout = true;
        assertSame(loginState.logoutFailure, assertThrows(LoginException.class, () -> login.logout()));
    }

    @Test
    public void testRenewedPrincipalIsRetainedAcrossFailedRetries() throws Exception {
        configure(false, true);
        login = new TestLogin(new ZKConfig());
        clearMessages();
        String renewedUser = "renewed@EXAMPLE.COM";
        loginState.authenticatedUser = renewedUser;
        login.reLogin();
        assertTrue(login.getSubject().getPrincipals().contains(new KerberosPrincipal(renewedUser)));
        loginState.failLogin = true;
        assertSame(loginState.loginFailure, assertThrows(LoginException.class, login::reLogin));
        assertSame(loginState.loginFailure, assertThrows(LoginException.class, login::reLogin));
        assertMessages("user=" + USER + "\toperation=logout\tresult=success",
                       "user=" + renewedUser + "\toperation=ticketRenewal\tresult=success",
                       "user=" + renewedUser + "\toperation=logout\tresult=success",
                       "user=" + renewedUser + "\toperation=ticketRenewal\tresult=failure",
                       "user=" + renewedUser + "\toperation=ticketRenewal\tresult=failure");
    }

    @Test
    public void testAuditFailureDoesNotInterruptRenewal() throws Exception {
        login = new TestLogin(new ZKConfig());
        auditLogger.failAudit = true;
        login.reLogin();
        assertTrue(login.getSubject().getPrincipals().contains(new KerberosPrincipal(USER)));
        assertEquals(1, login.getSubject().getPrivateCredentials(KerberosTicket.class).size());
    }

    @Test
    public void testAuditFailurePreservesFailedRenewal() throws Exception {
        login = new TestLogin(new ZKConfig());
        auditLogger.failAudit = true;
        loginState.failLogin = true;
        assertSame(loginState.loginFailure, assertThrows(LoginException.class, login::reLogin));
        assertTrue(login.getSubject().getPrincipals().isEmpty());
    }

    @Test
    public void testFailedRenewalRetainsPrincipalAcrossRetries() throws Exception {
        configure(false, false);
        login = new TestLogin(new ZKConfig());
        clearMessages();
        loginState.failLogin = true;
        assertSame(loginState.loginFailure, assertThrows(LoginException.class, login::reLogin));
        assertSame(loginState.loginFailure, assertThrows(LoginException.class, login::reLogin));
        assertMessages("user=" + USER + "\toperation=logout\tresult=success",
                       "user=" + USER + "\toperation=ticketRenewal\tresult=failure",
                       "user=" + USER + "\toperation=ticketRenewal\tresult=failure");
    }

    @Test
    public void testRenewalFailureDuringLogout() throws Exception {
        login = new TestLogin(new ZKConfig());
        clearMessages();
        loginState.failLogout = true;
        assertSame(loginState.logoutFailure, assertThrows(LoginException.class, login::reLogin));
        assertMessages("user=" + USER + "\toperation=logout\tresult=failure",
                       "user=" + USER + "\toperation=ticketRenewal\tresult=failure");
    }

    @Test
    public void testSkippedRenewalIsNotAudited() throws Exception {
        login = new TestLogin(new ZKConfig());
        clearMessages();
        login.allowReLogin = false;
        login.reLogin();
        assertMessages();
    }

    @Test
    public void testRenewalWithoutKerberosTicketIsNotAudited() throws Exception {
        loginState.includeTicket = false;
        login = new TestLogin(new ZKConfig());
        clearMessages();
        login.reLogin();
        login.shutdown();
        assertMessages();
    }

    @Test
    public void testTicketCacheRefreshFailuresAreAudited() throws Exception {
        configure(false, true);
        ZKConfig config = new ZKConfig();
        config.setProperty(ZKConfig.KINIT_COMMAND, tempDir.resolve("missing-kinit").toString());
        login = new TestLogin(config);
        clearMessages();
        login.startThreadIfNeeded();
        waitFor("ticket cache refresh failures were not audited", () -> messages().size() == 2, 5);
        login.shutdown();
        assertMessages("user=" + USER + "\toperation=ticketRenewal\tresult=failure",
                       "user=" + USER + "\toperation=ticketRenewal\tresult=failure");
    }

    @Test
    public void testEmbeddedServerRejectsDifferentAuditLogger() throws Exception {
        login = new TestLogin(new ZKConfig());
        ConfigException failure = assertThrows(ConfigException.class, () -> {
            try (ZooKeeperServerEmbedded server = createEmbeddedServer(Slf4jAuditLogger.class.getName())) {
                assertNotNull(server);
            }
        });
        assertTrue(failure.getCause() instanceof IllegalArgumentException);
        assertTrue(failure.getCause().getMessage().contains(TestAuditLogger.class.getName()));
        assertTrue(failure.getCause().getMessage().contains(Slf4jAuditLogger.class.getName()));
        assertEquals(TestAuditLogger.class.getName(), System.getProperty("zookeeper.audit.impl.class"));
        clearMessages();
        login.logout();
        assertMessages("user=" + USER + "\toperation=logout\tresult=success");
    }

    @Test
    public void testEmbeddedServerAcceptsSelectedAuditLogger() throws Exception {
        try (ZooKeeperServerEmbedded server = createEmbeddedServer(TestAuditLogger.class.getName())) {
            assertNotNull(server);
        }
    }

    private ZooKeeperServerEmbedded createEmbeddedServer(String auditLoggerClass) throws Exception {
        Properties config = new Properties();
        config.setProperty("audit.enable", "true");
        config.setProperty("audit.impl.class", auditLoggerClass);
        config.setProperty("tickTime", "2000");
        return ZooKeeperServerEmbedded.builder().baseDir(tempDir).configuration(config).build();
    }

    private void configure(boolean configuredPrincipal, boolean ticketCache) {
        Map<String, Object> options = new HashMap<>();
        options.put(LOGIN_STATE_OPTION, loginState);
        if (configuredPrincipal) {
            options.put("principal", CONFIGURED_USER);
        }
        options.put("useTicketCache", Boolean.toString(ticketCache));
        configure(CONTEXT, new AppConfigurationEntry(
            TestLoginModule.class.getName(), AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, options));
    }

    private void configureFailingKerberosChain(String contextName) throws Exception {
        Path jaasFile = tempDir.resolve("jaas.conf");
        Files.writeString(jaasFile, contextName + " {\n"
            + TestLoginModule.class.getName() + " required;\n"
            + "com.sun.security.auth.module.Krb5LoginModule required\n"
            + "principal=\"" + CONFIGURED_USER + "\" useTicketCache=true\n"
            + "ticketCache=\"" + tempDir.resolve("missing-cache") + "\"\n"
            + "useKeyTab=false doNotPrompt=true;\n};\n");
        Configuration.setConfiguration(Configuration.getInstance("JavaLoginConfig", new URIParameter(jaasFile.toUri())));
    }

    private void configure(String contextName, AppConfigurationEntry... entries) {
        Configuration.setConfiguration(new Configuration() {
            @Override
            public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
                if (!contextName.equals(name)) {
                    return null;
                }
                return entries;
            }
        });
    }

    private void clearMessages() {
        logger.getOutputStream().reset();
    }

    private List<String> messages() {
        return logger.getOutputStream().toString(StandardCharsets.UTF_8).lines()
            .map(line -> line.substring(line.indexOf("user=")))
            .collect(Collectors.toList());
    }

    private void assertMessages(String... expected) {
        assertEquals(Arrays.asList(expected), messages());
    }

    private static class TestLogin extends Login {

        private boolean allowReLogin = true;

        TestLogin(ZKConfig config) throws LoginException {
            super(CONTEXT, () -> callbacks -> { }, config);
        }

        @Override
        public long getLastLogin() {
            return allowReLogin ? Time.currentElapsedTime() - TimeUnit.HOURS.toMillis(1) : Time.currentElapsedTime();
        }

        @Override
        protected void sleepBeforeRetryFailedRefresh() {
        }
    }

    public static class TestAuditLogger extends Slf4jAuditLogger {

        private static TestAuditLogger instance;
        private boolean failAudit;
        private boolean failLinkage;

        public TestAuditLogger() {
            instance = this;
        }

        @Override
        public void logAuditEvent(AuditEvent event) {
            if (failAudit) {
                if (failLinkage) {
                    throw new NoSuchMethodError("test audit backend linkage failure");
                }
                throw new IllegalStateException("test audit backend failure");
            }
            super.logAuditEvent(event);
        }
    }

    private static class LoginState {

        private boolean failLogin;
        private boolean failLogout;
        private boolean includeTicket = true;
        private String authenticatedUser = USER;
        private final LoginException loginFailure = new LoginException("test login failure");
        private final LoginException logoutFailure = new LoginException("test logout failure");
    }

    // Exercises JAAS without a KDC.
    public static class TestLoginModule implements LoginModule {

        private static final int AES128_KEY_TYPE = 17;
        private static final int AES128_KEY_BYTES = 16;
        private LoginState state;
        private Subject subject;

        @Override
        public void initialize(Subject subject, CallbackHandler handler, Map<String, ?> sharedState, Map<String, ?> options) {
            this.subject = subject;
            Object configuredState = options.get(LOGIN_STATE_OPTION);
            state = configuredState instanceof LoginState ? (LoginState) configuredState : new LoginState();
        }

        @Override
        public boolean login() throws LoginException {
            if (state.failLogin) {
                throw state.loginFailure;
            }
            return true;
        }

        @Override
        public boolean commit() {
            KerberosPrincipal client = new KerberosPrincipal(state.authenticatedUser);
            subject.getPrincipals().add(client);
            if (state.includeTicket) {
                long now = System.currentTimeMillis();
                Date start = new Date(now - TimeUnit.HOURS.toMillis(1));
                subject.getPrivateCredentials().add(new KerberosTicket(
                    new byte[]{1}, client, new KerberosPrincipal("krbtgt/EXAMPLE.COM@EXAMPLE.COM"),
                    new byte[AES128_KEY_BYTES], AES128_KEY_TYPE, null,
                    start, start, new Date(now + TimeUnit.SECONDS.toMillis(10)),
                    new Date(now + TimeUnit.HOURS.toMillis(1)), null));
            }
            return true;
        }

        @Override
        public boolean abort() {
            return true;
        }

        @Override
        public boolean logout() throws LoginException {
            if (state.failLogout) {
                throw state.logoutFailure;
            }
            subject.getPrincipals().clear();
            subject.getPrivateCredentials().clear();
            return true;
        }
    }
}
