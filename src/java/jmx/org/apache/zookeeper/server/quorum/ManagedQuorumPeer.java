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

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Logger;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.jmx.ZKMBeanInfo;
import org.apache.zookeeper.jmx.server.ConnectionBean;
import org.apache.zookeeper.jmx.server.ConnectionMXBean;
import org.apache.zookeeper.jmx.server.DataTreeBean;
import org.apache.zookeeper.jmx.server.DataTreeMXBean;
import org.apache.zookeeper.jmx.server.ZooKeeperServerMXBean;
import org.apache.zookeeper.jmx.server.quorum.FollowerBean;
import org.apache.zookeeper.jmx.server.quorum.LeaderBean;
import org.apache.zookeeper.jmx.server.quorum.LeaderElectionBean;
import org.apache.zookeeper.jmx.server.quorum.LeaderElectionMXBean;
import org.apache.zookeeper.jmx.server.quorum.LocalPeerBean;
import org.apache.zookeeper.jmx.server.quorum.LocalPeerMXBean;
import org.apache.zookeeper.jmx.server.quorum.QuorumBean;
import org.apache.zookeeper.jmx.server.quorum.QuorumMXBean;
import org.apache.zookeeper.jmx.server.quorum.RemotePeerBean;
import org.apache.zookeeper.jmx.server.quorum.RemotePeerMXBean;
import org.apache.zookeeper.jmx.server.quorum.ServerBean;
import org.apache.zookeeper.server.NIOServerCnxn;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.ZooTrace;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.Util;
import org.apache.zookeeper.server.util.ConnectionObserver;
import org.apache.zookeeper.server.util.ObserverManager;
import org.apache.zookeeper.server.util.QuorumPeerObserver;
import org.apache.zookeeper.server.util.ServerObserver;

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
public class ManagedQuorumPeer extends ObservableQuorumPeer {
    private static final Logger LOG = Logger.getLogger(ManagedQuorumPeer.class);

    private QuorumBean quorumBean;
    private LocalPeerBean localPeerBean;
    private ServerBean svrBean;
    private LeaderElectionBean leBean;
    
    // tracking state of the quorum peer
    private class ManagedQuorumPeerObserver implements QuorumPeerObserver {
        public void onFollowerShutdown(QuorumPeer qp, Follower follower) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.JMX_TRACE_MASK,
                    "Follower shutdown "+follower);
            MBeanRegistry.getInstance().unregister(svrBean);
            svrBean=null;
        }

        public void onFollowerStarted(QuorumPeer qp, Follower newFollower) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.JMX_TRACE_MASK,
                    "Follower started "+newFollower);
            MBeanRegistry.getInstance().unregister(leBean);
            leBean=null;
            svrBean=new FollowerBean();
            MBeanRegistry.getInstance().register(svrBean, localPeerBean);
        }

        public void onLeaderElectionStarted(QuorumPeer qp) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.JMX_TRACE_MASK,
                    "Running leader election protocol...");
            leBean=new LeaderElectionBean();
            MBeanRegistry.getInstance().register(leBean, localPeerBean);            
        }

        public void onLeaderShutdown(QuorumPeer qp, Leader leader) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.JMX_TRACE_MASK,
                    "Leader shutdown "+leader);
            MBeanRegistry.getInstance().unregister(svrBean);
            svrBean=null;
        }

        public void onLeaderStarted(QuorumPeer qp, Leader newLeader) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.JMX_TRACE_MASK,
                    "Leader started "+newLeader);
            MBeanRegistry.getInstance().unregister(leBean);
            leBean=null;
            svrBean=new LeaderBean();
            MBeanRegistry.getInstance().register(svrBean, localPeerBean);
        }

        public void onShutdown(QuorumPeer qp) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.JMX_TRACE_MASK,
                    "Shutting down quorum peer");
            MBeanRegistry.getInstance().unregisterAll();
        }

        public void onStartup(QuorumPeer qp) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.JMX_TRACE_MASK,
                    "Starting quorum peer");
            quorumBean=new QuorumBean(qp);
            MBeanRegistry.getInstance().register(quorumBean, null);
            for(QuorumServer s: qp.quorumPeers.values()){
                ZKMBeanInfo p;
                if(qp.getId()==s.id)
                    p=localPeerBean=new LocalPeerBean(qp);
                else
                    p=new RemotePeerBean(s);
                MBeanRegistry.getInstance().register(p, quorumBean);
            }
        }
    }

    // on client connect/disconnect this observer will register/unregister 
    // a connection MBean with the MBean server
    private class ManagedConnectionObserver implements ConnectionObserver {
        private ConcurrentHashMap<ServerCnxn,ConnectionBean> map=
            new ConcurrentHashMap<ServerCnxn,ConnectionBean>();

        public void onClose(ServerCnxn sc) {
            ConnectionBean b=map.remove(sc);
            if(b!=null){
                ZooTrace.logTraceMessage(LOG, ZooTrace.JMX_TRACE_MASK,
                        "Un-registering a ConnectionBean: "+b);
                MBeanRegistry.getInstance().unregister(b);
            }
        }

        public void onNew(ServerCnxn sc) {
            ConnectionBean b=new ConnectionBean(sc,getActiveServer());
            map.put(sc, b);
            ZooTrace.logTraceMessage(LOG, ZooTrace.JMX_TRACE_MASK,
                    "Registering new ConnectionBean: "+b);
            MBeanRegistry.getInstance().register(b, localPeerBean);            
        }
    }

    // this observer tracks the state of the zookeeper server 
    private class ManagedServerObserver implements ServerObserver {
        private DataTreeBean dataTreeBean;

        public void onShutdown(ZooKeeperServer server) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.JMX_TRACE_MASK,
                    "Shutdown zookeeper server: "+server);
            MBeanRegistry.getInstance().unregister(dataTreeBean);
            dataTreeBean=null;
        }

        public void onStartup(ZooKeeperServer server) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.JMX_TRACE_MASK,
                    "Started new zookeeper server: "+server);
            try {
                dataTreeBean = new DataTreeBean(server.dataTree);
                MBeanRegistry.getInstance().register(dataTreeBean, svrBean);
            } catch (Exception e) {
                LOG.warn("Failed to register Standalone ZooKeeperServerMBean "
                                + e.getMessage());
            }
        }
    }

    private void setupObservers(){
        ObserverManager.getInstance().add(new ManagedQuorumPeerObserver());
        ObserverManager.getInstance().add(new ManagedServerObserver());        
        ObserverManager.getInstance().add(new ManagedConnectionObserver());        
    }
    
    public ManagedQuorumPeer() {
        super();
        setupObservers();
    }

    public ManagedQuorumPeer(HashMap<Long,QuorumServer> quorumPeers, File dataDir, File dataLogDir, int clientPort, int electionAlg, long myid, int tickTime, int initLimit,
                                int syncLimit) throws IOException {
        super(quorumPeers, dataDir, dataLogDir, clientPort, electionAlg, myid, tickTime, initLimit, 
syncLimit);
        setupObservers();
    }

    public ManagedQuorumPeer(HashMap<Long,QuorumServer> quorumPeers, File dataDir, File dataLogDir, int electionType, long myid, int tickTime, int initLimit, int syncLimit,
                                NIOServerCnxn.Factory cnxnFactory) throws IOException {
        super(quorumPeers, dataDir, dataLogDir, electionType, myid, tickTime, initLimit, syncLimit, cnxnFactory);
        setupObservers();
    }

}
