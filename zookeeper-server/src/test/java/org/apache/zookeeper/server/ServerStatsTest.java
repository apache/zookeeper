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
import org.apache.zookeeper.common.Time;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

public class ServerStatsTest extends ZKTestCase {

    private ServerStats.Provider providerMock;

    @Before
    public void setUp() {
        providerMock = mock(ServerStats.Provider.class);
    }

    @Test
    public void testPacketsMetrics() {
        // Given ...
        ServerStats serverStats = new ServerStats(providerMock);
        int incrementCount = 20;

        // When increment ...
        for (int i = 0; i < incrementCount; i++) {
            serverStats.incrementPacketsSent();
            serverStats.incrementPacketsReceived();
            serverStats.incrementPacketsReceived();
        }

        // Then ...
        Assert.assertEquals(incrementCount, serverStats.getPacketsSent());
        Assert.assertEquals(incrementCount*2, serverStats.getPacketsReceived());

        // When reset ...
        serverStats.resetRequestCounters();

        // Then ...
        assertAllPacketsZero(serverStats);

    }

    @Test
    public void testLatencyMetrics() {
        // Given ...
        ServerStats serverStats = new ServerStats(providerMock);

        // When incremented...
        serverStats.updateLatency(Time.currentElapsedTime()-1000);
        serverStats.updateLatency(Time.currentElapsedTime()-2000);

        // Then ...
        assertThat("Max latency check", 2000L,
                greaterThanOrEqualTo(serverStats.getMaxLatency()));
        assertThat("Min latency check", 1000L,
                greaterThanOrEqualTo(serverStats.getMinLatency()));
        assertThat("Avg latency check", 1500L,
                greaterThanOrEqualTo(serverStats.getAvgLatency()));

        // When reset...
        serverStats.resetLatency();

        // Then ...
        assertAllLatencyZero(serverStats);
    }

    @Test
    public void testFsyncThresholdExceedMetrics() {
        // Given ...
        ServerStats serverStats = new ServerStats(providerMock);
        int incrementCount = 30;

        // When increment ...
        for (int i = 0; i < incrementCount; i++) {
            serverStats.incrementFsyncThresholdExceedCount();
        }

        // Then ...
        Assert.assertEquals(incrementCount, serverStats.getFsyncThresholdExceedCount());

        // When reset ...
        serverStats.resetFsyncThresholdExceedCount();

        // Then ...
        assertFsyncThresholdExceedCountZero(serverStats);

    }

    @Test
    public void testReset() {
        // Given ...
        ServerStats serverStats = new ServerStats(providerMock);

        assertAllPacketsZero(serverStats);
        assertAllLatencyZero(serverStats);

        // When ...
        serverStats.incrementPacketsSent();
        serverStats.incrementPacketsReceived();
        serverStats.updateLatency(Time.currentElapsedTime()-1000);

        serverStats.reset();

        // Then ...
        assertAllPacketsZero(serverStats);
        assertAllLatencyZero(serverStats);
    }

    private void assertAllPacketsZero(ServerStats serverStats) {
        Assert.assertEquals(0L, serverStats.getPacketsSent());
        Assert.assertEquals(0L, serverStats.getPacketsReceived());
    }

    private void assertAllLatencyZero(ServerStats serverStats) {
        Assert.assertEquals(0L, serverStats.getMaxLatency());
        Assert.assertEquals(0L, serverStats.getMinLatency());
        Assert.assertEquals(0L, serverStats.getAvgLatency());
    }

    private void assertFsyncThresholdExceedCountZero(ServerStats serverStats) {
        Assert.assertEquals(0L, serverStats.getFsyncThresholdExceedCount());
    }
}