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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NewConfigNoQuorum;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.admin.ZooKeeperAdmin;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.QuorumUtil;
import org.apache.zookeeper.test.ReconfigTest;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ReconfigFailureCasesTest extends QuorumPeerTestBase {

    private QuorumUtil qu;

    @Before
    public void setup() {
        QuorumPeerConfig.setReconfigEnabled(true);
        System.setProperty("zookeeper.DigestAuthenticationProvider.superDigest",
                "super:D/InIHSb7yEEbrWz8b9l71RjZJU="/* password is 'test'*/);
    }

    @After
    public void tearDown() throws Exception {
        if (qu != null) {
            qu.tearDown();
        }
    }

    /*
     * Tests that an incremental reconfig fails if the current config is hiearchical.
     */
    @Test
    public void testIncrementalReconfigInvokedOnHiearchicalQS() throws Exception {
        qu = new QuorumUtil(2); // create 5 servers
        qu.disableJMXTest = true;
        qu.startAll();
        ZooKeeper[] zkArr = ReconfigTest.createHandles(qu);
        ZooKeeperAdmin[] zkAdminArr = ReconfigTest.createAdminHandles(qu);

        ArrayList<String> members = new ArrayList<String>();
        members.add("group.1=3:4:5");
        members.add("group.2=1:2");
        members.add("weight.1=0");
        members.add("weight.2=0");
        members.add("weight.3=1");
        members.add("weight.4=1");
        members.add("weight.5=1");

        for (int i = 1; i <= 5; i++) {
            members.add("server." + i + "=127.0.0.1:"
                    + qu.getPeer(i).peer.getQuorumAddress().getPort() + ":"
                    + qu.getPeer(i).peer.getElectionAddress().getPort() + ";"
                    + "127.0.0.1:" + qu.getPeer(i).peer.getClientPort());
        }

        // Change the quorum system from majority to hierarchical.
        ReconfigTest.reconfig(zkAdminArr[1], null, null, members, -1);
        ReconfigTest.testNormalOperation(zkArr[1], zkArr[2]);

        // Attempt an incremental reconfig.
        List<String> leavingServers = new ArrayList<String>();
        leavingServers.add("3");
        try {
             zkAdminArr[1].reconfigure(null, leavingServers, null, -1, null);
            Assert.fail("Reconfig should have failed since the current config isn't Majority QS");
        } catch (KeeperException.BadArgumentsException e) {
            // We expect this to happen.
        } catch (Exception e) {
            Assert.fail("Should have been BadArgumentsException!");
        }

        ReconfigTest.closeAllHandles(zkArr, zkAdminArr);
    }

    /*
     * Test that a reconfiguration fails if the proposed change would leave the
     * cluster with less than 2 participants (StandaloneEnabled = true).
     * StandaloneDisabledTest.java (startSingleServerTest) checks that if
     * StandaloneEnabled = false its legal to remove all but one remaining
     * server.
     */
    @Test
    public void testTooFewRemainingPariticipants() throws Exception {
        qu = new QuorumUtil(1); // create 3 servers
        qu.disableJMXTest = true;
        qu.startAll();
        ZooKeeper[] zkArr = ReconfigTest.createHandles(qu);
        ZooKeeperAdmin[] zkAdminArr = ReconfigTest.createAdminHandles(qu);

        List<String> leavingServers = new ArrayList<String>();
        leavingServers.add("2");
        leavingServers.add("3");
        try {
             zkAdminArr[1].reconfigure(null, leavingServers, null, -1, null);
            Assert.fail("Reconfig should have failed since the current config version is not 8");
        } catch (KeeperException.BadArgumentsException e) {
            // We expect this to happen.
        } catch (Exception e) {
            Assert.fail("Should have been BadArgumentsException!");
        }

        ReconfigTest.closeAllHandles(zkArr, zkAdminArr);
    }

    /*
     * Tests that a conditional reconfig fails if the specified version doesn't correspond
     * to the version of the current config.
     */
    @Test
    public void testReconfigVersionConditionFails() throws Exception {
        qu = new QuorumUtil(1); // create 3 servers
        qu.disableJMXTest = true;
        qu.startAll();
        ZooKeeper[] zkArr = ReconfigTest.createHandles(qu);
        ZooKeeperAdmin[] zkAdminArr = ReconfigTest.createAdminHandles(qu);

        List<String> leavingServers = new ArrayList<String>();
        leavingServers.add("3");
        try {
             zkAdminArr[1].reconfigure(null, leavingServers, null, 8, null);
            Assert.fail("Reconfig should have failed since the current config version is not 8");
        } catch (KeeperException.BadVersionException e) {
            // We expect this to happen.
        } catch (Exception e) {
            Assert.fail("Should have been BadVersionException!");
        }

        ReconfigTest.closeAllHandles(zkArr, zkAdminArr);
    }

    /*
     * Tests that if a quorum of a new config is synced with the leader and a reconfig
     * is allowed to start but then the new quorum is lost, the leader will time out and
     * we go to leader election.
     */
    @Test
    public void testLeaderTimesoutOnNewQuorum() throws Exception {
        qu = new QuorumUtil(1); // create 3 servers
        qu.disableJMXTest = true;
        qu.startAll();
        ZooKeeper[] zkArr = ReconfigTest.createHandles(qu);
        ZooKeeperAdmin[] zkAdminArr = ReconfigTest.createAdminHandles(qu);

        List<String> leavingServers = new ArrayList<String>();
        leavingServers.add("3");
        qu.shutdown(2);
        try {
            // Since we just shut down server 2, its still considered "synced"
            // by the leader, which allows us to start the reconfig
            // (PrepRequestProcessor checks that a quorum of the new
            // config is synced before starting a reconfig).
            // We try to remove server 3, which requires a quorum of {1,2,3}
            // (we have that) and of {1,2}, but 2 is down so we won't get a
            // quorum of new config ACKs.
            zkAdminArr[1].reconfigure(null, leavingServers, null, -1, null);
            Assert.fail("Reconfig should have failed since we don't have quorum of new config");
        } catch (KeeperException.ConnectionLossException e) {
            // We expect leader to lose quorum of proposed config and time out
        } catch (Exception e) {
            Assert.fail("Should have been ConnectionLossException!");
        }

        // The leader should time out and remaining servers should go into
        // LOOKING state. A new leader won't be established since that
        // would require completing the reconfig, which is not possible while
        // 2 is down.
        Assert.assertEquals(QuorumStats.Provider.LOOKING_STATE,
                qu.getPeer(1).peer.getServerState());
        Assert.assertEquals(QuorumStats.Provider.LOOKING_STATE,
                qu.getPeer(3).peer.getServerState());
        ReconfigTest.closeAllHandles(zkArr, zkAdminArr);
    }

    /*
     * Converting an observer into a participant may sometimes fail with a
     * NewConfigNoQuorum exception. This test-case demonstrates the scenario.
     * Current configuration is (A, B, C, D), where A, B and C are participant
     * and D is an observer. Suppose that B has crashed (or never booted). If a
     * reconfiguration is submitted where D is said to become a participant, it
     * will fail with NewConfigNoQuorum since in this configuration, a majority
     * of voters in the new configuration (any 3 voters), must be connected and
     * up-to-date with the leader. An observer cannot acknowledge the history
     * prefix sent during reconfiguration, and therefore it does not count towards
     * these 3 required servers and the reconfiguration will be aborted. In case
     * this happens, a client can achieve the same task by two reconfig commands:
     * first invoke a reconfig to remove D from the configuration and then invoke a
     * second command to add it back as a participant (follower). During the
     * intermediate state D is a non-voting follower and can ACK the state
     * transfer performed during the second reconfig command.
     */
    @Test
    public void testObserverToParticipantConversionFails() throws Exception {
        ClientBase.setupTestEnv();

        final int SERVER_COUNT = 4;
        int[][] ports = ReconfigRecoveryTest.generatePorts(SERVER_COUNT);

        // generate old config string
        Set<Integer> observers = new HashSet<Integer>();
        observers.add(3);
        StringBuilder sb = ReconfigRecoveryTest.generateConfig(SERVER_COUNT, ports, observers);
        String currentQuorumCfgSection = sb.toString();
        String nextQuorumCfgSection = currentQuorumCfgSection.replace("observer", "participant");

        MainThread mt[] = new MainThread[SERVER_COUNT];
        ZooKeeper zk[] = new ZooKeeper[SERVER_COUNT];
        ZooKeeperAdmin zkAdmin[] = new ZooKeeperAdmin[SERVER_COUNT];

        // Server 0 stays down
        for (int i = 1; i < SERVER_COUNT; i++) {
            mt[i] = new MainThread(i, ports[i][2], currentQuorumCfgSection,
                    true, "100000000");
            mt[i].start();
            zk[i] = new ZooKeeper("127.0.0.1:" + ports[i][2],
                    ClientBase.CONNECTION_TIMEOUT, this);
            zkAdmin[i] = new ZooKeeperAdmin("127.0.0.1:" + ports[i][2],
                    ClientBase.CONNECTION_TIMEOUT, this);
            zkAdmin[i].addAuthInfo("digest", "super:test".getBytes());
        }

        for (int i = 1; i < SERVER_COUNT; i++) {
            Assert.assertTrue("waiting for server " + i + " being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + ports[i][2],
                            CONNECTION_TIMEOUT * 2));
        }

        try {
            zkAdmin[1].reconfigure("", "", nextQuorumCfgSection, -1, new Stat());
            Assert.fail("Reconfig should have failed with NewConfigNoQuorum");
        } catch (NewConfigNoQuorum e) {
            // This is expected case since server 0 is down and 3 can't vote
            // (observer in current role) and we need 3 votes from 0, 1, 2, 3,
        } catch (Exception e) {
            Assert.fail("Reconfig should have failed with NewConfigNoQuorum");
        }
        // In this scenario to change 3's role to participant we need to remove it first
        ArrayList<String> leavingServers = new ArrayList<String>();
        leavingServers.add("3");
        ReconfigTest.reconfig(zkAdmin[1], null, leavingServers, null, -1);
        ReconfigTest.testNormalOperation(zk[2], zk[3]);
        ReconfigTest.testServerHasConfig(zk[3], null, leavingServers);

        // Now we're adding it back as a participant and everything should work.
        List<String> newMembers = Arrays.asList(nextQuorumCfgSection.split("\n"));
        ReconfigTest.reconfig(zkAdmin[1], null, null, newMembers, -1);
        ReconfigTest.testNormalOperation(zk[2], zk[3]);
        for (int i = 1; i < SERVER_COUNT; i++) {
            ReconfigTest.testServerHasConfig(zk[i], newMembers, null);
        }
        for (int i = 1; i < SERVER_COUNT; i++) {
            zk[i].close();
            zkAdmin[i].close();
            mt[i].shutdown();
        }
    }
}
