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
package org.apache.zookeeper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CheckedInputStream;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.audit.AuditConstants;
import org.apache.zookeeper.audit.AuditContext;
import org.apache.zookeeper.audit.AuditContextTestSupport;
import org.apache.zookeeper.audit.AuditEvent;
import org.apache.zookeeper.audit.AuditEvent.FieldName;
import org.apache.zookeeper.audit.AuditEvent.Result;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.SnapStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class AuditContextBindingTest extends ZKTestCase {

    @TempDir
    Path tempDir;
    private FileTxnSnapLog log;
    private ZooKeeperServer server;
    private final Set<ZKDatabase> databases = new HashSet<>();

    @BeforeEach
    public void setUpServer() throws Exception {
        log = new FileTxnSnapLog(tempDir.resolve("log").toFile(), tempDir.resolve("data").toFile());
        server = new ZooKeeperServer(log, ZooKeeperServer.DEFAULT_TICK_TIME, "");
        databases.add(server.getZKDatabase());
    }

    @AfterEach
    public void closeServerData() throws Exception {
        try {
            databases.add(server.getZKDatabase());
            for (ZKDatabase database : databases) {
                database.getDataTree().shutdownWatcher();
            }
            if (server.getSessionTracker() != null) {
                server.getSessionTracker().shutdown();
            }
        } finally {
            log.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testFactoriesShareServerContextAfterDataStartup(boolean enabled) throws Exception {
        String previousEnable = System.getProperty(ZKConfig.AUDIT_ENABLE);
        System.setProperty(ZKConfig.AUDIT_ENABLE, Boolean.toString(enabled));
        try {
            server.startdata();
            AuditContext context = server.getAuditContext();
            ServerCnxnFactory first = new NIOServerCnxnFactory();
            first.initializeAuditContext();
            first.setZooKeeperServer(server);
            ServerCnxnFactory second = new NIOServerCnxnFactory();
            second.initializeAuditContext();
            second.setZooKeeperServer(server);
            assertSame(context, server.getAuditContext());
            assertSame(context, first.getAuditContext());
            assertSame(context, second.getAuditContext());
            assertSame(server, first.getZooKeeperServer());
            assertSame(server, second.getZooKeeperServer());
        } finally {
            if (previousEnable == null) {
                System.clearProperty(ZKConfig.AUDIT_ENABLE);
            } else {
                System.setProperty(ZKConfig.AUDIT_ENABLE, previousEnable);
            }
        }
    }

    @Test
    public void testEnabledContextCannotBeReplacedDuringFactoryAssociation() {
        AuditContext enabled = AuditContextTestSupport.create(event -> { });
        server.setAuditContext(enabled);
        ServerCnxnFactory factory = new NIOServerCnxnFactory();
        factory.setAuditContext(AuditContext.disabled());
        assertThrows(IllegalStateException.class, () -> factory.setZooKeeperServer(server));
        ServerCnxnFactory other = new NIOServerCnxnFactory();
        other.setAuditContext(AuditContextTestSupport.create(event -> { }));
        assertThrows(IllegalStateException.class, () -> other.setZooKeeperServer(server));
        ServerCnxnFactory matching = new NIOServerCnxnFactory();
        matching.setAuditContext(enabled);
        matching.setZooKeeperServer(server);
        assertSame(enabled, server.getAuditContext());
        assertSame(server, matching.getZooKeeperServer());
    }

    @Test
    public void testFactoryAllowsEquivalentDisabledBindingWithoutReplacement() {
        ServerCnxnFactory factory = new NIOServerCnxnFactory();
        AuditContext disabled = AuditContext.disabled();
        factory.setAuditContext(disabled);
        factory.setAuditContext(AuditContext.disabled());
        assertSame(disabled, factory.getAuditContext());
        assertThrows(IllegalStateException.class,
            () -> factory.setAuditContext(AuditContextTestSupport.create(event -> { })));
    }

    @Test
    public void testDatabaseClearPreservesContextAndCustomTree() throws Exception {
        List<AuditEvent> events = new ArrayList<>();
        ZKDatabase database = customDatabase();
        database.setAuditContext(AuditContextTestSupport.create(events::add));
        createEphemeral(database, "/before-clear", 1L);
        database.killSession(1L, 2L);
        database.clear();
        assertTrue(database.getDataTree() instanceof CustomDataTree);
        createEphemeral(database, "/after-clear", 2L);
        database.killSession(2L, 3L);
        assertEquals(2, events.size());
        assertExpiration(events.get(0), "/before-clear", 1L);
        assertExpiration(events.get(1), "/after-clear", 2L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testDatabaseReplacementAndRestorePreserveContext(boolean bindBeforeRestore) throws Exception {
        List<AuditEvent> events = new ArrayList<>();
        AuditContext context = AuditContextTestSupport.create(events::add);
        if (bindBeforeRestore) {
            server.setAuditContext(context);
        }
        ZKDatabase replacement = customDatabase();
        server.setZKDatabase(replacement);
        long session = 1L;
        createEphemeral(replacement, "/restored", session);
        replacement.getSessionWithTimeOuts().put(session, ZooKeeperServer.DEFAULT_TICK_TIME);
        File snapshot = server.takeSnapshot(false, false);
        replacement.killSession(session, 2L);
        assertEquals(bindBeforeRestore ? 1 : 0, events.size());
        if (bindBeforeRestore) {
            assertExpiration(events.get(0), "/restored", session);
        }
        try (CheckedInputStream input = SnapStream.getInputStream(snapshot)) {
            server.restoreFromSnapshot(input);
        }
        server.setAuditContext(context);
        assertNotNull(server.getZKDatabase().getNode("/restored"));
        server.getZKDatabase().killSession(session, 3L);
        assertEquals(bindBeforeRestore ? 2 : 1, events.size());
        assertExpiration(events.get(events.size() - 1), "/restored", session);
    }

    private ZKDatabase customDatabase() {
        ZKDatabase database = new ZKDatabase(log) {
            @Override
            public DataTree createDataTree() {
                return new CustomDataTree();
            }
        };
        databases.add(database);
        return database;
    }

    private static void createEphemeral(ZKDatabase database, String path, long session) throws Exception {
        database.getDataTree().createNode(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, session, -1, session, 0);
    }

    private static void assertExpiration(AuditEvent event, String path, long session) {
        assertEquals(AuditConstants.OP_DEL_EZNODE_EXP, event.getValue(FieldName.OPERATION));
        assertEquals(path, event.getValue(FieldName.ZNODE));
        assertEquals("0x" + Long.toHexString(session), event.getValue(FieldName.SESSION));
        assertEquals(Result.SUCCESS, event.getResult());
    }

    private static class CustomDataTree extends DataTree {
    }
}
