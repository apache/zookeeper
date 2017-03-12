/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.zookeeper.server;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.zookeeper.server.NettyServerCnxnFactory.CnxnChannelHandler;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyServerCnxnFactoryTest {
    private static final Logger LOG = LoggerFactory.getLogger(NettyServerCnxnFactoryTest.class);
  
    @Test
    public void test() throws Exception {
        final ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        final MessageEvent me = mock(MessageEvent.class);
        when(me.getMessage()).thenReturn("message");
        NettyServerCnxnFactory factory = new NettyServerCnxnFactory();

        final AtomicInteger counter = new AtomicInteger(0);
        final CountDownLatch verifier = new CountDownLatch(1);
        final CnxnChannelHandler channelHandler = mock(CnxnChannelHandler.class);
        NettyServerCnxn cnxn = new NettyServerCnxn(null, null, factory);
        when(ctx.getAttachment()).thenReturn(cnxn);
        Mockito.doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                int val = counter.incrementAndGet();
                LOG.info("Counter is " + val);
                try {
                    verifier.await();
                } catch (InterruptedException ie) {
                    Assert.fail("Interrupted while waiting");
                }
                LOG.info("Done waiting on verified");
                return null;
            }
        }).when(channelHandler).processMessage(me, cnxn);
        Mockito.doCallRealMethod().when(channelHandler).messageReceived(ctx, me);

        // Run two threads (starting at the same time)
        int numThreads = 2;
        ExecutorService svc = Executors.newFixedThreadPool(numThreads);
        try {
            final CountDownLatch starter = new CountDownLatch(numThreads);
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < numThreads; i++) {
                futures.add(svc.submit(new Callable<Void>() {
                    @Override
                    public Void call() {
                        starter.countDown();
                        try {
                            starter.await();
                            LOG.info("Calling messageReceived");
                            channelHandler.messageReceived(ctx, me);
                        } catch (Exception e) {
                            LOG.error("Caught exception running messageReceived", e);
                            Assert.fail("Caught exception running messageReceived");
                        }
                        return null;
                    }
                }));
            }
            // Wait for the threads to start
            starter.await();
            boolean success = false;
            // Verify that one of the threads ran, check for up to 5 seconds
            for (int i = 0; i < 25; i++) {
                if (counter.get() == 1) {
                    success = true;
                    break;
                }
                Thread.sleep(200);
            }
            Assert.assertTrue("Did not see one thread running", success);
            // Let the other one run
            verifier.countDown();
            success = false;
            // Check that the counter reflects both threads running
            for (int i = 0; i < 25; i++) {
                if (counter.get() == 2) {
                    success = true;
                    break;
                }
                Thread.sleep(200);
            }
            Assert.assertTrue("Did not see both threads running", success);
            for (int i = 0; i < 25; i++) {
                for (Future<Void> future : futures) {
                    if (!future.isDone()) {
                        Thread.sleep(200);
                        continue;
                    }
                }
                return;
            }
            Assert.fail("Did not observe both threads finishing");
        } finally {
            if (null != svc) {
                svc.shutdown();
            }
        }
    }
}
