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
import java.net.InetSocketAddress;
import java.util.ArrayList;

import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumStats;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class QuorumTest extends ClientBase {
    private static final Logger LOG = Logger.getLogger(QuorumTest.class);

    private ClientTest ct = new ClientTest();

    File s1dir, s2dir, s3dir, s4dir, s5dir;
    QuorumPeer s1, s2, s3, s4, s5;

    @Before
    @Override
    protected void setUp() throws Exception {
        LOG.info("STARTING " + getName());

        setupTestEnv();

        hostPort = "127.0.0.1:2181,127.0.0.1:2182,127.0.0.1:2183,127.0.0.1:2184,127.0.0.1:2185";
        ct.hostPort = hostPort;

        s1dir = ClientBase.createTmpDir();
        s2dir = ClientBase.createTmpDir();
        s3dir = ClientBase.createTmpDir();
        s4dir = ClientBase.createTmpDir();
        s5dir = ClientBase.createTmpDir();
        
        startServers();
        
        LOG.info("Setup finished");
    }
    void startServers() throws IOException, InterruptedException {
        QuorumStats.registerAsConcrete();
        int tickTime = 2000;
        int initLimit = 3;
        int syncLimit = 3;
        ArrayList<QuorumServer> peers = new ArrayList<QuorumServer>();
        peers.add(new QuorumServer(1, new InetSocketAddress("127.0.0.1", 3181)));
        peers.add(new QuorumServer(2, new InetSocketAddress("127.0.0.1", 3182)));
        peers.add(new QuorumServer(3, new InetSocketAddress("127.0.0.1", 3183)));
        peers.add(new QuorumServer(4, new InetSocketAddress("127.0.0.1", 3184)));
        peers.add(new QuorumServer(5, new InetSocketAddress("127.0.0.1", 3185)));
        LOG.info("creating QuorumPeer 1");
        s1 = new QuorumPeer(peers, s1dir, s1dir, 2181, 0,  1181, 1, tickTime, initLimit, syncLimit);
        LOG.info("creating QuorumPeer 2");
        s2 = new QuorumPeer(peers, s2dir, s2dir, 2182, 0, 1182, 2, tickTime, initLimit, syncLimit);
        LOG.info("creating QuorumPeer 3");
        s3 = new QuorumPeer(peers, s3dir, s3dir, 2183, 0, 1183, 3, tickTime, initLimit, syncLimit);
        LOG.info("creating QuorumPeer 4");
        s4 = new QuorumPeer(peers, s4dir, s4dir, 2184, 0, 1184, 4, tickTime, initLimit, syncLimit);
        LOG.info("creating QuorumPeer 5");
        s5 = new QuorumPeer(peers, s5dir, s5dir, 2185, 0, 1185, 5, tickTime, initLimit, syncLimit);
        LOG.info("start QuorumPeer 1");
        s1.start();
        LOG.info("start QuorumPeer 2");
        s2.start();
        LOG.info("start QuorumPeer 3");
        s3.start();
        LOG.info("start QuorumPeer 4");
        s4.start();
        LOG.info("start QuorumPeer 5");
        s5.start();
        LOG.info("started QuorumPeer 5");

        for (String hp : hostPort.split(",")) {
            assertTrue("waiting for server up",
                       ClientBase.waitForServerUp(hp,
                                    CONNECTION_TIMEOUT));
            LOG.info(hp + " is accepting client connections");
        }
    }
    @After
    @Override
    protected void tearDown() throws Exception {
        LOG.info("TearDown started");
        shutdown(s1);
        shutdown(s2);
        shutdown(s3);
        shutdown(s4);
        shutdown(s5);

        for (String hp : hostPort.split(",")) {
            assertTrue("waiting for server down",
                       ClientBase.waitForServerDown(hp,
                                           ClientBase.CONNECTION_TIMEOUT));
            LOG.info(hp + " is no longer accepting client connections");
        }

        QuorumStats.unregister();
        LOG.info("FINISHED " + getName());
    }

    private void shutdown(QuorumPeer qp) {
        try {
            qp.shutdown();
            qp.join(30000);
            if (qp.isAlive()) {
                fail("QP failed to shutdown in 30 seconds");
            }
        } catch (InterruptedException e) {
            LOG.debug("QP interrupted", e);
        }
    }

    @Test
    public void testDeleteWithChildren() throws Exception {
        ct.testDeleteWithChildren();
    }
    
    @Test
    public void testHammerBasic() throws Throwable {
        ct.testHammerBasic();
    }
    
    @Test
    public void testPing() throws Exception {
        ct.testPing();
    }
    
    @Test
    public void testSequentialNodeNames()
        throws IOException, InterruptedException, KeeperException
    {
        ct.testSequentialNodeNames();
    }

    @Test
    public void testACLs() throws Exception {
        ct.testACLs();
    }

    @Test
    public void testClientwithoutWatcherObj() throws IOException,
            InterruptedException, KeeperException
    {
        ct.testClientwithoutWatcherObj();
    }

    @Test
    public void testClientWithWatcherObj() throws IOException,
            InterruptedException, KeeperException
    {
        ct.testClientWithWatcherObj();
    }
    
    // skip superhammer and clientcleanup as they are too expensive for quorum
}
