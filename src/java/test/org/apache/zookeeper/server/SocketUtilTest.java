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

import java.net.Socket;

import org.junit.Assert;
import org.junit.Test;

public class SocketUtilTest {

    @Test
    public void testSetSocketBufferSize() throws Exception {
        Socket s = new Socket();
        int initSendBufferSize = s.getSendBufferSize();
        int initReceiveBufferSize = s.getReceiveBufferSize();

        // check buffer size won't be changed without set NETWORK_BUFFER_SIZE
        SocketUtil.setSocketBufferSize("test", s);
        Assert.assertEquals(initSendBufferSize, s.getSendBufferSize());
        Assert.assertEquals(initReceiveBufferSize, s.getReceiveBufferSize());

        // set NETWORK_BUFFER_SIZE
        int networkBufferSize = 32 * 1024;
        SocketUtil.setNetworkBufferSize(networkBufferSize);
        SocketUtil.setSocketBufferSize("test", s);
        Assert.assertEquals(networkBufferSize, s.getSendBufferSize());
        Assert.assertEquals(networkBufferSize, s.getReceiveBufferSize());
    }
}
