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

package org.apache.zookeeper.test;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

import org.apache.log4j.Logger;

import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.NIOServerCnxn;
import org.apache.zookeeper.server.ServerStats;
import org.apache.zookeeper.server.ZooKeeperServer;

public abstract class ClientBase extends TestCase {
    protected static final Logger LOG = Logger.getLogger(ClientBase.class);
    protected static String hostPort = "127.0.0.1:33221";
    protected static final int CONNECTION_TIMEOUT = 30000;
    protected NIOServerCnxn.Factory f = null;
    protected File tmpDir = null;
    protected static File baseTest =
        new File(System.getProperty("build.test.dir", "build"));

    public ClientBase() {
        super();
    }

    public ClientBase(String name) {
        super(name);
    }

    protected void setUp() throws Exception {
        LOG.info("Client test setup");
        tmpDir = File.createTempFile("test", ".junit", baseTest);
        tmpDir = new File(tmpDir + ".dir");
        tmpDir.mkdirs();
        ServerStats.registerAsConcrete();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        f = new NIOServerCnxn.Factory(33221);
        f.startup(zks);
        Thread.sleep(5000);
        LOG.info("Client test setup finished");
    }

    protected void tearDown() throws Exception {
        LOG.info("Clent test shutdown");
        if (f != null) {
            f.shutdown();
        }
        if (tmpDir != null) {
            recursiveDelete(tmpDir);
        }
        ServerStats.unregister();
        LOG.info("Client test shutdown finished");
    }

    private static void recursiveDelete(File d) {
        if (d.isDirectory()) {
            File children[] = d.listFiles();
            for (File f : children) {
                recursiveDelete(f);
            }
        }
        d.delete();
    }

}