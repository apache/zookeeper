/*
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

import java.io.IOException;
import java.io.PrintWriter;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Set of threads for command ports. All the 4 letter commands are run via a
 * thread. Each class maps to a corresponding 4 letter command. CommandThread is
 * the abstract class from which all the others inherit.
 */
public abstract class AbstractFourLetterCommand {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractFourLetterCommand.class);

    public static final String ZK_NOT_SERVING = "This ZooKeeper instance is not currently serving requests";

    protected PrintWriter pw;
    protected ServerCnxn serverCnxn;
    protected ZooKeeperServer zkServer;
    protected ServerCnxnFactory factory;

    public AbstractFourLetterCommand(PrintWriter pw, ServerCnxn serverCnxn) {
        this.pw = pw;
        this.serverCnxn = serverCnxn;
    }

    public void start() {
        run();
    }

    public void run() {
        try {
            commandRun();
        } catch (IOException ie) {
            LOG.error("Error in running command ", ie);
        } finally {
            serverCnxn.cleanupWriterSocket(pw);
        }
    }

    public void setZkServer(ZooKeeperServer zkServer) {
        this.zkServer = zkServer;
    }

    /**
     * @return true if the server is running, false otherwise.
     */
    boolean isZKServerRunning() {
        return zkServer != null && zkServer.isRunning();
    }

    public void setFactory(ServerCnxnFactory factory) {
        this.factory = factory;
    }

    public abstract void commandRun() throws IOException;

}
