/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.zookeeper.recipes.lock;

import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.test.ClientBase;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * test for writelock
 */
public class WriteLockTest extends ClientBase {
    protected int sessionTimeout = 10 * 1000;
    protected String dir = "/" + getClass().getName();
    protected WriteLock[] nodes;
    protected CountDownLatch latch = new CountDownLatch(1);
    private boolean restartServer = true;
    private boolean workAroundClosingLastZNodeFails = true;
    private boolean killLeader = true;

    public void testRun() throws Exception {
        runTest(3);
    }

    class LockCallback implements LockListener {
        public void lockAcquired() {
            latch.countDown();
        }

        public void lockReleased() {
            
        }
        
    }
    protected void runTest(int count) throws Exception {
        nodes = new WriteLock[count];
        for (int i = 0; i < count; i++) {
            ZooKeeper keeper = createClient();
            WriteLock leader = new WriteLock(keeper, dir, null);
            leader.setLockListener(new LockCallback());
            nodes[i] = leader;

            leader.lock();
        }

        // lets wait for any previous leaders to die and one of our new
        // nodes to become the new leader
        latch.await(30, TimeUnit.SECONDS);

        WriteLock first = nodes[0];
        dumpNodes(count);

        // lets assert that the first election is the leader
        assertTrue("The first znode should be the leader " + first.getId(), first.isOwner());

        for (int i = 1; i < count; i++) {
            WriteLock node = nodes[i];
            assertFalse("Node should not be the leader " + node.getId(), node.isOwner());
        }

        if (count > 1) {
            if (killLeader) {
            System.out.println("Now killing the leader");
            // now lets kill the leader
            latch = new CountDownLatch(1);
            first.unlock();
            latch.await(30, TimeUnit.SECONDS);
            //Thread.sleep(10000);
            WriteLock second = nodes[1];
            dumpNodes(count);
            // lets assert that the first election is the leader
            assertTrue("The second znode should be the leader " + second.getId(), second.isOwner());

            for (int i = 2; i < count; i++) {
                WriteLock node = nodes[i];
                assertFalse("Node should not be the leader " + node.getId(), node.isOwner());
            }
            }


            if (restartServer) {
                // now lets stop the server
                System.out.println("Now stopping the server");
                stopServer();
                Thread.sleep(10000);

                // TODO lets assert that we are no longer the leader
                dumpNodes(count);

                System.out.println("Starting the server");
                startServer();
                Thread.sleep(10000);

                for (int i = 0; i < count - 1; i++) {
                    System.out.println("Calling acquire for node: " + i);
                    nodes[i].lock();
                }
                dumpNodes(count);
                System.out.println("Now closing down...");
            }
        }
    }

    protected void dumpNodes(int count) {
        for (int i = 0; i < count; i++) {
            WriteLock node = nodes[i];
            System.out.println("node: " + i + " id: " + 
                    node.getId() + " is leader: " + node.isOwner());
        }
    }

    @Override
    protected void tearDown() throws Exception {
        if (nodes != null) {
            for (int i = 0; i < nodes.length; i++) {
                WriteLock node = nodes[i];
                if (node != null) {
                    System.out.println("Closing node: " + i);
                    node.close();
                    if (workAroundClosingLastZNodeFails && i == nodes.length - 1) {
                        System.out.println("Not closing zookeeper: " + i + " due to bug!");
                    } else {
                        System.out.println("Closing zookeeper: " + i);
                        node.getZookeeper().close();
                        System.out.println("Closed zookeeper: " + i);
                    }
                }
            }
        }
        System.out.println("Now lets stop the server");
        super.tearDown();

    }
}
