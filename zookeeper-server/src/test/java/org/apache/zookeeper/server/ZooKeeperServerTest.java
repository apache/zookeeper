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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.apache.zookeeper.proto.ConnectRequest;
import org.apache.zookeeper.server.persistence.FileTxnLog;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.SnapStream;
import org.apache.zookeeper.server.persistence.Util;
import org.apache.zookeeper.server.util.QuotaMetricsUtils;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.Test;

public class ZooKeeperServerTest extends ZKTestCase {

    @Test
    public void testDirSize() throws Exception {
        ZooKeeperServer zks = null;
        ServerCnxnFactory cnxnFactory = null;

        try {
            final File dataDir = ClientBase.createTmpDir();
            final File logDir = ClientBase.createTmpDir();

            zks = new ZooKeeperServer(dataDir, logDir, 3000);

            // validate dir size before server starts
            assertEquals(0, zks.getDataDirSize());
            assertEquals(0, zks.getLogDirSize());

            // start server
            final String hostPort = "127.0.0.1:" + PortAssignment.unique();
            final int port = Integer.parseInt(hostPort.split(":")[1]);
            cnxnFactory = ServerCnxnFactory.createFactory(port, -1);
            cnxnFactory.startup(zks);
            assertTrue(ClientBase.waitForServerUp(hostPort, 120000));

            // validate data size is greater than 0 as snapshot has been taken when server starts
            assertTrue(zks.getDataDirSize() > 0);

            // validate log size is 0 as no txn yet
            assertEquals(0, zks.getLogDirSize());
        } finally {
            if (cnxnFactory != null) {
                cnxnFactory.shutdown();
            }

            if (zks != null) {
                zks.shutdown();
            }
        }
    }


    @Test
    public void testSortDataDirAscending() {
        File[] files = new File[5];

        files[0] = new File("foo.10027c6de");
        files[1] = new File("foo.10027c6df");
        files[2] = new File("bar.10027c6dd");
        files[3] = new File("foo.10027c6dc");
        files[4] = new File("foo.20027c6dc");

        File[] orig = files.clone();

        List<File> filelist = Util.sortDataDir(files, "foo", true);

        assertEquals(orig[2], filelist.get(0));
        assertEquals(orig[3], filelist.get(1));
        assertEquals(orig[0], filelist.get(2));
        assertEquals(orig[1], filelist.get(3));
        assertEquals(orig[4], filelist.get(4));
    }

    @Test
    public void testSortDataDirDescending() {
        File[] files = new File[5];

        files[0] = new File("foo.10027c6de");
        files[1] = new File("foo.10027c6df");
        files[2] = new File("bar.10027c6dd");
        files[3] = new File("foo.10027c6dc");
        files[4] = new File("foo.20027c6dc");

        File[] orig = files.clone();

        List<File> filelist = Util.sortDataDir(files, "foo", false);

        assertEquals(orig[4], filelist.get(0));
        assertEquals(orig[1], filelist.get(1));
        assertEquals(orig[0], filelist.get(2));
        assertEquals(orig[3], filelist.get(3));
        assertEquals(orig[2], filelist.get(4));
    }

    @Test
    public void testGetLogFiles() {
        File[] files = new File[5];

        files[0] = new File("log.10027c6de");
        files[1] = new File("log.10027c6df");
        files[2] = new File("snapshot.10027c6dd");
        files[3] = new File("log.10027c6dc");
        files[4] = new File("log.20027c6dc");

        File[] orig = files.clone();

        File[] filelist = FileTxnLog.getLogFiles(files, Long.parseLong("10027c6de", 16));

        assertEquals(3, filelist.length);
        assertEquals(orig[0], filelist[0]);
        assertEquals(orig[1], filelist[1]);
        assertEquals(orig[4], filelist[2]);
    }

    @Test
    public void testForceSyncDefaultEnabled() {
        File file = new File("foo.10027c6de");
        FileTxnLog log = new FileTxnLog(file);
        assertTrue(log.isForceSync());
    }

    @Test
    public void testForceSyncDefaultDisabled() {
        try {
            File file = new File("foo.10027c6de");
            System.setProperty("zookeeper.forceSync", "no");
            FileTxnLog log = new FileTxnLog(file);
            assertFalse(log.isForceSync());
        } finally {
            //Reset back to default.
            System.setProperty("zookeeper.forceSync", "yes");
        }
    }

    @Test
    public void testInvalidSnapshot() {
        File f = null;
        File tmpFileDir = null;
        try {
            tmpFileDir = ClientBase.createTmpDir();
            f = new File(tmpFileDir, "snapshot.0");
            if (!f.exists()) {
                f.createNewFile();
            }
            assertFalse(SnapStream.isValidSnapshot(f), "Snapshot file size is greater than 9 bytes");
            assertTrue(f.delete(), "Can't delete file");
        } catch (IOException e) {
        } finally {
            if (null != tmpFileDir) {
                ClientBase.recursiveDelete(tmpFileDir);
            }
        }
    }

    @Test
    public void testClientZxidAhead() {
        ZooKeeperServer zooKeeperServer = new ZooKeeperServer();
        final ZKDatabase zkDatabase = new ZKDatabase(mock(FileTxnSnapLog.class));
        zooKeeperServer.setZKDatabase(zkDatabase);

        final ConnectRequest request = new ConnectRequest();
        request.setProtocolVersion(1);
        request.setLastZxidSeen(99L);
        request.setTimeOut(500);
        request.setSessionId(123L);
        request.setPasswd(new byte[]{ 1 });
        request.setReadOnly(true);

        ServerCnxn.CloseRequestException e = assertThrows(
                ServerCnxn.CloseRequestException.class,
                () -> zooKeeperServer.processConnectRequest(new MockServerCnxn(), request));
        assertEquals(e.getReason(), ServerCnxn.DisconnectReason.CLIENT_ZXID_AHEAD);
    }

    @Test
    public void testUpdateQuotaExceededMetrics() {
        final String name = QuotaMetricsUtils.QUOTA_EXCEEDED_ERROR_PER_NAMESPACE;
        final String namespace = UUID.randomUUID().toString();
        final long count = 3L;

        for (int i = 0; i < count; i++) {
            ZooKeeperServer.updateQuotaExceededMetrics(namespace);
        }

        final Map<String, Object> values = MetricsUtils.currentServerMetrics();
        assertEquals(1, values.keySet().stream().filter(
                key -> key.contains(String.format("%s_%s", namespace, name))).count());

        assertEquals(count, values.get(String.format("%s_%s", namespace, name)));
    }

    @Test
    public void updateQuotaExceededMetricsRemoveTest() {
        final String name = QuotaMetricsUtils.QUOTA_EXCEEDED_ERROR_PER_NAMESPACE;
        final String[] namespaces = new String[3];
        final long count = 3L;

        // updateQuotaExceededMetrics multi times namespaces
        for (int i = 0; i < count; i++) {
            String namespace = UUID.randomUUID().toString();
            ZooKeeperServer.updateQuotaExceededMetrics(namespace);
            namespaces[i] = namespace;
        }

        // validate updateQuotaExceededMetrics success
        final Map<String, Object> values = MetricsUtils.currentServerMetrics();
        for (int i = 0; i < count; i++) {
            String namespace = namespaces[i];
            assertEquals(1, values.keySet().stream().filter(
                    key -> key.contains(String.format("%s_%s", namespace, name))).count());
            assertEquals(1L, values.get(String.format("%s_%s", namespace, name)));
        }

        // removeQuotaExceededMetrics multi times exists namespaces
        for (int i = 0; i < count; i++) {
            String namespace = namespaces[i];
            ZooKeeperServer.removeQuotaExceededMetrics(namespace);
        }

        // validate removeQuotaExceededMetrics success
        final Map<String, Object> noValues = MetricsUtils.currentServerMetrics();
        for (int i = 0; i < count; i++) {
            String namespace = namespaces[i];
            assertEquals(0, noValues.keySet().stream().filter(
                    key -> key.contains(String.format("%s_%s", namespace, name))).count());
            assertEquals(null, noValues.get(String.format("%s_%s", namespace, name)));
        }

        // removeQuotaExceededMetrics multi times no exists namespaces
        for (int i = 0; i < count; i++) {
            String namespace = namespaces[i];
            ZooKeeperServer.removeQuotaExceededMetrics(namespace);
        }
        ZooKeeperServer.removeQuotaExceededMetrics(null);
    }

    @Test
    public void updateQuotaExceededMetricsRemoveNoExistsTest() {
        final String[] namespaces = new String[3];
        final long count = 3L;

        // create multi times namespaces
        for (int i = 0; i < count; i++) {
            String namespace = UUID.randomUUID().toString();
            namespaces[i] = namespace;
        }

        // removeQuotaExceededMetrics multi times no exists namespaces
        for (int i = 0; i < count; i++) {
            String namespace = namespaces[i];
            ZooKeeperServer.removeQuotaExceededMetrics(namespace);
        }
    }

    @Test
    public void updateQuotaExceededMetricsRemoveNullKeyTest() {
        // removeQuotaExceededMetrics with null key and make sure no exception is thrown
        ZooKeeperServer.removeQuotaExceededMetrics(null);
    }

    @Test
    public void testUpdateQuotaExceededMetrics_nullNamespace() {
        assertDoesNotThrow(() -> ZooKeeperServer.updateQuotaExceededMetrics(null));
    }
}
