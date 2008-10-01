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

package org.apache.zookeeper.server;

import static org.apache.zookeeper.server.ServerConfig.getClientPort;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Logger;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.jmx.server.ConnectionBean;
import org.apache.zookeeper.jmx.server.ConnectionMXBean;
import org.apache.zookeeper.jmx.server.DataTreeBean;
import org.apache.zookeeper.jmx.server.DataTreeMXBean;
import org.apache.zookeeper.jmx.server.ZooKeeperServerBean;
import org.apache.zookeeper.jmx.server.ZooKeeperServerMXBean;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.Util;

import org.apache.zookeeper.server.util.ConnectionObserver;
import org.apache.zookeeper.server.util.ObserverManager;
import org.apache.zookeeper.server.util.ServerObserver;

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
                svrBean = new ZooKeeperServerBean(server);
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

    public ManagedZooKeeperServer(FileTxnSnapLog logFactory, 
            int tickTime,DataTreeBuilder treeBuilder) throws IOException {
        super(logFactory, tickTime,treeBuilder);
        ObserverManager.getInstance().add(new ManagedServerObserver());
        ObserverManager.getInstance().add(new ManagedConnectionObserver());
    }

    public ManagedZooKeeperServer(FileTxnSnapLog logFactory,
            DataTreeBuilder treeBuilder) throws IOException {
        super(logFactory,treeBuilder);
        ObserverManager.getInstance().add(new ManagedServerObserver());
        ObserverManager.getInstance().add(new ManagedConnectionObserver());
    }
}
