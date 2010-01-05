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

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.SocketChannel;
import java.util.List;

public class TestableZooKeeper extends ZooKeeper {

    public TestableZooKeeper(String host, int sessionTimeout,
            Watcher watcher) throws IOException {
        super(host, sessionTimeout, watcher);
    }
    
    @Override
    public List<String> getChildWatches() {
        return super.getChildWatches();
    }


    @Override
    public List<String> getDataWatches() {
        return super.getDataWatches();
    }


    @Override
    public List<String> getExistWatches() {
        return super.getExistWatches();
    }


    /**
     * Cause this ZooKeeper object to stop receiving from the ZooKeeperServer
     * for the given number of milliseconds.
     * @param ms the number of milliseconds to pause.
     */
    public void pauseCnxn(final long ms) {
        new Thread() {
            public void run() {
                synchronized(cnxn) {
                    try {
                        try {
                            ((SocketChannel)cnxn.sendThread.sockKey.channel()).socket().close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        Thread.sleep(ms);
                    } catch (InterruptedException e) {
                    }
                }
            }
        }.start();
    }
    
    public boolean testableWaitForShutdown(int wait)
        throws InterruptedException
    {
        return super.testableWaitForShutdown(wait);
    }

    public SocketAddress testableLocalSocketAddress() {
        return super.testableLocalSocketAddress();
    }

    public SocketAddress testableRemoteSocketAddress() {
        return super.testableRemoteSocketAddress();
    }
}
