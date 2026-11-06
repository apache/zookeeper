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
package org.apache.zookeeper.server.quorum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.audit.AuditConstants;
import org.apache.zookeeper.audit.AuditContext;
import org.apache.zookeeper.audit.AuditContextTestSupport;
import org.apache.zookeeper.audit.AuditContextTestSupport.RecordingAuditLogger;
import org.apache.zookeeper.audit.AuditEvent;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.auth.DigestLoginModule;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.apache.zookeeper.server.quorum.flexible.QuorumMaj;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class AuditContextQuorumTest extends ZKTestCase {

    @TempDir
    Path tempDir;

    @Test
    public void testRolesAndReplacementDatabaseRetainExplicitContext() throws Exception {
        String previousEnable = System.getProperty(ZKConfig.AUDIT_ENABLE);
        System.setProperty(ZKConfig.AUDIT_ENABLE, "false");
        List<AuditEvent> events = new ArrayList<>();
        AuditContext context = AuditContextTestSupport.create(events::add);
        QuorumPeer peer = new QuorumPeer();
        List<ZKDatabase> databases = new ArrayList<>();
        FileTxnSnapLog log = new FileTxnSnapLog(tempDir.resolve("log").toFile(), tempDir.resolve("data").toFile());
        try {
            peer.setTickTime(ZooKeeperServer.DEFAULT_TICK_TIME);
            peer.setAuditContext(context);
            peer.setAuditContext(context);
            assertThrows(IllegalStateException.class, () -> peer.setAuditContext(AuditContext.disabled()));
            assertThrows(IllegalStateException.class,
                () -> peer.setAuditContext(AuditContextTestSupport.create(event -> { })));

            long session = 1;
            for (ServerState role : ServerState.values()) {
                ZKDatabase database = new ZKDatabase(log);
                databases.add(database);
                ZooKeeperServer server;
                switch (role) {
                case LEADING:
                    server = new LeaderZooKeeperServer(log, peer, database);
                    break;
                case FOLLOWING:
                    server = new FollowerZooKeeperServer(log, peer, database);
                    break;
                case OBSERVING:
                    server = new ObserverZooKeeperServer(log, peer, database);
                    break;
                case LOOKING:
                    server = new ReadOnlyZooKeeperServer(log, peer, database);
                    break;
                default:
                    throw new AssertionError("Unsupported test role " + role);
                }
                deleteEphemeral(server.getZKDatabase().getDataTree(), "/" + role, session++);
            }

            ZKDatabase replacement = new ZKDatabase(log);
            databases.add(replacement);
            peer.setZKDatabase(replacement);
            deleteEphemeral(replacement.getDataTree(), "/replacement", session++);
            replacement.clear();
            deleteEphemeral(replacement.getDataTree(), "/after-clear", session);

            assertEquals(6, events.size());
            assertEquals(List.of("/LOOKING", "/FOLLOWING", "/LEADING", "/OBSERVING", "/replacement", "/after-clear"),
                events.stream().map(event -> event.getValue(AuditEvent.FieldName.ZNODE)).toList());
            for (AuditEvent event : events) {
                assertEquals(AuditConstants.OP_DEL_EZNODE_EXP, event.getValue(AuditEvent.FieldName.OPERATION));
                assertEquals(AuditEvent.Result.SUCCESS, event.getResult());
            }
        } finally {
            try {
                peer.setZKDatabase(null);
                peer.shutdown();
            } finally {
                for (ZKDatabase database : databases) {
                    database.getDataTree().shutdownWatcher();
                }
                try {
                    log.close();
                } finally {
                    restoreEnable(previousEnable);
                }
            }
        }
    }

    @Test
    public void testBothQuorumLoginsUsePeerContext() throws Exception {
        String previousEnable = System.getProperty(ZKConfig.AUDIT_ENABLE);
        Configuration previousConfiguration = Configuration.getConfiguration();
        System.setProperty(ZKConfig.AUDIT_ENABLE, "false");
        List<AuditEvent> events = new ArrayList<>();
        AuditContext context = AuditContextTestSupport.create(events::add);
        QuorumPeer peer = new QuorumPeer();
        Configuration.setConfiguration(new Configuration() {
            @Override
            public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
                return new AppConfigurationEntry[]{new AppConfigurationEntry(
                    DigestLoginModule.class.getName(), AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                    Map.of("username", name, "password", "test-password"))};
            }
        });
        try {
            peer.setAuditContext(context);
            peer.setQuorumVerifier(new QuorumMaj(Map.of()), false);
            peer.setQuorumSaslEnabled(true);
            peer.setQuorumServerLoginContext("QuorumServer");
            peer.setQuorumLearnerLoginContext("QuorumLearner");
            peer.setQuorumServicePrincipal("zkquorum/localhost");
            peer.initialize();
            assertEquals(2, events.size());
            assertEquals(List.of("QuorumServer", "QuorumLearner"),
                events.stream().map(event -> event.getValue(AuditEvent.FieldName.USER)).toList());
            for (AuditEvent event : events) {
                assertEquals(AuditConstants.OP_LOGIN, event.getValue(AuditEvent.FieldName.OPERATION));
                assertEquals(AuditEvent.Result.SUCCESS, event.getResult());
            }
        } finally {
            try {
                peer.shutdown();
            } finally {
                Configuration.setConfiguration(previousConfiguration);
                restoreEnable(previousEnable);
            }
        }
    }

    @Test
    public void testDirectPeerAdoptsFactoryBackendWithoutInitializingAgain() throws Exception {
        String previousEnable = System.getProperty(ZKConfig.AUDIT_ENABLE);
        String previousLogger = System.getProperty(ZKConfig.AUDIT_IMPL_CLASS);
        System.setProperty(ZKConfig.AUDIT_ENABLE, "true");
        System.setProperty(ZKConfig.AUDIT_IMPL_CLASS, RecordingAuditLogger.class.getName());
        RecordingAuditLogger.instances.clear();
        QuorumPeer peer = null;
        try {
            ServerCnxnFactory factory = ServerCnxnFactory.createFactory();
            AuditContext context = factory.initializeAuditContext();
            peer = new QuorumPeer(Map.of(), tempDir.resolve("direct-data").toFile(),
                tempDir.resolve("direct-log").toFile(), 3, 1, ZooKeeperServer.DEFAULT_TICK_TIME, 5, 2, 0, factory);
            assertSame(context, peer.getAuditContext());
            assertEquals(1, RecordingAuditLogger.instances.size());
            assertSame(context, factory.initializeAuditContext());
            assertEquals(1, RecordingAuditLogger.instances.size());
        } finally {
            try {
                if (peer != null) {
                    peer.setCnxnFactory(null);
                    peer.shutdown();
                    peer.getZkDb().getDataTree().shutdownWatcher();
                }
            } finally {
                restoreEnable(previousEnable);
                if (previousLogger == null) {
                    System.clearProperty(ZKConfig.AUDIT_IMPL_CLASS);
                } else {
                    System.setProperty(ZKConfig.AUDIT_IMPL_CLASS, previousLogger);
                }
            }
        }
    }

    @Test
    public void testPeerAcceptsEquivalentDisabledContextWithoutReplacement() throws Exception {
        QuorumPeer peer = new QuorumPeer();
        try {
            AuditContext disabled = AuditContext.disabled();
            peer.setAuditContext(disabled);
            peer.setAuditContext(AuditContext.disabled());
            assertSame(disabled, peer.getAuditContext());
            assertThrows(IllegalStateException.class,
                () -> peer.setAuditContext(AuditContextTestSupport.create(event -> { })));
        } finally {
            peer.shutdown();
        }
    }

    private static void deleteEphemeral(DataTree tree, String path, long session) throws Exception {
        tree.createNode(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, session, -1, session * 2, 0);
        DataTree.ProcessTxnResult result = tree.processTxn(
            new TxnHeader(session, 0, session * 2 + 1, 0, ZooDefs.OpCode.closeSession), null);
        assertEquals(0, result.err);
        assertNull(tree.getNode(path));
    }

    private static void restoreEnable(String value) {
        if (value == null) {
            System.clearProperty(ZKConfig.AUDIT_ENABLE);
        } else {
            System.setProperty(ZKConfig.AUDIT_ENABLE, value);
        }
    }

}
