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

import java.util.LinkedList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.AsyncCallback.VoidCallback;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.Assert;
import org.junit.Test;

public class AsyncHammerTest extends ZKTestCase
    implements StringCallback, VoidCallback, DataCallback
{
    private static final Logger LOG = LoggerFactory.getLogger(AsyncHammerTest.class);

    private QuorumBase qb = new QuorumBase();

    private volatile boolean bang;

    public void setUp(boolean withObservers) throws Exception {
        qb.setUp(withObservers);
    }

    protected void restart() throws Exception {
        LOG.info("RESTARTING " + getTestName());
        qb.tearDown();

        // don't call setup - we don't want to reassign ports/dirs, etc...
        JMXEnv.setUp();
        qb.startServers();
    }

    public void tearDown() throws Exception {
        LOG.info("Test clients shutting down");
        qb.tearDown();
    }

    /**
     * Create /test- sequence nodes asynchronously, max 30 outstanding
     */
    class HammerThread extends Thread implements StringCallback, VoidCallback {
        private static final int MAX_OUTSTANDING = 30;

        private TestableZooKeeper zk;
        private int outstanding;

        private volatile boolean failed = false;

        public HammerThread(String name) {
            super(name);
        }

        public void run() {
            try {
                CountdownWatcher watcher = new CountdownWatcher();
                zk = new TestableZooKeeper(qb.hostPort, CONNECTION_TIMEOUT,
                        watcher);
                watcher.waitForConnected(CONNECTION_TIMEOUT);
                while(bang) {
                    incOutstanding(); // before create otw race
                    zk.create("/test-", new byte[0], Ids.OPEN_ACL_UNSAFE,
                            CreateMode.PERSISTENT_SEQUENTIAL, this, null);
                }
            } catch (InterruptedException e) {
                if (bang) {
                    LOG.error("sanity check Assert.failed!!!"); // sanity check
                    return;
                }
            } catch (Exception e) {
                LOG.error("Client create operation Assert.failed", e);
                return;
            } finally {
                if (zk != null) {
                    try {
                        zk.close();
                        if (!zk.testableWaitForShutdown(CONNECTION_TIMEOUT)) {
                            failed = true;
                            LOG.error("Client did not shutdown");
                        }
                    } catch (InterruptedException e) {
                        LOG.info("Interrupted", e);
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
            Assert.assertTrue("outstanding >= 0", outstanding >= 0);
            notifyAll();
        }

        public void process(WatchedEvent event) {
            // ignore for purposes of this test
        }

        public void processResult(int rc, String path, Object ctx, String name) {
            if (rc != KeeperException.Code.OK.intValue()) {
                if (bang) {
                    failed = true;
                    LOG.error("Create Assert.failed for 0x"
                            + Long.toHexString(zk.getSessionId())
                            + "with rc:" + rc + " path:" + path);
                }
                decOutstanding();
                return;
            }
            try {
                decOutstanding();
                zk.delete(name, -1, this, null);
            } catch (Exception e) {
                if (bang) {
                    failed = true;
                    LOG.error("Client delete Assert.failed", e);
                }
            }
        }

        public void processResult(int rc, String path, Object ctx) {
            if (rc != KeeperException.Code.OK.intValue()) {
                if (bang) {
                    failed = true;
                    LOG.error("Delete Assert.failed for 0x"
                            + Long.toHexString(zk.getSessionId())
                            + "with rc:" + rc + " path:" + path);
                }
            }
        }
    }

    @Test
    public void testHammer() throws Exception {
        setUp(false);
        bang = true;
        LOG.info("Starting hammers");
        HammerThread[] hammers = new HammerThread[100];
        for (int i = 0; i < hammers.length; i++) {
            hammers[i] = new HammerThread("HammerThread-" + i);
            hammers[i].start();
        }
        LOG.info("Started hammers");
        Thread.sleep(5000); // allow the clients to run for max 5sec
        bang = false;
        LOG.info("Stopping hammers");
        for (int i = 0; i < hammers.length; i++) {
            hammers[i].interrupt();
            verifyThreadTerminated(hammers[i], 60000);
            Assert.assertFalse(hammers[i].failed);
        }

        // before restart
        LOG.info("Hammers stopped, verifying consistency");
        qb.verifyRootOfAllServersMatch(qb.hostPort);

        restart();

        // after restart
        LOG.info("Verifying hammers 2");
        qb.verifyRootOfAllServersMatch(qb.hostPort);
        tearDown();
    }

    @Test
    public void testObserversHammer() throws Exception {
        setUp(true);
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
        qb.verifyRootOfAllServersMatch(qb.hostPort);
        tearDown();
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
