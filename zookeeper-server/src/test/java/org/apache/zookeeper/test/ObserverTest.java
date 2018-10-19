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

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;

import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.ConnectionLossException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.junit.Assert;
import org.junit.Test;

public class ObserverTest extends QuorumPeerTestBase implements Watcher{
    protected static final Logger LOG =
        LoggerFactory.getLogger(ObserverTest.class);    
      
    CountDownLatch latch;
    ZooKeeper zk;
    WatchedEvent lastEvent = null;
          
    /**
     * This test ensures two things:
     * 1. That Observers can successfully proxy requests to the ensemble.
     * 2. That Observers don't participate in leader elections.
     * The second is tested by constructing an ensemble where a leader would
     * be elected if and only if an Observer voted. 
     * @throws Exception
     */
    @Test
    public void testObserver() throws Exception {
        ClientBase.setupTestEnv();
        // We expect two notifications before we want to continue        
        latch = new CountDownLatch(2);
        
        final int PORT_QP1 = PortAssignment.unique();
        final int PORT_QP2 = PortAssignment.unique();
        final int PORT_OBS = PortAssignment.unique();
        final int PORT_QP_LE1 = PortAssignment.unique();
        final int PORT_QP_LE2 = PortAssignment.unique();
        final int PORT_OBS_LE = PortAssignment.unique();

        final int CLIENT_PORT_QP1 = PortAssignment.unique();
        final int CLIENT_PORT_QP2 = PortAssignment.unique();
        final int CLIENT_PORT_OBS = PortAssignment.unique();

        
        String quorumCfgSection = 
            "server.1=127.0.0.1:" + (PORT_QP1)
            + ":" + (PORT_QP_LE1) + ";" +  CLIENT_PORT_QP1 
            + "\nserver.2=127.0.0.1:" + (PORT_QP2)
            + ":" + (PORT_QP_LE2) + ";" + CLIENT_PORT_QP2  
            + "\nserver.3=127.0.0.1:" 
            + (PORT_OBS)+ ":" + (PORT_OBS_LE) + ":observer" + ";" + CLIENT_PORT_OBS;
        
        MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
        MainThread q2 = new MainThread(2, CLIENT_PORT_QP2, quorumCfgSection);
        MainThread q3 = new MainThread(3, CLIENT_PORT_OBS, quorumCfgSection);
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
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_OBS,
                        CONNECTION_TIMEOUT));        
        
        zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT_OBS,
                ClientBase.CONNECTION_TIMEOUT, this);
        zk.create("/obstest", "test".getBytes(),Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        
        // Assert that commands are getting forwarded correctly
        Assert.assertEquals(new String(zk.getData("/obstest", null, null)), "test");
        
        // Now check that other commands don't blow everything up
        zk.sync("/", null, null);
        zk.setData("/obstest", "test2".getBytes(), -1);
        zk.getChildren("/", false);
        
        Assert.assertEquals(zk.getState(), States.CONNECTED);
        
        LOG.info("Shutting down server 2");
        // Now kill one of the other real servers        
        q2.shutdown();
                
        Assert.assertTrue("Waiting for server 2 to shut down",
                    ClientBase.waitForServerDown("127.0.0.1:"+CLIENT_PORT_QP2, 
                                    ClientBase.CONNECTION_TIMEOUT));

        LOG.info("Server 2 down");

        // Now the resulting ensemble shouldn't be quorate         
        latch.await();        
        Assert.assertNotSame("Client is still connected to non-quorate cluster", 
                KeeperState.SyncConnected,lastEvent.getState());

        LOG.info("Latch returned");

        try {
            Assert.assertFalse("Shouldn't get a response when cluster not quorate!",
                    new String(zk.getData("/obstest", null, null)).equals("test"));
        }
        catch (ConnectionLossException c) {
            LOG.info("Connection loss exception caught - ensemble not quorate (this is expected)");
        }
        
        latch = new CountDownLatch(1);

        LOG.info("Restarting server 2");

        // Bring it back
        q2 = new MainThread(2, CLIENT_PORT_QP2, quorumCfgSection);
        q2.start();
        
        LOG.info("Waiting for server 2 to come up");
        Assert.assertTrue("waiting for server 2 being up",
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP2,
                        CONNECTION_TIMEOUT));
        
        LOG.info("Server 2 started, waiting for latch");

        latch.await();
        // It's possible our session expired - but this is ok, shows we 
        // were able to talk to the ensemble
        Assert.assertTrue("Client didn't reconnect to quorate ensemble (state was" +
                lastEvent.getState() + ")",
                (KeeperState.SyncConnected==lastEvent.getState() ||
                KeeperState.Expired==lastEvent.getState())); 

        LOG.info("Shutting down all servers");

        q1.shutdown();
        q2.shutdown();
        q3.shutdown();
        
        LOG.info("Closing zk client");

        zk.close();        
        Assert.assertTrue("Waiting for server 1 to shut down",
                ClientBase.waitForServerDown("127.0.0.1:"+CLIENT_PORT_QP1, 
                                ClientBase.CONNECTION_TIMEOUT));
        Assert.assertTrue("Waiting for server 2 to shut down",
                ClientBase.waitForServerDown("127.0.0.1:"+CLIENT_PORT_QP2, 
                                ClientBase.CONNECTION_TIMEOUT));
        Assert.assertTrue("Waiting for server 3 to shut down",
                ClientBase.waitForServerDown("127.0.0.1:"+CLIENT_PORT_OBS, 
                                ClientBase.CONNECTION_TIMEOUT));
    
    }
    
    /**
     * Implementation of watcher interface.
     */
    public void process(WatchedEvent event) {
        lastEvent = event;
        latch.countDown();
        LOG.info("Latch got event :: " + event);        
    }    
    
    /**
     * This test ensures that an Observer does not elect itself as a leader, or
     * indeed come up properly, if it is the lone member of an ensemble.
     * @throws Exception
     */
    @Test
    public void testObserverOnly() throws Exception {
        ClientBase.setupTestEnv();
        final int CLIENT_PORT_QP1 = PortAssignment.unique();        
        
        String quorumCfgSection =
            "server.1=127.0.0.1:" + (PortAssignment.unique())
            + ":" + (PortAssignment.unique()) + ":observer;" + CLIENT_PORT_QP1 + "\n";
                    
        MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
        q1.start();
        q1.join(ClientBase.CONNECTION_TIMEOUT);
        Assert.assertFalse(q1.isAlive());
    }    
    
    /**
     * Ensure that observer only comes up when a proper ensemble is configured.
     * (and will not come up with standalone server).
     */
    @Test
    public void testObserverWithStandlone() throws Exception {
        ClientBase.setupTestEnv();
        final int CLIENT_PORT_QP1 = PortAssignment.unique();        

        String quorumCfgSection =
            "server.1=127.0.0.1:" + (PortAssignment.unique())
            + ":" + (PortAssignment.unique()) + ":observer\n"
            + "server.2=127.0.0.1:" + (PortAssignment.unique())
            + ":" + (PortAssignment.unique()) + "\npeerType=observer\n";

        MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
        q1.start();
        q1.join(ClientBase.CONNECTION_TIMEOUT);
        Assert.assertFalse(q1.isAlive());
    }    

}
