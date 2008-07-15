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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.proto.WatcherEvent;

public class WatcherFuncTest extends ClientBase {
    private static class SimpleWatcher implements Watcher {
        private LinkedBlockingQueue<WatcherEvent> events =
            new LinkedBlockingQueue<WatcherEvent>();
        private CountDownLatch latch;

        public SimpleWatcher(CountDownLatch latch) {
            this.latch = latch;
        }

        public void process(WatcherEvent event) {
            if (event.getState() == Event.KeeperStateSyncConnected) {
                if (latch != null) {
                    latch.countDown();
                }
            }

            if (event.getType() == Watcher.Event.EventNone) {
                return;
            }
            try {
                events.put(event);
            } catch (InterruptedException e) {
                assertTrue("interruption unexpected", false);
            }
        }
        public void verify(List<Integer> expected) throws InterruptedException{
            WatcherEvent event;
            int count = 0;
            while (count < expected.size()
                    && (event = events.poll(30, TimeUnit.SECONDS)) != null)
            {
                assertEquals(expected.get(count).intValue(), event.getType());
                count++;
            }
            assertEquals(expected.size(), count);
            events.clear();
        }
    }
    private SimpleWatcher client_dwatch;
    private volatile CountDownLatch client_latch;
    private ZooKeeper client;
    private SimpleWatcher lsnr_dwatch;
    private volatile CountDownLatch lsnr_latch;
    private ZooKeeper lsnr;

    private List<Integer> expected;

    protected void setUp() throws Exception {
        super.setUp();

        client_latch = new CountDownLatch(1);
        client_dwatch = new SimpleWatcher(client_latch);
        client = createClient(client_dwatch, client_latch);

        lsnr_latch = new CountDownLatch(1);
        lsnr_dwatch = new SimpleWatcher(lsnr_latch);
        lsnr = createClient(lsnr_dwatch, lsnr_latch);

        expected = new ArrayList<Integer>();
    }
    protected void tearDown() throws Exception {
        client.close();
        lsnr.close();
        Thread.sleep(5000);
        super.tearDown();
    }

    protected ZooKeeper createClient(Watcher watcher, CountDownLatch latch)
        throws IOException, InterruptedException
    {
        ZooKeeper zk = new ZooKeeper(hostPort, 20000, watcher);
        if(!latch.await(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)){
            fail("Unable to connect to server");
        }
        return zk;
    }

    private void verify() throws InterruptedException {
        lsnr_dwatch.verify(expected);
        expected.clear();
    }
    public void testExistsSync()
        throws IOException, InterruptedException, KeeperException
    {
        assertNull(lsnr.exists("/foo", true));
        assertNull(lsnr.exists("/foo/bar", true));

        client.create("/foo", "parent".getBytes(), Ids.OPEN_ACL_UNSAFE, 0);
        expected.add(Watcher.Event.EventNodeCreated);
        client.create("/foo/bar", "child".getBytes(), Ids.OPEN_ACL_UNSAFE, 0);
        expected.add(Watcher.Event.EventNodeCreated);

        verify();

        assertNotNull(lsnr.exists("/foo", true));
        assertNotNull(lsnr.exists("/foo/bar", true));

        try {
            assertNull(lsnr.exists("/car", true));
            client.setData("/car", "missing".getBytes(), -1);
            assertTrue(false);
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.NoNode, e.getCode());
        }

        try {
            assertNull(lsnr.exists("/foo/car", true));
            client.setData("/foo/car", "missing".getBytes(), -1);
            assertTrue(false);
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.NoNode, e.getCode());
        }

        client.setData("/foo", "parent".getBytes(), -1);
        expected.add(Watcher.Event.EventNodeDataChanged);
        client.setData("/foo/bar", "child".getBytes(), -1);
        expected.add(Watcher.Event.EventNodeDataChanged);

        verify();

        assertNotNull(lsnr.exists("/foo", true));
        assertNotNull(lsnr.exists("/foo/bar", true));

        client.delete("/foo/bar", -1);
        expected.add(Watcher.Event.EventNodeDeleted);
        client.delete("/foo", -1);
        expected.add(Watcher.Event.EventNodeDeleted);

        verify();
    }

    public void testGetDataSync()
        throws IOException, InterruptedException, KeeperException
    {
        try {
            lsnr.getData("/foo", true, null);
            assertTrue(false);
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.NoNode, e.getCode());
        }
        try {
            lsnr.getData("/foo/bar", true, null);
            assertTrue(false);
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.NoNode, e.getCode());
        }

        client.create("/foo", "parent".getBytes(), Ids.OPEN_ACL_UNSAFE, 0);
        assertNotNull(lsnr.getData("/foo", true, null));
        client.create("/foo/bar", "child".getBytes(), Ids.OPEN_ACL_UNSAFE, 0);
        assertNotNull(lsnr.getData("/foo/bar", true, null));

        client.setData("/foo", "parent".getBytes(), -1);
        expected.add(Watcher.Event.EventNodeDataChanged);
        client.setData("/foo/bar", "child".getBytes(), -1);
        expected.add(Watcher.Event.EventNodeDataChanged);

        verify();

        assertNotNull(lsnr.getData("/foo", true, null));
        assertNotNull(lsnr.getData("/foo/bar", true, null));

        client.delete("/foo/bar", -1);
        expected.add(Watcher.Event.EventNodeDeleted);
        client.delete("/foo", -1);
        expected.add(Watcher.Event.EventNodeDeleted);

        verify();
    }

    public void testGetChildrenSync()
        throws IOException, InterruptedException, KeeperException
    {
        try {
            lsnr.getChildren("/foo", true);
            assertTrue(false);
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.NoNode, e.getCode());
        }
        try {
            lsnr.getChildren("/foo/bar", true);
            assertTrue(false);
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.NoNode, e.getCode());
        }

        client.create("/foo", "parent".getBytes(), Ids.OPEN_ACL_UNSAFE, 0);
        assertNotNull(lsnr.getChildren("/foo", true));

        client.create("/foo/bar", "child".getBytes(), Ids.OPEN_ACL_UNSAFE, 0);
        expected.add(Watcher.Event.EventNodeChildrenChanged); // /foo
        assertNotNull(lsnr.getChildren("/foo/bar", true));


        client.setData("/foo", "parent".getBytes(), -1);
        client.setData("/foo/bar", "child".getBytes(), -1);


        assertNotNull(lsnr.exists("/foo", true));

        assertNotNull(lsnr.getChildren("/foo", true));
        assertNotNull(lsnr.getChildren("/foo/bar", true));

        client.delete("/foo/bar", -1);
        expected.add(Watcher.Event.EventNodeDeleted); // /foo/bar childwatch
        expected.add(Watcher.Event.EventNodeChildrenChanged); // /foo
        client.delete("/foo", -1);
        expected.add(Watcher.Event.EventNodeDeleted);

        verify();
    }

    public void testExistsSyncWObj()
        throws IOException, InterruptedException, KeeperException
    {
        SimpleWatcher w1 = new SimpleWatcher(null);
        SimpleWatcher w2 = new SimpleWatcher(null);
        SimpleWatcher w3 = new SimpleWatcher(null);
        SimpleWatcher w4 = new SimpleWatcher(null);

        List<Integer> e2 = new ArrayList<Integer>();

        assertNull(lsnr.exists("/foo", true));
        assertNull(lsnr.exists("/foo", w1));

        assertNull(lsnr.exists("/foo/bar", w2));
        assertNull(lsnr.exists("/foo/bar", w3));
        assertNull(lsnr.exists("/foo/bar", w3));
        assertNull(lsnr.exists("/foo/bar", w4));

        client.create("/foo", "parent".getBytes(), Ids.OPEN_ACL_UNSAFE, 0);
        expected.add(Watcher.Event.EventNodeCreated);
        client.create("/foo/bar", "child".getBytes(), Ids.OPEN_ACL_UNSAFE, 0);
        e2.add(Watcher.Event.EventNodeCreated);

        lsnr_dwatch.verify(expected);
        w1.verify(expected);
        w2.verify(e2);
        w3.verify(e2);
        w4.verify(e2);
        expected.clear();
        e2.clear();

        // default not registered
        assertNotNull(lsnr.exists("/foo", w1));

        assertNotNull(lsnr.exists("/foo/bar", w2));
        assertNotNull(lsnr.exists("/foo/bar", w3));
        assertNotNull(lsnr.exists("/foo/bar", w4));
        assertNotNull(lsnr.exists("/foo/bar", w4));

        client.setData("/foo", "parent".getBytes(), -1);
        expected.add(Watcher.Event.EventNodeDataChanged);
        client.setData("/foo/bar", "child".getBytes(), -1);
        e2.add(Watcher.Event.EventNodeDataChanged);

        lsnr_dwatch.verify(new ArrayList<Integer>()); // not reg so should = 0
        w1.verify(expected);
        w2.verify(e2);
        w3.verify(e2);
        w4.verify(e2);
        expected.clear();
        e2.clear();

        assertNotNull(lsnr.exists("/foo", true));
        assertNotNull(lsnr.exists("/foo", w1));
        assertNotNull(lsnr.exists("/foo", w1));

        assertNotNull(lsnr.exists("/foo/bar", w2));
        assertNotNull(lsnr.exists("/foo/bar", w2));
        assertNotNull(lsnr.exists("/foo/bar", w3));
        assertNotNull(lsnr.exists("/foo/bar", w4));

        client.delete("/foo/bar", -1);
        expected.add(Watcher.Event.EventNodeDeleted);
        client.delete("/foo", -1);
        e2.add(Watcher.Event.EventNodeDeleted);

        lsnr_dwatch.verify(expected);
        w1.verify(expected);
        w2.verify(e2);
        w3.verify(e2);
        w4.verify(e2);
        expected.clear();
        e2.clear();

    }

    public void testGetDataSyncWObj()
        throws IOException, InterruptedException, KeeperException
    {
        SimpleWatcher w1 = new SimpleWatcher(null);
        SimpleWatcher w2 = new SimpleWatcher(null);
        SimpleWatcher w3 = new SimpleWatcher(null);
        SimpleWatcher w4 = new SimpleWatcher(null);

        List<Integer> e2 = new ArrayList<Integer>();

        try {
            lsnr.getData("/foo", w1, null);
            assertTrue(false);
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.NoNode, e.getCode());
        }
        try {
            lsnr.getData("/foo/bar", w2, null);
            assertTrue(false);
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.NoNode, e.getCode());
        }

        client.create("/foo", "parent".getBytes(), Ids.OPEN_ACL_UNSAFE, 0);
        assertNotNull(lsnr.getData("/foo", true, null));
        assertNotNull(lsnr.getData("/foo", w1, null));
        client.create("/foo/bar", "child".getBytes(), Ids.OPEN_ACL_UNSAFE, 0);
        assertNotNull(lsnr.getData("/foo/bar", w2, null));
        assertNotNull(lsnr.getData("/foo/bar", w3, null));
        assertNotNull(lsnr.getData("/foo/bar", w4, null));
        assertNotNull(lsnr.getData("/foo/bar", w4, null));

        client.setData("/foo", "parent".getBytes(), -1);
        expected.add(Watcher.Event.EventNodeDataChanged);
        client.setData("/foo/bar", "child".getBytes(), -1);
        e2.add(Watcher.Event.EventNodeDataChanged);

        lsnr_dwatch.verify(expected);
        w1.verify(expected);
        w2.verify(e2);
        w3.verify(e2);
        w4.verify(e2);
        expected.clear();
        e2.clear();

        assertNotNull(lsnr.getData("/foo", true, null));
        assertNotNull(lsnr.getData("/foo", w1, null));
        assertNotNull(lsnr.getData("/foo/bar", w2, null));
        assertNotNull(lsnr.getData("/foo/bar", w3, null));
        assertNotNull(lsnr.getData("/foo/bar", w3, null));
        assertNotNull(lsnr.getData("/foo/bar", w4, null));

        client.delete("/foo/bar", -1);
        expected.add(Watcher.Event.EventNodeDeleted);
        client.delete("/foo", -1);
        e2.add(Watcher.Event.EventNodeDeleted);

        lsnr_dwatch.verify(expected);
        w1.verify(expected);
        w2.verify(e2);
        w3.verify(e2);
        w4.verify(e2);
        expected.clear();
        e2.clear();
    }

    public void testGetChildrenSyncWObj()
        throws IOException, InterruptedException, KeeperException
    {
        SimpleWatcher w1 = new SimpleWatcher(null);
        SimpleWatcher w2 = new SimpleWatcher(null);
        SimpleWatcher w3 = new SimpleWatcher(null);
        SimpleWatcher w4 = new SimpleWatcher(null);

        List<Integer> e2 = new ArrayList<Integer>();

        try {
            lsnr.getChildren("/foo", true);
            assertTrue(false);
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.NoNode, e.getCode());
        }
        try {
            lsnr.getChildren("/foo/bar", true);
            assertTrue(false);
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.NoNode, e.getCode());
        }

        client.create("/foo", "parent".getBytes(), Ids.OPEN_ACL_UNSAFE, 0);
        assertNotNull(lsnr.getChildren("/foo", true));
        assertNotNull(lsnr.getChildren("/foo", w1));

        client.create("/foo/bar", "child".getBytes(), Ids.OPEN_ACL_UNSAFE, 0);
        expected.add(Watcher.Event.EventNodeChildrenChanged); // /foo
        assertNotNull(lsnr.getChildren("/foo/bar", w2));
        assertNotNull(lsnr.getChildren("/foo/bar", w2));
        assertNotNull(lsnr.getChildren("/foo/bar", w3));
        assertNotNull(lsnr.getChildren("/foo/bar", w4));


        client.setData("/foo", "parent".getBytes(), -1);
        client.setData("/foo/bar", "child".getBytes(), -1);


        assertNotNull(lsnr.exists("/foo", true));
        assertNotNull(lsnr.exists("/foo", w1));
        assertNotNull(lsnr.exists("/foo", true));
        assertNotNull(lsnr.exists("/foo", w1));

        assertNotNull(lsnr.getChildren("/foo", true));
        assertNotNull(lsnr.getChildren("/foo", w1));
        assertNotNull(lsnr.getChildren("/foo/bar", w2));
        assertNotNull(lsnr.getChildren("/foo/bar", w3));
        assertNotNull(lsnr.getChildren("/foo/bar", w4));
        assertNotNull(lsnr.getChildren("/foo/bar", w4));

        client.delete("/foo/bar", -1);
        e2.add(Watcher.Event.EventNodeDeleted); // /foo/bar childwatch
        expected.add(Watcher.Event.EventNodeChildrenChanged); // /foo
        client.delete("/foo", -1);
        expected.add(Watcher.Event.EventNodeDeleted);

        lsnr_dwatch.verify(expected);
        w1.verify(expected);
        w2.verify(e2);
        w3.verify(e2);
        w4.verify(e2);
        expected.clear();
        e2.clear();
    }
}
