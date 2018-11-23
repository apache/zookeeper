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

package org.apache.zookeeper.server;

import org.apache.zookeeper.PortAssignment;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketException;

public class NIOServerCnxnFactoryTest {
    private InetSocketAddress listenAddress;
    private NIOServerCnxnFactory factory;

    @Before
    public void setUp() throws IOException {
        listenAddress = new InetSocketAddress(PortAssignment.unique());
        factory = new NIOServerCnxnFactory();
        factory.configure(listenAddress, 100);
    }

    @After
    public void tearDown() {
        if (factory != null) {
            factory.shutdown();
        }
    }

    @Test(expected = SocketException.class)
    public void testStartupWithoutStart_SocketAlreadyBound() throws IOException {
        ServerSocket ss = new ServerSocket(listenAddress.getPort());
    }

    @Test(expected = SocketException.class)
    public void testStartupWithStart_SocketAlreadyBound() throws IOException {
        factory.start();
        ServerSocket ss = new ServerSocket(listenAddress.getPort());
    }

    @Test
    public void testShutdownWithoutStart_SocketReleased() throws IOException {
        factory.shutdown();
        factory = null;

        ServerSocket ss = new ServerSocket(listenAddress.getPort());
        ss.close();
    }
}
