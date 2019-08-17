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

package org.apache.zookeeper.test.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.KeeperException.ConnectionLossException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.data.Stat;
import org.junit.Assert;
import org.junit.Test;
import org.apache.zookeeper.common.Time;

/**
 * This does a basic system test. It starts up an ensemble of servers and a set of clients.
 * It makes sure that all the clients come up. It kills off servers while making a change and
 * then ensures that all clients see the change. And then signals the clients to die and
 * watches them disappear.
 *
 */
public class SimpleSysTest extends BaseSysTest implements Watcher {
    int maxTries = 10;
    boolean connected;
    final private static Logger LOG = LoggerFactory.getLogger(SimpleSysTest.class);

    synchronized private boolean waitForConnect(ZooKeeper zk, long timeout) throws InterruptedException {
        connected = (zk.getState() == States.CONNECTED);
        long end = Time.currentElapsedTime() + timeout;
        while(!connected && end > Time.currentElapsedTime()) {
            wait(timeout);
            connected = (zk.getState() == States.CONNECTED);
        }
        return connected;
    }

    /**
     * This test checks the following:
     * 1) All clients connect successfully
     * 2) Half of the servers die (assuming odd number) and a write succeeds
     * 3) All servers are restarted and cluster stays alive
     * 4) Clients see a change by the server
     * 5) Clients' ephemeral nodes are cleaned up
     *
     * @throws Exception
     */
    @Test
    public void testSimpleCase() throws Exception {
        configureServers(serverCount);
        configureClients(clientCount, SimpleClient.class, getHostPort());
        Stat stat = new Stat();
        startServers();
        LOG.debug("Connecting to " + getHostPort());
        ZooKeeper zk = new ZooKeeper(getHostPort(), 15000, this);
        waitForConnect(zk, 10000);
        zk.create("/simpleCase", "orig".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        startClients();

        // Check that all clients connect properly
        for(int i = 0; i < getClientCount(); i++) {
            for(int j = 0; j < maxTries; j++) {
                try {
                    byte b[] = zk.getData("/simpleCase/" + i, false, stat);
                    Assert.assertEquals("orig", new String(b));
                } catch(NoNodeException e) {
                    if (j+1 == maxTries) {
                        Assert.fail("Max tries exceeded on client " + i);
                    }
                    Thread.sleep(1000);
                }
            }
        }

        // Kill half the servers, make a change, restart the dead
        // servers, and then bounce the other servers one by one
        for(int i = 0; i < getServerCount(); i++) {
            stopServer(i);
            if (i+1 > getServerCount()/2) {
                startServer(i);
            } else if (i+1 == getServerCount()/2) {
                Assert.assertTrue("Connection didn't recover", waitForConnect(zk, 10000));
                try {
                    zk.setData("/simpleCase", "new".getBytes(), -1);
                } catch(ConnectionLossException e) {
                    Assert.assertTrue("Connection didn't recover", waitForConnect(zk, 10000));
                    zk.setData("/simpleCase", "new".getBytes(), -1);
                }
                for(int j = 0; j < i; j++) {
                    LOG.info("Starting server " + j);
                    startServer(i);
                }
            }
        }
        Thread.sleep(100); // wait for things to stabilize
        Assert.assertTrue("Servers didn't bounce", waitForConnect(zk, 15000));
        try {
            zk.getData("/simpleCase", false, stat);
        } catch(ConnectionLossException e) {
            Assert.assertTrue("Servers didn't bounce", waitForConnect(zk, 15000));
        }

        // check that the change has propagated to everyone
        for(int i = 0; i < getClientCount(); i++) {
            for(int j = 0; j < maxTries; j++) {
                byte[] data = zk.getData("/simpleCase/" + i, false, stat);
                if (new String(data).equals("new")) {
                    break;
                }
                if (j+1 == maxTries) {
                    Assert.fail("max tries exceeded for " + i);
                }
                Thread.sleep(1000);
            }
        }

        // send out the kill signal
        zk.setData("/simpleCase", "die".getBytes(), -1);

        // watch for everyone to die
        for(int i = 0; i < getClientCount(); i++) {
            try {
                for(int j = 0; j < maxTries; j++) {
                    zk.getData("/simpleCase/" + i, false, stat);
                    if (j+1 == maxTries) {
                        Assert.fail("max tries exceeded waiting for child " + i + " to die");
                    }
                    Thread.sleep(200);
                }
            } catch(NoNodeException e) {
                // Great this is what we were hoping for!
            }
        }

        stopClients();
        stopServers();
    }

    public void process(WatchedEvent event) {
        if (event.getState() == KeeperState.SyncConnected) {
            synchronized(this) {
                connected = true;
                notifyAll();
            }
        } else if (event.getState() == KeeperState.Disconnected) {
            synchronized(this) {
                connected = false;
                notifyAll();
            }
        }
    }
}
