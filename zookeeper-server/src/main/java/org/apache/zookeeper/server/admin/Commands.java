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

import com.fasterxml.jackson.annotation.JsonProperty;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.apache.zookeeper.Environment;
import org.apache.zookeeper.Environment.Entry;
import org.apache.zookeeper.Version;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.ZooTrace;
import org.apache.zookeeper.server.persistence.SnapshotInfo;
import org.apache.zookeeper.server.quorum.Follower;
import org.apache.zookeeper.server.quorum.FollowerZooKeeperServer;
import org.apache.zookeeper.server.quorum.Leader;
import org.apache.zookeeper.server.quorum.LeaderZooKeeperServer;
import org.apache.zookeeper.server.quorum.MultipleAddresses;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeer.LearnerType;
import org.apache.zookeeper.server.quorum.QuorumZooKeeperServer;
import org.apache.zookeeper.server.quorum.ReadOnlyZooKeeperServer;
import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;
import org.apache.zookeeper.server.util.ZxidUtils;
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
                LOG.warn("Re-registering command {} (primary name = {})", name, command.getPrimaryName());
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
    public static CommandResponse runCommand(
        String cmdName,
        ZooKeeperServer zkServer,
        Map<String, String> kwargs) {
        Command command = getCommand(cmdName);
        if (command == null) {
            return new CommandResponse(cmdName, "Unknown command: " + cmdName);
        }
        if (command.isServerRequired() && (zkServer == null || !zkServer.isRunning())) {
            return new CommandResponse(cmdName, "This ZooKeeper instance is not currently serving requests");
        }
        return command.run(zkServer, kwargs);
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
        registerCommand(new DigestCommand());
        registerCommand(new DirsCommand());
        registerCommand(new DumpCommand());
        registerCommand(new EnvCommand());
        registerCommand(new GetTraceMaskCommand());
        registerCommand(new InitialConfigurationCommand());
        registerCommand(new IsroCommand());
        registerCommand(new LastSnapshotCommand());
        registerCommand(new LeaderCommand());
        registerCommand(new MonitorCommand());
        registerCommand(new ObserverCnxnStatResetCommand());
        registerCommand(new RuokCommand());
        registerCommand(new SetTraceMaskCommand());
        registerCommand(new SrvrCommand());
        registerCommand(new StatCommand());
        registerCommand(new StatResetCommand());
        registerCommand(new SyncedObserverConsCommand());
        registerCommand(new SystemPropertiesCommand());
        registerCommand(new VotingViewCommand());
        registerCommand(new WatchCommand());
        registerCommand(new WatchesByPathCommand());
        registerCommand(new WatchSummaryCommand());
        registerCommand(new ZabStateCommand());
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
     *   - "expiry_time_to_session_ids": Map&lt;Long, Set&lt;Long&gt;&gt;
     *                                   time -&gt; sessions IDs of sessions that expire at time
     *   - "session_id_to_ephemeral_paths": Map&lt;Long, Set&lt;String&gt;&gt;
     *                                       session ID -&gt; ephemeral paths created by that session
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
            super(Arrays.asList("environment", "env", "envi"), false);
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
     * Digest histories for every specific number of txns.
     */
    public static class DigestCommand extends CommandBase {

        public DigestCommand() {
            super(Arrays.asList("hash"));
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            CommandResponse response = initializeResponse();
            response.put("digests", zkServer.getZKDatabase().getDataTree().getDigestLog());
            return response;
        }

    }

    /**
     * The current trace mask. Returned map contains:
     *   - "tracemask": Long
     */
    public static class GetTraceMaskCommand extends CommandBase {

        public GetTraceMaskCommand() {
            super(Arrays.asList("get_trace_mask", "gtmk"), false);
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            CommandResponse response = initializeResponse();
            response.put("tracemask", ZooTrace.getTextTraceLevel());
            return response;
        }

    }

    public static class InitialConfigurationCommand extends CommandBase {

        public InitialConfigurationCommand() {
            super(Arrays.asList("initial_configuration", "icfg"));
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            CommandResponse response = initializeResponse();
            response.put("initial_configuration", zkServer.getInitialConfig());
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
     * Command returns information of the last snapshot that zookeeper server
     * has finished saving to disk. During the time between the server starts up
     * and it finishes saving its first snapshot, the command returns the zxid
     * and last modified time of the snapshot file used for restoration at
     * server startup. Returned map contains:
     *   - "zxid": String
     *   - "timestamp": Long
     */
    public static class LastSnapshotCommand extends CommandBase {

        public LastSnapshotCommand() {
            super(Arrays.asList("last_snapshot", "lsnp"));
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            CommandResponse response = initializeResponse();
            SnapshotInfo info = zkServer.getTxnLogFactory().getLastSnapshotInfo();
            response.put("zxid", Long.toHexString(info == null ? -1L : info.zxid));
            response.put("timestamp", info == null ? -1L : info.timestamp);
            return response;
        }

    }

    /**
     * Returns the leader status of this instance and the leader host string.
     */
    public static class LeaderCommand extends CommandBase {

        public LeaderCommand() {
            super(Arrays.asList("leader", "lead"));
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            CommandResponse response = initializeResponse();
            if (zkServer instanceof QuorumZooKeeperServer) {
                response.put("is_leader", zkServer instanceof LeaderZooKeeperServer);
                QuorumPeer peer = ((QuorumZooKeeperServer) zkServer).self;
                response.put("leader_id", peer.getLeaderId());
                String leaderAddress = peer.getLeaderAddress();
                response.put("leader_ip", leaderAddress != null ? leaderAddress : "");
            } else {
                response.put("error", "server is not initialized");
            }
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
     *   - "non_mtls_conn_count": Long
     *   - "non_mtls_remote_conn_count": Long
     *   - "non_mtls_local_conn_count": Long
     *   - "followers": Integer (leader only)
     *   - "synced_followers": Integer (leader only)
     *   - "pending_syncs": Integer (leader only)
     */
    public static class MonitorCommand extends CommandBase {

        public MonitorCommand() {
            super(Arrays.asList("monitor", "mntr"), false);
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            CommandResponse response = initializeResponse();
            zkServer.dumpMonitorValues(response::put);
            ServerMetrics.getMetrics().getMetricsProvider().dump(response::put);
            return response;

        }

    }

    /**
     * Reset all observer connection statistics.
     */
    public static class ObserverCnxnStatResetCommand extends CommandBase {

        public ObserverCnxnStatResetCommand() {
            super(Arrays.asList("observer_connection_stat_reset", "orst"));
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            CommandResponse response = initializeResponse();
            if (zkServer instanceof LeaderZooKeeperServer) {
                Leader leader = ((LeaderZooKeeperServer) zkServer).getLeader();
                leader.resetObserverConnectionStats();
            } else if (zkServer instanceof FollowerZooKeeperServer) {
                Follower follower = ((FollowerZooKeeperServer) zkServer).getFollower();
                follower.resetObserverConnectionStats();
            }
            return response;
        }

    }

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
            super(Arrays.asList("set_trace_mask", "stmk"), false);
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
                response.put("error", "setTraceMask requires long traceMask argument, got " + kwargs.get("traceMask"));
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
                Leader leader = ((LeaderZooKeeperServer) zkServer).getLeader();
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

            final Iterable<Map<String, Object>> connections;
            if (zkServer.getServerCnxnFactory() != null) {
                connections = zkServer.getServerCnxnFactory().getAllConnectionInfo(true);
            } else {
                connections = Collections.emptyList();
            }
            response.put("connections", connections);

            final Iterable<Map<String, Object>> secureConnections;
            if (zkServer.getSecureServerCnxnFactory() != null) {
                secureConnections = zkServer.getSecureServerCnxnFactory().getAllConnectionInfo(true);
            } else {
                secureConnections = Collections.emptyList();
            }
            response.put("secure_connections", secureConnections);
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
     * Information on observer connections to server. Returned Map contains:
     *   - "synced_observers": Integer (leader/follower only)
     *   - "observers": list of observer learner handler info objects (leader/follower only)
     * @see org.apache.zookeeper.server.quorum.LearnerHandler#getLearnerHandlerInfo()
     */
    public static class SyncedObserverConsCommand extends CommandBase {

        public SyncedObserverConsCommand() {
            super(Arrays.asList("observers", "obsr"));
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {

            CommandResponse response = initializeResponse();

            if (zkServer instanceof LeaderZooKeeperServer) {
                Leader leader = ((LeaderZooKeeperServer) zkServer).getLeader();

                response.put("synced_observers", leader.getObservingLearners().size());
                response.put("observers", leader.getObservingLearnersInfo());
                return response;
            } else if (zkServer instanceof FollowerZooKeeperServer) {
                Follower follower = ((FollowerZooKeeperServer) zkServer).getFollower();
                Integer syncedObservers = follower.getSyncedObserverSize();
                if (syncedObservers != null) {
                    response.put("synced_observers", syncedObservers);
                    response.put("observers", follower.getSyncedObserversInfo());
                    return response;
                }
            }

            response.put("synced_observers", 0);
            response.put("observers", Collections.emptySet());
            return response;
        }

    }

    /**
     * All defined system properties.
     */
    public static class SystemPropertiesCommand extends CommandBase {

        public SystemPropertiesCommand() {
            super(Arrays.asList("system_properties", "sysp"), false);
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            CommandResponse response = initializeResponse();
            Properties systemProperties = System.getProperties();
            SortedMap<String, String> sortedSystemProperties = new TreeMap<>();
            systemProperties.forEach((k, v) -> sortedSystemProperties.put(k.toString(), v.toString()));
            response.putAll(sortedSystemProperties);
            return response;
        }

    }

    /**
     * Returns the current ensemble configuration information.
     * It provides list of current voting members in the ensemble.
     */
    public static class VotingViewCommand extends CommandBase {

        public VotingViewCommand() {
            super(Arrays.asList("voting_view"));
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            CommandResponse response = initializeResponse();
            if (zkServer instanceof QuorumZooKeeperServer) {
                QuorumPeer peer = ((QuorumZooKeeperServer) zkServer).self;
                Map<Long, QuorumServerView> votingView = peer.getVotingView().entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> new QuorumServerView(e.getValue())));
                response.put("current_config", votingView);
            } else {
                response.put("current_config", Collections.emptyMap());
            }
            return response;
        }

        @SuppressFBWarnings(value = "URF_UNREAD_FIELD", justification = "class is used only for JSON serialization")
        private static class QuorumServerView {

            @JsonProperty
            private List<String> serverAddresses;

            @JsonProperty
            private List<String> electionAddresses;

            @JsonProperty
            private String clientAddress;

            @JsonProperty
            private String learnerType;

            public QuorumServerView(QuorumPeer.QuorumServer quorumServer) {
                this.serverAddresses = getMultiAddressString(quorumServer.addr);
                this.electionAddresses = getMultiAddressString(quorumServer.electionAddr);
                this.learnerType = quorumServer.type.equals(LearnerType.PARTICIPANT) ? "participant" : "observer";
                this.clientAddress = getAddressString(quorumServer.clientAddr);
            }

            private static List<String> getMultiAddressString(MultipleAddresses multipleAddresses) {
                if (multipleAddresses == null) {
                    return Collections.emptyList();
                }

                return multipleAddresses.getAllAddresses().stream()
                        .map(QuorumServerView::getAddressString)
                        .collect(Collectors.toList());
            }

            private static String getAddressString(InetSocketAddress address) {
                if (address == null) {
                    return "";
                }
                return String.format("%s:%d", QuorumPeer.QuorumServer.delimitedHostString(address), address.getPort());
            }
       }

    }

    /**
     * Watch information aggregated by session. Returned Map contains:
     *   - "session_id_to_watched_paths": Map&lt;Long, Set&lt;String&gt;&gt; session ID -&gt; watched paths
     * @see DataTree#getWatches()
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
     *   - "path_to_session_ids": Map&lt;String, Set&lt;Long&gt;&gt; path -&gt; session IDs of sessions watching path
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

    /**
     * Returns the current phase of Zab protocol that peer is running.
     * It can be in one of these phases: ELECTION, DISCOVERY, SYNCHRONIZATION, BROADCAST
     */
    public static class ZabStateCommand extends CommandBase {

        public ZabStateCommand() {
            super(Arrays.asList("zabstate"), false);
        }

        @Override
        public CommandResponse run(ZooKeeperServer zkServer, Map<String, String> kwargs) {
            CommandResponse response = initializeResponse();
            if (zkServer instanceof QuorumZooKeeperServer) {
                QuorumPeer peer = ((QuorumZooKeeperServer) zkServer).self;
                QuorumPeer.ZabState zabState = peer.getZabState();
                QuorumVerifier qv = peer.getQuorumVerifier();

                QuorumPeer.QuorumServer voter = qv.getVotingMembers().get(peer.getId());
                boolean voting = (
                        voter != null
                                && voter.addr.equals(peer.getQuorumAddress())
                                && voter.electionAddr.equals(peer.getElectionAddress())
                );
                response.put("myid", zkServer.getConf().getServerId());
                response.put("is_leader", zkServer instanceof LeaderZooKeeperServer);
                response.put("quorum_address", peer.getQuorumAddress());
                response.put("election_address", peer.getElectionAddress());
                response.put("client_address", peer.getClientAddress());
                response.put("voting", voting);
                long lastProcessedZxid = zkServer.getZKDatabase().getDataTreeLastProcessedZxid();
                response.put("last_zxid", "0x" + ZxidUtils.zxidToString(lastProcessedZxid));
                response.put("zab_epoch", ZxidUtils.getEpochFromZxid(lastProcessedZxid));
                response.put("zab_counter", ZxidUtils.getCounterFromZxid(lastProcessedZxid));
                response.put("zabstate", zabState.name().toLowerCase());
            } else {
                response.put("voting", false);
                response.put("zabstate", "");
            }
            return response;
        }

    }

    private Commands() {
    }

}
