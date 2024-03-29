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

package org.apache.zookeeper.common;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper methods for netty code.
 */
public class NettyUtils {

    public static final String THREAD_POOL_NAME_PREFIX = "zkNetty-";
    private static final Logger LOG = LoggerFactory.getLogger(NettyUtils.class);
    private static final int DEFAULT_INET_ADDRESS_COUNT = 1;

    /**
     * Returns a ThreadFactory which generates daemon threads, and uses
     * the passed class's name to generate the thread names.
     *
     * @param clazz Class name to use for generating thread names
     * @return Netty DefaultThreadFactory configured to create daemon threads
     */
    private static ThreadFactory createThreadFactory(String clazz) {
        final String poolName = THREAD_POOL_NAME_PREFIX + clazz;
        return new DefaultThreadFactory(poolName, true);
    }

    /**
     * If {@link Epoll#isAvailable()} <code>== true</code>, returns a new
     * {@link EpollEventLoopGroup}, otherwise returns a new
     * {@link NioEventLoopGroup}. Creates the event loop group using the
     * default number of threads.
     * @return a new {@link EventLoopGroup}.
     */
    public static EventLoopGroup newNioOrEpollEventLoopGroup() {
        return newNioOrEpollEventLoopGroup(0);
    }

    /**
     * If {@link Epoll#isAvailable()} <code>== true</code>, returns a new
     * {@link EpollEventLoopGroup}, otherwise returns a new
     * {@link NioEventLoopGroup}. Creates the event loop group using the
     * specified number of threads instead of the default.
     * @param nThreads see {@link NioEventLoopGroup#NioEventLoopGroup(int)}.
     * @return a new {@link EventLoopGroup}.
     */
    public static EventLoopGroup newNioOrEpollEventLoopGroup(int nThreads) {
        if (Epoll.isAvailable()) {
            final String clazz = EpollEventLoopGroup.class.getSimpleName();
            final ThreadFactory factory = createThreadFactory(clazz);
            return new EpollEventLoopGroup(nThreads, factory);
        } else {
            final String clazz = NioEventLoopGroup.class.getSimpleName();
            final ThreadFactory factory = createThreadFactory(clazz);
            return new NioEventLoopGroup(nThreads, factory);
        }
    }

    /**
     * If {@link Epoll#isAvailable()} <code>== true</code>, returns
     * {@link EpollSocketChannel}, otherwise returns {@link NioSocketChannel}.
     * @return a socket channel class.
     */
    public static Class<? extends SocketChannel> nioOrEpollSocketChannel() {
        if (Epoll.isAvailable()) {
            return EpollSocketChannel.class;
        } else {
            return NioSocketChannel.class;
        }
    }

    /**
     * If {@link Epoll#isAvailable()} <code>== true</code>, returns
     * {@link EpollServerSocketChannel}, otherwise returns
     * {@link NioServerSocketChannel}.
     * @return a server socket channel class.
     */
    public static Class<? extends ServerSocketChannel> nioOrEpollServerSocketChannel() {
        if (Epoll.isAvailable()) {
            return EpollServerSocketChannel.class;
        } else {
            return NioServerSocketChannel.class;
        }
    }

    /**
     * Attempts to detect and return the number of local network addresses that could be
     * used by a client to reach this server. This means we exclude the following address types:
     * <ul>
     *     <li>Multicast addresses. Zookeeper server sockets use TCP, thus cannot bind to a multicast address.</li>
     *     <li>Link-local addresses. Routers don't forward traffic sent to a link-local address, so
     *     any realistic server deployment would not have clients using these.</li>
     *     <li>Loopback addresses. These are typically only used for testing.</li>
     * </ul>
     * Any remaining addresses are counted, and the total count is returned. This number is
     * used to configure the number of threads for the "boss" event loop group, to make sure we have
     * enough threads for each address in case the server is configured to listen on
     * all available addresses.
     * If listing the network interfaces fails, this method will return 1.
     *
     * @return the number of client-reachable local network addresses found, or
     * 1 if listing the network interfaces fails.
     */
    public static int getClientReachableLocalInetAddressCount() {
        try {
            Set<InetAddress> validInetAddresses = new HashSet<>();
            Enumeration<NetworkInterface> allNetworkInterfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface networkInterface : Collections.list(allNetworkInterfaces)) {
                for (InetAddress inetAddress : Collections.list(networkInterface.getInetAddresses())) {
                    if (inetAddress.isLinkLocalAddress()) {
                        LOG.debug("Ignoring link-local InetAddress {}", inetAddress);
                        continue;
                    }
                    if (inetAddress.isMulticastAddress()) {
                        LOG.debug("Ignoring multicast InetAddress {}", inetAddress);
                        continue;
                    }
                    if (inetAddress.isLoopbackAddress()) {
                        LOG.debug("Ignoring loopback InetAddress {}", inetAddress);
                        continue;
                    }
                    validInetAddresses.add(inetAddress);
                }
            }
            LOG.debug("Detected {} local network addresses: {}", validInetAddresses.size(), validInetAddresses);
            return !validInetAddresses.isEmpty() ? validInetAddresses.size() : DEFAULT_INET_ADDRESS_COUNT;
        } catch (SocketException ex) {
            LOG.warn("Failed to list all network interfaces, assuming 1", ex);
            return DEFAULT_INET_ADDRESS_COUNT;
        }
    }

}
