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

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.Assert;
import org.junit.Test;

/**
 * Standalone server tests.
 */
public class StandaloneTest extends QuorumPeerTestBase implements Watcher{
    protected static final Logger LOG =
        LoggerFactory.getLogger(StandaloneTest.class);    
      
    /**
     * Ensure that a single standalone server comes up when misconfigured
     * with a single server.# line in the configuration. This handles the
     * case of HBase, which configures zoo.cfg in this way. Maintain b/w
     * compatibility.
     * TODO remove in a future version (4.0.0 hopefully)
     */
    @Test
    public void testStandaloneQuorum() throws Exception {
        ClientBase.setupTestEnv();
        final int CLIENT_PORT_QP1 = PortAssignment.unique();        
        
        String quorumCfgSection =
            "server.1=127.0.0.1:" + (PortAssignment.unique())
            + ":" + (PortAssignment.unique()) + ";" + CLIENT_PORT_QP1 + "\n";
                    
        MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
        q1.start();
        try {
            Assert.assertTrue("waiting for server 1 being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP1,
                    CONNECTION_TIMEOUT));
    } finally {
        Assert.assertFalse("Error- MainThread started in Quorum Mode!",
                   q1.isQuorumPeerRunning());
        q1.shutdown();
        }
    }    
    
    /**
     * Verify that reconfiguration in standalone mode fails with
     * KeeperException.UnimplementedException.
     */
    @Test
    public void testStandaloneReconfigFails() throws Exception {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT = PortAssignment.unique();
        final String HOSTPORT = "127.0.0.1:" + CLIENT_PORT;

        File tmpDir = ClientBase.createTmpDir();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);

        ServerCnxnFactory f = ServerCnxnFactory.createFactory(CLIENT_PORT, -1);
        f.startup(zks);
        Assert.assertTrue("waiting for server being up ", ClientBase
                .waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT));

        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(HOSTPORT, CONNECTION_TIMEOUT, watcher);
        watcher.waitForConnected(CONNECTION_TIMEOUT);

        List<String> joiners = new ArrayList<String>();
        joiners.add("server.2=localhost:1234:1235;1236");
        // generate some transactions that will get logged
        try {
            zk.reconfig(joiners, null, null, -1, new Stat());
            Assert.fail("Reconfiguration in standalone should trigger " +
                        "UnimplementedException");
        } catch (KeeperException.UnimplementedException ex) {
            // expected
        }
        zk.close();

        zks.shutdown();
        f.shutdown();
        Assert.assertTrue("waiting for server being down ", ClientBase
                .waitForServerDown(HOSTPORT, CONNECTION_TIMEOUT));
    }
}
