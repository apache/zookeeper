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

import java.io.File;
import java.io.IOException;

import org.apache.log4j.Logger;
import org.apache.zookeeper.server.NIOServerCnxn;
import org.apache.zookeeper.server.ServerConfig;
import org.apache.zookeeper.server.ZooKeeperServerMain;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;

/**
 * 
 * <h2>Configuration file</h2>
 *
 * When the main() method of this class is used to start the program, the file
 * "zoo.cfg" in the current directory will be used to obtain configuration
 * information. zoo.cfg is a Properties file, so keys and values are separated
 * by equals (=) and the key/value pairs are separated by new lines. The
 * following keys are used in the configuration file:
 * <ol>
 * <li>dataDir - The directory where the zookeeper data is stored.</li>
 * <li>clientPort - The port used to communicate with clients.</li>
 * <li>tickTime - The duration of a tick in milliseconds. This is the basic
 * unit of time in zookeeper.</li>
 * <li>initLimit - The maximum number of ticks that a follower will wait to
 * initially synchronize with a leader.</li>
 * <li>syncLimit - The maximum number of ticks that a follower will wait for a
 * message (including heartbeats) from the leader.</li>
 * <li>server.<i>id</i> - This is the host:port that the server with the
 * given id will use for the quorum protocol.</li>
 * </ol>
 * In addition to the zoo.cfg file. There is a file in the data directory called
 * "myid" that contains the server id as an ASCII decimal value.
 * 
 */
public class QuorumPeerMain {
    
    private static final Logger LOG = Logger.getLogger(QuorumPeerMain.class);

    /**
     * To start the replicated server specify the configuration file name on the
     * command line.
     * @param args command line
     */
    public static void main(String[] args) {
        if (args.length == 2) {
            ZooKeeperServerMain.main(args);
            return;
        }
        QuorumPeerConfig.parse(args);
        if (!QuorumPeerConfig.isStandalone()) {
            runPeer(new QuorumPeer.Factory() {
                public QuorumPeer create(NIOServerCnxn.Factory cnxnFactory) throws IOException {
                    QuorumPeer peer = new QuorumPeer();
                    peer.setClientPort(ServerConfig.getClientPort());
                    peer.setTxnFactory(new FileTxnSnapLog(
                                new File(QuorumPeerConfig.getDataLogDir()), 
                                new File(QuorumPeerConfig.getDataDir())));
                    peer.setQuorumPeers(QuorumPeerConfig.getServers());
                    peer.setElectionType(QuorumPeerConfig.getElectionAlg());
                    peer.setMyid(QuorumPeerConfig.getServerId());
                    peer.setTickTime(QuorumPeerConfig.getTickTime());
                    peer.setInitLimit(QuorumPeerConfig.getInitLimit());
                    peer.setSyncLimit(QuorumPeerConfig.getSyncLimit());
                    peer.setCnxnFactory(cnxnFactory);
                    return peer;
                }
                public NIOServerCnxn.Factory createConnectionFactory() throws IOException {
                    return new NIOServerCnxn.Factory(getClientPort());
                }
            });
        }else{
            // there is only server in the quorum -- run as standalone
            ZooKeeperServerMain.main(args);
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
