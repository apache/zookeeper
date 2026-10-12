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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import org.apache.zookeeper.audit.AuditEvent;
import org.apache.zookeeper.audit.AuditLogger;
import org.apache.zookeeper.audit.Slf4jAuditLogger;
import org.apache.zookeeper.audit.ZKAuditProvider;
import org.apache.zookeeper.common.ConfigException;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.server.auth.DigestLoginModule;
import org.apache.zookeeper.server.embedded.ZooKeeperServerEmbedded;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Surefire runs this class in a fresh JVM so audit initialization can be blocked.
public class LoginAuditConcurrentInitializationTest extends ZKTestCase {

    @TempDir
    Path tempDir;

    @Test
    public void testConfigurationRejectsConflictWhileAuditLoggerInitializes() throws Exception {
        Configuration previousConfiguration = Configuration.getConfiguration();
        System.setProperty(ZKAuditProvider.AUDIT_ENABLE, "true");
        System.setProperty("zookeeper.audit.impl.class", BlockingAuditLogger.class.getName());
        AtomicReference<Login> clientLogin = new AtomicReference<>();
        AtomicReference<Throwable> clientFailure = new AtomicReference<>();
        Configuration.setConfiguration(new Configuration() {
            @Override
            public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
                return new AppConfigurationEntry[]{new AppConfigurationEntry(
                    DigestLoginModule.class.getName(), AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                    Map.of("username", "audit-test", "password", "test-password"))};
            }
        });
        Thread client = new Thread(() -> {
            try {
                clientLogin.set(new Login("Client", () -> callbacks -> { }, new ZKConfig()));
            } catch (Throwable e) {
                clientFailure.set(e);
            }
        });
        try {
            client.start();
            assertTrue(BlockingAuditLogger.initializeEntered.await(5, TimeUnit.SECONDS));
            Properties config = new Properties();
            config.setProperty("audit.enable", "true");
            config.setProperty("audit.impl.class", Slf4jAuditLogger.class.getName());
            config.setProperty("tickTime", "2000");
            ConfigException failure = assertThrows(ConfigException.class, () -> {
                try (ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded.builder()
                        .baseDir(tempDir).configuration(config).build()) {
                    assertNotNull(server);
                }
            });
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
            assertTrue(failure.getCause().getMessage().contains(BlockingAuditLogger.class.getName()));
            assertTrue(failure.getCause().getMessage().contains(Slf4jAuditLogger.class.getName()));
            assertEquals(BlockingAuditLogger.class.getName(), System.getProperty("zookeeper.audit.impl.class"));
        } finally {
            BlockingAuditLogger.initializeRelease.countDown();
            client.join(5000);
            try {
                if (clientLogin.get() != null) {
                    clientLogin.get().shutdown();
                    clientLogin.get().logout();
                }
            } finally {
                Configuration.setConfiguration(previousConfiguration);
                System.clearProperty(ZKAuditProvider.AUDIT_ENABLE);
                System.clearProperty("zookeeper.audit.impl.class");
            }
        }
        assertFalse(client.isAlive());
        assertNull(clientFailure.get());
        assertNotNull(clientLogin.get());
        assertTrue(clientLogin.get().getSubject().getPublicCredentials().contains("audit-test"));
    }

    public static class BlockingAuditLogger implements AuditLogger {

        private static final CountDownLatch initializeEntered = new CountDownLatch(1);
        private static final CountDownLatch initializeRelease = new CountDownLatch(1);

        @Override
        public void initialize() {
            initializeEntered.countDown();
            try {
                assertTrue(initializeRelease.await(10, TimeUnit.SECONDS), "Audit initialization timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void logAuditEvent(AuditEvent event) {
        }
    }
}
