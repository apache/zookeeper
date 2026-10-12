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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginException;
import org.apache.zookeeper.audit.AuditEvent;
import org.apache.zookeeper.audit.AuditLogger;
import org.apache.zookeeper.audit.ZKAuditProvider;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.server.auth.DigestLoginModule;
import org.apache.zookeeper.test.LoggerTestTool;
import org.junit.jupiter.api.Test;

// A failed static initialization leaves the provider unusable for the rest of this JVM.
public class LoginAuditInitializationFailureTest extends ZKTestCase {

    @Test
    public void testAuditInitializationFailurePreservesJaasResults() throws Exception {
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
                String messages = logger.getOutputStream().toString(StandardCharsets.UTF_8);
                assertTrue(messages.contains("ExceptionInInitializerError"));
                assertTrue(messages.contains("NoClassDefFoundError"));
            } finally {
                login.logout();
            }
        } finally {
            Configuration.setConfiguration(previousConfiguration);
            System.clearProperty(ZKAuditProvider.AUDIT_ENABLE);
            System.clearProperty("zookeeper.audit.impl.class");
        }
    }

    public static class FailingAuditLogger implements AuditLogger {

        @Override
        public void initialize() {
            throw new IllegalStateException("test audit initialization failure");
        }

        @Override
        public void logAuditEvent(AuditEvent event) {
            throw new AssertionError("the uninitialized logger must not be used");
        }
    }
}
