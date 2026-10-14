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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginException;
import org.apache.zookeeper.audit.AuditConstants;
import org.apache.zookeeper.audit.AuditEvent;
import org.apache.zookeeper.audit.AuditLogger;
import org.apache.zookeeper.audit.ZKAuditProvider;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.server.ServerConfig;
import org.apache.zookeeper.server.ZooKeeperServerMain;
import org.apache.zookeeper.server.auth.DigestLoginModule;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.apache.zookeeper.server.quorum.QuorumPeerMain;
import org.apache.zookeeper.test.LoggerTestTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LoginAuditInitializationFailureTest extends ZKTestCase {

    @TempDir
    Path tempDir;

    @Test
    public void testClientAuditFailurePreservesJaasAndFailsServerStartupClearly() throws Exception {
        Configuration previousConfiguration = Configuration.getConfiguration();
        System.setProperty(ZKAuditProvider.AUDIT_ENABLE, "true");
        System.setProperty("zookeeper.audit.impl.class", FailingAuditLogger.class.getName());
        try (LoggerTestTool logger = new LoggerTestTool(Login.class)) {
            Configuration.setConfiguration(new Configuration() {
                @Override
                public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
                    return new AppConfigurationEntry[]{new AppConfigurationEntry(
                        DigestLoginModule.class.getName(), AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                        Map.of("username", "audit-test", "password", "test-password"))};
                }
            });
            Login login = new Login("Client", () -> callbacks -> { }, new ZKConfig());
            try {
                assertTrue(login.getSubject().getPublicCredentials().contains("audit-test"));
                LoginException failure = assertThrows(LoginException.class,
                    () -> new Login(null, () -> callbacks -> { }, new ZKConfig()));
                assertTrue(failure.getMessage().contains("loginContext name"));
                assertInitializationFailure(assertThrows(IllegalStateException.class, ZKAuditProvider::isAuditEnabled));
                assertInitializationFailure(assertThrows(IllegalStateException.class,
                    () -> ZKAuditProvider.log("audit-test", AuditConstants.OP_LOGIN, AuditEvent.Result.SUCCESS)));
                assertInitializationFailure(assertThrows(IllegalStateException.class,
                    () -> ZKAuditProvider.log("audit-test", AuditConstants.OP_LOGIN,
                        null, null, null, null, null, AuditEvent.Result.SUCCESS)));

                Properties properties = new Properties();
                properties.setProperty("dataDir", tempDir.toString());
                properties.setProperty("tickTime", "2000");
                properties.setProperty("clientPort", "0");
                QuorumPeerConfig quorumConfig = new QuorumPeerConfig();
                quorumConfig.parseProperties(properties);
                ServerConfig standaloneConfig = new ServerConfig();
                standaloneConfig.readFrom(quorumConfig);
                assertInitializationFailure(assertThrows(IllegalStateException.class,
                    () -> new ZooKeeperServerMain().runFromConfig(standaloneConfig)));

                Files.writeString(tempDir.resolve("myid"), "1");
                properties.setProperty("initLimit", "5");
                properties.setProperty("syncLimit", "2");
                properties.setProperty("server.1", "127.0.0.1:2888:3888");
                properties.setProperty("server.2", "127.0.0.1:2889:3889");
                QuorumPeerConfig distributedConfig = new QuorumPeerConfig();
                distributedConfig.parseProperties(properties);
                assertTrue(distributedConfig.isDistributed());
                assertInitializationFailure(assertThrows(IllegalStateException.class,
                    () -> new QuorumPeerMain().runFromConfig(distributedConfig)));
                assertFalse(Files.exists(tempDir.resolve("version-2")));
                assertEquals(1, FailingAuditLogger.initializations.get());
                String messages = logger.getOutputStream().toString(StandardCharsets.UTF_8);
                assertTrue(messages.contains(FailingAuditLogger.class.getName()));
                assertTrue(messages.contains(FailingAuditLogger.failure.getMessage()));
            } finally {
                login.logout();
            }
        } finally {
            Configuration.setConfiguration(previousConfiguration);
            System.clearProperty(ZKAuditProvider.AUDIT_ENABLE);
            System.clearProperty("zookeeper.audit.impl.class");
        }
    }

    private void assertInitializationFailure(IllegalStateException failure) {
        assertTrue(failure.getMessage().contains(FailingAuditLogger.class.getName()));
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertSame(FailingAuditLogger.failure, cause);
    }

    public static class FailingAuditLogger implements AuditLogger {

        private static final AtomicInteger initializations = new AtomicInteger();
        private static final IllegalStateException failure = new IllegalStateException("test audit initialization failure");

        @Override
        public void initialize() {
            initializations.incrementAndGet();
            throw failure;
        }

        @Override
        public void logAuditEvent(AuditEvent event) {
            throw new AssertionError("the uninitialized logger must not be used");
        }
    }
}
