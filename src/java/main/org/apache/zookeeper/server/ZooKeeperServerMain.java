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

import java.io.File;
import java.io.IOException;

import org.apache.log4j.Logger;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.ZooKeeperServer.BasicDataTreeBuilder;

/**
 * This class starts and runs a standalone ZooKeeperServer.
 */
public class ZooKeeperServerMain {

    private static final Logger LOG = Logger.getLogger(ZooKeeperServerMain.class);

    /*
     * Start up the ZooKeeper server.
     *
     * @param args the port and data directory
     */
    public static void main(String[] args) {
        ServerConfig.parse(args);
        runStandalone(new ZooKeeperServer.Factory() {
            public NIOServerCnxn.Factory createConnectionFactory() throws IOException {
                return new NIOServerCnxn.Factory(ServerConfig.getClientPort());
            }

            public ZooKeeperServer createServer() throws IOException {
                // create a file logger url from the command line args
                ZooKeeperServer zks = new ZooKeeperServer();
                zks.setClientPort(ServerConfig.getClientPort());

               FileTxnSnapLog ftxn = new FileTxnSnapLog(new 
                       File(ServerConfig.getDataLogDir()),
                        new File(ServerConfig.getDataDir()));
               zks.setTxnLogFactory(ftxn);
               return zks;
            }
        });
    }

    public static void runStandalone(ZooKeeperServer.Factory factory) {
        try {
            // Note that this thread isn't going to be doing anything else,
            // so rather than spawning another thread, we will just call
            // run() in this thread.
            ServerStats.registerAsConcrete();
            ZooKeeperServer zk = factory.createServer();
            zk.startup();
            NIOServerCnxn.Factory t = factory.createConnectionFactory();
            t.setZooKeeperServer(zk);
            t.join();
            if (zk.isRunning()) {
                zk.shutdown();
            }
        } catch (Exception e) {
            LOG.fatal("Unexpected exception",e);
        }
        System.exit(0);
    }
}
