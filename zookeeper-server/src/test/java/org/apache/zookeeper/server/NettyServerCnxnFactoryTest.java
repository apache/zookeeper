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

import java.net.InetSocketAddress;
import org.apache.zookeeper.PortAssignment;
import org.junit.Assert;
import org.junit.Test;

public class NettyServerCnxnFactoryTest {

    @Test
    public void testRebind() throws Exception {
        InetSocketAddress addr = new InetSocketAddress(PortAssignment.unique());
        NettyServerCnxnFactory factory = new NettyServerCnxnFactory();
        factory.configure(addr, 100, -1, false);
        factory.start();
        Assert.assertTrue(factory.getParentChannel().isActive());

        factory.reconfigure(addr);

        // wait the state change
        Thread.sleep(100);

        Assert.assertTrue(factory.getParentChannel().isActive());
    }

    @Test
    public void testRebindIPv4IPv6() throws Exception {
        int randomPort = PortAssignment.unique();
        InetSocketAddress addr = new InetSocketAddress("0.0.0.0", randomPort);
        NettyServerCnxnFactory factory = new NettyServerCnxnFactory();
        factory.configure(addr, 100, -1, false);
        factory.start();
        Assert.assertTrue(factory.getParentChannel().isActive());

        factory.reconfigure(new InetSocketAddress("[0:0:0:0:0:0:0:0]", randomPort));

        // wait the state change
        Thread.sleep(100);

        Assert.assertTrue(factory.getParentChannel().isActive());
    }

}
