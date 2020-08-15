/*
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.admin.ZooKeeperAdmin;
import org.apache.zookeeper.client.FourLetterWordMain;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ReconfigTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class StandaloneDisabledTest extends QuorumPeerTestBase {

    private final int NUM_SERVERS = 5;
    private MainThread[] peers;
    private ZooKeeper[] zkHandles;
    private ZooKeeperAdmin[] zkAdminHandles;
    private int[] clientPorts;
    private final int leaderId = 0;
    private final int follower1 = 1;
    private final int follower2 = 2;
    private final int observer1 = 3;
    private final int observer2 = 4;
    private ArrayList<String> serverStrings;
    private ArrayList<String> reconfigServers;

    /**
     * Test normal quorum operations work cleanly
     * with just a single server.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    public void startSingleServerTest() throws Exception {
        setUpData();

        //start one server
        startServer(leaderId, serverStrings.get(leaderId) + "\n");
        ReconfigTest.testServerHasConfig(zkHandles[leaderId], null, null);
        LOG.info("Initial Configuration:\n{}", new String(zkHandles[leaderId].getConfig(this, new Stat())));

        //start and add 2 followers
        startFollowers();
        testReconfig(leaderId, true, reconfigServers);
        LOG.info("Configuration after adding 2 followers:\n{}", new String(zkHandles[leaderId].getConfig(this, new Stat())));

        //shutdown leader- quorum should still exist
        shutDownServer(leaderId);
        ReconfigTest.testNormalOperation(zkHandles[follower1], zkHandles[follower2]);

        //should not be able to remove follower 2
        //No quorum in new config (1/2)
        reconfigServers.clear();
        reconfigServers.add(Integer.toString(follower2));
        try {
            ReconfigTest.reconfig(zkAdminHandles[follower1], null, reconfigServers, null, -1);
            fail("reconfig completed successfully even though there is no quorum up in new config!");
        } catch (KeeperException.NewConfigNoQuorum e) {
        }

        //reconfigure out leader and follower 1. Remaining follower
        //2 should elect itself as leader and run by itself
        reconfigServers.clear();
        reconfigServers.add(Integer.toString(leaderId));
        reconfigServers.add(Integer.toString(follower1));
        testReconfig(follower2, false, reconfigServers);
        LOG.info("Configuration after removing leader and follower 1:\n{}", new String(zkHandles[follower2].getConfig(this, new Stat())));

        // Kill server 1 to avoid it interferences with FLE of the quorum {2, 3, 4}.
        shutDownServer(follower1);

        // Try to remove follower2, which is the only remaining server. This should fail.
        reconfigServers.clear();
        reconfigServers.add(Integer.toString(follower2));
        try {
            zkAdminHandles[follower2].reconfigure(null, reconfigServers, null, -1, new Stat());
            fail("reconfig completed successfully even though there is no quorum up in new config!");
        } catch (KeeperException.BadArgumentsException e) {
            // This is expected.
        } catch (Exception e) {
            fail("Should have been BadArgumentsException!");
        }

        //Add two participants and change them to observers to check
        //that we can reconfigure down to one participant with observers.
        ArrayList<String> observerStrings = new ArrayList<String>();
        startObservers(observerStrings);
        testReconfig(follower2, true, reconfigServers); //add partcipants
        testReconfig(follower2, true, observerStrings); //change to observers
        LOG.info("Configuration after adding two observers:\n{}", new String(zkHandles[follower2].getConfig(this, new Stat())));

        shutDownData();
    }

    /**
     * Initialize private data for test.
     */
    private void setUpData() throws Exception {
        ClientBase.setupTestEnv();
        QuorumPeerConfig.setStandaloneEnabled(false);
        QuorumPeerConfig.setReconfigEnabled(true);
        peers = new MainThread[NUM_SERVERS];
        zkHandles = new ZooKeeper[NUM_SERVERS];
        zkAdminHandles = new ZooKeeperAdmin[NUM_SERVERS];
        clientPorts = new int[NUM_SERVERS];
        serverStrings = buildServerStrings();
        reconfigServers = new ArrayList<String>();
        System.setProperty("zookeeper.DigestAuthenticationProvider.superDigest", "super:D/InIHSb7yEEbrWz8b9l71RjZJU="/* password is 'test'*/);
    }

    /**
     * Stop server threads.
     */
    private void shutDownData() throws Exception {
        for (int i = 0; i < NUM_SERVERS; i++) {
            zkHandles[i].close();
            zkAdminHandles[i].close();
        }
        for (int i = 1; i < NUM_SERVERS; i++) {
            peers[i].shutdown();
        }
    }

    /**
     * Create config strings that will be used for
     * the test servers.
     */
    private ArrayList<String> buildServerStrings() {
        ArrayList<String> serverStrings = new ArrayList<String>();

        for (int i = 0; i < NUM_SERVERS; i++) {
            clientPorts[i] = PortAssignment.unique();
            String server = "server." + i + "=localhost:" + PortAssignment.unique() + ":" + PortAssignment.unique() + ":participant;"
                            + "localhost:" + clientPorts[i];
            serverStrings.add(server);
        }
        return serverStrings;
    }

    /**
     * Starts a single server in replicated mode,
     * initializes its client, and waits for it
     * to be connected.
     */
    private void startServer(int id, String config) throws Exception {
        peers[id] = new MainThread(id, clientPorts[id], config);
        peers[id].start();
        assertTrue(
            ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[id], CONNECTION_TIMEOUT),
            "Server " + id + " is not up");
        assertTrue(peers[id].isQuorumPeerRunning(), "Error- Server started in Standalone Mode!");
        zkHandles[id] = ClientBase.createZKClient("127.0.0.1:" + clientPorts[id]);
        zkAdminHandles[id] = new ZooKeeperAdmin("127.0.0.1:" + clientPorts[id], CONNECTION_TIMEOUT, this);
        zkAdminHandles[id].addAuthInfo("digest", "super:test".getBytes());
        String statCommandOut = FourLetterWordMain.send4LetterWord("127.0.0.1", clientPorts[id], "stat");
        LOG.info("Started server id {} with config:\n{}\nStat output:\n{}", id, config, statCommandOut);
    }

    /**
     * Shuts down a server, waits for it to disconnect,
     * and gives enough time for the learner handler
     * in its ensemble to realize it's been shut down.
     */
    private void shutDownServer(int id) throws Exception {
        peers[id].shutdown();
        ClientBase.waitForServerDown("127.0.0.1:" + clientPorts[id], CONNECTION_TIMEOUT);
        TimeUnit.SECONDS.sleep(25);
    }

    /**
     * Starts servers 1 and 2 as participants and
     * adds them to the list to be reconfigured
     * into the ensemble.
     */
    private void startFollowers() throws Exception {
        reconfigServers.clear();
        for (int i = 1; i <= 2; i++) {
            String config = serverStrings.get(leaderId)
                                    + "\n"
                                    + serverStrings.get(i)
                                    + "\n"
                                    + serverStrings.get(i % 2 + 1)
                                    + "\n";
            startServer(i, config);
            reconfigServers.add(serverStrings.get(i));
        }
    }
    /**
     * Starts servers 1 and 2 as participants,
     * adds them to the list to be reconfigured
     * into the ensemble, and adds an observer
     * version of their information to a list
     * so they will be turned into observers later.
     */
    private void startObservers(ArrayList<String> observerStrings) throws Exception {
        reconfigServers.clear();
        for (int i = observer1; i <= observer2; i++) {
            String config = serverStrings.get(follower2) + "\n" + serverStrings.get(i) + "\n";
            startServer(i, config);
            reconfigServers.add(serverStrings.get(i));
            observerStrings.add(serverStrings.get(i).replace("participant", "observer"));
        }
    }

    /**
     * Calls reconfig on the client corresponding to id to add or remove
     * the given servers. Tests appropriately to make sure the
     * reconfig succeeded.
     */
    private void testReconfig(int id, boolean adding, ArrayList<String> servers) throws Exception {
        if (adding) {
            ReconfigTest.reconfig(zkAdminHandles[id], servers, null, null, -1);
            for (String server : servers) {
                int id2 = Integer.parseInt(server.substring(7, 8)); //server.#
                ReconfigTest.testNormalOperation(zkHandles[id], zkHandles[id2]);
            }
            ReconfigTest.testServerHasConfig(zkHandles[id], servers, null);
        } else {
            ReconfigTest.reconfig(zkAdminHandles[id], null, servers, null, -1);
            ReconfigTest.testServerHasConfig(zkHandles[id], null, servers);
        }

    }

    /**
     * Ensure observer cannot start by itself
     **/
    @Test
    public void startObserver() throws Exception {
        int clientPort = PortAssignment.unique();
        String config = "server." + observer1 + "=localhost:" + PortAssignment.unique() + ":" + clientPort
                        + ":observer;" + "localhost:" + PortAssignment.unique();
        MainThread observer = new MainThread(observer1, clientPort, config);
        observer.start();
        assertFalse(
            ClientBase.waitForServerUp("127.0.0.1:" + clientPort, CONNECTION_TIMEOUT),
            "Observer was able to start by itself!");
    }

}
