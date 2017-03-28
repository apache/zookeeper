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

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.zookeeper.common.TokenBucketTest;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.apache.zookeeper.test.MaxCnxnsTest.CnxnThread;
import org.junit.Test;

public class MaxCnxnRateTest extends ClientBase {
    private int maxClientCnxnRate = 1;
    private int maxClientCnxnBurst = 10;
    private String host;
    private int port;
    static CountDownLatch startLatch;

    @Override
    public void startServer() throws Exception {
        LOG.info("STARTING server");
        maxCnxns = 1000;
        String split[] = hostPort.split(":");
        host = split[0];
        port = Integer.parseInt(split[1]);
        QuorumPeerConfig.setConfig(TokenBucketTest.getQuorumPeerConfig(maxClientCnxnBurst, maxClientCnxnRate));
        serverFactory = ServerCnxnFactory.createFactory(port, maxCnxns);
        startServer(serverFactory);
        startLatch = new CountDownLatch(1);
    }

    static class LatchedCnxnThread extends CnxnThread {
        public LatchedCnxnThread(int i, String host, int port, AtomicInteger connectionCounter) {
            super(i, host, port, connectionCounter);
        }

        @Override
        public void run() {
            try {
                startLatch.await();
                super.run();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Creates more connection threads than our burst size.  All try to connect
     * simultaneously.  Ensures that only the burst size succeeded.
     */
    @Test
    public void testMaxCnxnBurst() throws InterruptedException {
        AtomicInteger connectionCounter = new AtomicInteger(0);
        ArrayList<LatchedCnxnThread> threads = new ArrayList<>(maxClientCnxnBurst+5);
        for (int i = 0; i < maxClientCnxnBurst+5; i++) {
            LatchedCnxnThread thread = new LatchedCnxnThread(i, host, port, connectionCounter);
            thread.start();
            threads.add(thread);
        }

        startLatch.countDown();

        for (LatchedCnxnThread thread : threads) {
            thread.join();
        }
        assertEquals(maxClientCnxnBurst, connectionCounter.get());
    }

    /**
     * Creates connections in a tight loop, and ensures rate is limited
     */
    @Test
    public void testMaxCnxnRate() throws InterruptedException {
        AtomicInteger connectionCounter = new AtomicInteger(0);
        int i = 0;
        List<CnxnThread> threads = new ArrayList<>(300);
        long start = System.nanoTime();
        // slam with connections for 3 seconds
        while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3)) {
            CnxnThread thread = new CnxnThread(i++, host, port, connectionCounter);
            thread.start();
            threads.add(thread);
            Thread.sleep(10); // throttle - upper bound of 300 threads
        }
        for (CnxnThread thread : threads) {
            thread.join();
        }

        // burst size of 10
        // connection rate of 1 per second x 3 seconds
        // total connections should be 13
        // error epsilon of 1 in case timings were slightly off
        assertEquals(13, connectionCounter.get(), 1);
    }
}
