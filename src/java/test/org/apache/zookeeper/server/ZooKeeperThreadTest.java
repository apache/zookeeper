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
package org.apache.zookeeper.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


import org.apache.zookeeper.ZKTestCase;
import org.junit.Assert;
import org.junit.Test;

public class ZooKeeperThreadTest extends ZKTestCase {
    private CountDownLatch runningLatch = new CountDownLatch(1);

    public class MyThread extends ZooKeeperThread {

        public MyThread(String threadName) {
            super(threadName);
        }

        public void run() {
            throw new Error();
        }

        @Override
        protected void handleException(String thName, Throwable e) {
            runningLatch.countDown();
        }
    }

    public class MyCriticalThread extends ZooKeeperCriticalThread {

        public MyCriticalThread(String threadName) {
            super(threadName, new ZooKeeperServerListener() {

                @Override
                public void notifyStopping(String threadName, int erroCode) {

                }
            });
        }

        public void run() {
            throw new Error();
        }

        @Override
        protected void handleException(String thName, Throwable e) {
            runningLatch.countDown();
        }
    }

    /**
     * Test verifies uncaught exception handling of ZooKeeperThread
     */
    @Test(timeout = 30000)
    public void testUncaughtException() throws Exception {
        MyThread t1 = new MyThread("Test-Thread");
        t1.start();
        Assert.assertTrue("Uncaught exception is not properly handled.",
                runningLatch.await(10000, TimeUnit.MILLISECONDS));

        runningLatch = new CountDownLatch(1);
        MyCriticalThread t2 = new MyCriticalThread("Test-Critical-Thread");
        t2.start();
        Assert.assertTrue("Uncaught exception is not properly handled.",
                runningLatch.await(10000, TimeUnit.MILLISECONDS));
    }
}
