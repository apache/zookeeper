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

package org.apache.zookeeper;

import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperThread;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;

public class ClientCloseTest extends ZKTestCase {

    @BeforeClass
    public static void setupClass() {
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, "org.apache.zookeeper.server.NettyServerCnxnFactory");
        System.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET, "org.apache.zookeeper.ClientCnxnSocketNetty");
    }

    @Test
    public void testClientClose() throws InterruptedException, IOException, NoSuchFieldException, IllegalAccessException {
        ZooKeeper zooKeeper = new ZooKeeper("dummydomain.local:4096", 5000, DummyWatcher.INSTANCE);

        Field cnxnField = zooKeeper.getClass().getDeclaredField("cnxn");
        cnxnField.setAccessible(true);
        ClientCnxn clientCnxn = (ClientCnxn) cnxnField.get(zooKeeper);
        Field sendThreadField = ClientCnxn.class.getDeclaredField("sendThread");
        sendThreadField.setAccessible(true);
        ZooKeeperThread sendThread = (ZooKeeperThread) sendThreadField.get(clientCnxn);
        Field eventThreadField = ClientCnxn.class.getDeclaredField("eventThread");
        eventThreadField.setAccessible(true);
        ZooKeeperThread eventThread = (ZooKeeperThread) eventThreadField.get(clientCnxn);

        zooKeeper.close();
        Thread.sleep(1000);

        assertEquals(eventThread.getState(), Thread.State.TERMINATED);
        assertEquals(sendThread.getState(), Thread.State.TERMINATED);

    }
}
