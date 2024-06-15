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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import org.apache.zookeeper.client.ZKClientConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientCnxnSocketNettyTest {

    ClientCnxn.SendThread mockSendThread;
    ChannelFuture connectFutureMock;
    Bootstrap bootstrapMock;
    ChannelFutureListener channelFutureListener;

    @BeforeEach
    void setUp() throws Exception {
        mockSendThread = mock(ClientCnxn.SendThread.class);
        when(mockSendThread.tunnelAuthInProgress()).thenReturn(Boolean.FALSE);

        connectFutureMock = mock(ChannelFuture.class);

        bootstrapMock = mock(Bootstrap.class);
        when(bootstrapMock.connect(any(InetSocketAddress.class))).thenReturn(connectFutureMock);

        when(connectFutureMock.addListener(any()))
            .thenAnswer(args -> {
                channelFutureListener = args.getArgument(0);
                return connectFutureMock;
            });
    }

    @Test
    void testCleanupDoesNotBlockUnsuccessfulConnectionHandling() throws Exception {
        CountDownLatch cancelInvoked = new CountDownLatch(1);
        CountDownLatch operationCompleteInvoked = new CountDownLatch(1);

        when(connectFutureMock.cancel(false))
            .thenAnswer(args -> {
                cancelInvoked.countDown();
                operationCompleteInvoked.await();
                return false;
            });

        ClientCnxnSocketNetty target = new ClientCnxnSocketNetty(new ZKClientConfig()) {
            @Override
            Bootstrap configureBootstrapAllocator(Bootstrap bootstrap) {
                return bootstrapMock;
            }
        };

        target.introduce(mockSendThread, 0, new LinkedBlockingDeque<>());
        target.connect(InetSocketAddress.createUnresolved("127.0.0.1", 6000));

        CompletableFuture<Long> pendingCleanup = CompletableFuture
                .runAsync(() -> target.cleanup())
                .thenApply(nothing -> System.currentTimeMillis());
        cancelInvoked.await();

        channelFutureListener.operationComplete(mockChannelFuture(false));
        operationCompleteInvoked.countDown();

        long endOperationComplete = System.currentTimeMillis();
        long endCleanup = pendingCleanup.get();

        // `operationComplete` (exits without lock) completes before `cleanup` (holds lock until `operationComplete` exits)
        assertTrue(endOperationComplete <= endCleanup);
        assertFalse(target.isConnected());
    }

    @Test
    void testCancelledOperationComplete() throws Exception {
        testOperationCompleteLockAcquisition(3, 3);
    }

    @Test
    void testCancelledOperationCompleteRetriesLockAcquisition() throws Exception {
        testOperationCompleteLockAcquisition(3, 4);
    }

    void testOperationCompleteLockAcquisition(int cancelledInvocations, int unlockedCancelledInvocations) throws Exception {
        CountDownLatch cancelledInvoked = new CountDownLatch(cancelledInvocations);
        CountDownLatch cancelledInvokedUnlocked = new CountDownLatch(unlockedCancelledInvocations);
        CountDownLatch connectFutureCancelInvoked = new CountDownLatch(1);

        when(connectFutureMock.cancel(false))
            .thenAnswer(args -> {
                connectFutureCancelInvoked.countDown();
                cancelledInvoked.await(); // proceed after `cancelledInvocations` invocations of `#cancelled`
                return false;
            });

        ClientCnxnSocketNetty target = new ClientCnxnSocketNetty(new ZKClientConfig()) {
            @Override
            Bootstrap configureBootstrapAllocator(Bootstrap bootstrap) {
                return bootstrapMock;
            }
            @Override
            boolean cancelled(ChannelFuture channelFuture) {
                cancelledInvoked.countDown();
                cancelledInvokedUnlocked.countDown();
                return super.cancelled(channelFuture);
            }
            @Override
            void afterConnectFutureCancel() {
                try {
                    cancelledInvokedUnlocked.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        target.introduce(mockSendThread, 0, new LinkedBlockingDeque<>());
        target.connect(InetSocketAddress.createUnresolved("127.0.0.1", 6000));

        CompletableFuture<Long> pendingCleanup = CompletableFuture
                .runAsync(() -> target.cleanup())
                .thenApply(nothing -> System.currentTimeMillis());
        // Awaiting `connectFutureCancelInvoked` ensures that `#cleanup` obtains the lock first
        connectFutureCancelInvoked.await();
        channelFutureListener.operationComplete(mockChannelFuture(true));

        long endOperationComplete = System.currentTimeMillis();
        long endCleanup = pendingCleanup.get();

        // `cleanup` completes before `operationComplete` (exits when `#cancelled` is true)
        assertTrue(endCleanup <= endOperationComplete);
        assertFalse(target.isConnected());
    }

    ChannelFuture mockChannelFuture(boolean successful) {
        ChannelFuture channelFuture = mock(ChannelFuture.class);
        when(channelFuture.isSuccess()).thenReturn(successful);
        if (successful) {
            when(channelFuture.channel()).thenReturn(mock(Channel.class));
        }
        return channelFuture;
    }
}
