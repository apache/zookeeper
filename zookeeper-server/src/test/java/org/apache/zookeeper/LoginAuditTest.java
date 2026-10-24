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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.kerberos.KerberosPrincipal;
import javax.security.auth.kerberos.KerberosTicket;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;
import org.apache.zookeeper.audit.AuditContext;
import org.apache.zookeeper.audit.AuditContextTestSupport.FailingAuditLogger;
import org.apache.zookeeper.audit.AuditEvent;
import org.apache.zookeeper.audit.Slf4jAuditLogger;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperSaslServer;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.auth.DigestLoginModule;
import org.apache.zookeeper.test.LoggerTestTool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

public class LoginAuditTest extends ZKTestCase {

    private static final int ASYNC_TIMEOUT_SECONDS = 5;
    private static final String CONTEXT = "AuditTest";
    private static final String USER = "client@EXAMPLE.COM";
    private static final String CONFIGURED_USER = "configured@EXAMPLE.COM";
    private static final String LOGIN_STATE_OPTION = "testState";
    private Configuration previousConfiguration;
    private LoggerTestTool logger;
    private Login login;
    private LoginState loginState;
    private TestAuditLogger auditLogger;
    private AuditContext auditContext;

    @TempDir
    Path tempDir;

    @BeforeAll
    public static void enableAudit() {
        System.setProperty(ZKConfig.AUDIT_ENABLE, "true");
        System.setProperty(ZKConfig.AUDIT_IMPL_CLASS, TestAuditLogger.class.getName());
    }

    @AfterAll
    public static void clearAuditProperty() {
        System.clearProperty(ZKConfig.AUDIT_ENABLE);
        System.clearProperty(ZKConfig.AUDIT_IMPL_CLASS);
    }

    @BeforeEach
    public void setUp() {
        previousConfiguration = Configuration.getConfiguration();
        logger = new LoggerTestTool(Slf4jAuditLogger.class);
        auditContext = AuditContext.create(true, TestAuditLogger.class.getName());
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testLoginUsesAuthenticatedPrincipal(boolean configuredPrincipal) throws Exception {
        configure(configuredPrincipal, false);
        login = new TestLogin(new ZKConfig());
        assertEquals(configuredPrincipal ? CONFIGURED_USER : Login.SYSTEM_USER, login.getUserName());
        assertMessages("user=" + USER + "\toperation=login\tresult=success");
    }

    @Test
    public void testDigestLoginUsesSubjectUsername() throws Exception {
        configure(CONTEXT, new AppConfigurationEntry(
            DigestLoginModule.class.getName(), AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
            Map.of("username", "digest\n\t-client", "password", "private-test-password",
                "principal", CONFIGURED_USER)));
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

    @ParameterizedTest
    @MethodSource("nonStringPrincipalCases")
    public void testNonStringPrincipalPreservesJaasFailure(String contextName, boolean enabled) throws Exception {
        configure(contextName,
            new AppConfigurationEntry(
                DigestLoginModule.class.getName(), AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                Map.of("username", "audit-test", "password", "test-password", "principal", 42)),
            new AppConfigurationEntry(
                "com.sun.security.auth.module.Krb5LoginModule", AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                Map.of("principal", CONFIGURED_USER, "useTicketCache", "true",
                       "ticketCache", tempDir.resolve("missing-cache").toString(),
                       "useKeyTab", "false", "doNotPrompt", "true")));
        if (!enabled) {
            auditContext = AuditContext.disabled();
        }
        LoginContext jaasLogin = new LoginContext(CONTEXT, callbacks -> { });
        LoginException expected = assertThrows(LoginException.class, jaasLogin::login);
        LoginException actual = assertThrows(LoginException.class, () -> new TestLogin(new ZKConfig()));
        assertEquals(expected.getClass(), actual.getClass());
        assertEquals(expected.getMessage(), actual.getMessage());
        if (enabled) {
            assertMessages("user=" + CONFIGURED_USER + "\toperation=login\tresult=failure");
        } else {
            assertMessages();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testAuditFailureDoesNotInterruptSuccessfulLogin(boolean linkageFailure) throws Exception {
        auditLogger.failAudit = true;
        auditLogger.failLinkage = linkageFailure;
        try (LoggerTestTool loginLogger = new LoggerTestTool(Login.class)) {
            login = new TestLogin(new ZKConfig());
            assertTrue(login.getSubject().getPrincipals().contains(new KerberosPrincipal(USER)));
            assertTrue(loginLogger.getOutputStream().toString(StandardCharsets.UTF_8)
                .contains("Unable to audit login"));
        }
    }

    @Test
    public void testAuditFailurePreservesFailedLogin() {
        auditLogger.failAudit = true;
        loginState.failLogin = true;
        assertSame(loginState.loginFailure, assertThrows(LoginException.class, () -> new TestLogin(new ZKConfig())));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "Missing")
    public void testMissingLoginContextIsAudited(String loginContext) {
        assertThrows(LoginException.class, () -> new Login(loginContext, () -> callbacks -> { }, new ZKConfig()));
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
        TestLogin skipped = new TestLogin(new ZKConfig());
        login = skipped;
        clearMessages();
        skipped.allowReLogin = false;
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
    public void testClientUsesDefaultLogger() throws Exception {
        ZKConfig config = new ZKConfig();
        config.setProperty(ZKConfig.AUDIT_IMPL_CLASS, null);
        login = new Login(CONTEXT, () -> callbacks -> { }, config);
        assertMessages("user=" + USER + "\toperation=login\tresult=success");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testClientKeepsInitialAuditSettings(boolean enabled) throws Exception {
        ZKConfig config = new ZKConfig();
        config.setProperty(ZKConfig.AUDIT_ENABLE, Boolean.toString(enabled));
        login = new Login(CONTEXT, () -> callbacks -> { }, config);
        if (enabled) {
            assertNotSame(auditLogger, TestAuditLogger.instance);
            assertMessages("user=" + USER + "\toperation=login\tresult=success");
        } else {
            assertSame(auditLogger, TestAuditLogger.instance, "disabled auditing must not create a backend");
            assertMessages();
        }
        clearMessages();
        config.setProperty(ZKConfig.AUDIT_ENABLE, Boolean.toString(!enabled));
        login.logout();
        assertTrue(login.getSubject().getPrincipals().isEmpty());
        if (enabled) {
            assertMessages("user=" + USER + "\toperation=logout\tresult=success");
        } else {
            assertMessages();
        }
    }

    @Test
    public void testBackendInitializationFailurePreservesJaasResult() throws Exception {
        ZKConfig config = new ZKConfig();
        config.setProperty(ZKConfig.AUDIT_IMPL_CLASS, FailingAuditLogger.class.getName());
        try (LoggerTestTool diagnostic = new LoggerTestTool(Login.class)) {
            login = new Login(CONTEXT, () -> callbacks -> { }, config);
            assertTrue(login.getSubject().getPrincipals().contains(new KerberosPrincipal(USER)));
            loginState.failLogin = true;
            assertSame(loginState.loginFailure, assertThrows(LoginException.class,
                () -> new Login(CONTEXT, () -> callbacks -> { }, config)));
            String output = diagnostic.getOutputStream().toString(StandardCharsets.UTF_8);
            assertTrue(output.contains(FailingAuditLogger.class.getName()));
            assertTrue(output.contains(FailingAuditLogger.FAILURE.getMessage()));
            assertMessages();
        }
    }

    @Test
    public void testServerLoginDoesNotWaitForClientBackendInitialization() throws Exception {
        ZKConfig config = new ZKConfig();
        config.setProperty(ZKConfig.AUDIT_IMPL_CLASS, BlockingAuditLogger.class.getName());
        FutureTask<Login> pending = new FutureTask<>(() -> new Login(CONTEXT, () -> callbacks -> { }, config));
        Thread client = new Thread(pending, "client-audit-initialization");
        client.setDaemon(true);
        try {
            client.start();
            assertTrue(BlockingAuditLogger.entered.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            AuditContext serverContext = AuditContext.create(true, TestAuditLogger.class.getName());
            login = new Login(CONTEXT, () -> callbacks -> { }, new ZKConfig(), serverContext);
            assertFalse(pending.isDone());
            assertMessages("user=" + USER + "\toperation=login\tresult=success");
        } finally {
            BlockingAuditLogger.release.countDown();
            Login completed = pending.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            completed.shutdown();
            completed.logout();
        }
    }

    @Test
    public void testExistingFactoryLoginAdoptsServerContextWithoutLoggingInAgain() throws Exception {
        loginState.includeTicket = false;
        TestFactory owner = new TestFactory();
        owner.setAuditContext(auditContext);
        owner.setZooKeeperServer(new ZooKeeperServer());
        TestFactory factory = new TestFactory();
        String previousContext = System.getProperty(ZooKeeperSaslServer.LOGIN_CONTEXT_NAME_KEY);
        System.setProperty(ZooKeeperSaslServer.LOGIN_CONTEXT_NAME_KEY, CONTEXT);
        System.setProperty(ZKConfig.AUDIT_ENABLE, "false");
        try {
            factory.configureSaslLogin();
            login = factory.login;
            factory.setZooKeeperServer(owner.getZooKeeperServer());
            assertSame(login, factory.login);
            assertSame(auditContext, factory.getAuditContext());
            assertEquals(CONFIGURED_USER, auditContext.getServerUser());
            assertMessages();
            login.logout();
            assertMessages("user=" + USER + "\toperation=logout\tresult=success");
        } finally {
            System.setProperty(ZKConfig.AUDIT_ENABLE, "true");
            if (previousContext == null) {
                System.clearProperty(ZooKeeperSaslServer.LOGIN_CONTEXT_NAME_KEY);
            } else {
                System.setProperty(ZooKeeperSaslServer.LOGIN_CONTEXT_NAME_KEY, previousContext);
            }
        }
    }

    private static Stream<Arguments> nonStringPrincipalCases() {
        return Stream.of(Arguments.of(CONTEXT, true), Arguments.of("other", true), Arguments.of(CONTEXT, false));
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

    private class TestLogin extends Login {

        private boolean allowReLogin = true;

        TestLogin(ZKConfig config) throws LoginException {
            super(CONTEXT, () -> callbacks -> { }, config, LoginAuditTest.this.auditContext);
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

    private static class TestFactory extends NIOServerCnxnFactory {
        @Override
        public void configureSaslLogin() throws IOException {
            super.configureSaslLogin();
        }
    }

    public static class BlockingAuditLogger extends Slf4jAuditLogger {
        private static final CountDownLatch entered = new CountDownLatch(1);
        private static final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void initialize() {
            entered.countDown();
            try {
                if (!release.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Audit initialization timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
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
        public void initialize(Subject subject, CallbackHandler handler,
                               Map<String, ?> sharedState, Map<String, ?> options) {
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
