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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.audit.AuditContextTestSupport.FailingAuditLogger;
import org.apache.zookeeper.audit.AuditContextTestSupport.RecordingAuditLogger;
import org.apache.zookeeper.audit.AuditEvent.FieldName;
import org.apache.zookeeper.audit.AuditEvent.Result;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.metrics.BaseTestMetricsProvider.MetricsProviderWithErrorInStart;
import org.apache.zookeeper.server.DatadirCleanupManager;
import org.apache.zookeeper.server.ExitCode;
import org.apache.zookeeper.server.ServerConfig;
import org.apache.zookeeper.server.ZooKeeperServerMain;
import org.apache.zookeeper.server.controller.ZooKeeperServerController;
import org.apache.zookeeper.server.embedded.ExitHandler;
import org.apache.zookeeper.server.embedded.ZooKeeperServerEmbedded;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.apache.zookeeper.server.quorum.QuorumPeerMain;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.LoggerTestTool;
import org.apache.zookeeper.util.ServiceUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

public class AuditStartupFailureTest extends ZKTestCase {

    @TempDir
    Path tempDir;
    private String previousEnable;
    private String previousImpl;

    @BeforeEach
    public void setUpAudit() {
        previousEnable = System.getProperty(ZKConfig.AUDIT_ENABLE);
        previousImpl = System.getProperty(ZKConfig.AUDIT_IMPL_CLASS);
        System.setProperty(ZKConfig.AUDIT_ENABLE, "false");
        System.setProperty(ZKConfig.AUDIT_IMPL_CLASS, "no.such.DefaultAuditLogger");
        RecordingAuditLogger.instances.clear();
    }

    @AfterEach
    public void restoreAudit() {
        restoreProperty(ZKConfig.AUDIT_ENABLE, previousEnable);
        restoreProperty(ZKConfig.AUDIT_IMPL_CLASS, previousImpl);
        ServiceUtils.setSystemExitProcedure(ServiceUtils.LOG_ONLY);
    }

    @ParameterizedTest
    @MethodSource("startupFailures")
    public void testStartupFailureUsesFileAuditSettings(String property, String value, ExitCode exitCode)
        throws Exception {
        Properties properties = properties(RecordingAuditLogger.class);
        if (value == null) {
            properties.remove(property);
        } else {
            properties.setProperty(property, value);
        }
        Path configFile = writeConfig(properties);
        assertFailureAudited(configFile, ZooKeeperServerMain::main, exitCode);
        assertFailureAudited(configFile, QuorumPeerMain::main, exitCode);
        assertEquals("false", System.getProperty(ZKConfig.AUDIT_ENABLE));
        assertEquals("no.such.DefaultAuditLogger", System.getProperty(ZKConfig.AUDIT_IMPL_CLASS));
    }

    @ParameterizedTest
    @ValueSource(classes = {RuntimeFailingAuditLogger.class, LinkageFailingAuditLogger.class})
    public void testAuditWriteFailurePreservesStartupExitCode(Class<? extends AuditLogger> loggerClass)
        throws Exception {
        System.setProperty(ZKConfig.AUDIT_ENABLE, "true");
        System.setProperty(ZKConfig.AUDIT_IMPL_CLASS, loggerClass.getName());
        try (LoggerTestTool diagnostic = new LoggerTestTool(AuditContext.class)) {
            String[] args = {tempDir.resolve("missing.cfg").toString()};
            assertExit(() -> ZooKeeperServerMain.main(args), ExitCode.INVALID_INVOCATION);
            assertExit(() -> QuorumPeerMain.main(args), ExitCode.INVALID_INVOCATION);
            assertTrue(diagnostic.getOutputStream().toString(StandardCharsets.UTF_8)
                .contains("Unable to audit server startup failure"));
        }
    }

    @Test
    public void testBrokenBackendFailsBeforePurgeStorageOrListeners() throws Exception {
        Properties properties = properties(FailingAuditLogger.class);
        properties.setProperty("clientPort", "0");
        QuorumPeerConfig config = new QuorumPeerConfig();
        config.parseProperties(properties);
        ServerConfig standalone = new ServerConfig();
        standalone.readFrom(config);
        assertInitializationFails(() -> new ZooKeeperServerMain().runFromConfig(standalone));

        Files.writeString(tempDir.resolve("myid"), "1");
        properties.setProperty("initLimit", "5");
        properties.setProperty("syncLimit", "2");
        properties.setProperty("server.1", "127.0.0.1:2888:3888");
        properties.setProperty("server.2", "127.0.0.1:2889:3889");
        QuorumPeerConfig distributed = new QuorumPeerConfig();
        distributed.parseProperties(properties);
        assertTrue(distributed.isDistributed());
        assertInitializationFails(() -> new QuorumPeerMain().runFromConfig(distributed));
        assertInitializationFails(() -> QuorumPeer.createFromConfig(distributed));
        assertInitializationFails(() -> new ZooKeeperServerController(distributed));

        properties.setProperty("autopurge.purgeInterval", "1");
        Path configFile = writeConfig(properties);
        try (LoggerTestTool purge = new LoggerTestTool(DatadirCleanupManager.class)) {
            assertExit(() -> QuorumPeerMain.main(new String[]{configFile.toString()}), ExitCode.UNEXPECTED_ERROR);
            try (ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded.builder().baseDir(tempDir)
                    .configuration(properties).exitHandler(ExitHandler.LOG_ONLY).build()) {
                assertInitializationFails(() -> server.start(ClientBase.CONNECTION_TIMEOUT));
            }
            assertEquals("", purge.getOutputStream().toString(StandardCharsets.UTF_8));
        }
        assertFalse(Files.exists(tempDir.resolve("version-2")));
    }

    private Properties properties(Class<? extends AuditLogger> loggerClass) {
        Properties properties = new Properties();
        properties.setProperty(QuorumPeerConfig.AUDIT_ENABLE, "true");
        properties.setProperty(QuorumPeerConfig.AUDIT_IMPL_CLASS, loggerClass.getName());
        properties.setProperty("dataDir", tempDir.toString());
        return properties;
    }

    private Path writeConfig(Properties properties) throws Exception {
        Path configFile = tempDir.resolve("zoo.cfg");
        try (Writer writer = Files.newBufferedWriter(configFile)) {
            properties.store(writer, null);
        }
        return configFile;
    }

    private static Stream<Arguments> startupFailures() {
        return Stream.of(
            Arguments.of("tickTime", "invalid", ExitCode.INVALID_INVOCATION),
            Arguments.of("dataDir", null, ExitCode.INVALID_INVOCATION),
            Arguments.of("metricsProvider.className", MetricsProviderWithErrorInStart.class.getName(),
                ExitCode.UNEXPECTED_ERROR));
    }

    private static void assertInitializationFails(Executable startup) {
        IllegalStateException failure = assertThrows(IllegalStateException.class, startup);
        assertTrue(failure.getMessage().contains(FailingAuditLogger.class.getName()));
        assertSame(FailingAuditLogger.FAILURE, failure.getCause());
    }

    private static void assertFailureAudited(Path configFile, Consumer<String[]> startup, ExitCode exitCode) {
        RecordingAuditLogger.instances.clear();
        assertExit(() -> startup.accept(new String[]{configFile.toString()}), exitCode);
        assertEquals(1, RecordingAuditLogger.instances.size());
        RecordingAuditLogger logger = RecordingAuditLogger.instances.get(0);
        assertEquals(1, logger.initializations);
        assertEquals(1, logger.events.size());
        AuditEvent event = logger.events.get(0);
        assertEquals(AuditConstants.OP_START, event.getValue(FieldName.OPERATION));
        assertEquals(Result.FAILURE, event.getResult());
    }

    private static void assertExit(Runnable startup, ExitCode exitCode) {
        RuntimeException exit = new RuntimeException("test system exit");
        ServiceUtils.setSystemExitProcedure(code -> {
            assertEquals(exitCode.getValue(), code);
            throw exit;
        });
        assertSame(exit, assertThrows(RuntimeException.class, startup::run));
    }

    private static void restoreProperty(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previousValue);
        }
    }

    public static class RuntimeFailingAuditLogger implements AuditLogger {
        @Override
        public void logAuditEvent(AuditEvent event) {
            throw new IllegalStateException("test audit write failure");
        }
    }

    public static class LinkageFailingAuditLogger implements AuditLogger {
        @Override
        public void logAuditEvent(AuditEvent event) {
            throw new NoSuchMethodError("test audit write linkage failure");
        }
    }
}
