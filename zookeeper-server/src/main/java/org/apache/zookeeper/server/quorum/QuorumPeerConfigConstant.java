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

public class QuorumPeerConfigConstant {
    public static final String DATA_DIR = "dataDir";
    public static final String DATA_LOG_DIR = "dataLogDir";
    public static final String CLIENT_PORT = "clientPort";
    public static final String LOCAL_SESSIONS_ENABLE = "localSessionsEnabled";
    public static final String LOCAL_SESSIONS_UPGRADING_ENABLED = "localSessionsUpgradingEnabled";
    public static final String CLIENT_PORT_ADDRESS = "clientPortAddress";
    public static final String SECURE_CLIENT_PORT = "secureClientPort";
    public static final String SECURE_CLIENT_PORT_ADDRESS = "secureClientPortAddress";
    public static final String OBSERVER_MASTER_PORT = "observerMasterPort";
    public static final String CLIENT_PORT_LISTEN_BACK_LOG = "clientPortListenBacklog";
    public static final String TICK_TIME = "tickTime";
    public static final String MAX_CLIENT_CNXNS = "maxClientCnxns";
    public static final String MIN_SESSION_TIMEOUT = "minSessionTimeout";
    public static final String MAX_SESSION_TIMEOUT = "maxSessionTimeout";
    public static final String INIT_LIMIT = "initLimit";
    public static final String SYNC_LIMIT = "syncLimit";
    public static final String CONNECT_TO_LEARNER_MASTER_LIMIT = "connectToLearnerMasterLimit";
    public static final String ELECTION_ALG = "electionAlg";
    public static final String QUORUM_LISTEN_ON_ALL_IPS = "quorumListenOnAllIPs";
    public static final String PEER_TYPE = "peerType";
    public static final String SYNC_ENABLED = "syncEnabled";
    public static final String DYNAMIC_CONFIG_FILE = "dynamicConfigFile";
    public static final String AUTO_PURGE_SNAP_RETAIN_COUNT = "autopurge.snapRetainCount";
    public static final String AUTO_PURGE_INTERVAL = "autopurge.purgeInterval";
    public static final String STANDALONE_ENABLED = "standaloneEnabled";
    public static final String RECONFIG_ENABLED = "reconfigEnabled";
    public static final String SSL_QUORUM = "sslQuorum";
    public static final String PORT_UNIFICATION = "portUnification";
    public static final String SSL_QUORUM_RELOAD_CERT_FILES = "sslQuorumReloadCertFiles";

    public static final String QUORUM_CNXN_THREADS_SIZE = "quorum.cnxn.threads.size";

    /*
     * metrics
     */
    public static final String METRICS_PROVIDER_CLASS_NAME = "metricsProvider.className";
    public static final String METRICS_PROVIDER_CONFIG = "metricsProvider.";

    public static final String MULTI_ADDRESS_ENABLED = "multiAddress.enabled";
    public static final String MULTI_ADDRESS_REACH_ABILITY_CHECK_TIMEOUTMS = "multiAddress.reachabilityCheckTimeoutMs";
    public static final String MULTI_ADDRESS_REACH_ABILITY_CHECK_ENABLED = "multiAddress.reachabilityCheckEnabled";

    public static final String ORACLE_PATH = "oraclePath";

}
