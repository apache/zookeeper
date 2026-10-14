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
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import org.apache.zookeeper.audit.Slf4jAuditLogger;
import org.apache.zookeeper.audit.ZKAuditProvider;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.server.auth.DigestLoginModule;
import org.apache.zookeeper.server.embedded.ZooKeeperServerEmbedded;
import org.apache.zookeeper.test.LoggerTestTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Surefire runs each class in a fresh JVM because the audit provider initializes once.
public class LoginAuditInitializationTest extends ZKTestCase {

    @TempDir
    Path tempDir;

    @Test
    public void testClientLoginBeforeEmbeddedServerDoesNotDisableAudit() throws Exception {
        Configuration previousConfiguration = Configuration.getConfiguration();
        System.setProperty(ZKAuditProvider.AUDIT_ENABLE, "false");
        try (LoggerTestTool logger = new LoggerTestTool(Slf4jAuditLogger.class)) {
            Configuration.setConfiguration(new Configuration() {
                @Override
                public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
                    return new AppConfigurationEntry[]{new AppConfigurationEntry(
                        DigestLoginModule.class.getName(), AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                        Map.of("username", "audit-test", "password", "test-password"))};
                }
            });
            Login clientLogin = new Login("Client", () -> callbacks -> { }, new ZKConfig());
            clientLogin.logout();
            assertEquals("", logger.getOutputStream().toString(StandardCharsets.UTF_8));

            Properties config = new Properties();
            config.setProperty("audit.enable", "true");
            config.setProperty("audit.impl.class", TestAuditLogger.class.getName());
            config.setProperty("tickTime", "2000");
            try (ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded.builder()
                    .baseDir(tempDir).configuration(config).build()) {
                assertFalse(TestAuditLogger.initialized);
                assertTrue(ZKAuditProvider.isAuditEnabled());
                assertTrue(TestAuditLogger.initialized);
                Login serverLogin = new Login("Server", () -> callbacks -> { }, new ZKConfig());
                try {
                    assertTrue(logger.getOutputStream().toString(StandardCharsets.UTF_8)
                        .contains("user=audit-test\toperation=login\tresult=success"));
                } finally {
                    serverLogin.logout();
                }
            }
        } finally {
            Configuration.setConfiguration(previousConfiguration);
            System.clearProperty(ZKAuditProvider.AUDIT_ENABLE);
            System.clearProperty("zookeeper.audit.impl.class");
        }
    }

    public static class TestAuditLogger extends Slf4jAuditLogger {

        private static boolean initialized;

        @Override
        public void initialize() {
            initialized = true;
        }
    }
}
