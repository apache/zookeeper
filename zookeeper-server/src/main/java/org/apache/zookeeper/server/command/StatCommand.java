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

package org.apache.zookeeper.server.command;

import java.io.PrintWriter;

import org.apache.zookeeper.Version;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ServerStats;
import org.apache.zookeeper.server.quorum.Leader;
import org.apache.zookeeper.server.quorum.LeaderZooKeeperServer;
import org.apache.zookeeper.server.quorum.BufferStats;
import org.apache.zookeeper.server.quorum.ReadOnlyZooKeeperServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StatCommand extends AbstractFourLetterCommand {
    private static final Logger LOG = LoggerFactory
            .getLogger(AbstractFourLetterCommand.class);
    private int len;
    public StatCommand(PrintWriter pw, ServerCnxn serverCnxn, int len) {
        super(pw, serverCnxn);
        this.len = len;
    }

    @Override
    public void commandRun() {
        if (!isZKServerRunning()) {
            pw.println(ZK_NOT_SERVING);
        } else {
            pw.print("Zookeeper version: ");
            pw.println(Version.getFullVersion());
            if (zkServer instanceof ReadOnlyZooKeeperServer) {
                pw.println("READ-ONLY mode; serving only read-only clients");
            }
            if (len == FourLetterCommands.statCmd) {
                LOG.info("Stat command output");
                pw.println("Clients:");
                for(ServerCnxn c : factory.getConnections()){
                    c.dumpConnectionInfo(pw, true);
                    pw.println();
                }
                pw.println();
            }
            ServerStats serverStats = zkServer.serverStats();
            pw.print(serverStats.toString());
            pw.print("Node count: ");
            pw.println(zkServer.getZKDatabase().getNodeCount());
            if (serverStats.getServerState().equals("leader")) {
                Leader leader = ((LeaderZooKeeperServer)zkServer).getLeader();
                BufferStats proposalStats = leader.getProposalStats();
                pw.printf("Proposal sizes last/min/max: %s%n", proposalStats.toString());
            }
        }
    }
}
