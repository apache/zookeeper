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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ServerStats;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.quorum.BufferStats;
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
