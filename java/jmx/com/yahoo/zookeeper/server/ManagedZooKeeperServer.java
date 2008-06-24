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

package com.yahoo.zookeeper.server;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Logger;

import com.yahoo.zookeeper.jmx.MBeanRegistry;
import com.yahoo.zookeeper.jmx.server.ConnectionBean;
import com.yahoo.zookeeper.jmx.server.DataTreeBean;
import com.yahoo.zookeeper.jmx.server.ZooKeeperServerBean;
import com.yahoo.zookeeper.server.util.ConnectionObserver;
import com.yahoo.zookeeper.server.util.ObserverManager;
import com.yahoo.zookeeper.server.util.ServerObserver;
import com.yahoo.zookeeper.server.util.ZooKeeperObserverManager;

import static com.yahoo.zookeeper.server.ServerConfig.getClientPort;

/**
 * This class launches a standalone zookeeper server with JMX support
 * enabled. The users can connect to the server JVM and manage the server state 
 * (such as currently open client connections) and view runtime statistics using 
 * one of existing GUI JMX consoles (jconsole, for example). Please refer to 
 * the JDK vendor documentation for further information on how to enable JMX 
 * support in the JVM.
 * <p>
 * The server provides following MBeans:
 * <ul>
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
public class ManagedZooKeeperServer extends ObservableZooKeeperServer {
    private static final Logger LOG = Logger.getLogger(ManagedZooKeeperServer.class);

    private ZooKeeperServerBean svrBean;

    private class ManagedServerObserver implements ServerObserver {
        private DataTreeBean dataTreeBean;

        public void onShutdown(ZooKeeperServer server) {
            MBeanRegistry.getInstance().unregister(dataTreeBean);
            MBeanRegistry.getInstance().unregister(svrBean);
        }

        public void onStartup(ZooKeeperServer server) {
            try {
                svrBean = new ZooKeeperServerBean();
                MBeanRegistry.getInstance().register(svrBean, null);
                dataTreeBean = new DataTreeBean(server.dataTree);
                MBeanRegistry.getInstance().register(dataTreeBean, svrBean);
            } catch (Exception e) {
                LOG.warn("Failed to register Standalone ZooKeeperServerMBean "
                                + e.getMessage());
            }
        }
    }

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
            ConnectionBean b=new ConnectionBean(sc,ManagedZooKeeperServer.this);
            map.put(sc, b);
            ZooTrace.logTraceMessage(LOG, ZooTrace.JMX_TRACE_MASK,
                    "Registering new ConnectionBean: "+b);
            MBeanRegistry.getInstance().register(b, svrBean);            
        }
    }

    public ManagedZooKeeperServer(File dataDir, File dataLogDir, 
            int tickTime,DataTreeBuilder treeBuilder) throws IOException {
        super(dataDir, dataLogDir, tickTime,treeBuilder);
        ObserverManager.getInstance().add(new ManagedServerObserver());
        ObserverManager.getInstance().add(new ManagedConnectionObserver());
    }

    public ManagedZooKeeperServer(DataTreeBuilder treeBuilder) throws IOException {
        super(treeBuilder);
        ObserverManager.getInstance().add(new ManagedServerObserver());
        ObserverManager.getInstance().add(new ManagedConnectionObserver());
    }

    /**
     * To start the server specify the client port number and the data directory
     * on the command line.
     * @see ServerConfig#parse(String[])
     * @param args command line parameters.
     */
    public static void main(String[] args) {
        ServerConfig.parse(args);
        ZooKeeperObserverManager.setAsConcrete();
        runStandalone(new Factory() {
            public NIOServerCnxn.Factory createConnectionFactory()throws IOException {
                return new ObservableNIOServerCnxn.Factory(getClientPort());
            }
            public ZooKeeperServer createServer() throws IOException {
                // TODO: we may want to build an observable/managed data tree here instead
                return new ManagedZooKeeperServer(new BasicDataTreeBuilder());
            }
        });
    }

}
