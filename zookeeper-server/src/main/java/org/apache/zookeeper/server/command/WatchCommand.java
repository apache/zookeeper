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

import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.ServerCnxn;

public class WatchCommand extends AbstractFourLetterCommand {
    int len = 0;
    public WatchCommand(PrintWriter pw, ServerCnxn serverCnxn, int len) {
        super(pw, serverCnxn);
        this.len = len;
    }

    @Override
    public void commandRun() {
        if (!isZKServerRunning()) {
            pw.println(ZK_NOT_SERVING);
        } else {
            DataTree dt = zkServer.getZKDatabase().getDataTree();
            if (len == FourLetterCommands.wchsCmd) {
                dt.dumpWatchesSummary(pw);
            } else if (len == FourLetterCommands.wchpCmd) {
                dt.dumpWatches(pw, true);
            } else {
                dt.dumpWatches(pw, false);
            }
            pw.println();
        }
    }
}
