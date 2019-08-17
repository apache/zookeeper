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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.io.File;
import org.apache.zookeeper.server.ServerConfig;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.junit.Before;
import org.junit.Test;

public class ServerConfigTest {

    private ServerConfig serverConfig;

    @Before
    public void setUp() {
        serverConfig = new ServerConfig();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFewArguments() {
        String[] args = {"2181"};
        serverConfig.parse(args);
    }

    @Test
    public void testValidArguments() {
        String[] args = {"2181", "/data/dir", "60000", "10000"};
        serverConfig.parse(args);

        assertEquals(2181, serverConfig.getClientPortAddress().getPort());
        assertTrue(checkEquality("/data/dir", serverConfig.getDataDir()));
        assertEquals(60000, serverConfig.getTickTime());
        assertEquals(10000, serverConfig.getMaxClientCnxns());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTooManyArguments() {
        String[] args = {"2181", "/data/dir", "60000", "10000", "9999"};
        serverConfig.parse(args);
    }

    @Test
    public void testJvmPauseMonitorConfigured() {
        final Long sleepTime = 444L;
        final Long warnTH = 5555L;
        final Long infoTH = 555L;

        QuorumPeerConfig qpConfig = mock(QuorumPeerConfig.class);
        when(qpConfig.isJvmPauseMonitorToRun()).thenReturn(true);
        when(qpConfig.getJvmPauseSleepTimeMs()).thenReturn(sleepTime);
        when(qpConfig.getJvmPauseWarnThresholdMs()).thenReturn(warnTH);
        when(qpConfig.getJvmPauseInfoThresholdMs()).thenReturn(infoTH);

        serverConfig.readFrom(qpConfig);

        assertEquals(sleepTime, Long.valueOf(serverConfig.getJvmPauseSleepTimeMs()));
        assertEquals(warnTH, Long.valueOf(serverConfig.getJvmPauseWarnThresholdMs()));
        assertEquals(infoTH, Long.valueOf(serverConfig.getJvmPauseInfoThresholdMs()));
        assertTrue(serverConfig.isJvmPauseMonitorToRun());
    }

    boolean checkEquality(String a, String b) {
        assertNotNull(a);
        assertNotNull(b);
        return a.equals(b);
    }

    boolean checkEquality(String a, File b) {
        assertNotNull(a);
        assertNotNull(b);
        return new File(a).equals(b);
    }

}
