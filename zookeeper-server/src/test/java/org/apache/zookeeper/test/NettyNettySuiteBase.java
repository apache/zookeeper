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

import org.apache.zookeeper.ClientCnxnSocketNetty;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.server.NettyServerCnxnFactory;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Run tests with: Netty Client against Netty server
 */
@RunWith(Suite.class)
public class NettyNettySuiteBase {

    @BeforeAll
    public static void setUp() {
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, NettyServerCnxnFactory.class.getName());
        System.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET, ClientCnxnSocketNetty.class.getName());
        System.setProperty("zookeeper.admin.enableServer", "false");
    }

    @AfterAll
    public static void tearDown() {
        System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
        System.clearProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET);
    }

    @BeforeEach
    public void setUpTest() throws Exception {
        TestByteBufAllocatorTestHelper.setTestAllocator(TestByteBufAllocator.getInstance());
    }

    @AfterEach
    public void tearDownTest() throws Exception {
        TestByteBufAllocatorTestHelper.clearTestAllocator();
        TestByteBufAllocator.checkForLeaks();
    }

}
