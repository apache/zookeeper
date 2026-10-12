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
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import org.apache.zookeeper.audit.AuditConstants;
import org.apache.zookeeper.audit.AuditEvent.Result;
import org.apache.zookeeper.audit.Slf4jAuditLogger;
import org.apache.zookeeper.audit.ZKAuditProvider;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.server.auth.DigestLoginModule;
import org.apache.zookeeper.test.LoggerTestTool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Surefire runs this class in a separate JVM: audit configuration is initialized once.
public class LoginAuditDisabledTest extends ZKTestCase {

    @TempDir
    Path tempDir;

    @BeforeAll
    public static void disableAudit() {
        System.setProperty(ZKAuditProvider.AUDIT_ENABLE, "false");
    }

    @AfterAll
    public static void clearAuditProperty() {
        System.clearProperty(ZKAuditProvider.AUDIT_ENABLE);
    }

    @Test
    public void testDisabledAuditDoesNotAffectLogin() throws Exception {
        Configuration previousConfiguration = Configuration.getConfiguration();
        try (LoggerTestTool logger = new LoggerTestTool(Slf4jAuditLogger.class)) {
            Configuration.setConfiguration(new Configuration() {
                @Override
                public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
                    return new AppConfigurationEntry[]{new AppConfigurationEntry(
                        DigestLoginModule.class.getName(), AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                        Map.of("username", "audit-test", "password", "test-password"))};
                }
            });
            assertFalse(ZKAuditProvider.isAuditEnabled());
            Login login = new Login("Client", () -> callbacks -> { }, new ZKConfig());
            try {
                login.logout();
            } finally {
                login.shutdown();
            }
            assertThrows(LoginException.class, () -> new Login(null, () -> callbacks -> { }, new ZKConfig()));
            assertEquals("", logger.getOutputStream().toString(StandardCharsets.UTF_8));
        } finally {
            Configuration.setConfiguration(previousConfiguration);
        }
    }

    @Test
    public void testDisabledAuditProviderIgnoresEvents() throws Exception {
        try (LoggerTestTool logger = new LoggerTestTool(Slf4jAuditLogger.class)) {
            assertFalse(ZKAuditProvider.isAuditEnabled());
            ZKAuditProvider.log("user", AuditConstants.OP_LOGIN, Result.SUCCESS);
            ZKAuditProvider.log("user", AuditConstants.OP_LOGOUT, Result.FAILURE);
            ZKAuditProvider.log("user", AuditConstants.OP_TICKET_RENEWAL, Result.SUCCESS);
            assertEquals("", logger.getOutputStream().toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void testNonStringPrincipalPreservesJaasFailureWhenAuditDisabled() throws Exception {
        Configuration previousConfiguration = Configuration.getConfiguration();
        try (LoggerTestTool logger = new LoggerTestTool(Slf4jAuditLogger.class)) {
            Configuration.setConfiguration(new Configuration() {
                @Override
                public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
                    return new AppConfigurationEntry[]{
                        new AppConfigurationEntry(
                            DigestLoginModule.class.getName(), AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                            Map.of("username", "audit-test", "password", "test-password", "principal", 42)),
                        new AppConfigurationEntry(
                            "com.sun.security.auth.module.Krb5LoginModule",
                            AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                            Map.of("principal", "client@EXAMPLE.COM", "useTicketCache", "true",
                                   "ticketCache", tempDir.resolve("missing-cache").toString(),
                                   "useKeyTab", "false", "doNotPrompt", "true"))};
                }
            });
            LoginContext jaasLogin = new LoginContext("Client", callbacks -> { });
            LoginException expected = assertThrows(LoginException.class, jaasLogin::login);
            LoginException actual = assertThrows(
                LoginException.class, () -> new Login("Client", () -> callbacks -> { }, new ZKConfig()));
            assertEquals(expected.getClass(), actual.getClass());
            assertEquals(expected.getMessage(), actual.getMessage());
            assertEquals("", logger.getOutputStream().toString(StandardCharsets.UTF_8));
        } finally {
            Configuration.setConfiguration(previousConfiguration);
        }
    }
}
