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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.jmx.CommonNames;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeer.LearnerType;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.apache.zookeeper.server.quorum.flexible.QuorumHierarchical;
import org.junit.Assert;
import org.junit.Test;

public class HierarchicalQuorumTest extends ClientBase {
    private static final Logger LOG = LoggerFactory.getLogger(QuorumBase.class);

    File s1dir, s2dir, s3dir, s4dir, s5dir;
    QuorumPeer s1, s2, s3, s4, s5;
    protected int port1;
    protected int port2;
    protected int port3;
    protected int port4;
    protected int port5;

    protected int leport1;
    protected int leport2;
    protected int leport3;
    protected int leport4;
    protected int leport5;

    Properties qp;
    protected final ClientHammerTest cht = new ClientHammerTest();
    
    @Override
    public void setUp() throws Exception {
        setupTestEnv();

        JMXEnv.setUp();

        setUpAll();

        port1 = PortAssignment.unique();
        port2 = PortAssignment.unique();
        port3 = PortAssignment.unique();
        port4 = PortAssignment.unique();
        port5 = PortAssignment.unique();
        leport1 = PortAssignment.unique();
        leport2 = PortAssignment.unique();
        leport3 = PortAssignment.unique();
        leport4 = PortAssignment.unique();
        leport5 = PortAssignment.unique();

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

        cht.hostPort = hostPort;
        cht.setUpAll();
        
        LOG.info("Setup finished");
    }
    
    /**
     * This method is here to keep backwards compatibility with the test code 
     * written before observers. 
     * @throws Exception
     */
    void startServers() throws Exception {
        startServers(false);
    }
    
    /**
     * Starts 5 Learners. When withObservers == false, all 5 are Followers.
     * When withObservers == true, 3 are Followers and 2 Observers.
     * @param withObservers
     * @throws Exception
     */
    void startServers(boolean withObservers) throws Exception {
        int tickTime = 2000;
        int initLimit = 3;
        int syncLimit = 3;
        HashMap<Long,QuorumServer> peers = new HashMap<Long,QuorumServer>();
        peers.put(Long.valueOf(1), new QuorumServer(1, "127.0.0.1", port1 + 1000, leport1 + 1000, null));
        peers.put(Long.valueOf(2), new QuorumServer(2, "127.0.0.1", port2 + 1000, leport2 + 1000, null));
        peers.put(Long.valueOf(3), new QuorumServer(3, "127.0.0.1", port3 + 1000, leport3 + 1000, null));
        peers.put(Long.valueOf(4), new QuorumServer(4, "127.0.0.1", port4 + 1000, leport4 + 1000,
                withObservers ? QuorumPeer.LearnerType.OBSERVER
                        : QuorumPeer.LearnerType.PARTICIPANT));
        peers.put(Long.valueOf(5), new QuorumServer(5, "127.0.0.1", port5 + 1000, leport5 + 1000,
                withObservers ? QuorumPeer.LearnerType.OBSERVER
                        : QuorumPeer.LearnerType.PARTICIPANT));

        LOG.info("creating QuorumPeer 1 port " + port1);
        List <QuorumPeer> qps = new ArrayList<QuorumPeer>();
        QuorumHierarchical hq1 = new QuorumHierarchical(qp); 
        s1 = new QuorumPeer(peers, s1dir, s1dir, port1, 3, 1, tickTime, initLimit, syncLimit, hq1);
        qps.add(s1);
        Assert.assertEquals(port1, s1.getClientPort());
        
        LOG.info("creating QuorumPeer 2 port " + port2);
        QuorumHierarchical hq2 = new QuorumHierarchical(qp); 
        s2 = new QuorumPeer(peers, s2dir, s2dir, port2, 3, 2, tickTime, initLimit, syncLimit, hq2);
        qps.add(s2);
        Assert.assertEquals(port2, s2.getClientPort());
        
        LOG.info("creating QuorumPeer 3 port " + port3);
        QuorumHierarchical hq3 = new QuorumHierarchical(qp); 
        s3 = new QuorumPeer(peers, s3dir, s3dir, port3, 3, 3, tickTime, initLimit, syncLimit, hq3);
        qps.add(s3);
        Assert.assertEquals(port3, s3.getClientPort());
        
        LOG.info("creating QuorumPeer 4 port " + port4);
        QuorumHierarchical hq4 = new QuorumHierarchical(qp); 
        s4 = new QuorumPeer(peers, s4dir, s4dir, port4, 3, 4, tickTime, initLimit, syncLimit, hq4);
        qps.add(s4);
        if (withObservers) {
            s4.setLearnerType(QuorumPeer.LearnerType.OBSERVER);
        }
        Assert.assertEquals(port4, s4.getClientPort());
                       
        LOG.info("creating QuorumPeer 5 port " + port5);
        QuorumHierarchical hq5 = new QuorumHierarchical(qp); 
        s5 = new QuorumPeer(peers, s5dir, s5dir, port5, 3, 5, tickTime, initLimit, syncLimit, hq5);
        qps.add(s5);
        if (withObservers) {
            s5.setLearnerType(QuorumPeer.LearnerType.OBSERVER);
        }
        Assert.assertEquals(port5, s5.getClientPort());
        
        // Observers are currently only compatible with LeaderElection
        if (withObservers) {
            s1.setElectionType(0);
            s2.setElectionType(0);
            s3.setElectionType(0);
            s4.setElectionType(0);
            s5.setElectionType(0);
        }
        
        LOG.info("start QuorumPeer 1");
        s1.start();
        LOG.info("start QuorumPeer 2");
        s2.start();
        LOG.info("start QuorumPeer 3");
        s3.start();
        LOG.info("start QuorumPeer 4" + (withObservers ? "(observer)" : ""));
        s4.start();
        LOG.info("start QuorumPeer 5" + (withObservers ? "(observer)" : ""));
        s5.start();
        LOG.info("started QuorumPeer 5");

        LOG.info ("Closing ports " + hostPort);
        for (String hp : hostPort.split(",")) {
            Assert.assertTrue("waiting for server up",
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
        verifyElectionTimeTakenJMXAttribute(qps);
    }

    @Override
    public void tearDown() throws Exception {
        LOG.info("TearDown started");
        cht.tearDownAll();

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
            Assert.assertTrue("waiting for server down",
                       ClientBase.waitForServerDown(hp,
                                           ClientBase.CONNECTION_TIMEOUT));
            LOG.info(hp + " is no longer accepting client connections");
        }

        JMXEnv.tearDown();
    }

    protected void shutdown(QuorumPeer qp) {
        QuorumBase.shutdown(qp);
    }

    protected TestableZooKeeper createClient()
        throws IOException, InterruptedException
    {
        return createClient(hostPort);
    }

    protected TestableZooKeeper createClient(String hp)
        throws IOException, InterruptedException
    {
        CountdownWatcher watcher = new CountdownWatcher();
        return createClient(watcher, hp);
    }

    private void verifyElectionTimeTakenJMXAttribute(List<QuorumPeer> peers)
            throws Exception {
        LOG.info("Verify QuorumPeer#electionTimeTaken jmx bean attribute");

        for (int i = 1; i <= peers.size(); i++) {
            QuorumPeer qp = peers.get(i - 1);
            if (qp.getLearnerType() == LearnerType.OBSERVER) {
                continue; // Observer don't have electionTimeTaken attribute.
            }
            Long electionTimeTaken = -1L;
            String bean = "";
            if (qp.getPeerState() == ServerState.FOLLOWING) {
                bean = String.format(
                        "%s:name0=ReplicatedServer_id%d,name1=replica.%d,name2=Follower",
                        CommonNames.DOMAIN, i, i);
            } else if (qp.getPeerState() == ServerState.LEADING) {
                bean = String.format(
                        "%s:name0=ReplicatedServer_id%d,name1=replica.%d,name2=Leader",
                        CommonNames.DOMAIN, i, i);
            }
            electionTimeTaken = (Long) JMXEnv.ensureBeanAttribute(bean,
                    "ElectionTimeTaken");
            Assert.assertTrue("Wrong electionTimeTaken value!",
                    electionTimeTaken >= 0);
        }
    }

    @Test
    public void testHierarchicalQuorum() throws Throwable {
        cht.runHammer(5, 10);
    }
}
