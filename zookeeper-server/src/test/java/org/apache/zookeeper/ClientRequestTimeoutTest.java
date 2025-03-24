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

package org.apache.zookeeper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.server.FinalRequestProcessor;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class ClientRequestTimeoutTest extends ClientBase {

    private volatile boolean dropPacket = false;
    private volatile int dropPacketType = ZooDefs.OpCode.create;
    private volatile long delayPacket = 0;

    @BeforeEach
    @Override
    public void setUp() throws Exception {
        super.setUp();
        ZooKeeperServer zkServer = serverFactory.getZooKeeperServer();
        Field firstProcessField = zkServer.getClass().getDeclaredField("firstProcessor");
        firstProcessField.setAccessible(true);
        RequestProcessor processor = (RequestProcessor) firstProcessField.get(zkServer);
        for (;;) {
            Field nextProcessorField = processor.getClass().getDeclaredField("nextProcessor");
            nextProcessorField.setAccessible(true);
            RequestProcessor nextProcessor = (RequestProcessor) nextProcessorField.get(processor);
            if (nextProcessor instanceof FinalRequestProcessor) {
                nextProcessor = spy(nextProcessor);
                doAnswer(invocation -> {
                    Request request = invocation.getArgument(0);
                    if (dropPacket && request.type == dropPacketType) {
                        Field cnxnField = request.getClass().getDeclaredField("cnxn");
                        cnxnField.setAccessible(true);
                        cnxnField.set(request, null);
                    }
                    if (delayPacket != 0) {
                        Thread.sleep(delayPacket);
                    }
                    return invocation.callRealMethod();
                }).when(nextProcessor).processRequest(any());
                nextProcessorField.set(processor, nextProcessor);
                break;
            }
            processor = nextProcessor;
        }
    }

    @Test
    @Timeout(value = 120)
    public void testClientRequestTimeout() throws Exception {
        int requestTimeOut = CONNECTION_TIMEOUT / 4;
        System.setProperty("zookeeper.request.timeout", Integer.toString(requestTimeOut));

        CountdownWatcher watch = new CountdownWatcher();

        ZooKeeper zk = createClient(watch);

        watch.waitForConnected(ClientBase.CONNECTION_TIMEOUT);

        String data = "originalData";
        // lets see one successful operation
        zk.create("/clientHang1", data.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);

        // now make environment for client hang
        dropPacket = true;
        dropPacketType = ZooDefs.OpCode.create;

        watch.reset();

        // Test synchronous API
        try {
            zk.create("/clientHang2", data.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            fail("KeeperException is expected.");
        } catch (KeeperException exception) {
            assertEquals(KeeperException.Code.REQUESTTIMEOUT.intValue(), exception.code().intValue());
        }

        watch.waitForConnected(CONNECTION_TIMEOUT);

        // Test asynchronous API
        CompletableFuture<String> future3 = new CompletableFuture<>();
        CompletableFuture<String> future4 = new CompletableFuture<>();
        // delay so below two requests are handled in same batch in client side
        delayPacket = 500;
        zk.create("/clientHang3", data.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, (rc, path, ctx, name) -> {
            if (rc == 0) {
                future3.complete(name);
            } else {
                future3.completeExceptionally(KeeperException.create(KeeperException.Code.get(rc), path));
            }
        }, null);
        zk.create("/clientHang4", data.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, (rc, path, ctx, name) -> {
            if (rc == 0) {
                future4.complete(name);
            } else {
                future4.completeExceptionally(KeeperException.create(KeeperException.Code.get(rc), path));
            }
        }, null);
        try {
            future3.get();
        } catch (Exception ex) {
            KeeperException exception = (KeeperException) ex.getCause();
            assertEquals(KeeperException.Code.REQUESTTIMEOUT.intValue(), exception.code().intValue());
        }
        try {
            future4.get();
        } catch (Exception ex) {
            KeeperException exception = (KeeperException) ex.getCause();
            assertEquals(KeeperException.Code.CONNECTIONLOSS.intValue(), exception.code().intValue());
        }

        dropPacket = false;

        // do cleanup
        zk.close();
    }
}
