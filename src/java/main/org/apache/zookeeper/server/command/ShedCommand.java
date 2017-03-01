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

import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.ServerCnxn;

import java.io.PrintWriter;
import java.util.HashSet;

public class ShedCommand extends AbstractFourLetterCommand {
    private final HashSet<ServerCnxn> filter;
    private final double shedProbability;

    public ShedCommand(PrintWriter pw, ServerCnxn sourceServerCnxn) {
        this(pw, sourceServerCnxn, 1.0);
    }

    public ShedCommand(PrintWriter pw, ServerCnxn sourceServerCnxn, double shedProbability) {
        super(pw, sourceServerCnxn);
        this.shedProbability = shedProbability;
        this.filter = new HashSet<>();

        // Do not close the connection issuing the command
        filter.add(sourceServerCnxn);
    }

    @Override
    public void commandRun() {
        if (zkServer == null) {
            pw.print("server is null");
        } else {
            pw.print("Shedding clients (" + (shedProbability == 1.0 ? "all" : String.valueOf(shedProbability)) + ")");
            zkServer.getServerCnxnFactory().closeSome(filter, shedProbability);
        }
        pw.println();
    }
}
