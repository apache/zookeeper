/* Licensed to the Apache Software Foundation (ASF) under one
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
package org.apache.zookeeper.common;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Id;
import org.jboss.netty.channel.local.LocalAddress;

public class SocketAddressUtils {

    public static InetAddress getInetAddress(SocketAddress socketAddress) {
        if (socketAddress instanceof InetSocketAddress) {
            return ((InetSocketAddress) socketAddress).getAddress();
        } else if (socketAddress instanceof LocalAddress) {
            return InetAddress.getLoopbackAddress();
        } else {
            throw new IllegalArgumentException("Unexpected address type " + socketAddress.getClass().getName() + ": " + socketAddress.toString());
        }
    }

    public static LocalAddress mapToLocalAddress(InetSocketAddress socketAddress) {
        if (socketAddress.getAddress().getHostAddress().equals("0.0.0.0")) {
            return new LocalAddress(InetAddress.getLoopbackAddress().getHostAddress() + ":" + socketAddress.getPort());
        } else {
            return new LocalAddress(socketAddress.getAddress().getHostAddress() + ":" + socketAddress.getPort());
        }
    }

    public static int getPort(SocketAddress socketAddress) {
        if (socketAddress instanceof InetSocketAddress) {
            return ((InetSocketAddress) socketAddress).getPort();
        } else if (socketAddress instanceof LocalAddress) {
            LocalAddress local = (LocalAddress) socketAddress;
            String id = local.getId();
            try {
                int colon = id.lastIndexOf(':');
                return Integer.parseInt(id.substring(colon + 1));
            } catch (NumberFormatException | IndexOutOfBoundsException err) {
                throw new IllegalArgumentException("Unexpected local address " + id);
            }
        } else {
            throw new IllegalArgumentException("Unexpected address type " + socketAddress.getClass().getName() + ": " + socketAddress.toString());
        }
    }

    public static String getHostString(SocketAddress socketAddress) {
        if (socketAddress instanceof InetSocketAddress) {
            return ((InetSocketAddress) socketAddress).getHostString();
        } else if (socketAddress instanceof LocalAddress) {
            LocalAddress local = (LocalAddress) socketAddress;
            String id = local.getId();
            try {
                int colon = id.lastIndexOf(':');
                return id.substring(0, colon);
            } catch (IndexOutOfBoundsException err) {
                throw new IllegalArgumentException("Unexpected local address " + id);
            }
        } else {
            throw new IllegalArgumentException("Unexpected address type " + socketAddress.getClass().getName() + ": " + socketAddress.toString());
        }
    }

    public static String getHostAddress(SocketAddress socketAddress) {

        if (socketAddress instanceof InetSocketAddress) {
            return ((InetSocketAddress) socketAddress).getAddress().getHostAddress();
        } else if (socketAddress instanceof LocalAddress) {
            LocalAddress local = (LocalAddress) socketAddress;
            String id = local.getId();
            try {
                int colon = id.lastIndexOf(':');
                return id.substring(0, colon);
            } catch (IndexOutOfBoundsException err) {
                throw new IllegalArgumentException("Unexpected local address " + id);
            }
        } else {
            throw new IllegalArgumentException("Unexpected address type " + socketAddress.getClass().getName() + ": " + socketAddress.toString());
        }
    }
}
