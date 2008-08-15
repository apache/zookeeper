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

package org.apache.zookeeper.server.quorum;

import static org.apache.zookeeper.server.ServerConfig.getClientPort;
import static org.apache.zookeeper.server.quorum.QuorumPeerConfig.getElectionAlg;
import static org.apache.zookeeper.server.quorum.QuorumPeerConfig.getServerId;
import static org.apache.zookeeper.server.quorum.QuorumPeerConfig.getServers;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Logger;

import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.jmx.ZKMBeanInfo;
import org.apache.zookeeper.jmx.server.ConnectionBean;
import org.apache.zookeeper.jmx.server.DataTreeBean;
import org.apache.zookeeper.jmx.server.quorum.FollowerBean;
import org.apache.zookeeper.jmx.server.quorum.LeaderBean;
import org.apache.zookeeper.jmx.server.quorum.LeaderElectionBean;
import org.apache.zookeeper.jmx.server.quorum.LocalPeerBean;
import org.apache.zookeeper.jmx.server.quorum.QuorumBean;
import org.apache.zookeeper.jmx.server.quorum.RemotePeerBean;
import org.apache.zookeeper.jmx.server.quorum.ServerBean;
import org.apache.zookeeper.server.ManagedZooKeeperServerMain;
import org.apache.zookeeper.server.ManagedZooKeeperServer;
import org.apache.zookeeper.server.NIOServerCnxn;
import org.apache.zookeeper.server.ObservableNIOServerCnxn;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ServerConfig;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.ZooTrace;
import org.apache.zookeeper.server.util.ConnectionObserver;
import org.apache.zookeeper.server.util.ObserverManager;
import org.apache.zookeeper.server.util.QuorumPeerObserver;
import org.apache.zookeeper.server.util.ServerObserver;
import org.apache.zookeeper.server.util.ZooKeeperObserverManager;

/**
 * This class launches a replicated zookeeper server with JMX support
 * enabled. The users can connect to the server JVM and manage 
 * the server state (such as currently open client connections) and view runtime
 * statistics using one of existing GUI JMX consoles (jconsole, for example).
 * Please refer to the JDK vendor documentation for further information on how
 * to enable JMX support in the JVM.
 * <p>
 * The server provides following MBeans:
 * <ul>
 *  <li>Quorum MBean -- provides quorum runtime statistics, see {@link QuorumMXBean}.
 *  <li>Peer MBean -- provides information about quorum peers (local and remote),
 *  see {@link LocalPeerMXBean} and {@link RemotePeerMXBean}.
 *  <li>Leader election MBean -- provides runtime info on leader election protocol,
 *  see {@link LeaderElectionMXBean}
 *  <li>Zookeeper server MBean -- provides various configuraton data and runtime 
 *  statistics, see {@link ZooKeeperServerMXBean}
 *  <li>Data tree MBean -- provides runtime data tree statistics, see 
 *  {@link DataTreeMXBean}
 *  <li>Client connection MBean -- provides runtime statistics as well as 
 *  connection management operations, see {@link ConnectionMXBean}
 * </ul>
 * The client connection is a dynamic resource and therefore the connection
 * MBeans are dynamically created and destroyed as the clients connect to and 
 * disconnect from the server.
 */
public class ManagedQuorumPeerMain {
    
    private static final Logger LOG = Logger.getLogger(ManagedQuorumPeerMain.class);

    /**
     * To start the replicated server specify the configuration file name on the
     * command line.
     * @param args command line
     */
    public static void main(String[] args) {
        if (args.length == 2) {
            ManagedZooKeeperServerMain.main(args);
            return;
        }
        QuorumPeerConfig.parse(args);
        if (!QuorumPeerConfig.isStandalone()) {
            ZooKeeperObserverManager.setAsConcrete();
            runPeer(new QuorumPeer.Factory() {
                public QuorumPeer create(NIOServerCnxn.Factory cnxnFactory)
                        throws IOException {
                    
                    ManagedQuorumPeer peer = new ManagedQuorumPeer();
                    peer.setClientPort(ServerConfig.getClientPort());
                    peer.setDataDir(new File(ServerConfig.getDataDir()));
                    peer.setDataLogDir(new File(ServerConfig.getDataLogDir()));
                    peer.setQuorumPeers(QuorumPeerConfig.getServers());
                    peer.setElectionPort(QuorumPeerConfig.getElectionPort());
                    peer.setElectionType(QuorumPeerConfig.getElectionAlg());
                    peer.setMyid(QuorumPeerConfig.getServerId());
                    peer.setTickTime(QuorumPeerConfig.getTickTime());
                    peer.setInitLimit(QuorumPeerConfig.getInitLimit());
                    peer.setSyncLimit(QuorumPeerConfig.getSyncLimit());
                    peer.setCnxnFactory(cnxnFactory);
                    return peer;
                    
                }
                public NIOServerCnxn.Factory createConnectionFactory() throws IOException {
                    return new ObservableNIOServerCnxn.Factory(getClientPort());
                }
            });
        }else{
            // there is only server in the quorum -- run as standalone
            ManagedZooKeeperServerMain.main(args);
        }
    }
    
    public static void runPeer(QuorumPeer.Factory qpFactory) {
        try {
            QuorumStats.registerAsConcrete();
            QuorumPeer self = qpFactory.create(qpFactory.createConnectionFactory());
            self.start();
            self.join();
        } catch (Exception e) {
            LOG.fatal("Unexpected exception",e);
        }
        System.exit(2);
    }

}
