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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.jute.Record;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.proto.RequestHeader;

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
     * Cause this ZooKeeper object to disconnect from the server. It will then
     * later attempt to reconnect.
     */
    public void testableConnloss() throws IOException {
        synchronized(cnxn) {
            cnxn.sendThread.testableCloseSocket();
        }
    }

    /**
     * Cause this ZooKeeper object to stop receiving from the ZooKeeperServer
     * for the given number of milliseconds.
     * @param ms the number of milliseconds to pause.
     * @return true if the connection is paused, otherwise false
     */
    public boolean pauseCnxn(final long ms) {
        final CountDownLatch initiatedPause = new CountDownLatch(1);
        new Thread() {
            public void run() {
                synchronized(cnxn) {
                    try {
                        try {
                            cnxn.sendThread.testableCloseSocket();
                        } catch (IOException e) {
                            e.printStackTrace();
                        } finally {
                            initiatedPause.countDown();
                        }
                        Thread.sleep(ms);
                    } catch (InterruptedException e) {
                    }
                }
            }
        }.start();

        try {
            return initiatedPause.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
            return false;
        }
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

    /**
     * @return the last zxid as seen by the client session
     */
    public long testableLastZxid() {
        return cnxn.getLastZxid();
    }

    public ReplyHeader submitRequest(RequestHeader h, Record request,
            Record response, WatchRegistration watchRegistration) throws InterruptedException {
        return cnxn.submitRequest(h, request, response, watchRegistration);
    }

    /** Testing only!!! Really!!!! This is only here to test when the client
     * disconnects from the server w/o sending a session disconnect (ie
     * ending the session cleanly). The server will eventually notice the
     * client is no longer pinging and will timeout the session.
     */
    public void disconnect() {
        cnxn.disconnect();
    }
}
