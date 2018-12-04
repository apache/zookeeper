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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.LineNumberReader;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;


import org.apache.zookeeper.ClientCnxn;
import org.apache.zookeeper.ClientCnxnSocket;
import org.apache.zookeeper.ClientWatchManager;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NotReadOnlyException;
import org.apache.zookeeper.Transaction;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.client.HostProvider;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

public class ReadOnlyModeTest extends ZKTestCase {
    private static final org.slf4j.Logger LOG = LoggerFactory
            .getLogger(ReadOnlyModeTest.class);
    private static int CONNECTION_TIMEOUT = QuorumBase.CONNECTION_TIMEOUT;
    private QuorumUtil qu = new QuorumUtil(1);

    @Before
    public void setUp() throws Exception {
        System.setProperty("readonlymode.enabled", "true");
        qu.startQuorum();
    }

    @After
    public void tearDown() throws Exception {
        System.setProperty("readonlymode.enabled", "false");
        qu.tearDown();
    }

    public static class CustomZooKeeper extends ZooKeeper {
        public CustomZooKeeper(String connectString, int sessionTimeout,
                Watcher watcher, boolean canBeReadOnly) throws IOException {
            super(connectString, sessionTimeout, watcher, canBeReadOnly);
        }

        public volatile ClientCnxn myCnxn;

        @Override
        protected ClientCnxn createConnection(String chrootPath,
                HostProvider hostProvider, int sessionTimeout,
                ZooKeeper zooKeeper, ClientWatchManager watcher,
                ClientCnxnSocket clientCnxnSocket, boolean canBeReadOnly)
                        throws IOException {
            myCnxn = super.createConnection(chrootPath, hostProvider, sessionTimeout, zooKeeper, watcher, clientCnxnSocket, canBeReadOnly);
            return myCnxn;
        }
    }

    /**
     * Test write operations using multi request.
     */
    @Test(timeout = 90000)
    public void testMultiTransaction() throws Exception {
        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(qu.getConnString(), CONNECTION_TIMEOUT,
                watcher, true);
        watcher.waitForConnected(CONNECTION_TIMEOUT); // ensure zk got connected

        final String data = "Data to be read in RO mode";
        final String node1 = "/tnode1";
        final String node2 = "/tnode2";
        zk.create(node1, data.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);

        watcher.reset();
        qu.shutdown(2);
        watcher.waitForConnected(CONNECTION_TIMEOUT);
        Assert.assertEquals("Should be in r-o mode", States.CONNECTEDREADONLY,
                zk.getState());

        // read operation during r/o mode
        String remoteData = new String(zk.getData(node1, false, null));
        Assert.assertEquals("Failed to read data in r-o mode", data, remoteData);

        try {
            Transaction transaction = zk.transaction();
            transaction.setData(node1, "no way".getBytes(), -1);
            transaction.create(node2, data.getBytes(),
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            transaction.commit();
            Assert.fail("Write operation using multi-transaction"
                    + " api has succeeded during RO mode");
        } catch (NotReadOnlyException e) {
            // ok
        }

        Assert.assertNull("Should have created the znode:" + node2,
                zk.exists(node2, false));
    }
    
    /**
     * Basic test of read-only client functionality. Tries to read and write
     * during read-only mode, then regains a quorum and tries to write again.
     */
    @Test(timeout = 90000)
    public void testReadOnlyClient() throws Exception {
        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(qu.getConnString(), CONNECTION_TIMEOUT,
                watcher, true);
        watcher.waitForConnected(CONNECTION_TIMEOUT); // ensure zk got connected

        final String data = "Data to be read in RO mode";
        final String node = "/tnode";
        zk.create(node, data.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);

        watcher.reset();
        qu.shutdown(2);
        zk.close();

        // Re-connect the client (in case we were connected to the shut down
        // server and the local session was not persisted).
        zk = new ZooKeeper(qu.getConnString(), CONNECTION_TIMEOUT,
                watcher, true);
        watcher.waitForConnected(CONNECTION_TIMEOUT);

        // read operation during r/o mode
        String remoteData = new String(zk.getData(node, false, null));
        Assert.assertEquals(data, remoteData);

        try {
            zk.setData(node, "no way".getBytes(), -1);
            Assert.fail("Write operation has succeeded during RO mode");
        } catch (NotReadOnlyException e) {
            // ok
        }

        watcher.reset();
        qu.start(2);
        Assert.assertTrue("waiting for server up", ClientBase.waitForServerUp(
                "127.0.0.1:" + qu.getPeer(2).clientPort, CONNECTION_TIMEOUT));
        zk.close();
        watcher.reset();

        // Re-connect the client (in case we were connected to the shut down
        // server and the local session was not persisted).
        zk = new ZooKeeper(qu.getConnString(), CONNECTION_TIMEOUT,
                watcher, true);
        watcher.waitForConnected(CONNECTION_TIMEOUT);
        zk.setData(node, "We're in the quorum now".getBytes(), -1);

        zk.close();
    }

    /**
     * Ensures that upon connection to a read-only server client receives
     * ConnectedReadOnly state notification.
     */
    @Test(timeout = 90000)
    public void testConnectionEvents() throws Exception {
        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(qu.getConnString(), CONNECTION_TIMEOUT,
                watcher, true);
        boolean success = false;
        for (int i = 0; i < 30; i++) {
            try {
                zk.create("/test", "test".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT);
                success=true;
                break;
            } catch(KeeperException.ConnectionLossException e) {
                Thread.sleep(1000);               
            }            
        }
        Assert.assertTrue("Did not succeed in connecting in 30s", success);
        Assert.assertFalse("The connection should not be read-only yet", watcher.readOnlyConnected);

        // kill peer and wait no more than 5 seconds for read-only server
        // to be started (which should take one tickTime (2 seconds))
        qu.shutdown(2);

        // Re-connect the client (in case we were connected to the shut down
        // server and the local session was not persisted).
        zk = new ZooKeeper(qu.getConnString(), CONNECTION_TIMEOUT, watcher, true);
        long start = Time.currentElapsedTime();
        while (!(zk.getState() == States.CONNECTEDREADONLY)) {
            Thread.sleep(200);
            // FIXME this was originally 5 seconds, but realistically, on random/slow/virt hosts, there is no way to guarantee this
            Assert.assertTrue("Can't connect to the server",
                              Time.currentElapsedTime() - start < 30000);
        }

        watcher.waitForReadOnlyConnected(5000);
        zk.close();
    }

    /**
     * Tests a situation when client firstly connects to a read-only server and
     * then connects to a majority server. Transition should be transparent for
     * the user.
     */
    @Test(timeout = 90000)
    public void testSessionEstablishment() throws Exception {
        qu.shutdown(2);

        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(qu.getConnString(), CONNECTION_TIMEOUT,
                watcher, true);
        watcher.waitForConnected(CONNECTION_TIMEOUT);
        Assert.assertSame("should be in r/o mode", States.CONNECTEDREADONLY, zk
                .getState());
        long fakeId = zk.getSessionId();
        LOG.info("Connected as r/o mode with state {} and session id {}",
                zk.getState(), fakeId);

        watcher.reset();
        qu.start(2);
        Assert.assertTrue("waiting for server up", ClientBase.waitForServerUp(
                "127.0.0.1:" + qu.getPeer(2).clientPort, CONNECTION_TIMEOUT));
        LOG.info("Server 127.0.0.1:{} is up", qu.getPeer(2).clientPort);
        // ZOOKEEPER-2722: wait until we can connect to a read-write server after the quorum
        // is formed. Otherwise, it is possible that client first connects to a read-only server,
        // then drops the connection because of shutting down of the read-only server caused
        // by leader election / quorum forming between the read-only server and the newly started
        // server. If we happen to execute the zk.create after the read-only server is shutdown and
        // before the quorum is formed, we will get a ConnectLossException.
        watcher.waitForSyncConnected(CONNECTION_TIMEOUT);
        Assert.assertEquals("Should be in read-write mode", States.CONNECTED,
                zk.getState());
        LOG.info("Connected as rw mode with state {} and session id {}",
                zk.getState(), zk.getSessionId());
        zk.create("/test", "test".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        Assert.assertFalse("fake session and real session have same id", zk
                .getSessionId() == fakeId);
        zk.close();
    }

    /**
     * Ensures that client seeks for r/w servers while it's connected to r/o
     * server.
     */
    @SuppressWarnings("deprecation")
    @Test(timeout = 90000)
    public void testSeekForRwServer() throws Exception {
        qu.shutdown(2);
        CountdownWatcher watcher = new CountdownWatcher();
        CustomZooKeeper zk = new CustomZooKeeper(qu.getConnString(),
                CONNECTION_TIMEOUT, watcher, true);
        watcher.waitForConnected(CONNECTION_TIMEOUT);

        // if we don't suspend a peer it will rejoin a quorum
        qu.getPeer(1).peer.suspend();

        // start two servers to form a quorum; client should detect this and
        // connect to one of them
        watcher.reset();
        qu.start(2);
        qu.start(3);
        ClientBase.waitForServerUp(qu.getConnString(), 2000);
        watcher.waitForConnected(CONNECTION_TIMEOUT);
        zk.create("/test", "test".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);

        // resume poor fellow
        qu.getPeer(1).peer.resume();

        int iterations = ClientBase.CONNECTION_TIMEOUT / 500;
        while (zk.myCnxn.getObservedRWServerAddress() == null) {
            if (iterations-- == 0) {
                break;
            }
            LOG.info("still waiting for client to observe a read/write server");
            Thread.sleep(500);
        }
        Assert.assertNotNull(zk.myCnxn.getObservedRWServerAddress());
    }
}
