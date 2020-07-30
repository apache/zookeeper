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

package org.apache.zookeeper.server.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class JvmPauseMonitorTest {

    private final Long sleepTime = 100L;
    private final Long infoTH = -1L;
    private final Long warnTH = -1L;
    private JvmPauseMonitor pauseMonitor;

    @Test
    @Timeout(value = 5)
    public void testJvmPauseMonitorExceedInfoThreshold() throws InterruptedException {
        QuorumPeerConfig qpConfig = mock(QuorumPeerConfig.class);
        when(qpConfig.getJvmPauseSleepTimeMs()).thenReturn(sleepTime);
        when(qpConfig.getJvmPauseInfoThresholdMs()).thenReturn(infoTH);

        pauseMonitor = new JvmPauseMonitor(qpConfig);
        pauseMonitor.serviceStart();

        assertEquals(sleepTime, Long.valueOf(pauseMonitor.sleepTimeMs));
        assertEquals(infoTH, Long.valueOf(pauseMonitor.infoThresholdMs));

        while (pauseMonitor.getNumGcInfoThresholdExceeded() == 0) {
            Thread.sleep(200);
        }
    }

    @Test
    @Timeout(value = 5)
    public void testJvmPauseMonitorExceedWarnThreshold() throws InterruptedException {
        QuorumPeerConfig qpConfig = mock(QuorumPeerConfig.class);
        when(qpConfig.getJvmPauseSleepTimeMs()).thenReturn(sleepTime);
        when(qpConfig.getJvmPauseWarnThresholdMs()).thenReturn(warnTH);

        pauseMonitor = new JvmPauseMonitor(qpConfig);
        pauseMonitor.serviceStart();

        assertEquals(sleepTime, Long.valueOf(pauseMonitor.sleepTimeMs));
        assertEquals(warnTH, Long.valueOf(pauseMonitor.warnThresholdMs));

        while (pauseMonitor.getNumGcWarnThresholdExceeded() == 0) {
            Thread.sleep(200);
        }
    }

    @AfterEach
    public void teardown() {
        pauseMonitor.serviceStop();
    }

}
