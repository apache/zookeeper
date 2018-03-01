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

package org.apache.zookeeper.server.quorum;

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;

import java.io.ByteArrayOutputStream;
import java.io.LineNumberReader;
import java.io.StringReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.log4j.Layout;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.WriterAppender;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.server.quorum.Leader.Proposal;
import org.apache.zookeeper.test.ClientBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test stand-alone server.
 *
 */
public class QuorumPeerMainTest extends QuorumPeerTestBase {

    private Servers servers;
    private int numServers = 0;

    @After
    public void tearDown() throws Exception {
        if (servers == null || servers.mt == null) {
            LOG.info("No servers to shutdown!");
            return;
        }
        for (int i = 0; i < numServers; i++) {
            if (i < servers.mt.length) {
                servers.mt[i].shutdown();
            }
        }
    }

    /**
     * Verify the ability to start a cluster.
     */
    @Test
    public void testQuorum() throws Exception {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT_QP1 = PortAssignment.unique();
        final int CLIENT_PORT_QP2 = PortAssignment.unique();

        String quorumCfgSection =
            "server.1=127.0.0.1:" + PortAssignment.unique()
            + ":" + PortAssignment.unique() + ";" + CLIENT_PORT_QP1
            + "\nserver.2=127.0.0.1:" + PortAssignment.unique() 
            + ":" + PortAssignment.unique() + ";" + CLIENT_PORT_QP2;

        MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
        MainThread q2 = new MainThread(2, CLIENT_PORT_QP2, quorumCfgSection);
        q1.start();
        q2.start();

        Assert.assertTrue("waiting for server 1 being up",
                        ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP1,
                        CONNECTION_TIMEOUT));
        Assert.assertTrue("waiting for server 2 being up",
                        ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP2,
                        CONNECTION_TIMEOUT));
        QuorumPeer quorumPeer = q1.main.quorumPeer;

        int tickTime = quorumPeer.getTickTime();
        Assert.assertEquals(
                "Default value of minimumSessionTimeOut is not considered",
                tickTime * 2, quorumPeer.getMinSessionTimeout());
        Assert.assertEquals(
                "Default value of maximumSessionTimeOut is not considered",
                tickTime * 20, quorumPeer.getMaxSessionTimeout());

        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT_QP1,
                ClientBase.CONNECTION_TIMEOUT, this);
        waitForOne(zk, States.CONNECTED);
        zk.create("/foo_q1", "foobar1".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        Assert.assertEquals(new String(zk.getData("/foo_q1", null, null)), "foobar1");
        zk.close();

        zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT_QP2,
                ClientBase.CONNECTION_TIMEOUT, this);
        waitForOne(zk, States.CONNECTED);
        zk.create("/foo_q2", "foobar2".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        Assert.assertEquals(new String(zk.getData("/foo_q2", null, null)), "foobar2");
        zk.close();

        q1.shutdown();
        q2.shutdown();

        Assert.assertTrue("waiting for server 1 down",
                ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP1,
                        ClientBase.CONNECTION_TIMEOUT));
        Assert.assertTrue("waiting for server 2 down",
                ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP2,
                        ClientBase.CONNECTION_TIMEOUT));
    }

    /**
     * Test early leader abandonment.
     */
    @Test
    public void testEarlyLeaderAbandonment() throws Exception {
        ClientBase.setupTestEnv();
        final int SERVER_COUNT = 3;
        final int clientPorts[] = new int[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < SERVER_COUNT; i++) {
               clientPorts[i] = PortAssignment.unique();
               sb.append("server."+i+"=127.0.0.1:"+PortAssignment.unique()+":"+PortAssignment.unique()+";"+clientPorts[i]+"\n");
        }
        String quorumCfgSection = sb.toString();

        MainThread mt[] = new MainThread[SERVER_COUNT];
        ZooKeeper zk[] = new ZooKeeper[SERVER_COUNT];
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i] = new MainThread(i, clientPorts[i], quorumCfgSection);
            mt[i].start();
            zk[i] = new ZooKeeper("127.0.0.1:" + clientPorts[i], ClientBase.CONNECTION_TIMEOUT, this);
        }

        waitForAll(zk, States.CONNECTED);

        // we need to shutdown and start back up to make sure that the create session isn't the first transaction since
        // that is rather innocuous.
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].shutdown();
        }

        waitForAll(zk, States.CONNECTING);

        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].start();
            // Recreate a client session since the previous session was not persisted.
            zk[i] = new ZooKeeper("127.0.0.1:" + clientPorts[i], ClientBase.CONNECTION_TIMEOUT, this);
         }

        waitForAll(zk, States.CONNECTED);


        // ok lets find the leader and kill everything else, we have a few
        // seconds, so it should be plenty of time
        int leader = -1;
        Map<Long, Proposal> outstanding = null;
        for (int i = 0; i < SERVER_COUNT; i++) {
            if (mt[i].main.quorumPeer.leader == null) {
                mt[i].shutdown();
            } else {
                leader = i;
                outstanding = mt[leader].main.quorumPeer.leader.outstandingProposals;
            }
        }

        try {
            zk[leader].create("/zk" + leader, "zk".getBytes(), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);
            Assert.fail("create /zk" + leader + " should have failed");
        } catch (KeeperException e) {}

        // just make sure that we actually did get it in process at the
        // leader
        Assert.assertTrue(outstanding.size() == 1);
        Assert.assertTrue(((Proposal) outstanding.values().iterator().next()).request.getHdr().getType() == OpCode.create);
        // make sure it has a chance to write it to disk
        Thread.sleep(1000);
        mt[leader].shutdown();
        waitForAll(zk, States.CONNECTING);
        for (int i = 0; i < SERVER_COUNT; i++) {
            if (i != leader) {
                mt[i].start();
            }
        }
        for (int i = 0; i < SERVER_COUNT; i++) {
            if (i != leader) {
                // Recreate a client session since the previous session was not persisted.
                zk[i] = new ZooKeeper("127.0.0.1:" + clientPorts[i], ClientBase.CONNECTION_TIMEOUT, this);
                waitForOne(zk[i], States.CONNECTED);
                zk[i].create("/zk" + i, "zk".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
        }

        mt[leader].start();
        waitForAll(zk, States.CONNECTED);
        // make sure everything is consistent
        for (int i = 0; i < SERVER_COUNT; i++) {
            for (int j = 0; j < SERVER_COUNT; j++) {
                if (i == leader) {
                    Assert.assertTrue((j == leader ? ("Leader (" + leader + ")") : ("Follower " + j)) + " should not have /zk" + i, zk[j].exists("/zk" + i, false) == null);
                } else {
                    Assert.assertTrue((j == leader ? ("Leader (" + leader + ")") : ("Follower " + j)) + " does not have /zk" + i, zk[j].exists("/zk" + i, false) != null);
                }
            }
        }
        for (int i = 0; i < SERVER_COUNT; i++) {
            zk[i].close();
        }
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].shutdown();
        }
    }

    /**
     * Test the case of server with highest zxid not present at leader election and joining later.
     * This test case is for reproducing the issue and fixing the bug mentioned in ZOOKEEPER-1154
     * and ZOOKEEPER-1156.
     */
    @Test
    public void testHighestZxidJoinLate() throws Exception {
        numServers = 3;
        servers = LaunchServers(numServers);
        String path = "/hzxidtest";
        int leader = servers.findLeader();

        // make sure there is a leader
        Assert.assertTrue("There should be a leader", leader >= 0);

        int nonleader = (leader + 1) % numServers;

        byte[] input = new byte[1];
        input[0] = 1;
        byte[] output;

        // Create a couple of nodes
        servers.zk[leader].create(path + leader, input, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        servers.zk[leader].create(path + nonleader, input, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        // make sure the updates indeed committed. If it is not
        // the following statement will throw.
        output = servers.zk[leader].getData(path + nonleader, false, null);

        // Shutdown every one else but the leader
        for (int i = 0; i < numServers; i++) {
            if (i != leader) {
                servers.mt[i].shutdown();
            }
        }

        input[0] = 2;

        // Update the node on the leader
        servers.zk[leader].setData(path + leader, input, -1, null, null);

        // wait some time to let this get written to disk
        Thread.sleep(500);

        // shut the leader down
        servers.mt[leader].shutdown();

        System.gc();

        waitForAll(servers.zk, States.CONNECTING);

        // Start everyone but the leader
        for (int i = 0; i < numServers; i++) {
            if (i != leader) {
                servers.mt[i].start();
            }
        }

        // wait to connect to one of these
        waitForOne(servers.zk[nonleader], States.CONNECTED);

        // validate that the old value is there and not the new one
        output = servers.zk[nonleader].getData(path + leader, false, null);

        Assert.assertEquals(
                "Expecting old value 1 since 2 isn't committed yet",
                output[0], 1);

        // Do some other update, so we bump the maxCommttedZxid
        // by setting the value to 2
        servers.zk[nonleader].setData(path + nonleader, input, -1);

        // start the old leader
        servers.mt[leader].start();

        // connect to it
        waitForOne(servers.zk[leader], States.CONNECTED);

        // make sure it doesn't have the new value that it alone had logged
        output = servers.zk[leader].getData(path + leader, false, null);
        Assert.assertEquals(
                "Validating that the deposed leader has rolled back that change it had written",
                output[0], 1);

        // make sure the leader has the subsequent changes that were made while it was offline
        output = servers.zk[leader].getData(path + nonleader, false, null);
        Assert.assertEquals(
                "Validating that the deposed leader caught up on changes it missed",
                output[0], 2);
    }

    /**
     * This test validates that if a quorum member determines that it is leader without the support of the rest of the
     * quorum (the other members do not believe it to be the leader) it will stop attempting to lead and become a follower.
     *
     * @throws IOException
     * @throws InterruptedException
     */
    @Test
    public void testElectionFraud() throws IOException, InterruptedException {
        // capture QuorumPeer logging
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        String loggingPattern = ((PatternLayout) Logger.getRootLogger().getAppender("CONSOLE").getLayout()).getConversionPattern();
        WriterAppender appender = new WriterAppender(new PatternLayout(loggingPattern), os);
        appender.setThreshold(Level.INFO);
        Logger qlogger = Logger.getLogger(QuorumPeer.class);
        qlogger.addAppender(appender);

        numServers = 3;

        // used for assertions later
        boolean foundLeading = false;
        boolean foundLooking = false;
        boolean foundFollowing = false;

        try {
          // spin up a quorum, we use a small ticktime to make the test run faster
          servers = LaunchServers(numServers, 500);

          // find the leader
          int trueLeader = servers.findLeader();
          Assert.assertTrue("There should be a leader", trueLeader >= 0);

          // find a follower
          int falseLeader = (trueLeader + 1) % numServers;
          Assert.assertTrue("All servers should join the quorum", servers.mt[falseLeader].main.quorumPeer.follower != null);

          // to keep the quorum peer running and force it to go into the looking state, we kill leader election
          // and close the connection to the leader
          servers.mt[falseLeader].main.quorumPeer.electionAlg.shutdown();
          servers.mt[falseLeader].main.quorumPeer.follower.getSocket().close();

          // wait for the falseLeader to disconnect
          waitForOne(servers.zk[falseLeader], States.CONNECTING);

          // convince falseLeader that it is the leader
          servers.mt[falseLeader].main.quorumPeer.setPeerState(QuorumPeer.ServerState.LEADING);

          // provide time for the falseleader to realize no followers have connected
          // (this is twice the timeout used in Leader#getEpochToPropose)
          Thread.sleep(2 * servers.mt[falseLeader].main.quorumPeer.initLimit * servers.mt[falseLeader].main.quorumPeer.tickTime);

          // Restart leader election
          servers.mt[falseLeader].main.quorumPeer.startLeaderElection();

          // The previous client connection to falseLeader likely closed, create a new one
          servers.zk[falseLeader] = new ZooKeeper("127.0.0.1:" + servers.mt[falseLeader].getClientPort(), ClientBase.CONNECTION_TIMEOUT, this);

          // Wait for falseLeader to rejoin the quorum
          waitForOne(servers.zk[falseLeader], States.CONNECTED);

          // and ensure trueLeader is still the leader
          Assert.assertTrue(servers.mt[trueLeader].main.quorumPeer.leader != null);

          // Look through the logs for output that indicates the falseLeader is LEADING, then LOOKING, then FOLLOWING
          LineNumberReader r = new LineNumberReader(new StringReader(os.toString()));
          Pattern leading = Pattern.compile(".*myid=" + falseLeader + ".*LEADING.*");
          Pattern looking = Pattern.compile(".*myid=" + falseLeader + ".*LOOKING.*");
          Pattern following = Pattern.compile(".*myid=" + falseLeader + ".*FOLLOWING.*");

          String line;
          while ((line = r.readLine()) != null) {
            if (!foundLeading) {
              foundLeading = leading.matcher(line).matches();
            } else if(!foundLooking) {
              foundLooking = looking.matcher(line).matches();
            } else if (following.matcher(line).matches()){
              foundFollowing = true;
              break;
            }
          }
        } finally {
          qlogger.removeAppender(appender);
        }

        Assert.assertTrue("falseLeader never attempts to become leader", foundLeading);
        Assert.assertTrue("falseLeader never gives up on leadership", foundLooking);
        Assert.assertTrue("falseLeader never rejoins the quorum", foundFollowing);
    }

    private void waitForOne(ZooKeeper zk, States state) throws InterruptedException {
        int iterations = ClientBase.CONNECTION_TIMEOUT / 500;
        while (zk.getState() != state) {
            if (iterations-- == 0) {
                throw new RuntimeException("Waiting too long " + zk.getState() + " != " + state);
            }
            Thread.sleep(500);
        }
    }

    private void waitForAll(Servers servers, States state) throws InterruptedException {
        waitForAll(servers.zk, state);
    }

    private void waitForAll(ZooKeeper[] zks, States state) throws InterruptedException {
        int iterations = ClientBase.CONNECTION_TIMEOUT / 1000;
        boolean someoneNotConnected = true;
        while (someoneNotConnected) {
            if (iterations-- == 0) {
                ClientBase.logAllStackTraces();
                throw new RuntimeException("Waiting too long");
            }

            someoneNotConnected = false;
            for (ZooKeeper zk : zks) {
                if (zk.getState() != state) {
                    someoneNotConnected = true;
                    break;
                }
            }
            Thread.sleep(1000);
        }
    }

    // This class holds the servers and clients for those servers
    private static class Servers {
        MainThread mt[];
        ZooKeeper zk[];
        int[] clientPorts;

        public void shutDownAllServers() throws InterruptedException {
            for (MainThread t: mt) {
                t.shutdown();
            }
        }

        public void restartAllServersAndClients(Watcher watcher) throws IOException {
            for (MainThread t : mt) {
                if (!t.isAlive()) {
                    t.start();
                }
            }
            for (int i = 0; i < zk.length; i++) {
                restartClient(i, watcher);
            }
        }

        public void restartClient(int clientIndex, Watcher watcher) throws IOException {
            zk[clientIndex] = new ZooKeeper("127.0.0.1:" + clientPorts[clientIndex], ClientBase.CONNECTION_TIMEOUT, watcher);
        }

        public int findLeader() {
            for (int i = 0; i < mt.length; i++) {
                if (mt[i].main.quorumPeer.leader != null) {
                    return i;
                }
            }
            return -1;
        }
    }


  	private Servers LaunchServers(int numServers) throws IOException, InterruptedException {
  	    return LaunchServers(numServers, null);
    }

    /** * This is a helper function for launching a set of servers
  	 *
  	 * @param numServers the number of servers
     * @param tickTime A ticktime to pass to MainThread
  	 * @return
  	 * @throws IOException
  	 * @throws InterruptedException
  	 */
  	private Servers LaunchServers(int numServers, Integer tickTime) throws IOException, InterruptedException {
  	    int SERVER_COUNT = numServers;
  	    Servers svrs = new Servers();
  	    svrs.clientPorts = new int[SERVER_COUNT];
  	    StringBuilder sb = new StringBuilder();
  	    for(int i = 0; i < SERVER_COUNT; i++) {
  	        svrs.clientPorts[i] = PortAssignment.unique();
  	        sb.append("server."+i+"=127.0.0.1:"+PortAssignment.unique()+":"+PortAssignment.unique()+";"+svrs.clientPorts[i]+"\n");
  	    }
  	    String quorumCfgSection = sb.toString();

  	    svrs.mt = new MainThread[SERVER_COUNT];
        svrs.zk = new ZooKeeper[SERVER_COUNT];
  	    for(int i = 0; i < SERVER_COUNT; i++) {
  	        if (tickTime != null) {
  	            svrs.mt[i] = new MainThread(i, svrs.clientPorts[i], quorumCfgSection, new HashMap<String, String>(), tickTime);
            } else {
  	            svrs.mt[i] = new MainThread(i, svrs.clientPorts[i], quorumCfgSection);
            }
            svrs.mt[i].start();
  	        svrs.restartClient(i, this);
  	    }

  	    waitForAll(svrs, States.CONNECTED);

  	    return svrs;
  	}

    /**
     * Verify handling of bad quorum address
     */
    @Test
    public void testBadPeerAddressInQuorum() throws Exception {
        ClientBase.setupTestEnv();

        // setup the logger to capture all logs
        Layout layout =
                Logger.getRootLogger().getAppender("CONSOLE").getLayout();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        WriterAppender appender = new WriterAppender(layout, os);
        appender.setThreshold(Level.WARN);
        Logger qlogger = Logger.getLogger("org.apache.zookeeper.server.quorum");
        qlogger.addAppender(appender);

        try {
            final int CLIENT_PORT_QP1 = PortAssignment.unique();
            final int CLIENT_PORT_QP2 = PortAssignment.unique();

            String quorumCfgSection =
                "server.1=127.0.0.1:" + PortAssignment.unique()
                + ":" + PortAssignment.unique() + ";" + CLIENT_PORT_QP1
                + "\nserver.2=fee.fii.foo.fum:" + PortAssignment.unique()
                + ":" + PortAssignment.unique() + ";" + CLIENT_PORT_QP2;

            MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
            q1.start();

            boolean isup =
                    ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP1,
                    30000);

            Assert.assertFalse("Server never came up", isup);

            q1.shutdown();

            Assert.assertTrue("waiting for server 1 down",
                    ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP1,
                            ClientBase.CONNECTION_TIMEOUT));

        } finally {
            qlogger.removeAppender(appender);
        }

        LineNumberReader r = new LineNumberReader(new StringReader(os.toString()));
        String line;
        boolean found = false;
        Pattern p =
                Pattern.compile(".*Cannot open channel to .* at election address .*");
        while ((line = r.readLine()) != null) {
            found = p.matcher(line).matches();
            if (found) {
                break;
            }
        }
        Assert.assertTrue("complains about host", found);
    }

    /**
     * Verify handling of inconsistent peer type
     */
    @Test
    public void testInconsistentPeerType() throws Exception {
        ClientBase.setupTestEnv();

        // setup the logger to capture all logs
        Layout layout =
                Logger.getRootLogger().getAppender("CONSOLE").getLayout();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        WriterAppender appender = new WriterAppender(layout, os);
        appender.setThreshold(Level.INFO);
        Logger qlogger = Logger.getLogger("org.apache.zookeeper.server.quorum");
        qlogger.addAppender(appender);

        // test the most likely situation only: server is stated as observer in
        // servers list, but there's no "peerType=observer" token in config
        try {
            final int CLIENT_PORT_QP1 = PortAssignment.unique();
            final int CLIENT_PORT_QP2 = PortAssignment.unique();
            final int CLIENT_PORT_QP3 = PortAssignment.unique();

            String quorumCfgSection =
                "server.1=127.0.0.1:" + PortAssignment.unique()
                + ":" + PortAssignment.unique() + ";" + CLIENT_PORT_QP1
                + "\nserver.2=127.0.0.1:" + PortAssignment.unique()
                + ":" + PortAssignment.unique() + ";" + CLIENT_PORT_QP2
                + "\nserver.3=127.0.0.1:" + PortAssignment.unique()
                + ":" + PortAssignment.unique() + ":observer" + ";" + CLIENT_PORT_QP3;

            MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
            MainThread q2 = new MainThread(2, CLIENT_PORT_QP2, quorumCfgSection);
            MainThread q3 = new MainThread(3, CLIENT_PORT_QP3, quorumCfgSection);
            q1.start();
            q2.start();
            q3.start();

            Assert.assertTrue("waiting for server 1 being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP1,
                            CONNECTION_TIMEOUT));
            Assert.assertTrue("waiting for server 2 being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP2,
                            CONNECTION_TIMEOUT));
            Assert.assertTrue("waiting for server 3 being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP3,
                            CONNECTION_TIMEOUT));

            q1.shutdown();
            q2.shutdown();
            q3.shutdown();

            Assert.assertTrue("waiting for server 1 down",
                    ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP1,
                            ClientBase.CONNECTION_TIMEOUT));
            Assert.assertTrue("waiting for server 2 down",
                    ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP2,
                            ClientBase.CONNECTION_TIMEOUT));
            Assert.assertTrue("waiting for server 3 down",
                    ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP3,
                            ClientBase.CONNECTION_TIMEOUT));

        } finally {
            qlogger.removeAppender(appender);
        }

        LineNumberReader r = new LineNumberReader(new StringReader(os.toString()));
        String line;
        boolean warningPresent = false;
        boolean defaultedToObserver = false;
        Pattern pWarn =
                Pattern.compile(".*Peer type from servers list.* doesn't match peerType.*");
        Pattern pObserve = Pattern.compile(".*OBSERVING.*");
        while ((line = r.readLine()) != null) {
            if (pWarn.matcher(line).matches()) {
                warningPresent = true;
            }
            if (pObserve.matcher(line).matches()) {
                defaultedToObserver = true;
            }
            if (warningPresent && defaultedToObserver) {
                break;
            }
        }
        Assert.assertTrue("Should warn about inconsistent peer type",
                warningPresent && defaultedToObserver);
    }

    /**
     * verify if bad packets are being handled properly
     * at the quorum port
     * @throws Exception
     */
    @Test
    public void testBadPackets() throws Exception {
        ClientBase.setupTestEnv();
        final int CLIENT_PORT_QP1 = PortAssignment.unique();
        final int CLIENT_PORT_QP2 = PortAssignment.unique();
        int electionPort1 = PortAssignment.unique();
        int electionPort2 = PortAssignment.unique();
        String quorumCfgSection =
            "server.1=127.0.0.1:" + PortAssignment.unique()
            + ":" + electionPort1 + ";" + CLIENT_PORT_QP1
            + "\nserver.2=127.0.0.1:" + PortAssignment.unique()
            + ":" +  electionPort2 + ";" + CLIENT_PORT_QP2;

        MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
        MainThread q2 = new MainThread(2, CLIENT_PORT_QP2, quorumCfgSection);
        q1.start();
        q2.start();

        Assert.assertTrue("waiting for server 1 being up",
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP1,
                        CONNECTION_TIMEOUT));
        Assert.assertTrue("waiting for server 2 being up",
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP2,
                        CONNECTION_TIMEOUT));

        byte[] b = new byte[4];
        int length = 1024 * 1024 * 1024;
        ByteBuffer buff = ByteBuffer.wrap(b);
        buff.putInt(length);
        buff.position(0);
        SocketChannel s = SocketChannel.open(new InetSocketAddress("127.0.0.1", electionPort1));
        s.write(buff);
        s.close();
        buff.position(0);
        s = SocketChannel.open(new InetSocketAddress("127.0.0.1", electionPort2));
        s.write(buff);
        s.close();

        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT_QP1,
                ClientBase.CONNECTION_TIMEOUT, this);
        waitForOne(zk, States.CONNECTED);
        zk.create("/foo_q1", "foobar1".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        Assert.assertEquals(new String(zk.getData("/foo_q1", null, null)), "foobar1");
        zk.close();
        q1.shutdown();
        q2.shutdown();
    }

    /**
     * Verify handling of quorum defaults
     * * default electionAlg is fast leader election
     */
    @Test
    public void testQuorumDefaults() throws Exception {
        ClientBase.setupTestEnv();

        // setup the logger to capture all logs
        Layout layout =
                Logger.getRootLogger().getAppender("CONSOLE").getLayout();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        WriterAppender appender = new WriterAppender(layout, os);
        appender.setImmediateFlush(true);
        appender.setThreshold(Level.INFO);
        Logger zlogger = Logger.getLogger("org.apache.zookeeper");
        zlogger.addAppender(appender);

        try {
            final int CLIENT_PORT_QP1 = PortAssignment.unique();
            final int CLIENT_PORT_QP2 = PortAssignment.unique();

            String quorumCfgSection =
                "server.1=127.0.0.1:" + PortAssignment.unique()
                + ":" + PortAssignment.unique() + ";" + CLIENT_PORT_QP1
                + "\nserver.2=127.0.0.1:" + PortAssignment.unique()
                + ":" + PortAssignment.unique() + ";" + CLIENT_PORT_QP2;

            MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
            MainThread q2 = new MainThread(2, CLIENT_PORT_QP2, quorumCfgSection);
            q1.start();
            q2.start();

            Assert.assertTrue("waiting for server 1 being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP1,
                            CONNECTION_TIMEOUT));
            Assert.assertTrue("waiting for server 2 being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP2,
                            CONNECTION_TIMEOUT));

            q1.shutdown();
            q2.shutdown();

            Assert.assertTrue("waiting for server 1 down",
                    ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP1,
                            ClientBase.CONNECTION_TIMEOUT));
            Assert.assertTrue("waiting for server 2 down",
                    ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP2,
                            ClientBase.CONNECTION_TIMEOUT));

        } finally {
            zlogger.removeAppender(appender);
        }
        os.close();
        LineNumberReader r = new LineNumberReader(new StringReader(os.toString()));
        String line;
        boolean found = false;
        Pattern p =
                Pattern.compile(".*FastLeaderElection.*");
        while ((line = r.readLine()) != null) {
            found = p.matcher(line).matches();
            if (found) {
                break;
            }
        }
        Assert.assertTrue("fastleaderelection used", found);
    }

    /**
     * Verifies that QuorumPeer exits immediately
     */
    @Test
    public void testQuorumPeerExitTime() throws Exception {
        long maxwait = 3000;
        final int CLIENT_PORT_QP1 = PortAssignment.unique();
        String quorumCfgSection =
            "server.1=127.0.0.1:" + PortAssignment.unique()
            + ":" + PortAssignment.unique() + ";" + CLIENT_PORT_QP1
            + "\nserver.2=127.0.0.1:" + PortAssignment.unique()
            + ":" + PortAssignment.unique() + ";" + PortAssignment.unique();
        MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
        q1.start();
        // Let the notifications timeout
        Thread.sleep(30000);
        long start = Time.currentElapsedTime();
        q1.shutdown();
        long end = Time.currentElapsedTime();
        if ((end - start) > maxwait) {
            Assert.fail("QuorumPeer took " + (end - start) +
                    " to shutdown, expected " + maxwait);
        }
    }

    /**
     * Test verifies that the server is able to redefine the min/max session
     * timeouts
     */
    @Test
    public void testMinMaxSessionTimeOut() throws Exception {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT_QP1 = PortAssignment.unique();
        final int CLIENT_PORT_QP2 = PortAssignment.unique();

        String quorumCfgSection = "server.1=127.0.0.1:"
                + PortAssignment.unique() + ":" + PortAssignment.unique()
                + "\nserver.2=127.0.0.1:" + PortAssignment.unique() + ":"
                + PortAssignment.unique();

        final int minSessionTimeOut = 10000;
        final int maxSessionTimeOut = 15000;
        final String configs = "maxSessionTimeout=" + maxSessionTimeOut + "\n"
                + "minSessionTimeout=" + minSessionTimeOut + "\n";

        MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection,
                configs);
        MainThread q2 = new MainThread(2, CLIENT_PORT_QP2, quorumCfgSection,
                configs);
        q1.start();
        q2.start();

        Assert.assertTrue("waiting for server 1 being up", ClientBase
                .waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP1,
                        CONNECTION_TIMEOUT));
        Assert.assertTrue("waiting for server 2 being up", ClientBase
                .waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP2,
                        CONNECTION_TIMEOUT));

        QuorumPeer quorumPeer = q1.main.quorumPeer;

        Assert.assertEquals("minimumSessionTimeOut is not considered",
                minSessionTimeOut, quorumPeer.getMinSessionTimeout());
        Assert.assertEquals("maximumSessionTimeOut is not considered",
                maxSessionTimeOut, quorumPeer.getMaxSessionTimeout());
    }

    /**
     * Test verifies that the server is able to redefine if user configured only
     * minSessionTimeout limit
     */
    @Test
    public void testWithOnlyMinSessionTimeout() throws Exception {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT_QP1 = PortAssignment.unique();
        final int CLIENT_PORT_QP2 = PortAssignment.unique();

        String quorumCfgSection = "server.1=127.0.0.1:"
                + PortAssignment.unique() + ":" + PortAssignment.unique()
                + "\nserver.2=127.0.0.1:" + PortAssignment.unique() + ":"
                + PortAssignment.unique();

        final int minSessionTimeOut = 15000;
        final String configs = "minSessionTimeout=" + minSessionTimeOut + "\n";

        MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection,
                configs);
        MainThread q2 = new MainThread(2, CLIENT_PORT_QP2, quorumCfgSection,
                configs);
        q1.start();
        q2.start();

        Assert.assertTrue("waiting for server 1 being up", ClientBase
                .waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP1,
                        CONNECTION_TIMEOUT));
        Assert.assertTrue("waiting for server 2 being up", ClientBase
                .waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP2,
                        CONNECTION_TIMEOUT));

        QuorumPeer quorumPeer = q1.main.quorumPeer;
        final int maxSessionTimeOut = quorumPeer.tickTime * 20;

        Assert.assertEquals("minimumSessionTimeOut is not considered",
                minSessionTimeOut, quorumPeer.getMinSessionTimeout());
        Assert.assertEquals("maximumSessionTimeOut is wrong",
                maxSessionTimeOut, quorumPeer.getMaxSessionTimeout());
    }

    @Test
    public void testFailedTxnAsPartOfQuorumLoss() throws Exception {
        final int LEADER_TIMEOUT_MS = 10_000;
        // 1. start up server and wait for leader election to finish
        ClientBase.setupTestEnv();
        final int SERVER_COUNT = 3;
        servers = LaunchServers(SERVER_COUNT);

        waitForAll(servers, States.CONNECTED);

        // we need to shutdown and start back up to make sure that the create session isn't the first transaction since
        // that is rather innocuous.
        servers.shutDownAllServers();
        waitForAll(servers, States.CONNECTING);
        servers.restartAllServersAndClients(this);
        waitForAll(servers, States.CONNECTED);

        // 2. kill all followers
        int leader = servers.findLeader();
        Map<Long, Proposal> outstanding =  servers.mt[leader].main.quorumPeer.leader.outstandingProposals;
        // increase the tick time to delay the leader going to looking
        servers.mt[leader].main.quorumPeer.tickTime = LEADER_TIMEOUT_MS;
        LOG.warn("LEADER {}", leader);

        for (int i = 0; i < SERVER_COUNT; i++) {
            if (i != leader) {
                servers.mt[i].shutdown();
            }
        }

        // 3. start up the followers to form a new quorum
        for (int i = 0; i < SERVER_COUNT; i++) {
            if (i != leader) {
                servers.mt[i].start();
            }
        }

        // 4. wait one of the follower to be the new leader
        for (int i = 0; i < SERVER_COUNT; i++) {
            if (i != leader) {
                // Recreate a client session since the previous session was not persisted.
                servers.restartClient(i, this);
                waitForOne(servers.zk[i], States.CONNECTED);
            }
        }

        // 5. send a create request to old leader and make sure it's synced to disk,
        //    which means it acked from itself
        try {
            servers.zk[leader].create("/zk" + leader, "zk".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
            Assert.fail("create /zk" + leader + " should have failed");
        } catch (KeeperException e) {
        }

        // just make sure that we actually did get it in process at the
        // leader
        Assert.assertEquals(1, outstanding.size());
        Proposal p = outstanding.values().iterator().next();
        Assert.assertEquals(OpCode.create, p.request.getHdr().getType());

        // make sure it has a chance to write it to disk
        int sleepTime = 0;
        Long longLeader = new Long(leader);
        while (!p.qvAcksetPairs.get(0).getAckset().contains(longLeader)) {
            if (sleepTime > 2000) {
                Assert.fail("Transaction not synced to disk within 1 second " + p.qvAcksetPairs.get(0).getAckset()
                    + " expected " + leader);
            }
            Thread.sleep(100);
            sleepTime += 100;
        }

        // 6. wait for the leader to quit due to not enough followers and come back up as a part of the new quorum
        LOG.info("Waiting for leader {} to timeout followers", leader);
        sleepTime = 0;
        Follower f = servers.mt[leader].main.quorumPeer.follower;
        while (f == null || !f.isRunning()) {
            if (sleepTime > LEADER_TIMEOUT_MS * 2) {
                Assert.fail("Took too long for old leader to time out " + servers.mt[leader].main.quorumPeer.getPeerState());
            }
            Thread.sleep(100);
            sleepTime += 100;
            f = servers.mt[leader].main.quorumPeer.follower;
        }

        int newLeader = servers.findLeader();
        // make sure a different leader was elected
        Assert.assertNotEquals(leader, newLeader);

        // 7. restart the previous leader to force it to replay the edits and possibly come up in a bad state
        servers.mt[leader].shutdown();
        servers.mt[leader].start();
        waitForAll(servers, States.CONNECTED);

        // 8. check the node exist in previous leader but not others
        //    make sure everything is consistent
        for (int i = 0; i < SERVER_COUNT; i++) {
            Assert.assertNull("server " + i + " should not have /zk" + leader, servers.zk[i].exists("/zk" + leader, false));
        }
    }

    /**
     * Verify that a node without the leader in its view will not attempt to connect to the leader.
     */
    @Test
    public void testLeaderOutOfView() throws Exception {
        ClientBase.setupTestEnv();

        Layout layout = new PatternLayout("%d{ISO8601} [,yid:%X{myid}] - %5p [%t:%C{1}@%L] - %m%n");
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        WriterAppender appender = new WriterAppender(layout, os);
        appender.setThreshold(Level.DEBUG);
        Logger qlogger = Logger.getLogger("org.apache.zookeeper.server.quorum");
        qlogger.addAppender(appender);

        try {
            final int CLIENT_PORT_QP1 = PortAssignment.unique();
            final int CLIENT_PORT_QP2 = PortAssignment.unique();
            final int CLIENT_PORT_QP3 = PortAssignment.unique();

            String quorumCfgIncomplete = getUniquePortCfgForId(1) + "\n" + getUniquePortCfgForId(2);
            String quorumCfgComplete = quorumCfgIncomplete + "\n" + getUniquePortCfgForId(3);

            // Node 1 is started without the leader (3) in its config view
            MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgIncomplete);
            MainThread q2 = new MainThread(2, CLIENT_PORT_QP2, quorumCfgComplete);
            MainThread q3 = new MainThread(3, CLIENT_PORT_QP3, quorumCfgComplete);

            // Node 1 must be started first, before quorum is formed, to trigger the attempted invalid connection to 3
            q1.start();
            QuorumPeer quorumPeer1 = waitForQuorumPeer(q1, CONNECTION_TIMEOUT);
            Assert.assertTrue(quorumPeer1.getPeerState() == QuorumPeer.ServerState.LOOKING);

            // Node 3 started second to avoid 1 and 2 forming a quorum before 3 starts up
            q3.start();
            QuorumPeer quorumPeer3 = waitForQuorumPeer(q3, CONNECTION_TIMEOUT);
            Assert.assertTrue(quorumPeer3.getPeerState() == QuorumPeer.ServerState.LOOKING);

            // Node 2 started last, kicks off leader election
            q2.start();

            // Nodes 2 and 3 now form quorum and fully start. 1 attempts to vote for 3, fails, returns to LOOKING state
            Assert.assertTrue("waiting for server 2 to start",
                    ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP2, CONNECTION_TIMEOUT));
            Assert.assertTrue("waiting for server 3 to start",
                    ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP3, CONNECTION_TIMEOUT));

            Assert.assertTrue(q1.getQuorumPeer().getPeerState() == QuorumPeer.ServerState.LOOKING);
            Assert.assertTrue(q2.getQuorumPeer().getPeerState() == QuorumPeer.ServerState.FOLLOWING);
            Assert.assertTrue(q3.getQuorumPeer().getPeerState() == QuorumPeer.ServerState.LEADING);

            q1.shutdown();
            q2.shutdown();
            q3.shutdown();

            Assert.assertTrue("waiting for server 1 down",
                    ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP1,
                            ClientBase.CONNECTION_TIMEOUT));
            Assert.assertTrue("waiting for server 2 down",
                    ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP2,
                            ClientBase.CONNECTION_TIMEOUT));
            Assert.assertTrue("waiting for server 3 down",
                    ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP3,
                            ClientBase.CONNECTION_TIMEOUT));

        } finally {
            qlogger.removeAppender(appender);
        }

        // Verify that Node 1 never threw an exception
        LineNumberReader r = new LineNumberReader(new StringReader(os.toString()));
        String line;
        boolean found = false;
        Pattern p = Pattern.compile(".*java.lang.NullPointerException.*");
        while ((line = r.readLine()) != null) {
            found = p.matcher(line).matches();
            if (found) {
                break;
            }
        }

        Assert.assertFalse("Node should not attempt connection to out of view leader", found);
    }

    private String getUniquePortCfgForId(int id) {
        return String.format("server.%d=127.0.0.1:%d:%d", id, PortAssignment.unique(), PortAssignment.unique());
    }

    private QuorumPeer waitForQuorumPeer(MainThread mainThread, int timeout) {
        long start = Time.currentElapsedTime();
        while (true) {
            QuorumPeer quorumPeer = mainThread.getQuorumPeer();
            if (quorumPeer != null) {
                return quorumPeer;
            }

            if (Time.currentElapsedTime() > start + timeout) {
                LOG.error("Timed out while waiting for QuorumPeer");
                break;
            }

            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                // ignore
            }
        }
        throw new NullPointerException();
    }
}
