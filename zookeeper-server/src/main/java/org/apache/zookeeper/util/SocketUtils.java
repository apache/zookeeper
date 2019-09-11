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

package org.apache.zookeeper.util;

import java.net.Socket;
import java.net.SocketException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Util class used to set the common options on socket.
 */
public class SocketUtils {
    private static final Logger LOG = LoggerFactory.getLogger(SocketUtils.class);

    public static final String SOCKET_SO_LINGER = "zookeeper.quorum.solinger";
    private static int soLinger = 0;

    static {
        soLinger = Integer.getInteger(SOCKET_SO_LINGER, 0);
        LOG.info("{} = {}", SOCKET_SO_LINGER, soLinger);
    }

    public static void setSocketOption(Socket sock, int soTimeout) throws SocketException {
        sock.setSoTimeout(soTimeout);
        sock.setSoLinger(true, soLinger);
    }
}
