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

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.Assert;
import org.junit.Test;

public class RepeatStartupTest extends ZKTestCase {

    /** bring up 5 quorum peers and then shut them down
     * and then bring one of the nodes as server
     *
     * @throws Exception might be thrown here
     */
    @Test
    public void testFail() throws Exception {
        QuorumBase qb = new QuorumBase();
        qb.setUp();

        System.out.println("Comment: the servers are at " + qb.hostPort);
        ZooKeeper zk = qb.createClient();
        zk.create("/test", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.close();
        qb.shutdown(qb.s1);
        qb.shutdown(qb.s2);
        qb.shutdown(qb.s3);
        qb.shutdown(qb.s4);
        qb.shutdown(qb.s5);
        String hp = qb.hostPort.split(",")[0];
        ZooKeeperServer zks = new ZooKeeperServer(qb.s1.getTxnFactory().getSnapDir(),
                qb.s1.getTxnFactory().getDataDir(), 3000);
        final int PORT = Integer.parseInt(hp.split(":")[1]);
        ServerCnxnFactory factory = ServerCnxnFactory.createFactory(PORT, -1);

        factory.startup(zks);
        System.out.println("Comment: starting factory");
        Assert.assertTrue("waiting for server up",
                   ClientBase.waitForServerUp("127.0.0.1:" + PORT,
                           QuorumTest.CONNECTION_TIMEOUT));
        factory.shutdown();
        zks.shutdown();
        Assert.assertTrue("waiting for server down",
                   ClientBase.waitForServerDown("127.0.0.1:" + PORT,
                                                QuorumTest.CONNECTION_TIMEOUT));
        System.out.println("Comment: shutting down standalone");
    }
}
