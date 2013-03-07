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

import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ReconfigTest;
import org.junit.Assert;
import org.junit.Test;

public class ReconfigRecoveryTest extends QuorumPeerTestBase {
    /**
     * Reconfiguration recovery - test that a reconfiguration is completed 
     * if leader has .next file during startup and new config is not running yet
     */
    @Test
    public void testNextConfigCompletion() throws Exception {
        ClientBase.setupTestEnv();
        
        // 2 servers in current config, 3 in next config
        final int SERVER_COUNT = 3;
        final int clientPorts[] = new int[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        String server;
        ArrayList<String> allServers = new ArrayList<String>();

        String currentQuorumCfgSection = null, nextQuorumCfgSection;
        
        for(int i = 0; i < SERVER_COUNT; i++) {
               clientPorts[i] = PortAssignment.unique();
               server = "server." + i + "=localhost:"+PortAssignment.unique()+":"+PortAssignment.unique() + 
                      ":participant;localhost:" + clientPorts[i];        
               allServers.add(server);
               sb.append(server +"\n");
               if (i == 1) currentQuorumCfgSection = sb.toString();
        }
        sb.append("version=1\n"); // version of current config is 0
        nextQuorumCfgSection = sb.toString();
        
        // Both servers 0 and 1 will have the .next config file, which means
        // for them that a reconfiguration was in progress when they failed
        // and the leader will complete it
        MainThread mt[] = new MainThread[SERVER_COUNT];
        ZooKeeper zk[] = new ZooKeeper[SERVER_COUNT];
        for (int i = 0; i < SERVER_COUNT - 1; i++) {
            mt[i] = new MainThreadReconfigRecovery(i, clientPorts[i], currentQuorumCfgSection, nextQuorumCfgSection);
            mt[i].start();
            zk[i] = new ZooKeeper("127.0.0.1:" + clientPorts[i], ClientBase.CONNECTION_TIMEOUT, this);
        }
        
        Assert.assertTrue("waiting for server 0 being up",
                ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[0],
                        CONNECTION_TIMEOUT));
        Assert.assertTrue("waiting for server 1 being up",
                ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[1],
                        CONNECTION_TIMEOUT));
         
        int leader = mt[0].main.quorumPeer.leader == null ? 1: 0;
              
        // the new server's config is going to include itself and the current leader
        sb = new StringBuilder();
        sb.append(allServers.get(leader) + "\n");
        sb.append(allServers.get(2) + "\n");
        
        // suppose that this new server never heard about the reconfig proposal
        String newServerInitialConfig = sb.toString();
        mt[2] = new MainThread(2, clientPorts[2], newServerInitialConfig);
        mt[2].start();
        zk[2] = new ZooKeeper("127.0.0.1:" + clientPorts[2], ClientBase.CONNECTION_TIMEOUT, this);

       Assert.assertTrue("waiting for server 2 being up",
                ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[2],
                        CONNECTION_TIMEOUT));
        
        ReconfigTest.testServerHasConfig(zk[0], allServers, null);
        ReconfigTest.testServerHasConfig(zk[1], allServers, null);
        ReconfigTest.testServerHasConfig(zk[2], allServers, null);
        
        ReconfigTest.testNormalOperation(zk[0], zk[2]);
        ReconfigTest.testNormalOperation(zk[2], zk[1]);

        zk[2].close();
        mt[2].shutdown();
        
        //now suppose that the new server heard the reconfig request
        mt[2] = new MainThreadReconfigRecovery(2, clientPorts[2], newServerInitialConfig, nextQuorumCfgSection);
        mt[2].start();
        zk[2] = new ZooKeeper("127.0.0.1:" + clientPorts[2], ClientBase.CONNECTION_TIMEOUT, this);

       Assert.assertTrue("waiting for server 2 being up",
                ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[2],
                        CONNECTION_TIMEOUT));
        
        ReconfigTest.testServerHasConfig(zk[0], allServers, null);
        ReconfigTest.testServerHasConfig(zk[1], allServers, null);
        ReconfigTest.testServerHasConfig(zk[2], allServers, null);
        
        ReconfigTest.testNormalOperation(zk[0], zk[2]);
        ReconfigTest.testNormalOperation(zk[2], zk[1]);

        for (int i = 0; i < SERVER_COUNT; i++) {
            zk[i].close();
        }
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].shutdown();
        }
    }

    /**
     * Reconfiguration recovery - current config servers discover .next file,
     * but they're both observers and their ports change in next config. Suppose that next config wasn't activated yet.
     * Should complete reconfiguration. 
     */
    @Test
    public void testCurrentServersAreObserversInNextConfig() throws Exception {
        ClientBase.setupTestEnv();
        
        // 2 servers in current config, 5 in next config
        final int SERVER_COUNT = 5;
        final int clientPorts[] = new int[SERVER_COUNT];
        final int oldClientPorts[] = new int[2];
        StringBuilder sb = new StringBuilder();
        String server;

        String currentQuorumCfgSection = null, nextQuorumCfgSection;
        
        ArrayList<String> allServersCurrent = new ArrayList<String>();
        ArrayList<String> allServersNext = new ArrayList<String>();
        
        
        for(int i = 0; i < 2; i++) {
               oldClientPorts[i] = PortAssignment.unique();
               server = "server." + i + "=localhost:"+PortAssignment.unique()+":"+PortAssignment.unique() + 
                      ":participant;localhost:" + oldClientPorts[i];        
               allServersCurrent.add(server);
               sb.append(server +"\n");
        }
        
        currentQuorumCfgSection = sb.toString();
        sb = new StringBuilder();
        String role;
        for (int i=0; i<SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            if (i < 2) role = "observer";
            else role = "participant";
            server = "server." + i + "=localhost:"+PortAssignment.unique()+":"+PortAssignment.unique() + 
                  ":" + role + ";localhost:" + clientPorts[i];        
            allServersNext.add(server);
            sb.append(server +"\n");
           
        }
        sb.append("version=1\n"); // version of current config is 0
        nextQuorumCfgSection = sb.toString();
        
        MainThread mt[] = new MainThread[SERVER_COUNT];
        ZooKeeper zk[] = new ZooKeeper[SERVER_COUNT];

        for (int i = 0; i < 2; i++) {
            mt[i] = new MainThreadReconfigRecovery(i, oldClientPorts[i], currentQuorumCfgSection, nextQuorumCfgSection);
            mt[i].start();
            zk[i] = new ZooKeeper("127.0.0.1:" + clientPorts[i], ClientBase.CONNECTION_TIMEOUT, this);
        }

        // new members are initialized with current config + the new server
        for (int i = 2; i < SERVER_COUNT; i++) {
            mt[i] = new MainThread(i, clientPorts[i], currentQuorumCfgSection + allServersNext.get(i));
            mt[i].start();
            zk[i] = new ZooKeeper("127.0.0.1:" + clientPorts[i], ClientBase.CONNECTION_TIMEOUT, this);
        }

        for (int i=0; i<SERVER_COUNT; i++) {
            Assert.assertTrue("waiting for server "+ i + " being up",
                ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i],
                        CONNECTION_TIMEOUT * 2));
            ReconfigTest.testServerHasConfig(zk[i], allServersNext, null);  
        }
        
        ReconfigTest.testNormalOperation(zk[0], zk[2]);
        ReconfigTest.testNormalOperation(zk[4], zk[1]);

        for (int i = 0; i < SERVER_COUNT; i++) {
            zk[i].close();
        }
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].shutdown();
        }

     }
    
    
    
    /**
     * Reconfiguration recovery - test that if servers in old config have a .next file
     * but no quorum of new config is up then no progress should be possible (no progress will happen
     * to ensure safety as the new config might be actually up but partitioned from old config)
     */
    @Test
    public void testNextConfigUnreachable() throws Exception {
        ClientBase.setupTestEnv();
        
        // 2 servers in current config, 5 in next config
        final int SERVER_COUNT = 5;
        final int clientPorts[] = new int[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        String server;

        String currentQuorumCfgSection = null, nextQuorumCfgSection;
        
        ArrayList<String> allServers = new ArrayList<String>();
        for(int i = 0; i < SERVER_COUNT; i++) {
               clientPorts[i] = PortAssignment.unique();
               server = "server." + i + "=localhost:"+PortAssignment.unique()+":"+PortAssignment.unique() + 
                      ":participant;localhost:" + clientPorts[i];        
               allServers.add(server);
               sb.append(server +"\n");
               if (i == 1) currentQuorumCfgSection = sb.toString();
        }
        sb.append("version=1\n"); // version of current config is 0
        nextQuorumCfgSection = sb.toString();
        
        // lets start servers 2, 3, 4 with the new config
        MainThread mt[] = new MainThread[SERVER_COUNT];
        ZooKeeper zk[] = new ZooKeeper[SERVER_COUNT];

        // Both servers 0 and 1 will have the .next config file, which means
        // for them that a reconfiguration was in progress when they failed
        // and the leader will complete it. 
        for (int i = 0; i < 2; i++) {
            mt[i] = new MainThreadReconfigRecovery(i, clientPorts[i], currentQuorumCfgSection, nextQuorumCfgSection);
            mt[i].start();
            zk[i] = new ZooKeeper("127.0.0.1:" + clientPorts[i], ClientBase.CONNECTION_TIMEOUT, this);
        }

        Thread.sleep(CONNECTION_TIMEOUT*2);
        
        // make sure servers 0, 1 don't come online
        for (int i = 0; i < 2; i++) {
           Assert.assertFalse("server " +  i + " is up but shouldn't be",
                    ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i],
                            CONNECTION_TIMEOUT / 10));
        }
 
        for (int i = 0; i < 2; i++) {
            zk[i].close();
        }
        for (int i = 0; i < 2; i++) {
            mt[i].shutdown();
        }
    }
    
    /**
     * Reconfiguration recovery - test that old config members will join the new config 
     * if its already active, and not try to complete the reconfiguration
     */
    @Test
    public void testNextConfigAlreadyActive() throws Exception {
        ClientBase.setupTestEnv();
        
        // 2 servers in current config, 5 in next config
        final int SERVER_COUNT = 5;
        final int clientPorts[] = new int[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        String server;

        String currentQuorumCfgSection = null, nextQuorumCfgSection;
        
        ArrayList<String> allServers = new ArrayList<String>();
        for(int i = 0; i < SERVER_COUNT; i++) {
               clientPorts[i] = PortAssignment.unique();
               server = "server." + i + "=localhost:"+PortAssignment.unique()+":"+PortAssignment.unique() + 
                      ":participant;localhost:" + clientPorts[i];        
               allServers.add(server);
               sb.append(server +"\n");
               if (i == 1) currentQuorumCfgSection = sb.toString();
        }
        sb.append("version=1\n"); // version of current config is 0
        nextQuorumCfgSection = sb.toString();
        
        // lets start servers 2, 3, 4 with the new config
        MainThread mt[] = new MainThread[SERVER_COUNT];
        ZooKeeper zk[] = new ZooKeeper[SERVER_COUNT];
        for (int i = 2; i < SERVER_COUNT; i++) {
            mt[i] = new MainThread(i, clientPorts[i], nextQuorumCfgSection);
            mt[i].start();
            zk[i] = new ZooKeeper("127.0.0.1:" + clientPorts[i], ClientBase.CONNECTION_TIMEOUT, this);
        }
        for (int i = 2; i < SERVER_COUNT; i++) {            
            Assert.assertTrue("waiting for server " + i + " being up",
                ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i],
                        CONNECTION_TIMEOUT));
        }

        ReconfigTest.testNormalOperation(zk[2], zk[3]);

        long epoch = mt[2].main.quorumPeer.getAcceptedEpoch();
        
        // Both servers 0 and 1 will have the .next config file, which means
        // for them that a reconfiguration was in progress when they failed
        // and the leader will complete it. 
        for (int i = 0; i < 2; i++) {
            mt[i] = new MainThreadReconfigRecovery(i, clientPorts[i], currentQuorumCfgSection, nextQuorumCfgSection);
            mt[i].start();
            zk[i] = new ZooKeeper("127.0.0.1:" + clientPorts[i], ClientBase.CONNECTION_TIMEOUT, this);
        }

        
        // servers 0 and 1 should connect to all servers, including the one in their
        // .next file during startup, and will find the next config and join it
        for (int i = 0; i < 2; i++) {
           Assert.assertTrue("waiting for server " +  i + " being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i],
                            CONNECTION_TIMEOUT*2));
        }
        
        // make sure they joined the new config without any change to it
        Assert.assertEquals(epoch, mt[0].main.quorumPeer.getAcceptedEpoch());
        Assert.assertEquals(epoch, mt[1].main.quorumPeer.getAcceptedEpoch());
        Assert.assertEquals(epoch, mt[2].main.quorumPeer.getAcceptedEpoch());
        
        ReconfigTest.testServerHasConfig(zk[0], allServers, null);
        ReconfigTest.testServerHasConfig(zk[1], allServers, null);
        
        ReconfigTest.testNormalOperation(zk[0], zk[2]);
        ReconfigTest.testNormalOperation(zk[4], zk[1]);

       
        for (int i = 0; i < SERVER_COUNT; i++) {
            zk[i].close();
        }
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].shutdown();
        }
    }
    
 
    
}
