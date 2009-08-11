/* Licensed to the Apache Software Foundation (ASF) under one
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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

import org.apache.log4j.Logger;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.quorum.FastLeaderElection;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.flexible.QuorumHierarchical;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Comprehensive test of hierarchical quorums, assuming servers with zero weight.
 * This test uses ClientTest to verify that the ensemble works after a leader is 
 * elected.
 * 
 * This implementation is based on QuorumBase, the main difference being that it 
 * uses hierarchical quorums and FLE.
 */

public class HierarchicalQuorumTest extends ClientBase {
    private static final Logger LOG = Logger.getLogger(QuorumBase.class);

    File s1dir, s2dir, s3dir, s4dir, s5dir;
    QuorumPeer s1, s2, s3, s4, s5;
    private int port1;
    private int port2;
    private int port3;
    private int port4;
    private int port5;

    private int leport1;
    private int leport2;
    private int leport3;
    private int leport4;
    private int leport5;

    Properties qp;
    private final ClientTest ct = new ClientTest();
    
    @Override
    protected void setUp() throws Exception {
        LOG.info("STARTING " + getName());
        int baseport = 12333;
        int baseLEport = 14333;
        
        setupTestEnv();

        JMXEnv.setUp();

        //setUpAll();

        port1 = baseport;
        port2 = baseport + 1;
        port3 = baseport + 2;
        port4 = baseport + 3;
        port5 = baseport + 4;
        leport1 = baseLEport;
        leport2 = baseLEport + 1;
        leport3 = baseLEport + 2;
        leport4 = baseLEport + 3;
        leport5 = baseLEport + 4;

        hostPort = "127.0.0.1:" + port1 
            + ",127.0.0.1:" + port2 
            + ",127.0.0.1:" + port3 
            + ",127.0.0.1:" + port4 
            + ",127.0.0.1:" + port5;
        LOG.info("Ports are: " + hostPort);

        s1dir = ClientBase.createTmpDir();
        s2dir = ClientBase.createTmpDir();
        s3dir = ClientBase.createTmpDir();
        s4dir = ClientBase.createTmpDir();
        s5dir = ClientBase.createTmpDir();

        String config = "group.1=1:2:3\n" +
        "group.2=4:5\n" +
        "weight.1=1\n" +
        "weight.2=1\n" +
        "weight.3=1\n" +
        "weight.4=0\n" +
        "weight.5=0\n";

        ByteArrayInputStream is = new ByteArrayInputStream(config.getBytes());
        this.qp = new Properties();
        
        qp.load(is);
        startServers();

        ct.hostPort = hostPort;
        //ct.setUpAll();
        
        LOG.info("Setup finished");
    }
    
    
    void startServers() throws Exception {
        int tickTime = 2000;
        int initLimit = 3;
        int syncLimit = 3;
        HashMap<Long,QuorumServer> peers = new HashMap<Long,QuorumServer>();
        peers.put(Long.valueOf(1), new QuorumServer(1, 
                new InetSocketAddress("127.0.0.1", port1 + 1000),
                new InetSocketAddress("127.0.0.1", leport1 + 1000)));
        peers.put(Long.valueOf(2), new QuorumServer(2, 
                new InetSocketAddress("127.0.0.1", port2 + 1000),
                new InetSocketAddress("127.0.0.1", leport2 + 1000)));
        peers.put(Long.valueOf(3), new QuorumServer(3, 
                new InetSocketAddress("127.0.0.1", port3 + 1000),
                new InetSocketAddress("127.0.0.1", leport3 + 1000)));
        peers.put(Long.valueOf(4), new QuorumServer(4,
                new InetSocketAddress("127.0.0.1", port4 + 1000),
                new InetSocketAddress("127.0.0.1", leport4 + 1000)));
        peers.put(Long.valueOf(5), new QuorumServer(5,
                new InetSocketAddress("127.0.0.1", port5 + 1000),
                new InetSocketAddress("127.0.0.1", leport5 + 1000)));

        LOG.info("creating QuorumPeer 1 port " + port1);
        QuorumHierarchical hq1 = new QuorumHierarchical(qp); 
        s1 = new QuorumPeer(peers, s1dir, s1dir, port1, 3, 1, tickTime, initLimit, syncLimit, hq1);
        assertEquals(port1, s1.getClientPort());
        
        LOG.info("creating QuorumPeer 2 port " + port2);
        QuorumHierarchical hq2 = new QuorumHierarchical(qp); 
        s2 = new QuorumPeer(peers, s2dir, s2dir, port2, 3, 2, tickTime, initLimit, syncLimit, hq2);
        assertEquals(port2, s2.getClientPort());
        
        LOG.info("creating QuorumPeer 3 port " + port3);
        QuorumHierarchical hq3 = new QuorumHierarchical(qp); 
        s3 = new QuorumPeer(peers, s3dir, s3dir, port3, 3, 3, tickTime, initLimit, syncLimit, hq3);
        assertEquals(port3, s3.getClientPort());
        
        LOG.info("creating QuorumPeer 4 port " + port4);
        QuorumHierarchical hq4 = new QuorumHierarchical(qp); 
        s4 = new QuorumPeer(peers, s4dir, s4dir, port4, 3, 4, tickTime, initLimit, syncLimit, hq4);
        assertEquals(port4, s4.getClientPort());
        
        LOG.info("creating QuorumPeer 5 port " + port5);
        QuorumHierarchical hq5 = new QuorumHierarchical(qp); 
        s5 = new QuorumPeer(peers, s5dir, s5dir, port5, 3, 5, tickTime, initLimit, syncLimit, hq5);
        assertEquals(port5, s5.getClientPort());
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

        LOG.info ("Closing ports " + hostPort);
        for (String hp : hostPort.split(",")) {
            assertTrue("waiting for server up",
                       ClientBase.waitForServerUp(hp,
                                    CONNECTION_TIMEOUT));
            LOG.info(hp + " is accepting client connections");
        }

        // interesting to see what's there...
        JMXEnv.dump();
        // make sure we have these 5 servers listed
        Set<String> ensureNames = new LinkedHashSet<String>();
        for (int i = 1; i <= 5; i++) {
            ensureNames.add("InMemoryDataTree");
        }
        for (int i = 1; i <= 5; i++) {
            ensureNames.add("name0=ReplicatedServer_id" + i
                 + ",name1=replica." + i + ",name2=");
        }
        for (int i = 1; i <= 5; i++) {
            for (int j = 1; j <= 5; j++) {
                ensureNames.add("name0=ReplicatedServer_id" + i
                     + ",name1=replica." + j);
            }
        }
        for (int i = 1; i <= 5; i++) {
            ensureNames.add("name0=ReplicatedServer_id" + i);
        }
        JMXEnv.ensureAll(ensureNames.toArray(new String[ensureNames.size()]));
    }

    @After
    @Override
    protected void tearDown() throws Exception {
        LOG.info("TearDown started");
        //ct.tearDownAll();

        LOG.info("Shutting down server 1");
        shutdown(s1);
        LOG.info("Shutting down server 2");
        shutdown(s2);
        LOG.info("Shutting down server 3");
        shutdown(s3);
        LOG.info("Shutting down server 4");
        shutdown(s4);
        LOG.info("Shutting down server 5");
        shutdown(s5);
        
        for (String hp : hostPort.split(",")) {
            assertTrue("waiting for server down",
                       ClientBase.waitForServerDown(hp,
                                           ClientBase.CONNECTION_TIMEOUT));
            LOG.info(hp + " is no longer accepting client connections");
        }

        JMXEnv.tearDown();

        LOG.info("FINISHED " + getName());
    }

    protected void shutdown(QuorumPeer qp) {
        try {
            ((FastLeaderElection) qp.getElectionAlg()).shutdown();
            LOG.info("Done with leader election");
            qp.shutdown();
            LOG.info("Done with quorum peer");
            qp.join(30000);
            if (qp.isAlive()) {
                fail("QP failed to shutdown in 30 seconds");
            }
        } catch (InterruptedException e) {
            LOG.debug("QP interrupted", e);
        }
    }

    protected ZooKeeper createClient()
        throws IOException, InterruptedException
    {
        return createClient(hostPort);
    }

    protected ZooKeeper createClient(String hp)
        throws IOException, InterruptedException
    {
        CountdownWatcher watcher = new CountdownWatcher();
        return createClient(watcher, hp);
    }

    @Test
    public void testHierarchicalQuorum() throws Throwable {
        ct.testHammerBasic();
    }
}