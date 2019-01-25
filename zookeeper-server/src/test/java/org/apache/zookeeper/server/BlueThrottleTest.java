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

import org.apache.zookeeper.ZKTestCase;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

public class BlueThrottleTest extends ZKTestCase {
    private static final Logger LOG = LoggerFactory.getLogger(BlueThrottleTest.class);

    class MockRandom extends Random {
        int flag = 0;
        BlueThrottle throttle;

        @Override
        public double nextDouble() {
            if (throttle.getDropChance() > 0) {
                flag = 1 - flag;
                return flag;
            } else {
                return 1;
            }
        }
    }

    class BlueThrottleWithMockRandom extends BlueThrottle {
        public BlueThrottleWithMockRandom(MockRandom random) {
            super();
            this.rng = random;
            random.throttle = this;
        }
    }

    @Test
    public void testThrottleDisabled() {
        BlueThrottle throttler = new BlueThrottle();
        Assert.assertTrue("Throttle should be disabled by default", throttler.checkLimit(1));
    }

    @Test
    public void testThrottleWithoutRefill() {
        BlueThrottle throttler = new BlueThrottle();
        throttler.setMaxTokens(1);
        throttler.setFillTime(2000);
        Assert.assertTrue("First request should be allowed", throttler.checkLimit(1));
        Assert.assertFalse("Second request should be denied", throttler.checkLimit(1));
    }

    @Test
    public void testThrottleWithRefill() throws InterruptedException {
        BlueThrottle throttler = new BlueThrottle();
        throttler.setMaxTokens(1);
        throttler.setFillTime(500);
        Assert.assertTrue("First request should be allowed", throttler.checkLimit(1));
        Assert.assertFalse("Second request should be denied", throttler.checkLimit(1));

        //wait for the bucket to be refilled
        Thread.sleep(750);
        Assert.assertTrue("Third request should be allowed since we've got a new token", throttler.checkLimit(1));
    }

    @Test
    public void testThrottleWithoutRandomDropping() throws InterruptedException {
        int maxTokens = 5;
        BlueThrottle throttler = new BlueThrottleWithMockRandom(new MockRandom());
        throttler.setMaxTokens(maxTokens);
        throttler.setFillCount(maxTokens);
        throttler.setFillTime(1000);

        for (int i=0;i<maxTokens;i++) {
            throttler.checkLimit(1);
        }
        Assert.assertEquals("All tokens should be used up by now", throttler.getMaxTokens(), throttler.getDeficit());

        Thread.sleep(110);
        throttler.checkLimit(1);
        Assert.assertFalse("Dropping probability should still be zero", throttler.getDropChance()>0);

        //allow bucket to be refilled
        Thread.sleep(1500);

        for (int i=0;i<maxTokens;i++) {
            Assert.assertTrue("The first " + maxTokens + " requests should be allowed", throttler.checkLimit(1));
        }

        for (int i=0;i<maxTokens;i++) {
            Assert.assertFalse("The latter " + maxTokens + " requests should be denied", throttler.checkLimit(1));
        }
    }

    @Test
    public void testThrottleWithRandomDropping() throws InterruptedException {
        int maxTokens = 5;
        BlueThrottle throttler = new BlueThrottleWithMockRandom(new MockRandom());
        throttler.setMaxTokens(maxTokens);
        throttler.setFillCount(maxTokens);
        throttler.setFillTime(1000);
        throttler.setFreezeTime(100);
        throttler.setDropIncrease(0.5);

        for (int i=0;i<maxTokens;i++)
            throttler.checkLimit(1);
        Assert.assertEquals("All tokens should be used up by now", throttler.getMaxTokens(), throttler.getDeficit());

        Thread.sleep(120);
        //this will trigger dropping probability being increased
        throttler.checkLimit(1);
        Assert.assertTrue("Dropping probability should be increased", throttler.getDropChance()>0);
        LOG.info("Dropping probability is {}", throttler.getDropChance());

        //allow bucket to be refilled
        Thread.sleep(1100);
        LOG.info("Bucket is refilled with {} tokens.", maxTokens);

        int accepted = 0;
        for (int i=0;i<maxTokens;i++) {
            if (throttler.checkLimit(1)) {
                accepted ++;
            }
        }

        LOG.info("Send {} requests, {} are accepted", maxTokens, accepted);
        Assert.assertTrue("The dropping should be distributed", accepted<maxTokens);

        accepted = 0;

        for (int i=0;i<maxTokens;i++) {
            if (throttler.checkLimit(1)) {
                accepted ++;
            }
        }

        LOG.info("Send another {} requests, {} are accepted", maxTokens, accepted);
        Assert.assertTrue("Later requests should have a chance", accepted > 0);
    }
}
