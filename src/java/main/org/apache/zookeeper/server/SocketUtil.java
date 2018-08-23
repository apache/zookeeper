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

import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SocketUtil {

    private static final Logger LOG = LoggerFactory.getLogger(SocketUtil.class);

    public static final String NETWORK_BUFFER_SIZE = "zookeeper.networkBufferSize";
    protected static int networkBufferSize;


    static {
        networkBufferSize = Integer.getInteger(NETWORK_BUFFER_SIZE, -1);
        LOG.info("{} = {} bytes", NETWORK_BUFFER_SIZE, networkBufferSize);
    }

    public static void setNetworkBufferSize(int networkBufferSize) {
        SocketUtil.networkBufferSize = networkBufferSize;
        LOG.info("{} changed to {} bytes", NETWORK_BUFFER_SIZE, SocketUtil.networkBufferSize);
    }

    public static int getNetworkBufferSize() {
        return networkBufferSize;
    }

    /**
     * Set socket parameters. Also log warning message if it is unable to set
     * buffer size to the specified value.
     *
     * @param name - name of socket for log printing
     * @param sock
     * @throws SocketException
     */
    public static void setSocketBufferSize(String name, Socket sock)
            throws SocketException {
        if (networkBufferSize <= 0) {
            return;
        }

        sock.setSendBufferSize(networkBufferSize);
        int size = sock.getSendBufferSize();
        if (size != networkBufferSize) {
            LOG.warn("Unable to set send buffer size on {}, expected = " +
                    "{}, actual = {}", name, networkBufferSize, size);
        }

        sock.setReceiveBufferSize(networkBufferSize);
        size = sock.getReceiveBufferSize();
        if (size != networkBufferSize) {
            LOG.warn("Unable to set receive buffer size on {}, " +
                    "expected = {}, actual = {}", name, networkBufferSize,
                    size);
        }
    }
}
