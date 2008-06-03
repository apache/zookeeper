/**
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yahoo.zookeeper.server.quorum;

import static com.yahoo.zookeeper.server.ServerConfig.getClientPort;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Logger;

import com.yahoo.zookeeper.jmx.MBeanRegistry;
import com.yahoo.zookeeper.jmx.ZKMBeanInfo;
import com.yahoo.zookeeper.jmx.server.ConnectionBean;
import com.yahoo.zookeeper.jmx.server.DataTreeBean;
import com.yahoo.zookeeper.jmx.server.quorum.FollowerBean;
import com.yahoo.zookeeper.jmx.server.quorum.LeaderBean;
import com.yahoo.zookeeper.jmx.server.quorum.LeaderElectionBean;
import com.yahoo.zookeeper.jmx.server.quorum.LocalPeerBean;
import com.yahoo.zookeeper.jmx.server.quorum.QuorumBean;
import com.yahoo.zookeeper.jmx.server.quorum.RemotePeerBean;
import com.yahoo.zookeeper.jmx.server.quorum.ServerBean;
import com.yahoo.zookeeper.server.ManagedZooKeeperServer;
import com.yahoo.zookeeper.server.NIOServerCnxn;
import com.yahoo.zookeeper.server.ObservableNIOServerCnxn;
import com.yahoo.zookeeper.server.ServerCnxn;
import com.yahoo.zookeeper.server.ZooKeeperServer;
import com.yahoo.zookeeper.server.ZooTrace;
import com.yahoo.zookeeper.server.util.ConnectionObserver;
import com.yahoo.zookeeper.server.util.ObserverManager;
import com.yahoo.zookeeper.server.util.QuorumPeerObserver;
import com.yahoo.zookeeper.server.util.ServerObserver;
import com.yahoo.zookeeper.server.util.ZooKeeperObserverManager;

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
            for(QuorumServer s: qp.quorumPeers){
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
    
    public ManagedQuorumPeer(ArrayList<QuorumServer> quorumPeers, File dataDir,
            File dataLogDir,int electionAlg, int electionPort,long myid,    int tickTime, 
            int initLimit, int syncLimit,NIOServerCnxn.Factory cnxnFactory) 
                throws IOException {
        super(quorumPeers, dataDir, dataLogDir,electionAlg, electionPort,myid,
                tickTime, initLimit, syncLimit,cnxnFactory);
        setupObservers();
    }

    public ManagedQuorumPeer(NIOServerCnxn.Factory cnxnFactory) throws IOException {
        super(cnxnFactory);
        setupObservers();
    }

    /**
     * To start the replicated server specify the configuration file name on the
     * command line.
     * @param args command line
     */
    public static void main(String[] args) {
        if (args.length == 2) {
            ManagedZooKeeperServer.main(args);
            return;
        }
        QuorumPeerConfig.parse(args);
        if (!QuorumPeerConfig.isStandalone()) {
            ZooKeeperObserverManager.setAsConcrete();
            runPeer(new QuorumPeer.Factory() {
                public QuorumPeer create(NIOServerCnxn.Factory cnxnFactory)
                        throws IOException {
                    return new ManagedQuorumPeer(cnxnFactory);
                }
                public NIOServerCnxn.Factory createConnectionFactory() throws IOException {
                    return new ObservableNIOServerCnxn.Factory(getClientPort());
                }
            });
        }else{
            // there is only server in the quorum -- run as standalone
            ManagedZooKeeperServer.main(args);
        }
    }
}
