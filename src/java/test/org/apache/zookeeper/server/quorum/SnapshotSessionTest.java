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

import junit.framework.Assert;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.test.QuorumUtil;
import org.junit.After;
import org.junit.Test;

public class SnapshotSessionTest {
    QuorumUtil qu = new QuorumUtil(1);
    private static final String ZOOKEEPER_SNAP_COUNT = "zookeeper.snapCount";
    String snapCount = System.getProperty(ZOOKEEPER_SNAP_COUNT);
    
    public SnapshotSessionTest() {
        System.setProperty(ZOOKEEPER_SNAP_COUNT, "10");
    }
    
    @After
    public void tearDown() throws Exception {
        qu.shutdownAll();
        if (snapCount == null) {
            System.clearProperty(ZOOKEEPER_SNAP_COUNT);
        } else {
            System.setProperty(ZOOKEEPER_SNAP_COUNT, snapCount);
        }
    }
    
    class NullWatcher implements Watcher {
        boolean connected;
        @Override
        synchronized public void process(WatchedEvent event) {
            if (event.getState() == KeeperState.SyncConnected) {
                connected = true;
                notifyAll();
            }
        }
        synchronized public void waitForConnected() throws InterruptedException {
            while(!connected) {
                wait();
            }
        }
    };
    
    /**
     * This test makes sure that session events in DIFFs are applied to the snapshot
     * if they have been committed by the leader before the diff is sent.
     * (see ZOOKEEPER-1367)
     */
    @Test
    public void testSessionInSnapshot() throws Exception {
        qu.startAll();
        int follower = 1;
        if (qu.getPeer(1).peer.follower == null) {
            follower = 2;
        }
        String hostPort = qu.getConnString();
        /* we want to prime the peers to make sure that we have a good
         * base for a diff when the follower disconnects and reconnnects
         */
        ZooKeeper zk = new ZooKeeper(hostPort, 3000, new NullWatcher());
        zk.setData("/", "foo".getBytes(), -1);
        zk.close();
        
        qu.shutdown(follower);
        
        /* while the follower is down create a session and generate a bit of
         * traffic.
         */
        NullWatcher nullWatcher = new NullWatcher();
        zk = new ZooKeeper(hostPort, 3000, nullWatcher);
        nullWatcher.waitForConnected();
        pumpRequests(zk, "/", 20);
        qu.restart(follower);
        
        /* make sure the session is there! */
        Assert.assertNotNull("Session is not in snapshot!", qu.getPeer(follower).peer.follower.zk.getZKDatabase().getSessionWithTimeOuts().get(zk.getSessionId()));
    }

    private void pumpRequests(ZooKeeper zk, String path, int i) throws KeeperException, InterruptedException {
        while(i > 0) {
            zk.setData(path, ("set"+i).getBytes(), -1);
            i--;
        }
    }
}
