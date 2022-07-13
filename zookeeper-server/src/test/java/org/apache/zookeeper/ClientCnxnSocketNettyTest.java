package org.apache.zookeeper;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import org.apache.zookeeper.client.ZKClientConfig;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClientCnxnSocketNettyTest {

    ClientCnxn.SendThread mockSendThread;
    ChannelFuture connectFutureMock;
    Bootstrap bootstrapMock;
    ChannelFutureListener channelFutureListener;

    @Before
    public void setUp() throws Exception {
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
    public void testCleanupDoesNotBlockUnsuccessfulConnectionHandling() throws Exception {
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
    public void testCancelledOperationComplete() throws Exception {
        testOperationCompleteLockAcquisition(3, 3);
    }

    @Test
    public void testCancelledOperationCompleteRetriesLockAcquisition() throws Exception {
        testOperationCompleteLockAcquisition(3, 4);
    }

    void testOperationCompleteLockAcquisition(int cancelledInvocations, int unlockedCancelledInvocations) throws Exception {
        CountDownLatch cancelledInvoked = new CountDownLatch(cancelledInvocations);
        CountDownLatch cancelledInvokedUnlocked = new CountDownLatch(unlockedCancelledInvocations);

        when(connectFutureMock.cancel(false))
            .thenAnswer(args -> {
                cancelledInvoked.await(); // proceed after 3 invocations of `#cancelled`
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
