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

package org.apache.zookeeper.server;

import static org.junit.jupiter.api.Assertions.assertThrows;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import org.apache.zookeeper.PortAssignment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class NIOServerCnxnFactoryTest {

    private InetSocketAddress listenAddress;
    private NIOServerCnxnFactory factory;

    @BeforeEach
    public void setUp() throws IOException {
        listenAddress = new InetSocketAddress(PortAssignment.unique());
        factory = new NIOServerCnxnFactory();
        factory.configure(listenAddress, 100);
    }

    @AfterEach
    public void tearDown() {
        if (factory != null) {
            factory.shutdown();
        }
    }

    @Test
    public void testStartupWithoutStart_SocketAlreadyBound() throws IOException {
        assertThrows(SocketException.class, () -> {
            ServerSocket ss = new ServerSocket(listenAddress.getPort());
        });
    }

    @Test
    public void testStartupWithStart_SocketAlreadyBound() throws IOException {
        assertThrows(SocketException.class, () -> {
            factory.start();
            ServerSocket ss = new ServerSocket(listenAddress.getPort());
        });
    }

    @Test
    public void testShutdownWithoutStart_SocketReleased() throws IOException {
        factory.shutdown();
        factory = null;

        ServerSocket ss = new ServerSocket(listenAddress.getPort());
        ss.close();
    }

}
