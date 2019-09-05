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

package org.apache.zookeeper;

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.apache.zookeeper.test.ClientBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class JuteMaxBufferTest extends QuorumPeerTestBase {
    private MainThread mt;

    @Before
    public void setup() throws Exception {
        // Request size for 100 nodes in this test class is 6197 bytes
        System.setProperty(ZKConfig.JUTE_MAXBUFFER, Integer.toString(6197));
    }

    /**
     * ZOOKEEPER-3496
     */
    @Test
    public void testServerAllowsTransactionMoreThanMaxBufferSize() throws Exception {
        // in this test case, multi operation request size is 6196 bytes
        int clientPort = PortAssignment.unique();
        String quorumCfgSection = "server.1=127.0.0.1:" + (PortAssignment.unique()) + ":"
                + (PortAssignment.unique()) + ":participant;" + clientPort + "\n";

        mt = new MainThread(1, clientPort, quorumCfgSection, false);
        mt.start();
        Assert.assertTrue("waiting for server 1 being up",
                ClientBase.waitForServerUp("127.0.0.1:" + clientPort, CONNECTION_TIMEOUT));

        ZooKeeper zk = ClientBase.createZKClient("127.0.0.1:" + clientPort);
        String parent = "/parent";
        zk.create(parent, "".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.addAuthInfo("digest", "pat:test".getBytes());
        List<Op> ops = new ArrayList<Op>();
        int numberOfNodes = 100;
        for (int i = 0; i < numberOfNodes; i++) {
            ops.add(Op.create(parent + "/child" + i, ("data" + i).getBytes(), Ids.CREATOR_ALL_ACL,
                    CreateMode.PERSISTENT));
        }
        /**
         * Have set jute.maxbuffer to 6197. 100 znode size is 6197, so creation will be
         * success
         */
        zk.multi(ops);
        List<String> children = zk.getChildren(parent, false);
        // check nodes are created successfully.
        assertEquals(numberOfNodes, children.size());

        /**
         * Server added some additional information(ACL in this case) in those 100
         * znodes. So total size of 100 records in transaction log file is more than
         * 6197. Total size is around 9616. So it added 3419 extra bytes. If extrasize
         * is kept 1024, then test case will fail (earlier default scenario). Now the
         * extraSize default value is same as jute buffer size. In this case it will be
         * 6197. So 9616 less than (6197+6197) will pass.
         */
        File dataDir = new File(mt.getConfFile().getParentFile(), "data");
        assertTrue("data directory does not exist", dataDir.exists());
        ZKDatabase database = new ZKDatabase(new FileTxnSnapLog(dataDir, dataDir));
        database.loadDataBase();
    }

    /**
     * ZOOKEEPER-3496. This test is normal jute.maxbuffer functionality test. It
     * should pass before and after fix
     */
    @Test
    public void testZKOperationRequestOfSizeGreaterThanMaxBuffer() throws Exception {
        int clientPort = PortAssignment.unique();
        String quorumCfgSection = "server.1=127.0.0.1:" + (PortAssignment.unique()) + ":"
                + (PortAssignment.unique()) + ":participant;" + clientPort + "\n";

        mt = new MainThread(1, clientPort, quorumCfgSection, false);
        mt.start();
        Assert.assertTrue("waiting for server 1 being up",
                ClientBase.waitForServerUp("127.0.0.1:" + clientPort, CONNECTION_TIMEOUT));

        ZooKeeper zk = ClientBase.createZKClient("127.0.0.1:" + clientPort);
        String parent = "/parent";
        zk.create(parent, "".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.addAuthInfo("digest", "pat:test".getBytes());
        List<Op> ops = new ArrayList<Op>();

        /**
         * This code will create 101 Nodes , 100 node size is 6197 Bytes. So this
         * operation should fail
         */
        int numberOfNodes = 101;
        for (int i = 0; i < numberOfNodes; i++) {
            ops.add(Op.create(parent + "/child" + i, ("data" + i).getBytes(), Ids.CREATOR_ALL_ACL,
                    CreateMode.PERSISTENT));
        }
        try {
            zk.multi(ops);
            fail("KeeperException is expected as request size is more than jute.maxbuffer size");
        } catch (KeeperException e) {
            System.out.println("Expected to fail as request size exceeded jute max buffer size"
                    + e.getMessage());
            // do nothing, Exception is expected
        }
    }

    @After
    public void tearDown() throws Exception {
        System.clearProperty(ZKConfig.JUTE_MAXBUFFER);
        if (mt != null) {
            // do cleanup
            mt.shutdown();
        }
    }
}
