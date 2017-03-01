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

import java.util.concurrent.atomic.AtomicInteger;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.client.FourLetterWordMain;
import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.apache.zookeeper.server.NettyServerCnxnFactory;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ShedClientsTest extends ClientBase {

    @Before
    @Override
    public void setUp() throws Exception {
        // Prevent super.setUp() from doing anything.
        // We first want to run setUpWithCustomCnxnFactory.
        // This hack is required because the JUnit Parametrized runner cannot be used for Zookeeper tests :-(
    }

    public void setUpWithCustomCnxnFactory(String cnxnFactoryClassName) throws Exception {
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, cnxnFactoryClassName);
        super.setUp();
    }

    @After
    @Override
    public void tearDown() throws Exception {
        System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
        super.tearDown();
    }

    @Test(timeout = 60000)
    public void testShedAllClientsNIO() throws Exception {
        setUpWithCustomCnxnFactory(NIOServerCnxnFactory.class.getCanonicalName());
        testShedAllClients();
    }

    @Test(timeout = 60000)
    public void testShedSomeClientsNIO() throws Exception {
        setUpWithCustomCnxnFactory(NIOServerCnxnFactory.class.getCanonicalName());
        testShedSomeClients();
    }

    @Test(timeout = 60000)
    public void testShedAllClientsNetty() throws Exception {
        setUpWithCustomCnxnFactory(NettyServerCnxnFactory.class.getCanonicalName());
        testShedAllClients();
    }

    @Test(timeout = 60000)
    public void testShedSomeClientsNetty() throws Exception {
        setUpWithCustomCnxnFactory(NettyServerCnxnFactory.class.getCanonicalName());
        testShedSomeClients();
    }

    public void testShedAllClients() throws Exception {

        HostPort hpobj = ClientBase.parseHostPortList(hostPort).get(0);

        DisconnectionCountingWatcher watcher = new DisconnectionCountingWatcher();
        ZooKeeper zk = new ZooKeeper(hostPort, CONNECTION_TIMEOUT, watcher);

        watcher.waitForState(Watcher.Event.KeeperState.SyncConnected);

        for (int i = 0; i < 10; i++) {

            FourLetterWordMain.send4LetterWord(hpobj.host, hpobj.port, "shed");

            watcher.waitForState(Watcher.Event.KeeperState.Disconnected);

            watcher.waitForState(Watcher.Event.KeeperState.SyncConnected);
        }

        Assert.assertEquals(10, watcher.disconnections.get());

        zk.close();
    }

    public void testShedSomeClients() throws Exception {

        HostPort hpobj = ClientBase.parseHostPortList(hostPort).get(0);

        DisconnectionCountingWatcher watcher = new DisconnectionCountingWatcher();
        ZooKeeper zk = new ZooKeeper(hostPort, CONNECTION_TIMEOUT, watcher);

        watcher.waitForState(Watcher.Event.KeeperState.SyncConnected);

        for (int i = 0; i < 10; i++) {

            FourLetterWordMain.send4LetterWord(hpobj.host, hpobj.port, "sh50");

            // Client has a 50% chance of getting dropped in each iterations.
            // We cannot block and see. Just give it some time:
            Thread.currentThread().sleep(500);
        }

        LOG.info("Client got disconnection count: " + watcher.disconnections.get());

        // Not guaranteed but very very likely true (P=0.999)
        Assert.assertTrue(watcher.disconnections.get() > 0);

        zk.close();
    }

    private class DisconnectionCountingWatcher implements Watcher {

        Event.KeeperState currentState = Event.KeeperState.Disconnected;
        private AtomicInteger disconnections = new AtomicInteger(0);

        @Override
        public void process(WatchedEvent event) {
            LOG.info("Setting client state: " + event.getState().name());
            synchronized (this) {

                if (!currentState.equals(Event.KeeperState.Disconnected) && event.getState().equals(Event.KeeperState.Disconnected)) {
                    disconnections.incrementAndGet();
                }

                currentState = event.getState();
                notifyAll();
            }
        }

        void waitForState(Event.KeeperState state) {
            try {
                synchronized (this) {
                    while (!currentState.equals(state)) {
                        this.wait(100);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for state: " + state.name());
            }
        }
    }
}
