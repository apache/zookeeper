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

package org.apache.zookeeper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Random;
import java.util.concurrent.TimeoutException;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.QuorumUtil;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlueThrottleTest extends ZKTestCase {

    private static final Logger LOG = LoggerFactory.getLogger(BlueThrottleTest.class);
    private static final int RAPID_TIMEOUT = 10000;

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
        assertTrue(throttler.checkLimit(1), "Throttle should be disabled by default");
    }

    @Test
    public void testThrottleWithoutRefill() {
        BlueThrottle throttler = new BlueThrottle();
        throttler.setMaxTokens(1);
        throttler.setFillTime(2000);
        assertTrue(throttler.checkLimit(1), "First request should be allowed");
        assertFalse(throttler.checkLimit(1), "Second request should be denied");
    }

    @Test
    public void testThrottleWithRefill() throws InterruptedException {
        BlueThrottle throttler = new BlueThrottle();
        throttler.setMaxTokens(1);
        throttler.setFillTime(500);
        assertTrue(throttler.checkLimit(1), "First request should be allowed");
        assertFalse(throttler.checkLimit(1), "Second request should be denied");

        //wait for the bucket to be refilled
        Thread.sleep(750);
        assertTrue(throttler.checkLimit(1), "Third request should be allowed since we've got a new token");
    }

    @Test
    public void testThrottleWithoutRandomDropping() throws InterruptedException {
        int maxTokens = 5;
        BlueThrottle throttler = new BlueThrottleWithMockRandom(new MockRandom());
        throttler.setMaxTokens(maxTokens);
        throttler.setFillCount(maxTokens);
        throttler.setFillTime(1000);

        for (int i = 0; i < maxTokens; i++) {
            throttler.checkLimit(1);
        }
        assertEquals(throttler.getMaxTokens(), throttler.getDeficit(), "All tokens should be used up by now");

        Thread.sleep(110);
        throttler.checkLimit(1);
        assertFalse(throttler.getDropChance() > 0, "Dropping probability should still be zero");

        //allow bucket to be refilled
        Thread.sleep(1500);

        for (int i = 0; i < maxTokens; i++) {
            assertTrue(throttler.checkLimit(1), "The first " + maxTokens + " requests should be allowed");
        }

        for (int i = 0; i < maxTokens; i++) {
            assertFalse(throttler.checkLimit(1), "The latter " + maxTokens + " requests should be denied");
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

        for (int i = 0; i < maxTokens; i++) {
            throttler.checkLimit(1);
        }
        assertEquals(throttler.getMaxTokens(), throttler.getDeficit(), "All tokens should be used up by now");

        Thread.sleep(120);
        //this will trigger dropping probability being increased
        throttler.checkLimit(1);
        assertTrue(throttler.getDropChance() > 0, "Dropping probability should be increased");
        LOG.info("Dropping probability is {}", throttler.getDropChance());

        //allow bucket to be refilled
        Thread.sleep(1100);
        LOG.info("Bucket is refilled with {} tokens.", maxTokens);

        int accepted = 0;
        for (int i = 0; i < maxTokens; i++) {
            if (throttler.checkLimit(1)) {
                accepted++;
            }
        }

        LOG.info("Send {} requests, {} are accepted", maxTokens, accepted);
        assertTrue(accepted < maxTokens, "The dropping should be distributed");

        accepted = 0;

        for (int i = 0; i < maxTokens; i++) {
            if (throttler.checkLimit(1)) {
                accepted++;
            }
        }

        LOG.info("Send another {} requests, {} are accepted", maxTokens, accepted);
        assertTrue(accepted > 0, "Later requests should have a chance");
    }

    private QuorumUtil quorumUtil = new QuorumUtil(1);
    private ClientBase.CountdownWatcher[] watchers;
    private ZooKeeper[] zks;

    private int connect(int n) throws Exception {
        String connStr = quorumUtil.getConnectionStringForServer(1);
        int connected = 0;

        zks = new ZooKeeper[n];
        watchers = new ClientBase.CountdownWatcher[n];
        for (int i = 0; i < n; i++){
            watchers[i] = new ClientBase.CountdownWatcher();
            zks[i] = new ZooKeeper(connStr, 3000, watchers[i]);
            try {
                watchers[i].waitForConnected(RAPID_TIMEOUT);
                connected++;
            } catch (TimeoutException e) {
                LOG.info("Connection denied by the throttler due to insufficient tokens");
                break;
            }
        }

        return connected;
    }

    private void shutdownQuorum() throws Exception{
        for (ZooKeeper zk : zks) {
            if (zk != null) {
                zk.close();
            }
        }

        quorumUtil.shutdownAll();
    }

    @Test
    public void testNoThrottling() throws Exception {
        quorumUtil.startAll();

        //disable throttling
        quorumUtil.getPeer(1).peer.getActiveServer().connThrottle().setMaxTokens(0);

        int connected = connect(10);

        assertEquals(10, connected);
        shutdownQuorum();
    }

    @Test
    public void testThrottling() throws Exception {
        quorumUtil.enableLocalSession(true);
        quorumUtil.startAll();

        quorumUtil.getPeer(1).peer.getActiveServer().connThrottle().setMaxTokens(2);
        //no refill, makes testing easier
        quorumUtil.getPeer(1).peer.getActiveServer().connThrottle().setFillCount(0);


        int connected = connect(3);
        assertEquals(2, connected);
        shutdownQuorum();

        quorumUtil.enableLocalSession(false);
        quorumUtil.startAll();

        quorumUtil.getPeer(1).peer.getActiveServer().connThrottle().setMaxTokens(2);
        //no refill, makes testing easier
        quorumUtil.getPeer(1).peer.getActiveServer().connThrottle().setFillCount(0);


        connected = connect(3);
        assertEquals(2, connected);
        shutdownQuorum();
    }

    @Test
    public void testWeighedThrottling() throws Exception {
        // this test depends on the session weights set to the default values
        // 3 for global session, 2 for renew sessions, 1 for local sessions
        BlueThrottle.setConnectionWeightEnabled(true);

        quorumUtil.enableLocalSession(true);
        quorumUtil.startAll();
        quorumUtil.getPeer(1).peer.getActiveServer().connThrottle().setMaxTokens(10);
        quorumUtil.getPeer(1).peer.getActiveServer().connThrottle().setFillCount(0);

        //try to create 11 local sessions, 10 created, because we have only 10 tokens
        int connected = connect(11);
        assertEquals(10, connected);
        shutdownQuorum();

        quorumUtil.enableLocalSession(false);
        quorumUtil.startAll();
        quorumUtil.getPeer(1).peer.getActiveServer().connThrottle().setMaxTokens(10);
        quorumUtil.getPeer(1).peer.getActiveServer().connThrottle().setFillCount(0);
        //tyr to create 11 global sessions, 3 created, because we have 10 tokens and each connection needs 3
        connected = connect(11);
        assertEquals(3, connected);
        shutdownQuorum();

        quorumUtil.startAll();
        quorumUtil.getPeer(1).peer.getActiveServer().connThrottle().setMaxTokens(10);
        quorumUtil.getPeer(1).peer.getActiveServer().connThrottle().setFillCount(0);
        connected = connect(2);
        assertEquals(2, connected);

        quorumUtil.shutdown(1);
        watchers[0].waitForDisconnected(RAPID_TIMEOUT);
        watchers[1].waitForDisconnected(RAPID_TIMEOUT);

        quorumUtil.restart(1);
        //client will try to reconnect
        quorumUtil.getPeer(1).peer.getActiveServer().connThrottle().setMaxTokens(3);
        quorumUtil.getPeer(1).peer.getActiveServer().connThrottle().setFillCount(0);
        int reconnected = 0;
        for (int i = 0; i < 2; i++){
            try {
                watchers[i].waitForConnected(RAPID_TIMEOUT);
                reconnected++;
            } catch (TimeoutException e) {
                LOG.info("One reconnect fails due to insufficient tokens");
            }
        }
        //each reconnect takes two tokens, we have 3, so only one reconnects
        LOG.info("reconnected {}", reconnected);
        assertEquals(1, reconnected);
        shutdownQuorum();
    }
}
