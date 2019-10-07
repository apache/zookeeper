/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.util;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Assert;
import org.junit.Test;

public class TestCircularBlockingQueue {

  @Test
  public void testCircularBlockingQueue() throws InterruptedException {
    final CircularBlockingQueue<Integer> testQueue =
        new CircularBlockingQueue<>(2);

    testQueue.offer(1);
    testQueue.offer(2);
    testQueue.offer(3);

    Assert.assertEquals(2, testQueue.size());

    Assert.assertEquals(2, testQueue.take().intValue());
    Assert.assertEquals(3, testQueue.take().intValue());

    Assert.assertEquals(1L, testQueue.getDroppedCount());
    Assert.assertEquals(0, testQueue.size());
    Assert.assertEquals(true, testQueue.isEmpty());
  }

  @Test(timeout = 10000L)
  public void testCircularBlockingQueueTakeBlock()
      throws InterruptedException, ExecutionException {

    final CircularBlockingQueue<Integer> testQueue = new CircularBlockingQueue<>(2);

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<Integer> testTake = executor.submit(() -> {
        return testQueue.take();
      });

      // Allow the other thread to get into position; waiting for item to be
      // inserted
      while (!testQueue.isConsumerThreadBlocked()) {
        Thread.sleep(50L);
      }

      testQueue.offer(10);

      Integer result = testTake.get();
      Assert.assertEquals(10, result.intValue());
    } finally {
      executor.shutdown();
    }
  }

}
