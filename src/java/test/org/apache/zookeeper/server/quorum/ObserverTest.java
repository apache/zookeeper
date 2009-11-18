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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper.States;

import org.apache.zookeeper.AsyncCallback.VoidCallback;
import org.apache.zookeeper.KeeperException.ConnectionLossException;

import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Test;

/**
 * Test Observer behaviour and specific code paths.
 *
 */
public class ObserverTest extends QuorumPeerTestBase implements Watcher{
    protected static final Logger LOG =
        Logger.getLogger(ObserverTest.class);    
      
    // We expect two notifications before we want to continue
    CountDownLatch latch = new CountDownLatch(2);
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
        final int CLIENT_PORT_QP1 = 3181;
        final int CLIENT_PORT_QP2 = CLIENT_PORT_QP1 + 3;
        final int CLIENT_PORT_OBS = CLIENT_PORT_QP2 + 3;

        String quorumCfgSection =
            "electionAlg=0\n" + 
            "server.1=localhost:" + (CLIENT_PORT_QP1 + 1)
            + ":" + (CLIENT_PORT_QP1 + 2)
            + "\nserver.2=localhost:" + (CLIENT_PORT_QP2 + 1)
            + ":" + (CLIENT_PORT_QP2 + 2)
            + "\nserver.3=localhost:" 
            + (CLIENT_PORT_OBS+1)+ ":" + (CLIENT_PORT_OBS + 2) + ":observer";
        String obsCfgSection =  quorumCfgSection + "\npeerType=observer";
        MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
        MainThread q2 = new MainThread(2, CLIENT_PORT_QP2, quorumCfgSection);
        MainThread q3 = new MainThread(3, CLIENT_PORT_OBS, obsCfgSection);
        q1.start();
        q2.start();
        q3.start();
        assertTrue("waiting for server 1 being up",
                ClientBase.waitForServerUp("localhost:" + CLIENT_PORT_QP1,
                        CONNECTION_TIMEOUT));
        assertTrue("waiting for server 2 being up",
                ClientBase.waitForServerUp("localhost:" + CLIENT_PORT_QP2,
                        CONNECTION_TIMEOUT));
        assertTrue("waiting for server 3 being up",
                ClientBase.waitForServerUp("localhost:" + CLIENT_PORT_OBS,
                        CONNECTION_TIMEOUT));        
        
        zk = new ZooKeeper("localhost:" + CLIENT_PORT_OBS,
                ClientBase.CONNECTION_TIMEOUT, this);
        zk.create("/obstest", "test".getBytes(),Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        
        // Assert that commands are getting forwarded correctly
        assertEquals(new String(zk.getData("/obstest", null, null)), "test");
        
        // Now check that other commands don't blow everything up
        zk.sync("/", null, null);
        zk.setData("/obstest", "test2".getBytes(), -1);
        zk.getChildren("/", false);
        
        assertEquals(zk.getState(), States.CONNECTED);
        
        // Now kill one of the other real servers        
        q2.shutdown();
                
        assertTrue("Waiting for server 2 to shut down",
                    ClientBase.waitForServerDown("localhost:"+CLIENT_PORT_QP2, 
                                    ClientBase.CONNECTION_TIMEOUT));
        
        // Now the resulting ensemble shouldn't be quorate         
        latch.await();        
        assertNotSame("zk should not be connected", KeeperState.SyncConnected,lastEvent.getState());

        try {
            assertFalse("Shouldn't get a response when cluster not quorate!",
                    new String(zk.getData("/obstest", null, null)).equals("test"));
        }
        catch (ConnectionLossException c) {
            LOG.info("Connection loss exception caught - ensemble not quorate (this is expected)");
        }
        
        latch = new CountDownLatch(1);
        
        // Bring it back
        q2 = new MainThread(2, CLIENT_PORT_QP2, quorumCfgSection);
        q2.start();
        assertTrue("waiting for server 2 being up",
                ClientBase.waitForServerUp("localhost:" + CLIENT_PORT_QP2,
                        CONNECTION_TIMEOUT));
        
        latch.await();
        // It's possible our session expired - but this is ok, shows we 
        // were able to talk to the ensemble
        assertTrue("Didn't reconnect", 
                (KeeperState.SyncConnected==lastEvent.getState() ||
                KeeperState.Expired==lastEvent.getState())); 
                       
        q1.shutdown();
        q2.shutdown();
        q3.shutdown();
        
        zk.close();        
        assertTrue("Waiting for server 1 to shut down",
                ClientBase.waitForServerDown("localhost:"+CLIENT_PORT_QP1, 
                                ClientBase.CONNECTION_TIMEOUT));
        assertTrue("Waiting for server 2 to shut down",
                ClientBase.waitForServerDown("localhost:"+CLIENT_PORT_QP2, 
                                ClientBase.CONNECTION_TIMEOUT));
        assertTrue("Waiting for server 3 to shut down",
                ClientBase.waitForServerDown("localhost:"+CLIENT_PORT_OBS, 
                                ClientBase.CONNECTION_TIMEOUT));
    
    }
    
    public void process(WatchedEvent event) {
        latch.countDown();
        lastEvent = event;
    }    
    
    /**
     * This test ensures that an Observer does not elect itself as a leader, or
     * indeed come up properly, if it is the lone member of an ensemble.
     * @throws IOException
     */
    @Test
    public void testSingleObserver() throws IOException{
        ClientBase.setupTestEnv();
        final int CLIENT_PORT_QP1 = 3181;        

        String quorumCfgSection =
            "server.1=localhost:" + (CLIENT_PORT_QP1 + 1)
            + ":" + (CLIENT_PORT_QP1 + 2) + "\npeerType=observer";
                    
        MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
        q1.start();
        assertFalse("Observer shouldn't come up",
                ClientBase.waitForServerUp("localhost:" + CLIENT_PORT_QP1,
                                            CONNECTION_TIMEOUT));
        
        q1.shutdown();
    }    
    
    @Test
    public void testLeaderElectionFail() throws Exception {        
        ClientBase.setupTestEnv();
        final int CLIENT_PORT_QP1 = 3181;
        final int CLIENT_PORT_QP2 = CLIENT_PORT_QP1 + 3;
        final int CLIENT_PORT_OBS = CLIENT_PORT_QP2 + 3;

        String quorumCfgSection =
            "electionAlg=1\n" + 
            "server.1=localhost:" + (CLIENT_PORT_QP1 + 1)
            + ":" + (CLIENT_PORT_QP1 + 2)
            + "\nserver.2=localhost:" + (CLIENT_PORT_QP2 + 1)
            + ":" + (CLIENT_PORT_QP2 + 2)
            + "\nserver.3=localhost:" 
            + (CLIENT_PORT_OBS+1)+ ":" + (CLIENT_PORT_OBS + 2) + ":observer";
        QuorumPeerConfig qpc = new QuorumPeerConfig();
        
        File tmpDir = ClientBase.createTmpDir();
        File confFile = new File(tmpDir, "zoo.cfg");

        FileWriter fwriter = new FileWriter(confFile);
        fwriter.write("tickTime=2000\n");
        fwriter.write("initLimit=10\n");
        fwriter.write("syncLimit=5\n");

        File dataDir = new File(tmpDir, "data");
        if (!dataDir.mkdir()) {
            throw new IOException("Unable to mkdir " + dataDir);
        }
        fwriter.write("dataDir=" + dataDir.toString() + "\n");

        fwriter.write("clientPort=" + CLIENT_PORT_QP1 + "\n");
        fwriter.write(quorumCfgSection + "\n");
        fwriter.flush();
        fwriter.close();
        try {
            qpc.parse(confFile.toString());
        } catch (ConfigException e) {
            LOG.info("Config exception caught as expected: " + e.getCause());
            return;
        }
        
        assertTrue("Didn't get the expected config exception", false);        
    } 
}
