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
            assertEquals(Code.NOAUTH, Code.get(results.get(1)));
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
