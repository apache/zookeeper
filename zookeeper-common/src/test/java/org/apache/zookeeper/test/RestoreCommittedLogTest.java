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
import java.util.List;
import java.util.LinkedList;

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.server.quorum.Leader.Proposal;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.junit.Assert;
import org.junit.Test;

/** After a replica starts, it should load commits in its committedLog list. 
 *  This test checks if committedLog != 0 after replica restarted.
 */
public class RestoreCommittedLogTest extends ZKTestCase implements  Watcher {
    private static final Logger LOG = Logger.getLogger(RestoreCommittedLogTest.class);
    private static String HOSTPORT = "127.0.0.1:" + PortAssignment.unique();
    private static final int CONNECTION_TIMEOUT = 3000;
    /**
     * test the purge
     * @throws Exception an exception might be thrown here
     */
    @Test
    public void testRestoreCommittedLog() throws Exception {
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        SyncRequestProcessor.setSnapCount(100);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        Assert.assertTrue("waiting for server being up ",
                ClientBase.waitForServerUp(HOSTPORT,CONNECTION_TIMEOUT));
        ZooKeeper zk = new ZooKeeper(HOSTPORT, CONNECTION_TIMEOUT, this);
        try {
            for (int i = 0; i< 2000; i++) {
                zk.create("/invalidsnap-" + i, new byte[0], Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT);
            }
        } finally {
            zk.close();
        }
        f.shutdown();
        zks.shutdown();
        Assert.assertTrue("waiting for server to shutdown",
                ClientBase.waitForServerDown(HOSTPORT, CONNECTION_TIMEOUT));

        // start server again
        zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        zks.startdata();
        LinkedList<Proposal> committedLog = zks.getZKDatabase().getCommittedLog();
        int logsize = committedLog.size();
        LOG.info("committedLog size = " + logsize);
        Assert.assertTrue("log size != 0", (logsize != 0));
        zks.shutdown();
    }

    public void process(WatchedEvent event) {
        // do nothing
    }

}
