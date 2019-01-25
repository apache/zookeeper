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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.zookeeper.Environment;
import org.apache.zookeeper.Environment.Entry;
import org.apache.zookeeper.Version;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.ServerStats;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.ZooTrace;
import org.apache.zookeeper.server.quorum.Follower;
import org.apache.zookeeper.server.quorum.FollowerZooKeeperServer;
import org.apache.zookeeper.server.quorum.Leader;
import org.apache.zookeeper.server.quorum.LeaderZooKeeperServer;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumZooKeeperServer;
import org.apache.zookeeper.server.quorum.ObserverZooKeeperServer;
import org.apache.zookeeper.server.quorum.ReadOnlyZooKeeperServer;
import org.apache.zookeeper.server.util.OSMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class containing static methods for registering and running Commands, as well
 * as default Command definitions.
 *
 * @see Command
 * @see JettyAdminServer
 */
public class Commands {
    static final Logger LOG = LoggerFactory.getLogger(Commands.class);

    /** Maps command names to Command instances */
    private static Map<String, Command> commands = new HashMap<String, Command>();
    private static Set<String> primaryNames = new HashSet<String>();

    /**
     * Registers the given command. Registered commands can be run by passing
     * any of their names to runCommand.
     */
    public static void registerCommand(Command command) {
        for (String name : command.getNames()) {
            Command prev = commands.put(name, command);
            if (prev != null) {
                LOG.warn("Re-registering command %s (primary name = %s)", name, command.getPrimaryName());
            }
        }
        primaryNames.add(command.getPrimaryName());
    }

    /**
     * Run the registered command with name cmdName. Commands should not produce
     * any exceptions; any (anticipated) errors should be reported in the
     * "error" entry of the returned map. Likewise, if no command with the given
     * name is registered, this will be noted in the "error" entry.
     *
     * @param cmdName
     * @param zkServer
     * @param kwargs String-valued keyword arguments to the command
     *        (may be null if command requires no additional arguments)
     * @return Map representing response to command containing at minimum:
     *    - "command" key containing the command's primary name
     *    - "error" key containing a String error message or null if no error
     */
    public static CommandResponse runCommand(String cmdName, ZooKeeperServer zkServer, Map<String, String> kwargs) {
        if (!commands.containsKey(cmdName)) {
            return new CommandResponse(cmdName, "Unknown command: " + cmdName);
        }
        if (zkServer == null || !zkServer.isRunning()) {
            return new CommandResponse(cmdName, "This ZooKeeper instance is not currently serving requests");
        }
        return commands.get(cmdName).run(zkServer, kwargs);
    }

    /**
     * Returns the primary names of all registered commands.
     */
    public static Set<String> getPrimaryNames() {
        return primaryNames;
    }

    /**
     * Returns the commands registered under cmdName with registerCommand, or
     * null if no command is registered with that name.
     */
    public static Command getCommand(String cmdName) {
        return commands.get(cmdName);
    }

    static {
        registerCommand(new CnxnStatResetCommand());
        registerCommand(new ConfCommand());
        registerCommand(new ConsCommand());
        registerCommand(new DirsCommand());
        registerCommand(new DumpCommand());
        registerCommand(new EnvCommand());
        registerCommand(new GetTraceMaskCommand());
        registerCommand(new IsroCommand());
        registerCommand(new MonitorCommand());
        registerCommand(new RuokCommand());
        registerCommand(new SetTraceMaskCommand());
        registerCommand(new SrvrCommand());
        registerCommand(new StatCommand());
        registerCommand(new StatResetCommand());
        registerCommand(new WatchCommand());
        registerCommand(new WatchesByPathCommand());
        registerCommand(new WatchSummaryCommand());
    }

    /**
     * Reset all connection statistics.
     */
    public static class CnxnStatResetCommand extends CommandBase {
        public CnxnStatResetCommand() {
            super(Arrays.asList("connection_stat_reset", "crst"));
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            CommandResponse response = initializeResponse();
            zkServer.getServerCnxnFactory().resetAllConnectionStats();
            return response;

        }
    }

    /**
     * Server configuration parameters.
     * @see ZooKeeperServer#getConf()
     */
    public static class ConfCommand extends CommandBase {
        public ConfCommand() {
            super(Arrays.asList("configuration", "conf", "config"));
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            CommandResponse response = initializeResponse();
            response.putAll(zkServer.getConf().toMap());
            return response;
        }
    }

    /**
     * Information on client connections to server. Returned Map contains:
     *   - "connections": list of connection info objects
     * @see org.apache.zookeeper.server.ServerCnxn#getConnectionInfo(boolean)
     */
    public static class ConsCommand extends CommandBase {
        public ConsCommand() {
            super(Arrays.asList("connections", "cons"));
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            CommandResponse response = initializeResponse();
            ServerCnxnFactory serverCnxnFactory = zkServer.getServerCnxnFactory();
            if (serverCnxnFactory != null) {
                response.put("connections", serverCnxnFactory.getAllConnectionInfo(false));
            } else {
                response.put("connections", Collections.emptyList());
            }
            ServerCnxnFactory secureServerCnxnFactory = zkServer.getSecureServerCnxnFactory();
            if (secureServerCnxnFactory != null) {
                response.put("secure_connections", secureServerCnxnFactory.getAllConnectionInfo(false));
            } else {
                response.put("secure_connections", Collections.emptyList());
            }
            return response;
        }
    }

    /**
     * Information on ZK datadir and snapdir size in bytes
     */
    public static class DirsCommand extends CommandBase {
        public DirsCommand() {
            super(Arrays.asList("dirs"));
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            CommandResponse response = initializeResponse();
            response.put("datadir_size", zkServer.getDataDirSize());
            response.put("logdir_size", zkServer.getLogDirSize());
            return response;
        }
    }

    /**
     * Information on session expirations and ephemerals. Returned map contains:
     *   - "expiry_time_to_session_ids": Map<Long, Set<Long>>
     *                                   time -> sessions IDs of sessions that expire at time
     *   - "session_id_to_ephemeral_paths": Map<Long, Set<String>>
     *                                       session ID -> ephemeral paths created by that session
     * @see ZooKeeperServer#getSessionExpiryMap()
     * @see ZooKeeperServer#getEphemerals()
     */
    public static class DumpCommand extends CommandBase {
        public DumpCommand() {
            super(Arrays.asList("dump"));
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            CommandResponse response = initializeResponse();
            response.put("expiry_time_to_session_ids", zkServer.getSessionExpiryMap());
            response.put("session_id_to_ephemeral_paths", zkServer.getEphemerals());
            return response;
        }
    }

    /**
     * All defined environment variables.
     */
    public static class EnvCommand extends CommandBase {
        public EnvCommand() {
            super(Arrays.asList("environment", "env", "envi"));
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            CommandResponse response = initializeResponse();
            for (Entry e : Environment.list()) {
                response.put(e.getKey(), e.getValue());
            }
            return response;
        }
    }

    /**
     * The current trace mask. Returned map contains:
     *   - "tracemask": Long
     */
    public static class GetTraceMaskCommand extends CommandBase {
        public GetTraceMaskCommand() {
            super(Arrays.asList("get_trace_mask", "gtmk"));
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            CommandResponse response = initializeResponse();
            response.put("tracemask", ZooTrace.getTextTraceLevel());
            return response;
        }
    }

    /**
     * Is this server in read-only mode. Returned map contains:
     *   - "is_read_only": Boolean
     */
    public static class IsroCommand extends CommandBase {
        public IsroCommand() {
            super(Arrays.asList("is_read_only", "isro"));
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            CommandResponse response = initializeResponse();
            response.put("read_only", zkServer instanceof ReadOnlyZooKeeperServer);
            return response;
        }
    }

    /**
     * Some useful info for monitoring. Returned map contains:
     *   - "version": String
     *                server version
     *   - "avg_latency": Long
     *   - "max_latency": Long
     *   - "min_latency": Long
     *   - "packets_received": Long
     *   - "packets_sents": Long
     *   - "num_alive_connections": Integer
     *   - "outstanding_requests": Long
     *                             number of unprocessed requests
     *   - "server_state": "leader", "follower", or "standalone"
     *   - "znode_count": Integer
     *   - "watch_count": Integer
     *   - "ephemerals_count": Integer
     *   - "approximate_data_size": Long
     *   - "open_file_descriptor_count": Long (unix only)
     *   - "max_file_descriptor_count": Long (unix only)
     *   - "fsync_threshold_exceed_count": Long
     *   - "followers": Integer (leader only)
     *   - "synced_followers": Integer (leader only)
     *   - "pending_syncs": Integer (leader only)
     */
    public static class MonitorCommand extends CommandBase {
        public MonitorCommand() {
            super(Arrays.asList("monitor", "mntr"));
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            ZKDatabase zkdb = zkServer.getZKDatabase();
            ServerStats stats = zkServer.serverStats();

            CommandResponse response = initializeResponse();

            response.put("version", Version.getFullVersion());

            response.put("avg_latency", stats.getAvgLatency());
            response.put("max_latency", stats.getMaxLatency());
            response.put("min_latency", stats.getMinLatency());

            response.put("packets_received", stats.getPacketsReceived());
            response.put("packets_sent", stats.getPacketsSent());
            response.put("num_alive_connections", stats.getNumAliveClientConnections());

            response.put("outstanding_requests", stats.getOutstandingRequests());
            response.put("uptime", stats.getUptime());

            response.put("server_state", stats.getServerState());
            response.put("znode_count", zkdb.getNodeCount());

            response.put("watch_count", zkdb.getDataTree().getWatchCount());
            response.put("ephemerals_count", zkdb.getDataTree().getEphemeralsCount());
            response.put("approximate_data_size", zkdb.getDataTree().cachedApproximateDataSize());

            response.put("global_sessions", zkdb.getSessionCount());
            response.put("local_sessions",
                    zkServer.getSessionTracker().getLocalSessionCount());

            OSMXBean osMbean = new OSMXBean();
            response.put("open_file_descriptor_count", osMbean.getOpenFileDescriptorCount());
            response.put("max_file_descriptor_count", osMbean.getMaxFileDescriptorCount());
            response.put("connection_drop_probability", zkServer.getConnectionDropChance());

            response.put("last_client_response_size", stats.getClientResponseStats().getLastBufferSize());
            response.put("max_client_response_size", stats.getClientResponseStats().getMaxBufferSize());
            response.put("min_client_response_size", stats.getClientResponseStats().getMinBufferSize());

            if (zkServer instanceof QuorumZooKeeperServer) {
                QuorumPeer peer = ((QuorumZooKeeperServer) zkServer).self;
                response.put("quorum_size", peer.getQuorumSize());
            }

            if (zkServer instanceof LeaderZooKeeperServer) {
                Leader leader = ((LeaderZooKeeperServer) zkServer).getLeader();

                response.put("learners", leader.getLearners().size());
                response.put("synced_followers", leader.getForwardingFollowers().size());
                response.put("synced_non_voting_followers", leader.getNonVotingFollowers().size());
                response.put("synced_observers", leader.getObservingLearners().size());
                response.put("pending_syncs", leader.getNumPendingSyncs());
                response.put("leader_uptime", leader.getUptime());

                response.put("last_proposal_size", leader.getProposalStats().getLastBufferSize());
                response.put("max_proposal_size", leader.getProposalStats().getMaxBufferSize());
                response.put("min_proposal_size", leader.getProposalStats().getMinBufferSize());
            }

            if (zkServer instanceof FollowerZooKeeperServer) {
                Follower follower = ((FollowerZooKeeperServer) zkServer).getFollower();
                Integer syncedObservers = follower.getSyncedObserverSize();
                if (syncedObservers != null) {
                    response.put("synced_observers", syncedObservers);
                }
            }

            if (zkServer instanceof ObserverZooKeeperServer) {
                response.put("observer_master_id", ((ObserverZooKeeperServer)zkServer).getObserver().getLearnerMasterId());
            }

            response.putAll(ServerMetrics.getAllValues());

            return response;

        }}

    /**
     * No-op command, check if the server is running
     */
    public static class RuokCommand extends CommandBase {
        public RuokCommand() {
            super(Arrays.asList("ruok"));
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            return initializeResponse();
        }
    }

    /**
     * Sets the trace mask. Required arguments:
     *   - "traceMask": Long
     *  Returned Map contains:
     *   - "tracemask": Long
     */
    public static class SetTraceMaskCommand extends CommandBase {
        public SetTraceMaskCommand() {
            super(Arrays.asList("set_trace_mask", "stmk"));
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            CommandResponse response = initializeResponse();
            long traceMask;
            if (!kwargs.containsKey("traceMask")) {
                response.put("error", "setTraceMask requires long traceMask argument");
                return response;
            }
            try {
                traceMask = Long.parseLong(kwargs.get("traceMask"));
            } catch (NumberFormatException e) {
                response.put("error", "setTraceMask requires long traceMask argument, got "
                                      + kwargs.get("traceMask"));
                return response;
            }

            ZooTrace.setTextTraceLevel(traceMask);
            response.put("tracemask", traceMask);
            return response;
        }
    }

    /**
     * Server information. Returned map contains:
     *   - "version": String
     *                version of server
     *   - "read_only": Boolean
     *                  is server in read-only mode
     *   - "server_stats": ServerStats object
     *   - "node_count": Integer
     */
    public static class SrvrCommand extends CommandBase {
        public SrvrCommand() {
            super(Arrays.asList("server_stats", "srvr"));
        }

        // Allow subclasses (e.g. StatCommand) to specify their own names
        protected SrvrCommand(List<String> names) {
            super(names);
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            CommandResponse response = initializeResponse();
            LOG.info("running stat");
            response.put("version", Version.getFullVersion());
            response.put("read_only", zkServer instanceof ReadOnlyZooKeeperServer);
            response.put("server_stats", zkServer.serverStats());
            response.put("client_response", zkServer.serverStats().getClientResponseStats());
            if (zkServer instanceof LeaderZooKeeperServer) {
                Leader leader = ((LeaderZooKeeperServer)zkServer).getLeader();
                response.put("proposal_stats", leader.getProposalStats());
            }
            response.put("node_count", zkServer.getZKDatabase().getNodeCount());
            return response;
        }
    }

    /**
     * Same as SrvrCommand but has extra "connections" entry.
     */
    public static class StatCommand extends SrvrCommand {
        public StatCommand() {
            super(Arrays.asList("stats", "stat"));
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            CommandResponse response = super.run(zkServer, kwargs);
            response.put("connections", zkServer.getServerCnxnFactory().getAllConnectionInfo(true));
            return response;
        }
    }

    /**
     * Resets server statistics.
     */
    public static class StatResetCommand extends CommandBase {
        public StatResetCommand() {
            super(Arrays.asList("stat_reset", "srst"));
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            CommandResponse response = initializeResponse();
            zkServer.serverStats().reset();
            return response;
        }
    }

    /**
     * Watch information aggregated by session. Returned Map contains:
     *   - "session_id_to_watched_paths": Map<Long, Set<String>> session ID -> watched paths
     * @see DataTree#getWatches()
     */
    public static class WatchCommand extends CommandBase {
        public WatchCommand() {
            super(Arrays.asList("watches", "wchc"));
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            DataTree dt = zkServer.getZKDatabase().getDataTree();
            CommandResponse response = initializeResponse();
            response.put("session_id_to_watched_paths", dt.getWatches().toMap());
            return response;
        }
    }

    /**
     * Watch information aggregated by path. Returned Map contains:
     *   - "path_to_session_ids": Map<String, Set<Long>> path -> session IDs of sessions watching path
     * @see DataTree#getWatchesByPath()
     */
    public static class WatchesByPathCommand extends CommandBase {
        public WatchesByPathCommand() {
            super(Arrays.asList("watches_by_path", "wchp"));
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            DataTree dt = zkServer.getZKDatabase().getDataTree();
            CommandResponse response = initializeResponse();
            response.put("path_to_session_ids", dt.getWatchesByPath().toMap());
            return response;
        }
    }

    /**
     * Summarized watch information.
     * @see DataTree#getWatchesSummary()
     */
    public static class WatchSummaryCommand extends CommandBase {
        public WatchSummaryCommand() {
            super(Arrays.asList("watch_summary", "wchs"));
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            DataTree dt = zkServer.getZKDatabase().getDataTree();
            CommandResponse response = initializeResponse();
            response.putAll(dt.getWatchesSummary().toMap());
            return response;
        }
    }

    private Commands() {}
}
