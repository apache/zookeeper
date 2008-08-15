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

import org.apache.log4j.Logger;
import org.apache.zookeeper.jmx.server.ConnectionMXBean;
import org.apache.zookeeper.jmx.server.DataTreeMXBean;
import org.apache.zookeeper.jmx.server.ZooKeeperServerMXBean;
import org.apache.zookeeper.server.util.ZooKeeperObserverManager;

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
public class ManagedZooKeeperServerMain extends ZooKeeperServerMain {
    
    /**
     * To start the server specify the client port number and the data directory
     * on the command line.
     * @see ServerConfig#parse(String[])
     * @param args command line parameters.
     */
    public static void main(String[] args) {
        ServerConfig.parse(args);
        ZooKeeperObserverManager.setAsConcrete();
        runStandalone(new ZooKeeperServer.Factory() {
            public NIOServerCnxn.Factory createConnectionFactory()throws IOException {
                return new ObservableNIOServerCnxn.Factory(getClientPort());
            }
            public ZooKeeperServer createServer() throws IOException {
                ManagedZooKeeperServer zks = new ManagedZooKeeperServer();
                zks.setDataDir(new File(ServerConfig.getDataDir()));
                zks.setDataLogDir(new File(ServerConfig.getDataLogDir()));
                zks.setClientPort(ServerConfig.getClientPort());
                // TODO: we may want to build an observable/managed data tree here instead
                zks.setTreeBuilder(new ZooKeeperServer.BasicDataTreeBuilder());
                return zks;
            }
        });
    }

}
