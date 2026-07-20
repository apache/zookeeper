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

package org.apache.zookeeper.server.admin;

import static org.apache.zookeeper.server.ZooKeeperServer.ZOOKEEPER_SERIALIZE_LAST_PROCESSED_ZXID_ENABLED;
import static org.apache.zookeeper.server.admin.Commands.ADMIN_RATE_LIMITER_INTERVAL;
import static org.apache.zookeeper.server.admin.Commands.RestoreCommand.ADMIN_RESTORE_ENABLED;
import static org.apache.zookeeper.server.admin.Commands.SnapshotCommand.ADMIN_SNAPSHOT_ENABLED;
import static org.apache.zookeeper.server.admin.Commands.SnapshotCommand.REQUEST_QUERY_PARAM_STREAMING;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.http.HttpServletResponse;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ServerStats;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.quorum.BufferStats;
import org.apache.zookeeper.server.watch.WatchManager;
import org.apache.zookeeper.server.watch.WatchManagerFactory;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.Test;

public class CommandsTest extends ClientBase {

    /**
     * Checks that running a given Command returns the expected Map. Asserts
     * that all specified keys are present with values of the specified types
     * and that there are no extra entries.
     *
     * @param cmdName
     *            - the primary name of the command
     * @param kwargs
     *            - keyword arguments to the command
     * @param inputStream
     *            - InputStream to the command
     * @param authInfo
     *            - authInfo for the command
     * @param expectedHeaders
     *            - expected HTTP response headers
     * @param expectedStatusCode
     *            - expected HTTP status code
     * @param fields
     *            - the fields that are expected in the returned Map
     * @throws IOException
     * @throws InterruptedException
     */
    private void testCommand(String cmdName, Map<String, String> kwargs, InputStream inputStream,
                             String authInfo,
                             Map<String, String> expectedHeaders, int expectedStatusCode,
                             Field... fields) throws IOException, InterruptedException {
        ZooKeeperServer zks = serverFactory.getZooKeeperServer();
        final CommandResponse commandResponse = inputStream == null
        ? Commands.runGetCommand(cmdName, zks, kwargs, authInfo, null) : Commands.runPostCommand(cmdName, zks, inputStream, authInfo, null);
        assertNotNull(commandResponse);
        assertEquals(expectedStatusCode, commandResponse.getStatusCode());
        try (final InputStream responseStream = commandResponse.getInputStream()) {
            if (Boolean.parseBoolean(kwargs.getOrDefault(REQUEST_QUERY_PARAM_STREAMING, "false"))) {
                assertNotNull(responseStream, "InputStream in the response of command " + cmdName + " should not be null");
            } else {
                Map<String, Object> result = commandResponse.toMap();
                assertTrue(result.containsKey("command"));
                // This is only true because we're setting cmdName to the primary name
                assertEquals(cmdName, result.remove("command"));
                assertTrue(result.containsKey("error"));
                assertNull(result.remove("error"), "error: " + result.get("error"));

                for (Field field : fields) {
                    String k = field.key;
                    assertTrue(result.containsKey(k),
                            "Result from command " + cmdName + " missing field \"" + k + "\"" + "\n" + result);
                    Class<?> t = field.type;
                    Object v = result.remove(k);
                    assertTrue(t.isAssignableFrom(v.getClass()),
                            "\"" + k + "\" field from command " + cmdName
                                    + " should be of type " + t + ", is actually of type " + v.getClass());
                }

                assertTrue(result.isEmpty(), "Result from command " + cmdName + " contains extra fields: " + result);
            }
        }
        assertEquals(expectedHeaders, commandResponse.getHeaders());
    }

    public void testCommand(String cmdName, Field... fields) throws IOException, InterruptedException {
        testCommand(cmdName, new HashMap<>(), null, null, new HashMap<>(), HttpServletResponse.SC_OK, fields);
    }

    private static class Field {

        String key;
        Class<?> type;
        Field(String key, Class<?> type) {
            this.key = key;
            this.type = type;
        }
    }

    @Test
    public void testConfiguration() throws IOException, InterruptedException {
        testCommand("configuration", new Field("client_port", Integer.class), new Field("data_dir", String.class), new Field("data_log_dir", String.class), new Field("tick_time", Integer.class), new Field("max_client_cnxns", Integer.class), new Field("min_session_timeout", Integer.class), new Field("max_session_timeout", Integer.class), new Field("server_id", Long.class), new Field("client_port_listen_backlog", Integer.class));
    }

    @Test
    public void testConnections() throws IOException, InterruptedException {
        testCommand("connections", new Field("connections", Iterable.class), new Field("secure_connections", Iterable.class));
    }

    @Test
    public void testObservers() throws IOException, InterruptedException {
        testCommand("observers", new Field("synced_observers", Integer.class), new Field("observers", Iterable.class));
    }

    @Test
    public void testObserverConnectionStatReset() throws IOException, InterruptedException {
        testCommand("observer_connection_stat_reset");
    }

    @Test
    public void testConnectionStatReset() throws IOException, InterruptedException {
        testCommand("connection_stat_reset");
    }

    @Test
    public void testDump() throws IOException, InterruptedException {
        testCommand("dump", new Field("expiry_time_to_session_ids", Map.class), new Field("session_id_to_ephemeral_paths", Map.class));
    }

    @Test
    public void testEnvironment() throws IOException, InterruptedException {
        testCommand("environment", new Field("zookeeper.version", String.class), new Field("host.name", String.class), new Field("java.version", String.class), new Field("java.vendor", String.class), new Field("java.home", String.class), new Field("java.class.path", String.class), new Field("java.library.path", String.class), new Field("java.io.tmpdir", String.class), new Field("java.compiler", String.class), new Field("os.name", String.class), new Field("os.arch", String.class), new Field("os.version", String.class), new Field("user.name", String.class), new Field("user.home", String.class), new Field("user.dir", String.class), new Field("jvm.memory.free", String.class), new Field("jvm.memory.max", String.class), new Field("jvm.memory.total", String.class));
    }

    @Test
    public void testGetTraceMask() throws IOException, InterruptedException {
        testCommand("get_trace_mask", new Field("tracemask", Long.class));
    }

    @Test
    public void testIsReadOnly() throws IOException, InterruptedException {
        testCommand("is_read_only", new Field("read_only", Boolean.class));
    }

    @Test
    public void testLastSnapshot() throws IOException, InterruptedException {
        testCommand("last_snapshot", new Field("zxid", String.class), new Field("timestamp", Long.class));
    }

    @Test
    public void testMonitor() throws IOException, InterruptedException {
        ArrayList<Field> fields = new ArrayList<>(Arrays.asList(
                new Field("version", String.class),
                new Field("avg_latency", Double.class),
                new Field("max_latency", Long.class),
                new Field("min_latency", Long.class),
                new Field("packets_received", Long.class),
                new Field("packets_sent", Long.class),
                new Field("num_alive_connections", Integer.class),
                new Field("outstanding_requests", Long.class),
                new Field("server_state", String.class),
                new Field("znode_count", Integer.class),
                new Field("watch_count", Integer.class),
                new Field("ephemerals_count", Integer.class),
                new Field("approximate_data_size", Long.class),
                new Field("open_file_descriptor_count", Long.class),
                new Field("max_file_descriptor_count", Long.class),
                new Field("last_client_response_size", Integer.class),
                new Field("max_client_response_size", Integer.class),
                new Field("min_client_response_size", Integer.class),
                new Field("auth_failed_count", Long.class),
                new Field("non_mtls_remote_conn_count", Long.class),
                new Field("non_mtls_local_conn_count", Long.class),
                new Field("uptime", Long.class),
                new Field("global_sessions", Long.class),
                new Field("local_sessions", Long.class),
                new Field("connection_drop_probability", Double.class),
                new Field("outstanding_tls_handshake", Integer.class)
        ));
        Map<String, Object> metrics = MetricsUtils.currentServerMetrics();

        for (String metric : metrics.keySet()) {
            boolean alreadyDefined = fields.stream().anyMatch(f -> {
                return f.key.equals(metric);
            });
            if (alreadyDefined) {
                // known metrics are defined statically in the block above
                continue;
            }
            if (metric.startsWith("avg_")) {
                fields.add(new Field(metric, Double.class));
            } else {
                fields.add(new Field(metric, Long.class));
            }
        }
        Field[] fieldsArray = fields.toArray(new Field[0]);
        testCommand("monitor", fieldsArray);
    }

    @Test
    public void testRuok() throws IOException, InterruptedException {
        testCommand("ruok");
    }

    @Test
    public void testRestore_invalidInputStream() throws IOException, InterruptedException {
        setupForRestoreCommand();

        try (final InputStream inputStream = new ByteArrayInputStream("Invalid snapshot data".getBytes())){
            final Map<String, String> kwargs = new HashMap<>();
            final Map<String, String> expectedHeaders = new HashMap<>();
            final String authInfo = CommandAuthTest.buildAuthorizationForDigest();
            testCommand("restore", kwargs, inputStream, authInfo, expectedHeaders, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        } finally {
            clearForRestoreCommand();
        }
    }

    @Test
    public void testRestore_nullInputStream() {
        setupForRestoreCommand();
        final ZooKeeperServer zks = serverFactory.getZooKeeperServer();
        try {

            final String authInfo = CommandAuthTest.buildAuthorizationForDigest();
            final CommandResponse commandResponse = Commands.runPostCommand("restore", zks, null, authInfo, null);
            assertNotNull(commandResponse);
            assertEquals(HttpServletResponse.SC_BAD_REQUEST, commandResponse.getStatusCode());
        } finally {
          clearForRestoreCommand();
          if (zks != null) {
              zks.shutdown();
          }
        }
    }

    @Test
    public void testSnapshot_streaming() throws IOException, InterruptedException {
        testSnapshot(true);
    }

    @Test
    public void testSnapshot_nonStreaming() throws IOException, InterruptedException {
        testSnapshot(false);
    }

    @Test
    public void testServerStats() throws IOException, InterruptedException {
        testCommand("server_stats", new Field("version", String.class), new Field("read_only", Boolean.class), new Field("server_stats", ServerStats.class), new Field("node_count", Integer.class), new Field("client_response", BufferStats.class));
    }

    @Test
    public void testSetTraceMask() throws IOException, InterruptedException {
        Map<String, String> kwargs = new HashMap<>();
        kwargs.put("traceMask", "1");
        testCommand("set_trace_mask", kwargs, null, null, new HashMap<>(), HttpServletResponse.SC_OK, new Field("tracemask", Long.class));
    }

    @Test
    public void testStat() throws IOException, InterruptedException {
        testCommand("stats",
                    new Field("version", String.class),
                    new Field("read_only", Boolean.class),
                    new Field("server_stats", ServerStats.class),
                    new Field("node_count", Integer.class),
                    new Field("connections", Iterable.class),
                    new Field("secure_connections", Iterable.class),
                    new Field("client_response", BufferStats.class));
    }

    @Test
    public void testStatReset() throws IOException, InterruptedException {
        testCommand("stat_reset");
    }

    @Test
    public void testWatches() throws IOException, InterruptedException {
        testCommand("watches", new Field("session_id_to_watched_paths", Map.class));
    }

    @Test
    public void testWatchesByPath() throws IOException, InterruptedException {
        testCommand("watches_by_path", new Field("path_to_session_ids", Map.class));
    }

    @Test
    public void testWatchSummary() throws IOException, InterruptedException {
        testCommand("watch_summary", new Field("num_connections", Integer.class), new Field("num_paths", Integer.class), new Field("num_total_watches", Integer.class));
    }

    @Test
    public void testWatchDetailsRegistrationMetadata() {
        Command command = Commands.getCommand("watch_details");

        assertNotNull(command);
        assertSame(command, Commands.getCommand("wchd"));
        assertEquals("watch_details", command.getPrimaryName());
        assertTrue(Commands.getPrimaryNames().contains("watch_details"));
        assertNull(command.getAuthRequest());
    }

    @Test
    public void testWatchDetailsQueryValidationAndEmptyResponse() {
        DataTree dataTree = mock(DataTree.class);
        when(dataTree.getDataWatchRegistrations("/", Collections.emptySet(), 1001))
            .thenReturn(Collections.emptyList());
        when(dataTree.getChildWatchRegistrations("/", Collections.emptySet(), 1001))
            .thenReturn(Collections.emptyList());
        when(dataTree.getDataWatchRegistrations("/", Collections.emptySet(), 2))
            .thenReturn(Collections.emptyList());
        when(dataTree.getChildWatchRegistrations("/", Collections.emptySet(), 2))
            .thenReturn(Collections.emptyList());
        ZooKeeperServer zkServer = mockWatchDetailsServer(dataTree, null, null);
        Commands.WatchDetailsCommand command = new Commands.WatchDetailsCommand();

        assertWatchDetailsBadRequest(command, zkServer, "path", "relative", "Invalid path: relative");
        assertWatchDetailsBadRequest(command, zkServer, "session_id", "not-a-session", "Invalid session_id: not-a-session");
        assertWatchDetailsBadRequest(command, zkServer, "client_ip", "   ", "client_ip must not be empty");
        assertWatchDetailsBadRequest(command, zkServer, "limit", "abc", "limit must be an integer");
        assertWatchDetailsBadRequest(command, zkServer, "limit", "0", "limit must be between 1 and 1000");
        assertWatchDetailsBadRequest(command, zkServer, "limit", "1001", "limit must be between 1 and 1000");

        Map<String, String> kwargs = new HashMap<>();
        kwargs.put("path", "/");
        kwargs.put("session_id", "0xffffffffffffffff");
        kwargs.put("client_ip", "127.0.0.1");
        kwargs.put("limit", "1000");
        CommandResponse response = command.runGet(zkServer, kwargs);

        assertEquals(HttpServletResponse.SC_OK, response.getStatusCode());
        assertEquals(7L, response.toMap().get("server_id"));
        assertEquals(0, response.toMap().get("returned_count"));
        assertEquals(false, response.toMap().get("truncated"));
        assertEquals(new ArrayList<>(), response.toMap().get("watches"));

        kwargs.put("limit", "1");
        assertEquals(HttpServletResponse.SC_OK, command.runGet(zkServer, kwargs).getStatusCode());
    }

    @Test
    public void testWatchDetailsDoesNotRequireAuthorization() {
        ZooKeeperServer zkServer = serverFactory.getZooKeeperServer();
        CommandResponse response = Commands.runGetCommand(
            "watch_details",
            zkServer,
            new HashMap<>(),
            null,
            null);

        assertEquals(HttpServletResponse.SC_OK, response.getStatusCode());
        assertNull(response.getError());
    }

    @Test
    public void testWatchDetailsIncludesKindsModesAndConnectionFields() throws Exception {
        DataTree dataTree = newWatchDetailsDataTree();
        try {
            ServerCnxn plain = mockWatchConnection(
                0x11L,
                "10.10.1.11",
                5111,
                1000L,
                20000,
                false,
                new AtomicBoolean(false));
            ServerCnxn secure = mockWatchConnection(
                -1L,
                "10.10.1.25",
                51324,
                1784112305123L,
                30000,
                true,
                new AtomicBoolean(false));

            dataTree.statNode("/", plain);
            dataTree.getChildren("/", null, secure);
            dataTree.addWatch("/persistent", secure, ZooDefs.AddWatchModes.persistent);
            dataTree.addWatch("/recursive", secure, ZooDefs.AddWatchModes.persistentRecursive);

            ZooKeeperServer zkServer = mockWatchDetailsServer(
                dataTree,
                Collections.singletonList(plain),
                Collections.singletonList(secure));
            Map<String, String> kwargs = new HashMap<>();
            kwargs.put("limit", "1000");
            CommandResponse response = new Commands.WatchDetailsCommand().runGet(zkServer, kwargs);
            List<Map<String, Object>> watches = watchDetails(response);

            assertEquals(4, watches.size());
            Map<String, Object> plainWatch = findWatch(watches, "/", "0x11", "standard");
            assertEquals("0x11", plainWatch.get("session_id"));
            assertEquals("10.10.1.11", plainWatch.get("client_ip"));
            assertEquals(5111, plainWatch.get("client_port"));
            assertEquals(Arrays.asList("data"), plainWatch.get("watch_kind"));
            assertEquals("standard", plainWatch.get("watch_mode"));
            assertEquals(1000L, plainWatch.get("connection_established_at"));
            assertEquals(20000, plainWatch.get("session_timeout_ms"));
            assertEquals(false, plainWatch.get("secure"));

            Map<String, Object> childWatch = findWatch(
                watches,
                "/",
                "0xffffffffffffffff",
                "standard");
            assertEquals(Arrays.asList("children"), childWatch.get("watch_kind"));

            Map<String, Object> recursiveWatch = findWatch(
                watches,
                "/recursive",
                "0xffffffffffffffff",
                "persistent_recursive");
            assertEquals("0xffffffffffffffff", recursiveWatch.get("session_id"));
            assertEquals(Arrays.asList("data", "children"), recursiveWatch.get("watch_kind"));
            assertEquals("persistent_recursive", recursiveWatch.get("watch_mode"));
            assertEquals(true, recursiveWatch.get("secure"));

            kwargs.put("path", "/persistent");
            kwargs.put("session_id", "0xffffffffffffffff");
            kwargs.put("client_ip", "10.10.1.25");
            watches = watchDetails(new Commands.WatchDetailsCommand().runGet(zkServer, kwargs));
            assertEquals(1, watches.size());
            Map<String, Object> persistentWatch = findWatch(
                watches,
                "/persistent",
                "0xffffffffffffffff",
                "persistent");
            assertEquals(Arrays.asList("data", "children"), persistentWatch.get("watch_kind"));

            kwargs.remove("path");
            kwargs.put("session_id", "18446744073709551615");
            assertEquals(3, watchDetails(new Commands.WatchDetailsCommand().runGet(zkServer, kwargs)).size());
        } finally {
            dataTree.shutdownWatcher();
        }
    }

    @Test
    public void testWatchDetailsAggregatesStandardDataAndChildKinds() throws Exception {
        DataTree dataTree = newWatchDetailsDataTree();
        try {
            ServerCnxn connection = mockWatchConnection(
                0x12L,
                "10.10.1.12",
                5112,
                1200L,
                20000,
                false,
                new AtomicBoolean(false));
            dataTree.statNode("/", connection);
            dataTree.getChildren("/", null, connection);

            ZooKeeperServer zkServer = mockWatchDetailsServer(
                dataTree,
                Collections.singletonList(connection),
                null);
            Map<String, String> kwargs = new HashMap<>();
            kwargs.put("path", "/");
            kwargs.put("limit", "1");

            CommandResponse response = new Commands.WatchDetailsCommand().runGet(zkServer, kwargs);
            List<Map<String, Object>> watches = watchDetails(response);

            assertEquals(1, response.toMap().get("returned_count"));
            assertEquals(false, response.toMap().get("truncated"));
            assertEquals(1, watches.size());
            Map<String, Object> watch = findWatch(watches, "/", "0x12", "standard");
            assertEquals(Arrays.asList("data", "children"), watch.get("watch_kind"));
        } finally {
            dataTree.shutdownWatcher();
        }
    }

    @Test
    public void testWatchDetailsUsesNewestConnectionAndFiltersInactiveWatches() throws Exception {
        DataTree dataTree = newWatchDetailsDataTree();
        try {
            ServerCnxn oldConnection = mockWatchConnection(
                0x22L,
                "10.10.2.1",
                5201,
                1000L,
                20000,
                false,
                new AtomicBoolean(false));
            ServerCnxn newConnection = mockWatchConnection(
                0x22L,
                "10.10.2.2",
                5202,
                2000L,
                30000,
                false,
                new AtomicBoolean(false));
            AtomicBoolean staleState = new AtomicBoolean(false);
            ServerCnxn staleConnection = mockWatchConnection(
                0x33L,
                "10.10.3.3",
                5303,
                3000L,
                30000,
                false,
                staleState);
            ServerCnxn disconnected = mockWatchConnection(
                0x44L,
                "10.10.4.4",
                5404,
                4000L,
                30000,
                false,
                new AtomicBoolean(false));

            dataTree.statNode("/", oldConnection);
            dataTree.addWatch("/stale", staleConnection, ZooDefs.AddWatchModes.persistentRecursive);
            dataTree.addWatch("/disconnected", disconnected, ZooDefs.AddWatchModes.persistentRecursive);
            staleState.set(true);

            ZooKeeperServer zkServer = mockWatchDetailsServer(
                dataTree,
                Arrays.asList(oldConnection, newConnection, staleConnection),
                null);
            Map<String, String> kwargs = new HashMap<>();
            kwargs.put("session_id", "34");
            kwargs.put("client_ip", "10.10.2.2");
            List<Map<String, Object>> watches = watchDetails(
                new Commands.WatchDetailsCommand().runGet(zkServer, kwargs));

            assertEquals(1, watches.size());
            Map<String, Object> watch = watches.get(0);
            assertEquals("10.10.2.2", watch.get("client_ip"));
            assertEquals(5202, watch.get("client_port"));
            assertEquals(2000L, watch.get("connection_established_at"));
            assertEquals(30000, watch.get("session_timeout_ms"));

            watches = watchDetails(new Commands.WatchDetailsCommand().runGet(zkServer, new HashMap<>()));
            assertEquals(1, watches.size());
            assertEquals("/", watches.get(0).get("path"));
        } finally {
            dataTree.shutdownWatcher();
        }
    }

    @Test
    public void testWatchDetailsLimitAndTruncation() {
        DataTree dataTree = newWatchDetailsDataTree();
        try {
            ServerCnxn connection = mockWatchConnection(
                0x55L,
                "10.10.5.5",
                5505,
                5000L,
                30000,
                false,
                new AtomicBoolean(false));
            for (int i = 0; i < 101; i++) {
                dataTree.addWatch("/watch-" + i, connection, ZooDefs.AddWatchModes.persistentRecursive);
            }
            ZooKeeperServer zkServer = mockWatchDetailsServer(
                dataTree,
                Collections.singletonList(connection),
                null);

            CommandResponse response = new Commands.WatchDetailsCommand().runGet(zkServer, new HashMap<>());
            assertEquals(100, response.toMap().get("returned_count"));
            assertEquals(true, response.toMap().get("truncated"));
            assertEquals(100, watchDetails(response).size());

            Map<String, String> kwargs = new HashMap<>();
            kwargs.put("limit", "1000");
            response = new Commands.WatchDetailsCommand().runGet(zkServer, kwargs);
            assertEquals(101, response.toMap().get("returned_count"));
            assertEquals(false, response.toMap().get("truncated"));
            assertEquals(101, watchDetails(response).size());
        } finally {
            dataTree.shutdownWatcher();
        }
    }

    @Test
    public void testWatchDetailsReturnsNotImplementedForUnsupportedManager() {
        DataTree dataTree = mock(DataTree.class);
        when(dataTree.getDataWatchRegistrations("/", Collections.singleton(0x66L), 101))
            .thenThrow(new UnsupportedOperationException("Watch registration details are not supported"));
        ServerCnxn connection = mockWatchConnection(
            0x66L,
            "10.10.6.6",
            5606,
            6000L,
            30000,
            false,
            new AtomicBoolean(false));
        ZooKeeperServer zkServer = mockWatchDetailsServer(
            dataTree,
            Collections.singletonList(connection),
            null);
        Map<String, String> kwargs = new HashMap<>();
        kwargs.put("path", "/");

        CommandResponse response = new Commands.WatchDetailsCommand().runGet(zkServer, kwargs);

        assertEquals(HttpServletResponse.SC_NOT_IMPLEMENTED, response.getStatusCode());
        assertEquals("Watch details are not supported by the configured WatchManager", response.getError());
    }

    @Test
    public void testWatchDetailsReturnsNotImplementedWithoutActiveConnections() {
        DataTree dataTree = mock(DataTree.class);
        when(dataTree.getDataWatchRegistrations(null, Collections.emptySet(), 101))
            .thenThrow(new UnsupportedOperationException("Watch registration details are not supported"));
        ZooKeeperServer zkServer = mockWatchDetailsServer(dataTree, null, null);

        CommandResponse response = new Commands.WatchDetailsCommand().runGet(
            zkServer,
            Collections.emptyMap());

        assertEquals(HttpServletResponse.SC_NOT_IMPLEMENTED, response.getStatusCode());
        assertEquals("Watch details are not supported by the configured WatchManager", response.getError());
    }

    @Test
    public void testWatchDetailsReturnsInternalServerErrorForUnexpectedFailure() {
        DataTree dataTree = mock(DataTree.class);
        when(dataTree.getDataWatchRegistrations("/", Collections.singleton(0x77L), 101))
            .thenThrow(new IllegalStateException("unexpected"));
        ServerCnxn connection = mockWatchConnection(
            0x77L,
            "10.10.7.7",
            5707,
            7000L,
            30000,
            false,
            new AtomicBoolean(false));
        ZooKeeperServer zkServer = mockWatchDetailsServer(
            dataTree,
            Collections.singletonList(connection),
            null);
        Map<String, String> kwargs = new HashMap<>();
        kwargs.put("path", "/");

        CommandResponse response = new Commands.WatchDetailsCommand().runGet(zkServer, kwargs);

        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Failed to query watch details", response.getError());
    }

    @Test
    public void testWatchDetailsDoesNotTreatConnectionFailureAsUnsupportedManager() {
        ServerCnxnFactory factory = mock(ServerCnxnFactory.class);
        when(factory.getConnections()).thenThrow(new UnsupportedOperationException("unexpected"));
        ZooKeeperServer zkServer = mock(ZooKeeperServer.class);
        when(zkServer.getServerCnxnFactory()).thenReturn(factory);

        CommandResponse response = new Commands.WatchDetailsCommand().runGet(
            zkServer,
            Collections.emptyMap());

        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Failed to query watch details", response.getError());
    }

    private void assertWatchDetailsBadRequest(
            Commands.WatchDetailsCommand command,
            ZooKeeperServer zkServer,
            String key,
            String value,
            String expectedError) {
        Map<String, String> kwargs = new HashMap<>();
        kwargs.put(key, value);

        CommandResponse response = command.runGet(zkServer, kwargs);

        assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getStatusCode());
        assertEquals(expectedError, response.getError());
    }

    private static DataTree newWatchDetailsDataTree() {
        String property = WatchManagerFactory.ZOOKEEPER_WATCH_MANAGER_NAME;
        String previous = System.getProperty(property);
        System.setProperty(property, WatchManager.class.getName());
        try {
            return new DataTree();
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    private static ServerCnxn mockWatchConnection(
            long sessionId,
            String clientIp,
            int clientPort,
            long establishedAt,
            int sessionTimeout,
            boolean secure,
            AtomicBoolean stale) {
        ServerCnxn connection = mock(ServerCnxn.class);
        when(connection.getSessionId()).thenReturn(sessionId);
        when(connection.getRemoteSocketAddress()).thenReturn(new InetSocketAddress(clientIp, clientPort));
        when(connection.getEstablished()).thenReturn(new Date(establishedAt));
        when(connection.getSessionTimeout()).thenReturn(sessionTimeout);
        when(connection.isSecure()).thenReturn(secure);
        when(connection.isStale()).thenAnswer(invocation -> stale.get());
        return connection;
    }

    private static ZooKeeperServer mockWatchDetailsServer(
            DataTree dataTree,
            Iterable<ServerCnxn> plainConnections,
            Iterable<ServerCnxn> secureConnections) {
        ZooKeeperServer zkServer = mock(ZooKeeperServer.class);
        ZKDatabase zkDatabase = mock(ZKDatabase.class);
        when(zkServer.getServerId()).thenReturn(7L);
        when(zkServer.getZKDatabase()).thenReturn(zkDatabase);
        when(zkDatabase.getDataTree()).thenReturn(dataTree);
        if (plainConnections != null) {
            ServerCnxnFactory factory = mock(ServerCnxnFactory.class);
            when(factory.getConnections()).thenReturn(plainConnections);
            when(zkServer.getServerCnxnFactory()).thenReturn(factory);
        }
        if (secureConnections != null) {
            ServerCnxnFactory factory = mock(ServerCnxnFactory.class);
            when(factory.getConnections()).thenReturn(secureConnections);
            when(zkServer.getSecureServerCnxnFactory()).thenReturn(factory);
        }
        return zkServer;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> watchDetails(CommandResponse response) {
        return (List<Map<String, Object>>) response.toMap().get("watches");
    }

    private static Map<String, Object> findWatch(
            List<Map<String, Object>> watches,
            String path,
            String sessionId,
            String watchMode) {
        for (Map<String, Object> watch : watches) {
            if (path.equals(watch.get("path"))
                    && sessionId.equals(watch.get("session_id"))
                    && watchMode.equals(watch.get("watch_mode"))) {
                return watch;
            }
        }
        throw new AssertionError(
            "Watch not found for path " + path + ", session " + sessionId + ", and mode " + watchMode);
    }

    @Test
    public void testVotingViewCommand() throws IOException, InterruptedException {
        testCommand("voting_view",
                    new Field("current_config", Map.class));
    }

    @Test
    public void testConsCommandSecureOnly() {
        // Arrange
        Commands.ConsCommand cmd = new Commands.ConsCommand();
        ZooKeeperServer zkServer = mock(ZooKeeperServer.class);
        ServerCnxnFactory cnxnFactory = mock(ServerCnxnFactory.class);
        when(zkServer.getSecureServerCnxnFactory()).thenReturn(cnxnFactory);

        // Act
        CommandResponse response = cmd.runGet(zkServer, null);

        // Assert
        assertThat(response.toMap().containsKey("connections"), is(true));
        assertThat(response.toMap().containsKey("secure_connections"), is(true));
    }

    /**
     * testing Stat command, when only SecureClientPort is defined by the user and there is no
     * regular (non-SSL port) open. In this case zkServer.getServerCnxnFactory === null
     * see: ZOOKEEPER-3633
     */
    @Test
    public void testStatCommandSecureOnly() {
        Commands.StatCommand cmd = new Commands.StatCommand();
        ZooKeeperServer zkServer = mock(ZooKeeperServer.class);
        ServerCnxnFactory cnxnFactory = mock(ServerCnxnFactory.class);
        ServerStats serverStats = mock(ServerStats.class);
        ZKDatabase zkDatabase = mock(ZKDatabase.class);
        when(zkServer.getSecureServerCnxnFactory()).thenReturn(cnxnFactory);
        when(zkServer.serverStats()).thenReturn(serverStats);
        when(zkServer.getZKDatabase()).thenReturn(zkDatabase);
        when(zkDatabase.getNodeCount()).thenReturn(0);

        CommandResponse response = cmd.runGet(zkServer, null);

        assertThat(response.toMap().containsKey("connections"), is(true));
        assertThat(response.toMap().containsKey("secure_connections"), is(true));
    }

    @Test
    public void testShedConnections() throws IOException, InterruptedException {
        final Map<String, String> kwargs = new HashMap<>();
        final InputStream inputStream = new ByteArrayInputStream("{\"percentage\": 25}".getBytes());
        final String authInfo = CommandAuthTest.buildAuthorizationForDigest();
        testCommand("shed_connections", kwargs, inputStream, authInfo, new HashMap<>(), HttpServletResponse.SC_OK,
                new Field("percentage_requested", Integer.class),
                new Field("connections_shed", Integer.class));
    }

    private void testSnapshot(final boolean streaming) throws IOException, InterruptedException {
        System.setProperty(ADMIN_SNAPSHOT_ENABLED, "true");
        System.setProperty(ADMIN_RATE_LIMITER_INTERVAL, "0");
        System.setProperty(ZOOKEEPER_SERIALIZE_LAST_PROCESSED_ZXID_ENABLED, "true");
        try {
            final Map<String, String> kwargs = new HashMap<>();
            kwargs.put(REQUEST_QUERY_PARAM_STREAMING, String.valueOf(streaming));
            final String autInfo = CommandAuthTest.buildAuthorizationForDigest();
            final Map<String, String> expectedHeaders = new HashMap<>();
            expectedHeaders.put(Commands.SnapshotCommand.RESPONSE_HEADER_LAST_ZXID, "0x0");
            expectedHeaders.put(Commands.SnapshotCommand.RESPONSE_HEADER_SNAPSHOT_SIZE, "478");
            testCommand("snapshot", kwargs, null, autInfo, expectedHeaders, HttpServletResponse.SC_OK);
        } finally {
            System.clearProperty(ADMIN_SNAPSHOT_ENABLED);
            System.clearProperty(ADMIN_RATE_LIMITER_INTERVAL);
            System.clearProperty(ZOOKEEPER_SERIALIZE_LAST_PROCESSED_ZXID_ENABLED);
        }
    }

    private void setupForRestoreCommand() {
        System.setProperty(ADMIN_RESTORE_ENABLED, "true");
        System.setProperty(ADMIN_RATE_LIMITER_INTERVAL, "0");
        System.setProperty(ZOOKEEPER_SERIALIZE_LAST_PROCESSED_ZXID_ENABLED, "true");
    }

    private void clearForRestoreCommand() {
        System.clearProperty(ADMIN_RESTORE_ENABLED);
        System.clearProperty(ADMIN_RATE_LIMITER_INTERVAL);
        System.clearProperty(ZOOKEEPER_SERIALIZE_LAST_PROCESSED_ZXID_ENABLED);
    }
}
