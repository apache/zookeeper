/**
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

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ServerStats;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.quorum.BufferStats;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Test;

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
     * @param keys
     *            - the keys that are expected in the returned Map
     * @param types
     *            - the classes of the values in the returned Map. types[i] is
     *            the type of the value for keys[i].
     * @throws IOException
     * @throws InterruptedException
     */
    public void testCommand(String cmdName, Map<String, String> kwargs, Field... fields)
            throws IOException, InterruptedException {
        ZooKeeperServer zks = serverFactory.getZooKeeperServer();
        Map<String, Object> result = Commands.runCommand(cmdName, zks, kwargs).toMap();

        assertTrue(result.containsKey("command"));
        // This is only true because we're setting cmdName to the primary name
        assertEquals(cmdName, result.remove("command"));
        assertTrue(result.containsKey("error"));
        assertNull("error: " + result.get("error"), result.remove("error"));

        for (Field field : fields) {
            String k = field.key;
            assertTrue("Result from command " + cmdName + " missing field \"" + k + "\""
                       + "\n" + result,
                       result.containsKey(k));
            Class<?> t = field.type;
            Object v = result.remove(k);
            assertTrue("\"" + k + "\" field from command " + cmdName + " should be of type " + t
                       + ", is actually of type " + v.getClass(),
                       t.isAssignableFrom(v.getClass()));
        }

        assertTrue("Result from command " + cmdName + " contains extra fields: " + result,
                   result.isEmpty());
    }

    public void testCommand(String cmdName, Field... fields)
            throws IOException, InterruptedException {
        testCommand(cmdName, new HashMap<String, String>(), fields);
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
        testCommand("configuration",
                    new Field("client_port", Integer.class),
                    new Field("data_dir", String.class),
                    new Field("data_log_dir", String.class),
                    new Field("tick_time", Integer.class),
                    new Field("max_client_cnxns", Integer.class),
                    new Field("min_session_timeout", Integer.class),
                    new Field("max_session_timeout", Integer.class),
                    new Field("server_id", Long.class));
    }

    @Test
    public void testConnections() throws IOException, InterruptedException {
        testCommand("connections",
                    new Field("connections", Iterable.class),
                    new Field("secure_connections", Iterable.class)
                );
    }

    @Test
    public void testConnectionStatReset() throws IOException, InterruptedException {
        testCommand("connection_stat_reset");
    }

    @Test
    public void testDump() throws IOException, InterruptedException {
        testCommand("dump",
                    new Field("expiry_time_to_session_ids", Map.class),
                    new Field("session_id_to_ephemeral_paths", Map.class));
    }

    @Test
    public void testEnvironment() throws IOException, InterruptedException {
        testCommand("environment",
                    new Field("zookeeper.version", String.class),
                    new Field("host.name", String.class),
                    new Field("java.version", String.class),
                    new Field("java.vendor", String.class),
                    new Field("java.home", String.class),
                    new Field("java.class.path", String.class),
                    new Field("java.library.path", String.class),
                    new Field("java.io.tmpdir", String.class),
                    new Field("java.compiler", String.class),
                    new Field("os.name", String.class),
                    new Field("os.arch", String.class),
                    new Field("os.version", String.class),
                    new Field("user.name", String.class),
                    new Field("user.home", String.class),
                    new Field("user.dir", String.class),
                    new Field("os.memory.free", String.class),
                    new Field("os.memory.max", String.class),
                    new Field("os.memory.total", String.class));
    }

    @Test
    public void testGetTraceMask() throws IOException, InterruptedException {
        testCommand("get_trace_mask",
                    new Field("tracemask", Long.class));
    }

    @Test
    public void testIsReadOnly() throws IOException, InterruptedException {
        testCommand("is_read_only",
                    new Field("read_only", Boolean.class));
    }

    @Test
    public void testMonitor() throws IOException, InterruptedException {
        testCommand("monitor",
                    new Field("version", String.class),
                    new Field("avg_latency", Long.class),
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
                    new Field("min_client_response_size", Integer.class));
    }

    @Test
    public void testRuok() throws IOException, InterruptedException {
        testCommand("ruok");
    }

    @Test
    public void testServerStats() throws IOException, InterruptedException {
        testCommand("server_stats",
                new Field("version", String.class),
                new Field("read_only", Boolean.class),
                new Field("server_stats", ServerStats.class),
                new Field("node_count", Integer.class),
                new Field("client_response", BufferStats.class));
    }

    @Test
    public void testSetTraceMask() throws IOException, InterruptedException {
        Map<String, String> kwargs = new HashMap<String, String>();
        kwargs.put("traceMask", "1");
        testCommand("set_trace_mask", kwargs,
                    new Field("tracemask", Long.class));
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
        testCommand("watches",
                    new Field("session_id_to_watched_paths", Map.class));
    }

    @Test
    public void testWatchesByPath() throws IOException, InterruptedException {
        testCommand("watches_by_path",
                    new Field("path_to_session_ids", Map.class));
    }

    @Test
    public void testWatchSummary() throws IOException, InterruptedException {
        testCommand("watch_summary",
                    new Field("num_connections", Integer.class),
                    new Field("num_paths", Integer.class),
                    new Field("num_total_watches", Integer.class));
    }

    @Test
    public void testConsCommandSecureOnly() {
        // Arrange
        Commands.ConsCommand cmd = new Commands.ConsCommand();
        ZooKeeperServer zkServer = mock(ZooKeeperServer.class);
        ServerCnxnFactory cnxnFactory = mock(ServerCnxnFactory.class);
        when(zkServer.getSecureServerCnxnFactory()).thenReturn(cnxnFactory);

        // Act
        CommandResponse response = cmd.run(zkServer, null);

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

        CommandResponse response = cmd.run(zkServer, null);

        assertThat(response.toMap().containsKey("connections"), is(true));
        assertThat(response.toMap().containsKey("secure_connections"), is(true));
    }

}
