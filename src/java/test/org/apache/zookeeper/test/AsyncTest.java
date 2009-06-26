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
import static org.apache.zookeeper.test.ClientBase.verifyThreadTerminated;

import java.io.IOException;
import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.AsyncCallback.VoidCallback;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AsyncTest extends TestCase
    implements StringCallback, VoidCallback, DataCallback
{
    private static final Logger LOG = Logger.getLogger(AsyncTest.class);

    private QuorumBase qb = new QuorumBase();

    private volatile boolean bang;

    @Before
    @Override
    protected void setUp() throws Exception {
        LOG.info("STARTING " + getName());
        qb.setUp();
    }

    protected void restart() throws Exception {
        JMXEnv.setUp();
        qb.startServers();
    }

    @After
    @Override
    protected void tearDown() throws Exception {
        LOG.info("Test clients shutting down");
        qb.tearDown();
        LOG.info("FINISHED " + getName());
    }

    private static class CountdownWatcher implements Watcher {
        volatile CountDownLatch clientConnected = new CountDownLatch(1);

        public void process(WatchedEvent event) {
            if (event.getState() == KeeperState.SyncConnected) {
                clientConnected.countDown();
            }
        }
    }

    private ZooKeeper createClient() throws IOException,InterruptedException {
        return createClient(qb.hostPort);
    }

    private ZooKeeper createClient(String hp)
        throws IOException, InterruptedException
    {
        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(hp, CONNECTION_TIMEOUT, watcher);
        if(!watcher.clientConnected.await(CONNECTION_TIMEOUT,
                TimeUnit.MILLISECONDS))
        {
            fail("Unable to connect to server");
        }
        return zk;
    }

    /**
     * Create /test- sequence nodes asynchronously, max 30 outstanding
     */
    class HammerThread extends Thread
        implements Watcher, StringCallback, VoidCallback
    {
        private static final int MAX_OUTSTANDING = 30;

        private ZooKeeper zk;
        private int outstanding;

        public HammerThread(String name) {
            super(name);
        }

        public void run() {
            try {
                zk = new ZooKeeper(qb.hostPort, CONNECTION_TIMEOUT, this);
                while(bang) {
                    incOutstanding(); // before create otw race
                    zk.create("/test-", new byte[0], Ids.OPEN_ACL_UNSAFE,
                            CreateMode.PERSISTENT_SEQUENTIAL, this, null);
                }
            } catch (InterruptedException e) {
                if (bang) {
                    LOG.error("sanity check failed!!!"); // sanity check
                    return;
                }
            } catch (Exception e) {
                LOG.error("Client create operation failed", e);
                return;
            } finally {
                if (zk != null) {
                    try {
                        zk.close();
                    } catch (InterruptedException e) {
                        LOG.warn("Unexpected", e);
                    }
                }
            }
        }

        private synchronized void incOutstanding() throws InterruptedException {
            outstanding++;
            while(outstanding > MAX_OUTSTANDING) {
                wait();
            }
        }

        private synchronized void decOutstanding() {
            outstanding--;
            assertTrue("outstanding >= 0", outstanding >= 0);
            notifyAll();
        }

        public void process(WatchedEvent event) {
            // ignore for purposes of this test
        }

        public void processResult(int rc, String path, Object ctx, String name) {
            try {
                decOutstanding();
                zk.delete(path, -1, this, null);
            } catch (Exception e) {
                LOG.error("Client delete failed", e);
            }
        }

        public void processResult(int rc, String path, Object ctx) {
            // ignore for purposes of this test
        }
    }

    @Test
    public void testHammer() throws Exception {
        bang = true;
        Thread[] hammers = new Thread[100];
        for (int i = 0; i < hammers.length; i++) {
            hammers[i] = new HammerThread("HammerThread-" + i);
            hammers[i].start();
        }
        Thread.sleep(5000); // allow the clients to run for max 5sec
        bang = false;
        for (int i = 0; i < hammers.length; i++) {
            hammers[i].interrupt();
            verifyThreadTerminated(hammers[i], 60000);
        }
        // before restart
        QuorumBase qt = new QuorumBase();
        qt.verifyRootOfAllServersMatch(qb.hostPort);
        tearDown();

        restart();

        // after restart
        qt.verifyRootOfAllServersMatch(qb.hostPort);
    }

    LinkedList<Integer> results = new LinkedList<Integer>();
    @Test
    public void testAsync()
        throws IOException, InterruptedException, KeeperException
    {
        ZooKeeper zk = null;
        zk = createClient();
        try {
            zk.addAuthInfo("digest", "ben:passwd".getBytes());
            zk.create("/ben", new byte[0], Ids.READ_ACL_UNSAFE, CreateMode.PERSISTENT, this, results);
            zk.create("/ben/2", new byte[0], Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT, this, results);
            zk.delete("/ben", -1, this, results);
            zk.create("/ben2", new byte[0], Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT, this, results);
            zk.getData("/ben2", false, this, results);
            synchronized (results) {
                while (results.size() < 5) {
                    results.wait();
                }
            }
            assertEquals(0, (int) results.get(0));
            assertEquals(Code.NOAUTH, Code.get((int) results.get(1)));
            assertEquals(0, (int) results.get(2));
            assertEquals(0, (int) results.get(3));
            assertEquals(0, (int) results.get(4));
        } finally {
            zk.close();
        }

        zk = createClient();
        try {
            zk.addAuthInfo("digest", "ben:passwd2".getBytes());
            try {
                zk.getData("/ben2", false, new Stat());
                fail("Should have received a permission error");
            } catch (KeeperException e) {
                assertEquals(Code.NOAUTH, e.code());
            }
        } finally {
            zk.close();
        }

        zk = createClient();
        try {
            zk.addAuthInfo("digest", "ben:passwd".getBytes());
            zk.getData("/ben2", false, new Stat());
        } finally {
            zk.close();
        }
    }

    @SuppressWarnings("unchecked")
    public void processResult(int rc, String path, Object ctx, String name) {
        synchronized(ctx) {
            ((LinkedList<Integer>)ctx).add(rc);
            ctx.notifyAll();
        }
    }

    @SuppressWarnings("unchecked")
    public void processResult(int rc, String path, Object ctx) {
        synchronized(ctx) {
            ((LinkedList<Integer>)ctx).add(rc);
            ctx.notifyAll();
        }
    }

    @SuppressWarnings("unchecked")
    public void processResult(int rc, String path, Object ctx, byte[] data,
            Stat stat) {
        synchronized(ctx) {
            ((LinkedList<Integer>)ctx).add(rc);
            ctx.notifyAll();
        }
    }
}
