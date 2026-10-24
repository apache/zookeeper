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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;
import org.apache.zookeeper.audit.AuditEvent;
import org.apache.zookeeper.audit.AuditLogger;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.ZKConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class LoginAuditClientRuntimeTest extends ZKTestCase {

    private static final int SESSION_TIMEOUT_MILLIS = 30000;
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int FAILED_LOGIN_ATTEMPTS = 1;
    private static final int SUCCESSFUL_LOGIN_ATTEMPTS = 1;
    private static final int RECONNECTS_WITH_EXISTING_LOGIN = 1;
    private Configuration previousConfiguration;

    @BeforeEach
    public void resetFixtures() {
        previousConfiguration = Configuration.getConfiguration();
        RecordingAuditLogger.initializations.set(0);
        RecordingAuditLogger.events.clear();
        RetryingLoginModule.loginAttempts.set(0);
    }

    @AfterEach
    public void restoreConfiguration() {
        Configuration.setConfiguration(previousConfiguration);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testClientInitializesOneBackendOnlyWhenJaasIsConfigured(boolean jaasConfigured) throws Exception {
        Configuration.setConfiguration(new Configuration() {
            @Override
            public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
                if (!jaasConfigured) {
                    return null;
                }
                return new AppConfigurationEntry[]{new AppConfigurationEntry(
                    RetryingLoginModule.class.getName(), AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                    Map.of("username", "audit-client"))};
            }
        });
        int loginAttempts = jaasConfigured ? FAILED_LOGIN_ATTEMPTS + SUCCESSFUL_LOGIN_ATTEMPTS : 0;
        RetryingSocket.connectionAttempts = new CountDownLatch(loginAttempts + RECONNECTS_WITH_EXISTING_LOGIN);
        try (ZooKeeper client = new ZooKeeper("127.0.0.1:1", SESSION_TIMEOUT_MILLIS, event -> { }, clientConfig())) {
            assertTrue(RetryingSocket.connectionAttempts.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(jaasConfigured ? 1 : 0, RecordingAuditLogger.initializations.get());
            assertEquals(loginAttempts, RetryingLoginModule.loginAttempts.get());
            if (jaasConfigured) {
                assertNotNull(client.cnxn.sendThread.getLogin());
            } else {
                assertNull(client.cnxn.sendThread.getLogin());
            }
            List<AuditEvent.Result> expected = jaasConfigured
                ? Arrays.asList(AuditEvent.Result.FAILURE, AuditEvent.Result.SUCCESS) : Collections.emptyList();
            assertEquals(expected,
                RecordingAuditLogger.events.stream().map(AuditEvent::getResult).collect(Collectors.toList()));
            for (AuditEvent event : RecordingAuditLogger.events) {
                assertEquals("login", event.getValue(AuditEvent.FieldName.OPERATION));
                assertEquals("audit-client", event.getValue(AuditEvent.FieldName.USER));
            }
        }
    }

    private ZKClientConfig clientConfig() {
        ZKClientConfig config = new ZKClientConfig();
        config.setProperty(ZKClientConfig.ENABLE_CLIENT_SASL_KEY, "true");
        config.setProperty(ZKClientConfig.LOGIN_CONTEXT_NAME_KEY, "Client");
        config.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET, RetryingSocket.class.getName());
        config.setProperty(ZKConfig.AUDIT_ENABLE, "true");
        config.setProperty(ZKConfig.AUDIT_IMPL_CLASS, RecordingAuditLogger.class.getName());
        return config;
    }

    // Exercise the real SendThread retry loop without opening network sockets.
    public static class RetryingSocket extends ClientCnxnSocketNIO {

        private static CountDownLatch connectionAttempts;

        public RetryingSocket(ZKClientConfig config) throws IOException {
            super(config);
        }

        @Override
        void connect(InetSocketAddress address) throws IOException {
            connectionAttempts.countDown();
            throw new IOException("test transport reconnect");
        }
    }

    public static class RecordingAuditLogger implements AuditLogger {

        private static final AtomicInteger initializations = new AtomicInteger();
        private static final List<AuditEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void initialize() {
            initializations.incrementAndGet();
        }

        @Override
        public void logAuditEvent(AuditEvent event) {
            events.add(event);
        }
    }

    public static class RetryingLoginModule implements LoginModule {

        private static final AtomicInteger loginAttempts = new AtomicInteger();
        private Subject subject;

        @Override
        public void initialize(Subject subject, CallbackHandler callbackHandler,
                               Map<String, ?> sharedState, Map<String, ?> options) {
            this.subject = subject;
        }

        @Override
        public boolean login() throws LoginException {
            if (loginAttempts.incrementAndGet() <= FAILED_LOGIN_ATTEMPTS) {
                throw new LoginException("test transient JAAS failure");
            }
            return true;
        }

        @Override
        public boolean commit() {
            subject.getPublicCredentials().add("audit-client");
            subject.getPrivateCredentials().add("test-only-password");
            return true;
        }

        @Override
        public boolean abort() {
            return true;
        }

        @Override
        public boolean logout() {
            subject.getPublicCredentials().clear();
            subject.getPrivateCredentials().clear();
            return true;
        }
    }
}
