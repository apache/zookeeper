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
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.ServerStats;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.quorum.Leader;
import org.apache.zookeeper.server.quorum.LeaderZooKeeperServer;
import org.apache.zookeeper.server.util.OSMXBean;

public class MonitorCommand extends AbstractFourLetterCommand {

    MonitorCommand(PrintWriter pw, ServerCnxn serverCnxn) {
        super(pw, serverCnxn);
    }

    @Override
    public void commandRun() {
        if (!isZKServerRunning()) {
            pw.println(ZK_NOT_SERVING);
            return;
        }
        ServerStats stats = zkServer.serverStats();

        print("version", Version.getFullVersion());

        print("server_state", stats.getServerState());

        ServerMetrics.getMetrics()
                    .getMetricsProvider()
                    .dump(
                    (metric, value) -> {
                        if (value == null) {
                            print(metric, null);
                        } else if (value instanceof Long
                                || value instanceof Integer) {
                            print(metric, ((Number) value).longValue());
                        } else if (value instanceof Number) {
                            print(metric, ((Number) value).doubleValue());                        
                        } else {
                            print(metric, value.toString());
                        }
                    });
    }

    private void print(String key, long number) {
        print(key, "" + number);
    }
    
    private void print(String key, double number) {
        print(key, "" + number);
    }

    private void print(String key, String value) {
        pw.print("zk_");
        pw.print(key);
        pw.print("\t");
        pw.println(value);
    }

}
