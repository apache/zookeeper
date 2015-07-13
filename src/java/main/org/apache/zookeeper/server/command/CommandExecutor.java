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

import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;

public class CommandExecutor {
    /**
     * This class decides which command to be executed and then executes
     */
    public boolean execute(ServerCnxn serverCnxn, PrintWriter pwriter,
            final int commandCode, ZooKeeperServer zkServer, ServerCnxnFactory factory) {
        AbstractFourLetterCommand command = getCommand(serverCnxn,pwriter, commandCode);

        if (command == null) {
            return false;
        }

        command.setZkServer(zkServer);
        command.setFactory(factory);
        command.start();
        return true;
    }

    private AbstractFourLetterCommand getCommand(ServerCnxn serverCnxn,
            PrintWriter pwriter, final int commandCode) {
        AbstractFourLetterCommand command = null;
        if (commandCode == FourLetterCommands.ruokCmd) {
            command = new RuokCommand(pwriter, serverCnxn);
        } else if (commandCode == FourLetterCommands.getTraceMaskCmd) {
            command = new TraceMaskCommand(pwriter, serverCnxn);
        } else if (commandCode == FourLetterCommands.enviCmd) {
            command = new EnvCommand(pwriter, serverCnxn);
        } else if (commandCode == FourLetterCommands.confCmd) {
            command = new ConfCommand(pwriter, serverCnxn);
        } else if (commandCode == FourLetterCommands.srstCmd) {
            command = new StatResetCommand(pwriter, serverCnxn);
        } else if (commandCode == FourLetterCommands.crstCmd) {
            command = new CnxnStatResetCommand(pwriter, serverCnxn);
        } else if (commandCode == FourLetterCommands.dirsCmd) {
            command = new DirsCommand(pwriter, serverCnxn);
        } else if (commandCode == FourLetterCommands.dumpCmd) {
            command = new DumpCommand(pwriter, serverCnxn);
        } else if (commandCode == FourLetterCommands.statCmd
                || commandCode == FourLetterCommands.srvrCmd) {
            command = new StatCommand(pwriter, serverCnxn, commandCode);
        } else if (commandCode == FourLetterCommands.consCmd) {
            command = new ConsCommand(pwriter, serverCnxn);
        } else if (commandCode == FourLetterCommands.wchpCmd
                || commandCode == FourLetterCommands.wchcCmd
                || commandCode == FourLetterCommands.wchsCmd) {
            command = new WatchCommand(pwriter, serverCnxn, commandCode);
        } else if (commandCode == FourLetterCommands.mntrCmd) {
            command = new MonitorCommand(pwriter, serverCnxn);
        } else if (commandCode == FourLetterCommands.isroCmd) {
            command = new IsroCommand(pwriter, serverCnxn);
        }
        return command;
    }

}
