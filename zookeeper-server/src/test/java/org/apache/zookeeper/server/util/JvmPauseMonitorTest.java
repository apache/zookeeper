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

package org.apache.zookeeper.server.util;

import org.apache.zookeeper.server.ServerConfig;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

public class JvmPauseMonitorTest {

    @Test
    public void testJvmPauseMonitorInit() {
        final Long sleepTime = 444L;
        final Long warnTH = 5555L;
        final Long infoTH = 555L;

        ServerConfig serverConfig = new ServerConfig();
        QuorumPeerConfig quorumPeerConfig = new QuorumPeerConfig();

        Assert.assertEquals(false, serverConfig.isJvmPauseMonitorToRun());
        Assert.assertEquals(false, quorumPeerConfig.isJvmPauseMonitorToRun());

        Properties zkProp = new Properties();
        zkProp.setProperty("dataDir", new File("myDataDir").getAbsolutePath());
        zkProp.setProperty("jvm.pause.monitor", "true");
        zkProp.setProperty("jvm.pause.sleep.time.ms", sleepTime.toString());
        zkProp.setProperty("jvm.pause.warn-threshold.ms", warnTH.toString());
        zkProp.setProperty("jvm.pause.info-threshold.ms", infoTH.toString());
        try {
            quorumPeerConfig.parseProperties(zkProp);
        } catch (IOException | QuorumPeerConfig.ConfigException e) {
            Assert.fail("Exception while reading config for JvmPauseMonitor");
        }
        serverConfig.readFrom(quorumPeerConfig);

        Assert.assertEquals(true, serverConfig.isJvmPauseMonitorToRun());
        Assert.assertEquals(true, quorumPeerConfig.isJvmPauseMonitorToRun());

        JvmPauseMonitor pauseMonitor = new JvmPauseMonitor(serverConfig);
        Assert.assertFalse(pauseMonitor.isStarted());
        pauseMonitor.serviceStart();
        Assert.assertTrue(pauseMonitor.isStarted());

        Assert.assertEquals(sleepTime, Long.valueOf(pauseMonitor.sleepTimeMs));
        Assert.assertEquals(warnTH, Long.valueOf(pauseMonitor.warnThresholdMs));
        Assert.assertEquals(infoTH, Long.valueOf(pauseMonitor.infoThresholdMs));
    }

    @Test
    public void testJvmPauseMonitorExceedInfoThreshold() throws InterruptedException {
        final Long sleepTime = 100L;
        final Long infoTH = -1L;

        ServerConfig serverConfig = new ServerConfig();
        QuorumPeerConfig quorumPeerConfig = new QuorumPeerConfig();

        Properties zkProp = new Properties();
        zkProp.setProperty("dataDir", new File("myDataDir").getAbsolutePath());
        zkProp.setProperty("jvm.pause.monitor", "true");
        zkProp.setProperty("jvm.pause.sleep.time.ms", sleepTime.toString());
        zkProp.setProperty("jvm.pause.info-threshold.ms", infoTH.toString());
        try {
            quorumPeerConfig.parseProperties(zkProp);
        } catch (IOException | QuorumPeerConfig.ConfigException e) {
            Assert.fail("Exception while reading config for JvmPauseMonitor");
        }
        serverConfig.readFrom(quorumPeerConfig);

        JvmPauseMonitor pauseMonitor = new JvmPauseMonitor(serverConfig);
        pauseMonitor.serviceStart();

        Assert.assertEquals(sleepTime, Long.valueOf(pauseMonitor.sleepTimeMs));
        Assert.assertEquals(infoTH, Long.valueOf(pauseMonitor.infoThresholdMs));

        Thread.sleep(1000);

        Assert.assertNotEquals(0L, pauseMonitor.getNumGcInfoThresholdExceeded());
        Assert.assertEquals(0L, pauseMonitor.getNumGcWarnThresholdExceeded());
    }

    @Test
    public void testJvmPauseMonitorExceedWarnThreshold() throws InterruptedException {
        final Long sleepTime = 100L;
        final Long warnTH = -1L;

        ServerConfig serverConfig = new ServerConfig();
        QuorumPeerConfig quorumPeerConfig = new QuorumPeerConfig();

        Properties zkProp = new Properties();
        zkProp.setProperty("dataDir", new File("myDataDir").getAbsolutePath());
        zkProp.setProperty("jvm.pause.monitor", "true");
        zkProp.setProperty("jvm.pause.sleep.time.ms", sleepTime.toString());
        zkProp.setProperty("jvm.pause.warn-threshold.ms", warnTH.toString());
        try {
            quorumPeerConfig.parseProperties(zkProp);
        } catch (IOException | QuorumPeerConfig.ConfigException e) {
            Assert.fail("Exception while reading config for JvmPauseMonitor");
        }
        serverConfig.readFrom(quorumPeerConfig);

        JvmPauseMonitor pauseMonitor = new JvmPauseMonitor(serverConfig);
        pauseMonitor.serviceStart();

        Assert.assertEquals(sleepTime, Long.valueOf(pauseMonitor.sleepTimeMs));
        Assert.assertEquals(warnTH, Long.valueOf(pauseMonitor.warnThresholdMs));

        Thread.sleep(1000);

        Assert.assertEquals(0L, pauseMonitor.getNumGcInfoThresholdExceeded());
        Assert.assertNotEquals(0L, pauseMonitor.getNumGcWarnThresholdExceeded());
    }
}
