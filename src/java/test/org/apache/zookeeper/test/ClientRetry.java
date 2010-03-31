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
package org.apache.zookeeper.test;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;
import org.junit.Assert;
import org.junit.Test;

public class ClientRetry extends ClientBase {

    @Override
    public void setUp() throws Exception {
        maxCnxns = 1;
        super.setUp();
    }
    /*
     * This is a simple test - try to connect two clients to a server
     * accepting a maximum of one connection from each address. Check that
     * only one is accepted. Close that connection, and check that the other
     * eventually connects.
     *
     * There is a possibility of a false positive here, as when zk2 is tested
     * for having connected it might not have been given enough time, and finish
     * connecting after the test is done. Since the
     * server doesn't tell the client why it hasn't connected, there's no
     * obvious way to detect the difference.
     */
    @Test
    public void testClientRetry() throws IOException, InterruptedException, TimeoutException{
        CountdownWatcher cdw1 = new CountdownWatcher();
        CountdownWatcher cdw2 = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(hostPort, 10000, cdw1);
        try {
            cdw1.waitForConnected(CONNECTION_TIMEOUT);
            ZooKeeper zk2 = new ZooKeeper(hostPort, 10000, cdw2);
            try {
                States s1 = zk.getState();
                States s2 = zk2.getState();
                Assert.assertSame(s1,States.CONNECTED);
                Assert.assertSame(s2,States.CONNECTING);
                cdw1.reset();
                cdw1.waitForDisconnected(CONNECTION_TIMEOUT);
                cdw2.waitForConnected(CONNECTION_TIMEOUT);
                Assert.assertSame(zk2.getState(),States.CONNECTED);
            } finally {
                zk2.close();
            }
        } finally {
            zk.close();
        }
    }
}

