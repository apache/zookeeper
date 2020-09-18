/*
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.ByteArrayOutputStream;
import java.io.LineNumberReader;
import java.io.StringReader;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.apache.log4j.Layout;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.WriterAppender;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NotReadOnlyException;
import org.apache.zookeeper.Transaction;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

public class ReadOnlyModeTest extends ZKTestCase {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(ReadOnlyModeTest.class);
    private static int CONNECTION_TIMEOUT = QuorumBase.CONNECTION_TIMEOUT;
    private QuorumUtil qu = new QuorumUtil(1);

    @BeforeEach
    public void setUp() throws Exception {
        System.setProperty("readonlymode.enabled", "true");
    }

    @AfterEach
    public void tearDown() throws Exception {
        System.setProperty("readonlymode.enabled", "false");
        qu.tearDown();
    }

    /**
     * Test write operations using multi request.
     */
    @Test
    @Timeout(value = 90)
    public void testMultiTransaction() throws Exception {
        qu.enableLocalSession(true);
        qu.startQuorum();

        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(qu.getConnString(), CONNECTION_TIMEOUT, watcher, true);
        watcher.waitForConnected(CONNECTION_TIMEOUT); // ensure zk got connected

        final String data = "Data to be read in RO mode";
        final String node1 = "/tnode1";
        final String node2 = "/tnode2";
        zk.create(node1, data.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.close();
        watcher.waitForDisconnected(CONNECTION_TIMEOUT);

        watcher.reset();
        qu.shutdown(2);
        zk = new ZooKeeper(qu.getConnString(), CONNECTION_TIMEOUT, watcher, true);
        watcher.waitForConnected(CONNECTION_TIMEOUT);
        assertEquals(States.CONNECTEDREADONLY, zk.getState(), "Should be in r-o mode");

        // read operation during r/o mode
        String remoteData = new String(zk.getData(node1, false, null));
        assertEquals(data, remoteData, "Failed to read data in r-o mode");

        try {
            Transaction transaction = zk.transaction();
            transaction.setData(node1, "no way".getBytes(), -1);
            transaction.create(node2, data.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            transaction.commit();
            fail("Write operation using multi-transaction" + " api has succeeded during RO mode");
        } catch (NotReadOnlyException e) {
            // ok
        }

        assertNull(zk.exists(node2, false), "Should have created the znode:" + node2);
    }

    /**
     * Basic test of read-only client functionality. Tries to read and write
     * during read-only mode, then regains a quorum and tries to write again.
     */
    @Test
    @Timeout(value = 90)
    public void testReadOnlyClient() throws Exception {
        qu.enableLocalSession(true);
        qu.startQuorum();

        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(qu.getConnString(), CONNECTION_TIMEOUT, watcher, true);
        watcher.waitForConnected(CONNECTION_TIMEOUT); // ensure zk got connected

        final String data = "Data to be read in RO mode";
        final String node = "/tnode";
        zk.create(node, data.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        watcher.reset();
        qu.shutdown(2);
        zk.close();

        // Re-connect the client (in case we were connected to the shut down
        // server and the local session was not persisted).
        zk = new ZooKeeper(qu.getConnString(), CONNECTION_TIMEOUT, watcher, true);
        watcher.waitForConnected(CONNECTION_TIMEOUT);

        // read operation during r/o mode
        String remoteData = new String(zk.getData(node, false, null));
        assertEquals(data, remoteData);

        try {
            zk.setData(node, "no way".getBytes(), -1);
            fail("Write operation has succeeded during RO mode");
        } catch (NotReadOnlyException e) {
            // ok
        }

        watcher.reset();
        qu.start(2);
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + qu.getPeer(2).clientPort, CONNECTION_TIMEOUT),
            "waiting for server up");
        zk.close();
        watcher.reset();

        // Re-connect the client (in case we were connected to the shut down
        // server and the local session was not persisted).
        zk = new ZooKeeper(qu.getConnString(), CONNECTION_TIMEOUT, watcher, true);
        watcher.waitForConnected(CONNECTION_TIMEOUT);
        zk.setData(node, "We're in the quorum now".getBytes(), -1);

        zk.close();
    }

    /**
     * Ensures that upon connection to a read-only server client receives
     * ConnectedReadOnly state notification.
     */
    @Test
    @Timeout(value = 90)
    public void testConnectionEvents() throws Exception {
        qu.enableLocalSession(true);
        qu.startQuorum();

        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(qu.getConnString(), CONNECTION_TIMEOUT, watcher, true);
        boolean success = false;
        for (int i = 0; i < 30; i++) {
            try {
                zk.create("/test", "test".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                success = true;
                break;
            } catch (KeeperException.ConnectionLossException e) {
                Thread.sleep(1000);
            }
        }
        assertTrue(success, "Did not succeed in connecting in 30s");
        assertFalse(watcher.readOnlyConnected, "The connection should not be read-only yet");

        // kill peer and wait no more than 5 seconds for read-only server
        // to be started (which should take one tickTime (2 seconds))
        qu.shutdown(2);

        // Re-connect the client (in case we were connected to the shut down
        // server and the local session was not persisted).
        zk = new ZooKeeper(qu.getConnString(), CONNECTION_TIMEOUT, watcher, true);
        long start = Time.currentElapsedTime();
        while (!(zk.getState() == States.CONNECTEDREADONLY)) {
            Thread.sleep(200);
            // TODO this was originally 5 seconds, but realistically, on random/slow/virt hosts, there is no way to guarantee this
            assertTrue(Time.currentElapsedTime() - start < 30000, "Can't connect to the server");
        }

        watcher.waitForReadOnlyConnected(5000);
        zk.close();
    }

    /**
     * Tests a situation when client firstly connects to a read-only server and
     * then connects to a majority server. Transition should be transparent for
     * the user.
     */
    @Test
    @Timeout(value = 90)
    public void testSessionEstablishment() throws Exception {
        qu.enableLocalSession(true);
        qu.startQuorum();

        qu.shutdown(2);

        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(qu.getConnString(), CONNECTION_TIMEOUT, watcher, true);
        watcher.waitForConnected(CONNECTION_TIMEOUT);
        assertSame(States.CONNECTEDREADONLY, zk.getState(), "should be in r/o mode");
        long fakeId = zk.getSessionId();
        LOG.info("Connected as r/o mode with state {} and session id {}", zk.getState(), fakeId);

        watcher.reset();
        qu.start(2);
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + qu.getPeer(2).clientPort, CONNECTION_TIMEOUT),
            "waiting for server up");
        LOG.info("Server 127.0.0.1:{} is up", qu.getPeer(2).clientPort);
        // ZOOKEEPER-2722: wait until we can connect to a read-write server after the quorum
        // is formed. Otherwise, it is possible that client first connects to a read-only server,
        // then drops the connection because of shutting down of the read-only server caused
        // by leader election / quorum forming between the read-only server and the newly started
        // server. If we happen to execute the zk.create after the read-only server is shutdown and
        // before the quorum is formed, we will get a ConnectLossException.
        watcher.waitForSyncConnected(CONNECTION_TIMEOUT);
        assertEquals(States.CONNECTED, zk.getState(), "Should be in read-write mode");
        LOG.info("Connected as rw mode with state {} and session id {}", zk.getState(), zk.getSessionId());
        zk.create("/test", "test".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertFalse(zk.getSessionId() == fakeId, "fake session and real session have same id");
        zk.close();
    }

    @Test
    @Timeout(value = 90)
    public void testGlobalSessionInRO() throws Exception {
        qu.startQuorum();

        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(qu.getConnString(), CONNECTION_TIMEOUT, watcher, true);
        watcher.waitForConnected(CONNECTION_TIMEOUT);
        LOG.info("global session created 0x{}", Long.toHexString(zk.getSessionId()));

        watcher.reset();
        qu.shutdown(2);
        try {
            watcher.waitForConnected(CONNECTION_TIMEOUT);
            fail("Should not be able to renew a global session");
        } catch (TimeoutException e) {
        }
        zk.close();

        watcher.reset();
        zk = new ZooKeeper(qu.getConnString(), CONNECTION_TIMEOUT, watcher, true);
        try {
            watcher.waitForConnected(CONNECTION_TIMEOUT);
            fail("Should not be able to create a global session");
        } catch (TimeoutException e) {
        }
        zk.close();

        qu.getPeer(1).peer.enableLocalSessions(true);
        zk = new ZooKeeper(qu.getConnString(), CONNECTION_TIMEOUT, watcher, true);
        try {
            watcher.waitForConnected(CONNECTION_TIMEOUT);
        } catch (TimeoutException e) {
            fail("Should be able to create a local session");
        }
        zk.close();
    }

    /**
     * Ensures that client seeks for r/w servers while it's connected to r/o
     * server.
     */
    @SuppressWarnings("deprecation")
    @Test
    @Timeout(value = 90)
    public void testSeekForRwServer() throws Exception {
        qu.enableLocalSession(true);
        qu.startQuorum();

        // setup the logger to capture all logs
        Layout layout = Logger.getRootLogger().getAppender("CONSOLE").getLayout();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        WriterAppender appender = new WriterAppender(layout, os);
        appender.setImmediateFlush(true);
        appender.setThreshold(Level.INFO);
        Logger zlogger = Logger.getLogger("org.apache.zookeeper");
        zlogger.addAppender(appender);

        try {
            qu.shutdown(2);
            CountdownWatcher watcher = new CountdownWatcher();
            ZooKeeper zk = new ZooKeeper(qu.getConnString(), CONNECTION_TIMEOUT, watcher, true);
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
            zk.create("/test", "test".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            // resume poor fellow
            qu.getPeer(1).peer.resume();
        } finally {
            zlogger.removeAppender(appender);
        }

        os.close();
        LineNumberReader r = new LineNumberReader(new StringReader(os.toString()));
        String line;
        Pattern p = Pattern.compile(".*Majority server found.*");
        boolean found = false;
        while ((line = r.readLine()) != null) {
            if (p.matcher(line).matches()) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Majority server wasn't found while connected to r/o server");
    }

}
