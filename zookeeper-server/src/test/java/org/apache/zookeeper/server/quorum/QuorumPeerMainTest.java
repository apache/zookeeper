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
import static org.apache.zookeeper.test.ClientBase.createEmptyTestDir;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.LineNumberReader;
import java.io.StringReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import javax.security.sasl.SaslException;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.WriterAppender;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.common.X509Exception;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
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
        WriterAppender appender = getConsoleAppender(os, Level.INFO);
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

    public static void waitForOne(ZooKeeper zk, States state) throws InterruptedException {
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

    public static void waitForAll(ZooKeeper[] zks, States state) throws InterruptedException {
        int iterations = ClientBase.CONNECTION_TIMEOUT / 1000;
        boolean someoneNotConnected = true;
        while (someoneNotConnected) {
            if (iterations-- == 0) {
                logStates(zks);
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

    public static void logStates(ZooKeeper[] zks) {
            StringBuilder sbBuilder = new StringBuilder("Connection States: {");
           for (int i = 0; i < zks.length; i++) {
                sbBuilder.append(i + " : " + zks[i].getState() + ", ");
           }
            sbBuilder.append('}');
            LOG.error(sbBuilder.toString());
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

        public void restartAllServersAndClients(Watcher watcher) throws IOException, InterruptedException {
            for (MainThread t : mt) {
                if (!t.isAlive()) {
                    t.start();
                }
            }
            for (int i = 0; i < zk.length; i++) {
                restartClient(i, watcher);
            }
        }

        public void restartClient(int clientIndex, Watcher watcher) throws IOException, InterruptedException {
            if (zk[clientIndex] != null) {
                zk[clientIndex].close();
            }
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

    /**
     * This is a helper function for launching a set of servers
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
        for (int i = 0; i < SERVER_COUNT; i++) {
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
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        WriterAppender appender = getConsoleAppender(os, Level.WARN);
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
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        WriterAppender appender = getConsoleAppender(os, Level.INFO);
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
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        WriterAppender appender = getConsoleAppender(os, Level.INFO);
        appender.setImmediateFlush(true);
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
        // there can be extra sessionClose proposals
        Assert.assertTrue(outstanding.size() > 0);
        Proposal p = findProposalOfType(outstanding, OpCode.create);
        LOG.info(String.format("Old leader id: %d. All proposals: %s", leader, outstanding));
        Assert.assertNotNull("Old leader doesn't have 'create' proposal", p);

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
        // old client session can expire, restart it
        servers.restartClient(leader, this);
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

        int numServers = 3;

        // used for assertions later
        boolean foundLeading = false;
        boolean foundFollowing = false;

        // capture QuorumPeer logging
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        WriterAppender appender = getConsoleAppender(os, Level.DEBUG);
        Logger qlogger = Logger.getLogger("org.apache.zookeeper.server.quorum");
        qlogger.addAppender(appender);

        try {
            Servers svrs = new Servers();
            svrs.clientPorts = new int[numServers];
            for (int i = 0; i < numServers; i++) {
                svrs.clientPorts[i] = PortAssignment.unique();
            }

            String quorumCfgIncomplete = getUniquePortCfgForId(1) + "\n" + getUniquePortCfgForId(2);
            String quorumCfgComplete = quorumCfgIncomplete + "\n" + getUniquePortCfgForId(3);
            svrs.mt = new MainThread[3];

            // Node 1 is started without the leader (3) in its config view
            svrs.mt[0] = new MainThread(1, svrs.clientPorts[0], quorumCfgIncomplete);
            for (int i = 1; i < numServers; i++) {
                svrs.mt[i] = new MainThread(i + 1, svrs.clientPorts[i], quorumCfgComplete);
            }

            // Node 1 must be started first, before quorum is formed, to trigger the attempted invalid connection to 3
            svrs.mt[0].start();
            QuorumPeer quorumPeer1 = waitForQuorumPeer(svrs.mt[0], CONNECTION_TIMEOUT);
            Assert.assertTrue(quorumPeer1.getPeerState() == QuorumPeer.ServerState.LOOKING);

            // Node 3 started second to avoid 1 and 2 forming a quorum before 3 starts up
            int highestServerIndex = numServers - 1;
            svrs.mt[highestServerIndex].start();
            QuorumPeer quorumPeer3 = waitForQuorumPeer(svrs.mt[highestServerIndex], CONNECTION_TIMEOUT);
            Assert.assertTrue(quorumPeer3.getPeerState() == QuorumPeer.ServerState.LOOKING);

            // Node 2 started last, kicks off leader election
            for (int i = 1; i < highestServerIndex; i++) {
                svrs.mt[i].start();
            }

            // Nodes 2 and 3 now form quorum and fully start. 1 attempts to vote for 3, fails, returns to LOOKING state
            for (int i = 1; i < numServers; i++) {
                Assert.assertTrue("waiting for server to start",
                        ClientBase.waitForServerUp("127.0.0.1:" + svrs.clientPorts[i], CONNECTION_TIMEOUT));
            }

            Assert.assertTrue(svrs.mt[0].getQuorumPeer().getPeerState() == QuorumPeer.ServerState.LOOKING);
            Assert.assertTrue(svrs.mt[highestServerIndex].getQuorumPeer().getPeerState() == QuorumPeer.ServerState.LEADING);
            for (int i = 1; i < highestServerIndex; i++) {
                Assert.assertTrue(svrs.mt[i].getQuorumPeer().getPeerState() == QuorumPeer.ServerState.FOLLOWING);
            }

            // Look through the logs for output that indicates Node 1 is LEADING or FOLLOWING
            LineNumberReader r = new LineNumberReader(new StringReader(os.toString()));
            Pattern leading = Pattern.compile(".*myid=1.*QuorumPeer.*LEADING.*");
            Pattern following = Pattern.compile(".*myid=1.*QuorumPeer.*FOLLOWING.*");

            String line;
            while ((line = r.readLine()) != null && !foundLeading && !foundFollowing) {
                foundLeading = leading.matcher(line).matches();
                foundFollowing = following.matcher(line).matches();
            }

        } finally {
            qlogger.removeAppender(appender);
        }

        Assert.assertFalse("Corrupt peer should never become leader", foundLeading);
        Assert.assertFalse("Corrupt peer should not attempt connection to out of view leader", foundFollowing);
    }

    @Test
    public void testDataDirAndDataLogDir() throws Exception {
        File dataDir = createEmptyTestDir();
        File dataLogDir = createEmptyTestDir();

        // Arrange
        try {
            QuorumPeerConfig configMock = mock(QuorumPeerConfig.class);
            when(configMock.getDataDir()).thenReturn(dataDir);
            when(configMock.getDataLogDir()).thenReturn(dataLogDir);

            QuorumPeer qpMock = mock(QuorumPeer.class);

            doCallRealMethod().when(qpMock).setTxnFactory(any(FileTxnSnapLog.class));
            when(qpMock.getTxnFactory()).thenCallRealMethod();
            InjectableQuorumPeerMain qpMain = new InjectableQuorumPeerMain(qpMock);

            // Act
            qpMain.runFromConfig(configMock);

            // Assert
            FileTxnSnapLog txnFactory = qpMain.getQuorumPeer().getTxnFactory();
            Assert.assertEquals(Paths.get(dataLogDir.getAbsolutePath(), "version-2").toString(), txnFactory.getDataDir().getAbsolutePath());
            Assert.assertEquals(Paths.get(dataDir.getAbsolutePath(), "version-2").toString(), txnFactory.getSnapDir().getAbsolutePath());
        } finally {
            FileUtils.deleteDirectory(dataDir);
            FileUtils.deleteDirectory(dataLogDir);
        }
    }

    private class InjectableQuorumPeerMain extends QuorumPeerMain {
        QuorumPeer qp;

        InjectableQuorumPeerMain(QuorumPeer qp) {
            this.qp = qp;
        }

        @Override
        protected QuorumPeer getQuorumPeer() {
            return qp;
        }
    }

    private WriterAppender getConsoleAppender(ByteArrayOutputStream os, Level level) {
        String loggingPattern = ((PatternLayout) Logger.getRootLogger().getAppender("CONSOLE").getLayout()).getConversionPattern();
        WriterAppender appender = new WriterAppender(new PatternLayout(loggingPattern), os);
        appender.setThreshold(level);
        return appender;
    }

    private String getUniquePortCfgForId(int id) {
        return String.format("server.%d=127.0.0.1:%d:%d", id, PortAssignment.unique(), PortAssignment.unique());
    }

    private QuorumPeer waitForQuorumPeer(MainThread mainThread, int timeout) throws TimeoutException {
        long start = Time.currentElapsedTime();
        while (true) {
            QuorumPeer quorumPeer = mainThread.isAlive() ? mainThread.getQuorumPeer() : null;
            if (quorumPeer != null) {
                return quorumPeer;
            }

            if (Time.currentElapsedTime() > start + timeout) {
                LOG.error("Timed out while waiting for QuorumPeer");
                throw new TimeoutException();
            }

            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                // ignore
            }
        }
    }
    
    private Proposal findProposalOfType(Map<Long, Proposal> proposals, int type) {
        for (Proposal proposal : proposals.values()) {
            if (proposal.request.getHdr().getType() == type) {
                return proposal;
            }
        }
        return null;
    }

    /**
     * Currently, in SNAP sync, the leader will start queuing the
     * proposal/commits and the NEWLEADER packet before sending
     * over the snapshot over wire. So it's possible that the zxid
     * associated with the snapshot might be higher than all the
     * packets queued before NEWLEADER.
     *
     * When the follower received the snapshot, it will apply all
     * the txns queued before NEWLEADER, which may not cover all
     * the txns up to the zxid in the snapshot. After that, it
     * will write the snapshot out to disk with the zxid associated
     * with the snapshot. In case the server crashed after writing
     * this out, when loading the data from disk, it will use zxid
     * of the snapshot file to sync with leader, and it could cause
     * data inconsistent, because we only replayed partial of the
     * historical data during previous syncing.
     *
     * This test case is going to cover and simulate this scenario
     * and make sure there is no data inconsistency issue after fix.
     */
    @Test
    public void testInconsistentDueToNewLeaderOrder() throws Exception {

        // 1. set up an ensemble with 3 servers
        final int ENSEMBLE_SERVERS = 3;
        final int clientPorts[] = new int[ENSEMBLE_SERVERS];
        StringBuilder sb = new StringBuilder();
        String server;

        for (int i = 0; i < ENSEMBLE_SERVERS; i++) {
            clientPorts[i] = PortAssignment.unique();
            server = "server." + i + "=127.0.0.1:" + PortAssignment.unique()
                    + ":" + PortAssignment.unique() + ":participant;127.0.0.1:"
                    + clientPorts[i];
            sb.append(server + "\n");
        }
        String currentQuorumCfgSection = sb.toString();

        // start servers
        MainThread[] mt = new MainThread[ENSEMBLE_SERVERS];
        ZooKeeper zk[] = new ZooKeeper[ENSEMBLE_SERVERS];
        Context contexts[] = new Context[ENSEMBLE_SERVERS];
        for (int i = 0; i < ENSEMBLE_SERVERS; i++) {
            final Context context = new Context();
            contexts[i] = context;
            mt[i] = new MainThread(i, clientPorts[i], currentQuorumCfgSection,
                    false) {
                @Override
                public TestQPMain getTestQPMain() {
                    return new CustomizedQPMain(context);
                }
            };
            mt[i].start();
            zk[i] = new ZooKeeper("127.0.0.1:" + clientPorts[i],
                    ClientBase.CONNECTION_TIMEOUT, this);
        }
        waitForAll(zk, States.CONNECTED);
        LOG.info("all servers started");

        String nodePath = "/testInconsistentDueToNewLeader";

        int leaderId = -1;
        int followerA = -1;
        for (int i = 0; i < ENSEMBLE_SERVERS; i++) {
            if (mt[i].main.quorumPeer.leader != null) {
                leaderId = i;
            } else if (followerA == -1) {
                followerA = i;
            }
        }
        LOG.info("shutdown follower {}", followerA);
        mt[followerA].shutdown();
        waitForOne(zk[followerA], States.CONNECTING);

        try {
            // 2. set force snapshot to be true
            LOG.info("force snapshot sync");
            System.setProperty(LearnerHandler.FORCE_SNAP_SYNC, "true");

            // 3. create a node
            String initialValue = "1";
            final ZooKeeper leaderZk = zk[leaderId];
            leaderZk.create(nodePath, initialValue.getBytes(), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);
            LOG.info("created node {} with value {}", nodePath, initialValue);

            CustomQuorumPeer leaderQuorumPeer =
                    (CustomQuorumPeer) mt[leaderId].main.quorumPeer;

            // 4. on the customized leader catch the startForwarding call
            //    (without synchronized), set the node to value v1, then
            //    call the super.startForwarding to generate the ongoing
            //    txn proposal and commit for v1 value update
            leaderQuorumPeer.setStartForwardingListener(
                    new StartForwardingListener() {
                @Override
                public void start() {
                    if (!Boolean.getBoolean(LearnerHandler.FORCE_SNAP_SYNC)) {
                        return;
                    }
                    final String value = "2";
                    LOG.info("start forwarding, set {} to {}", nodePath, value);
                    // use async, otherwise it will block the logLock in
                    // ZKDatabase and the setData request will timeout
                    try {
                        leaderZk.setData(nodePath, value.getBytes(), -1,
                                new AsyncCallback.StatCallback() {
                            public void processResult(int rc, String path,
                                   Object ctx, Stat stat) {}
                        }, null);
                        // wait for the setData txn being populated
                        Thread.sleep(1000);
                    } catch (Exception e) {
                        LOG.error("error when set {} to {}", nodePath, value, e);
                    }
                }
            });

            // 5. on the customized leader catch the beginSnapshot call in
            //    LearnerSnapshotThrottler to set the node to value v2,
            //    wait it hit data tree
            leaderQuorumPeer.setBeginSnapshotListener(new BeginSnapshotListener() {
                @Override
                public void start() {
                    String value = "3";
                    LOG.info("before sending snapshot, set {} to {}",
                            nodePath, value);
                    try {
                        leaderZk.setData(nodePath, value.getBytes(), -1);
                        LOG.info("successfully set {} to {}", nodePath, value);
                    } catch (Exception e) {
                        LOG.error("error when set {} to {}, {}", nodePath, value, e);
                    }
                }
            });

            // 6. exit follower A after taking snapshot
            CustomQuorumPeer followerAQuorumPeer =
                    ((CustomQuorumPeer) mt[followerA].main.quorumPeer);
            LOG.info("set exit when ack new leader packet on {}", followerA);
            contexts[followerA].exitWhenAckNewLeader = true;
            CountDownLatch latch = new CountDownLatch(1);
            final MainThread followerAMT = mt[followerA];
            contexts[followerA].newLeaderAckCallback = new NewLeaderAckCallback() {
                @Override
                public void start() {
                    try {
                        latch.countDown();
                        followerAMT.shutdown();
                    } catch (Exception e) {}
                }
            };

            // 7. start follower A to do snapshot sync
            LOG.info("starting follower {}", followerA);
            mt[followerA].start();
            Assert.assertTrue(latch.await(30, TimeUnit.SECONDS));

            // 8. now we have invalid data on disk, let's load it and verify
            LOG.info("disable exit when ack new leader packet on {}", followerA);
            System.setProperty(LearnerHandler.FORCE_SNAP_SYNC, "false");
            contexts[followerA].exitWhenAckNewLeader = true;
            contexts[followerA].newLeaderAckCallback = null;

            LOG.info("restarting follower {}", followerA);
            mt[followerA].start();
            zk[followerA].close();

            zk[followerA] = new ZooKeeper("127.0.0.1:" + clientPorts[followerA],
                    ClientBase.CONNECTION_TIMEOUT, this);

            // 9. start follower A, after it's in broadcast state, make sure
            //    the node value is same as what we have on leader
            waitForOne(zk[followerA], States.CONNECTED);
            Assert.assertEquals(
                new String(zk[followerA].getData(nodePath, null, null)),
                new String(zk[leaderId].getData(nodePath, null, null))
            );
        } finally {
            System.clearProperty(LearnerHandler.FORCE_SNAP_SYNC);
            for (int i = 0; i < ENSEMBLE_SERVERS; i++) {
                mt[i].shutdown();
                zk[i].close();
            }
        }
    }

    static class Context {
        boolean quitFollowing = false;
        boolean exitWhenAckNewLeader = false;
        NewLeaderAckCallback newLeaderAckCallback = null;
    }

    static interface NewLeaderAckCallback {
        public void start();
    }

    static interface StartForwardingListener {
        public void start();
    }

    static interface BeginSnapshotListener {
        public void start();
    }

    static class CustomizedQPMain extends TestQPMain {

        private Context context;

        public CustomizedQPMain(Context context) {
            this.context = context;
        }

        @Override
        protected QuorumPeer getQuorumPeer() throws SaslException {
            return new CustomQuorumPeer(context);
        }
    }

    static class CustomQuorumPeer extends QuorumPeer {
        private Context context;

        private StartForwardingListener startForwardingListener;
        private BeginSnapshotListener beginSnapshotListener;

        public CustomQuorumPeer(Context context)
                throws SaslException {
            this.context = context;
        }

        public void setStartForwardingListener(
                StartForwardingListener startForwardingListener) {
            this.startForwardingListener = startForwardingListener;
        }

        public void setBeginSnapshotListener(
                BeginSnapshotListener beginSnapshotListener) {
            this.beginSnapshotListener = beginSnapshotListener;
        }

        @Override
        protected Follower makeFollower(FileTxnSnapLog logFactory)
                throws IOException {
            return new Follower(this, new FollowerZooKeeperServer(logFactory,
                    this, this.getZkDb())) {

                @Override
                void writePacket(QuorumPacket pp, boolean flush) throws IOException {
                    if (pp != null && pp.getType() == Leader.ACK
                            && context.exitWhenAckNewLeader) {
                        if (context.newLeaderAckCallback != null) {
                            context.newLeaderAckCallback.start();
                        }
                    }
                    super.writePacket(pp, flush);
                }
            };
        }

        @Override
        protected Leader makeLeader(FileTxnSnapLog logFactory) throws IOException, X509Exception {
            return new Leader(this, new LeaderZooKeeperServer(logFactory,
                    this, this.getZkDb())) {
                @Override
                public long startForwarding(LearnerHandler handler,
                        long lastSeenZxid) {
                    if (startForwardingListener != null) {
                        startForwardingListener.start();
                    }
                    return super.startForwarding(handler, lastSeenZxid);
                }

                @Override
                public LearnerSnapshotThrottler createLearnerSnapshotThrottler(
                        int maxConcurrentSnapshots, long maxConcurrentSnapshotTimeout) {
                    return new LearnerSnapshotThrottler(
                            maxConcurrentSnapshots, maxConcurrentSnapshotTimeout) {

                        @Override
                        public LearnerSnapshot beginSnapshot(boolean essential)
                                throws SnapshotThrottleException, InterruptedException {
                            if (beginSnapshotListener != null) {
                                beginSnapshotListener.start();
                            }
                            return super.beginSnapshot(essential);
                        }
                    };
                }
            };
        }
    }
}
