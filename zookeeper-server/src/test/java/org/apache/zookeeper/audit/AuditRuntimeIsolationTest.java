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
package org.apache.zookeeper.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.Login;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.audit.AuditContextTestSupport.RecordingAuditLogger;
import org.apache.zookeeper.audit.AuditEvent.FieldName;
import org.apache.zookeeper.audit.AuditEvent.Result;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.server.ServerConfig;
import org.apache.zookeeper.server.ZooKeeperSaslServer;
import org.apache.zookeeper.server.auth.DigestLoginModule;
import org.apache.zookeeper.server.embedded.ExitHandler;
import org.apache.zookeeper.server.embedded.ZooKeeperServerEmbedded;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class AuditRuntimeIsolationTest extends ZKTestCase {

    private static final int SERVER_COLLECTION_TIMEOUT_SECONDS = 10;

    @TempDir
    Path tempDir;

    @BeforeEach
    public void clearLoggers() {
        RecordingAuditLogger.instances.clear();
    }

    @Test
    public void testParsingKeepsAuditSettingsLocalAndUsesCapturedDefaults() throws Exception {
        String previousEnable = System.getProperty(ZKConfig.AUDIT_ENABLE);
        String previousImpl = System.getProperty(ZKConfig.AUDIT_IMPL_CLASS);
        int initializationsBefore = RecordingAuditLogger.instances.size();
        try {
            System.setProperty(ZKConfig.AUDIT_ENABLE, "true");
            System.setProperty(ZKConfig.AUDIT_IMPL_CLASS, RecordingAuditLogger.class.getName());
            QuorumPeerConfig capturedDefaults = new QuorumPeerConfig();
            System.setProperty(ZKConfig.AUDIT_ENABLE, "false");
            System.setProperty(ZKConfig.AUDIT_IMPL_CLASS, "no.such.LateDefaultLogger");
            Properties properties = new Properties();
            properties.setProperty("dataDir", tempDir.toString());
            capturedDefaults.parseProperties(properties);
            assertTrue(capturedDefaults.isAuditEnabled());
            assertEquals(RecordingAuditLogger.class.getName(), capturedDefaults.getAuditLoggerClass());

            properties.setProperty(QuorumPeerConfig.AUDIT_ENABLE, "true");
            properties.setProperty(QuorumPeerConfig.AUDIT_IMPL_CLASS, FirstAuditLogger.class.getName());
            QuorumPeerConfig explicitSettings = new QuorumPeerConfig();
            explicitSettings.parseProperties(properties);
            assertTrue(explicitSettings.isAuditEnabled());
            assertEquals(FirstAuditLogger.class.getName(), explicitSettings.getAuditLoggerClass());
            ServerConfig standalone = new ServerConfig();
            standalone.readFrom(explicitSettings);
            assertTrue(standalone.isAuditEnabled());
            assertEquals(FirstAuditLogger.class.getName(), standalone.getAuditLoggerClass());

            properties.setProperty(QuorumPeerConfig.AUDIT_ENABLE, "false");
            properties.setProperty(QuorumPeerConfig.AUDIT_IMPL_CLASS, SecondAuditLogger.class.getName());
            QuorumPeerConfig disabled = new QuorumPeerConfig();
            disabled.parseProperties(properties);
            assertFalse(disabled.isAuditEnabled());
            assertEquals(SecondAuditLogger.class.getName(), disabled.getAuditLoggerClass());
            assertEquals("false", System.getProperty(ZKConfig.AUDIT_ENABLE));
            assertEquals("no.such.LateDefaultLogger", System.getProperty(ZKConfig.AUDIT_IMPL_CLASS));
            assertEquals(initializationsBefore, RecordingAuditLogger.instances.size());
        } finally {
            restoreProperty(ZKConfig.AUDIT_ENABLE, previousEnable);
            restoreProperty(ZKConfig.AUDIT_IMPL_CLASS, previousImpl);
        }
    }

    @Test
    public void testClientAndTwoEmbeddedServersOwnTheirAuditLoggers() throws Exception {
        Configuration previousConfiguration = Configuration.getConfiguration();
        String previousEnable = System.getProperty(ZKConfig.AUDIT_ENABLE);
        String previousImpl = System.getProperty(ZKConfig.AUDIT_IMPL_CLASS);
        String previousAdmin = System.getProperty("zookeeper.admin.enableServer");
        String previousServerContext = System.getProperty(ZooKeeperSaslServer.LOGIN_CONTEXT_NAME_KEY);
        Login clientLogin = null;
        System.setProperty("zookeeper.admin.enableServer", "false");
        Configuration.setConfiguration(new Configuration() {
            @Override
            public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
                String user = "Client".equals(name) ? "runtime-client"
                    : "ServerA".equals(name) ? "runtime-A" : "runtime-B";
                return new AppConfigurationEntry[]{new AppConfigurationEntry(
                    DigestLoginModule.class.getName(), AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                    Map.of("username", user, "password", "test-only", "principal", user))};
            }
        });
        try {
            System.setProperty(ZKConfig.AUDIT_ENABLE, "true");
            System.setProperty(ZKConfig.AUDIT_IMPL_CLASS, RecordingAuditLogger.class.getName());
            clientLogin = new Login("Client", () -> callbacks -> { }, new ZKConfig());
            assertEquals(1, RecordingAuditLogger.instances.size());
            assertEquals("runtime-client",
                RecordingAuditLogger.instances.get(0).events.get(0).getValue(FieldName.USER));

            Path firstDir = Files.createDirectory(tempDir.resolve("first"));
            Path secondDir = Files.createDirectory(tempDir.resolve("second"));
            try (ZooKeeperServerEmbedded first = embedded(firstDir, FirstAuditLogger.class);
                    ZooKeeperServerEmbedded second = embedded(secondDir, SecondAuditLogger.class)) {
                assertEquals(1, RecordingAuditLogger.instances.size());
                assertEquals(RecordingAuditLogger.class.getName(), System.getProperty(ZKConfig.AUDIT_IMPL_CLASS));
                assertEquals("true", System.getProperty(ZKConfig.AUDIT_ENABLE));

                System.setProperty(ZKConfig.AUDIT_ENABLE, "false");
                System.setProperty(ZKConfig.AUDIT_IMPL_CLASS, "no.such.LateDefaultLogger");
                System.setProperty(ZooKeeperSaslServer.LOGIN_CONTEXT_NAME_KEY, "ServerA");
                first.start(ClientBase.CONNECTION_TIMEOUT);
                System.setProperty(ZooKeeperSaslServer.LOGIN_CONTEXT_NAME_KEY, "ServerB");
                second.start(ClientBase.CONNECTION_TIMEOUT);
                assertEquals(3, RecordingAuditLogger.instances.size());
                RecordingAuditLogger firstLogger = RecordingAuditLogger.instances.get(1);
                RecordingAuditLogger secondLogger = RecordingAuditLogger.instances.get(2);
                assertEquals(FirstAuditLogger.class, firstLogger.getClass());
                assertEquals(SecondAuditLogger.class, secondLogger.getClass());
                assertEquals(1, firstLogger.initializations);
                assertEquals(1, secondLogger.initializations);

                ZKClientConfig config = new ZKClientConfig();
                config.setProperty(ZKClientConfig.ENABLE_CLIENT_SASL_KEY, "false");
                try (ZooKeeper firstClient = ClientBase.createZKClient(first.getConnectionString(),
                        ClientBase.CONNECTION_TIMEOUT, ClientBase.CONNECTION_TIMEOUT, config);
                        ZooKeeper secondClient = ClientBase.createZKClient(second.getConnectionString(),
                        ClientBase.CONNECTION_TIMEOUT, ClientBase.CONNECTION_TIMEOUT, config)) {
                    firstClient.create("/owned-by-A", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                    assertThrows(NodeExistsException.class, () -> firstClient.create(
                        "/owned-by-A", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
                    firstClient.multi(List.of(
                        Op.create("/multi-A", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                        Op.setData("/multi-A", new byte[]{1}, -1)));
                    secondClient.create("/owned-by-B", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                    assertOwner(firstLogger.events, "runtime-A", "/owned-by-A", "/owned-by-B");
                    assertOwner(secondLogger.events, "runtime-B", "/owned-by-B", "/owned-by-A");
                    assertEquals(1, RecordingAuditLogger.instances.get(0).events.size());
                    assertEquals(3, RecordingAuditLogger.instances.size());
                    List<AuditEvent> requests = firstLogger.events.stream()
                        .filter(event -> event.getValue(FieldName.ZNODE) != null).toList();
                    assertEquals(List.of("create:/owned-by-A:SUCCESS", "create:/owned-by-A:FAILURE",
                        "create:/multi-A:SUCCESS", "setData:/multi-A:SUCCESS"), requests.stream()
                        .map(event -> event.getValue(FieldName.OPERATION) + ":" + event.getValue(FieldName.ZNODE)
                            + ":" + event.getResult()).toList());
                    for (AuditEvent event : requests) {
                        assertEquals("0x" + Long.toHexString(firstClient.getSessionId()),
                            event.getValue(FieldName.SESSION));
                        assertEquals("127.0.0.1", event.getValue(FieldName.IP));
                        assertNotNull(event.getValue(FieldName.USER));
                    }
                    assertEquals("persistent", requests.get(0).getValue(FieldName.ZNODE_TYPE));
                    assertEquals("persistent", requests.get(2).getValue(FieldName.ZNODE_TYPE));
                }
            }
        } finally {
            if (clientLogin != null) {
                clientLogin.shutdown();
            }
            Configuration.setConfiguration(previousConfiguration);
            restoreProperty(ZKConfig.AUDIT_ENABLE, previousEnable);
            restoreProperty(ZKConfig.AUDIT_IMPL_CLASS, previousImpl);
            restoreProperty("zookeeper.admin.enableServer", previousAdmin);
            restoreProperty(ZooKeeperSaslServer.LOGIN_CONTEXT_NAME_KEY, previousServerContext);
        }
    }

    @Test
    public void testShutdownAuditDoesNotRetainClosedEmbeddedServer() throws Exception {
        assumeFalse(ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:+DisableExplicitGC"),
            "This test needs explicit GC to observe collection of the closed server");
        String previousAdmin = System.getProperty("zookeeper.admin.enableServer");
        try {
            System.setProperty("zookeeper.admin.enableServer", "false");
            WeakReference<ZooKeeperServerEmbedded> closedServer = startAndCloseEmbeddedServer();
            waitFor("closed embedded server was not collected; check GC settings or retained references", () -> {
                System.gc();
                return closedServer.get() == null;
            }, SERVER_COLLECTION_TIMEOUT_SECONDS);
        } finally {
            restoreProperty("zookeeper.admin.enableServer", previousAdmin);
        }
    }

    private WeakReference<ZooKeeperServerEmbedded> startAndCloseEmbeddedServer() throws Exception {
        try (ZooKeeperServerEmbedded server = embedded(tempDir, Slf4jAuditLogger.class)) {
            server.start(ClientBase.CONNECTION_TIMEOUT);
            return new WeakReference<>(server);
        }
    }

    private ZooKeeperServerEmbedded embedded(Path directory, Class<? extends AuditLogger> loggerClass)
        throws Exception {
        Properties properties = new Properties();
        properties.setProperty(QuorumPeerConfig.AUDIT_ENABLE, "true");
        properties.setProperty(QuorumPeerConfig.AUDIT_IMPL_CLASS, loggerClass.getName());
        properties.setProperty("tickTime", "2000");
        properties.setProperty("clientPort", "0");
        return ZooKeeperServerEmbedded.builder().baseDir(directory).configuration(properties)
            .exitHandler(ExitHandler.LOG_ONLY).build();
    }

    private void assertOwner(List<AuditEvent> events, String user, String ownPath, String otherPath) {
        assertEquals(List.of(AuditConstants.OP_LOGIN, AuditConstants.OP_START), events.subList(0, 2).stream()
            .map(event -> event.getValue(FieldName.OPERATION)).toList());
        assertTrue(events.stream().anyMatch(event -> AuditConstants.OP_LOGIN.equals(event.getValue(FieldName.OPERATION))
            && user.equals(event.getValue(FieldName.USER)) && event.getResult() == Result.SUCCESS));
        assertTrue(events.stream().anyMatch(event -> AuditConstants.OP_START.equals(event.getValue(FieldName.OPERATION))
            && user.equals(event.getValue(FieldName.USER)) && event.getResult() == Result.SUCCESS));
        assertTrue(events.stream().anyMatch(event -> ownPath.equals(event.getValue(FieldName.ZNODE))
            && event.getResult() == Result.SUCCESS));
        assertFalse(events.stream().anyMatch(event -> otherPath.equals(event.getValue(FieldName.ZNODE))));
        assertFalse(events.stream().anyMatch(event ->
            AuditConstants.OP_LOGIN.equals(event.getValue(FieldName.OPERATION))
            && !user.equals(event.getValue(FieldName.USER))));
    }

    private static void restoreProperty(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previousValue);
        }
    }

    public static class FirstAuditLogger extends RecordingAuditLogger {
    }

    public static class SecondAuditLogger extends RecordingAuditLogger {
    }
}
