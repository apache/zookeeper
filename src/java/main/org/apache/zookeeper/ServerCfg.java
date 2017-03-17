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

package org.apache.zookeeper;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

public class ServerCfg {
    private final String hostStr;
    private final InetSocketAddress address;

    public ServerCfg(final String hostStr, final InetSocketAddress address) {
        this.hostStr = hostStr;
        this.address = address;
    }

    public String getHostStr() {
        return hostStr;
    }

    /**
     * Return a new InetSocketAddress, so that it can be re-resolved again.
     * @return
     */
    public InetSocketAddress getInetAddress() {
        return new InetSocketAddress(getHostStr(), address.getPort());
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof ServerCfg
                && this.hostStr.equals(((ServerCfg)other).getHostStr())
                && this.address.getPort() == ((ServerCfg) other).getPort();
    }

    @Override
    public int hashCode() {
        return hostStr.hashCode() ^ this.address.getPort();
    }

    public String getHostString() {
        return getHostStr();
    }

    public int getPort() {
        return this.address.getPort();
    }
}