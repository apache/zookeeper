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

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.zookeeper.server.ServerConfig;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This code is originally from hadoop-common, see:
 * https://github.com/apache/hadoop/blob/trunk/hadoop-common-project/hadoop-common/src/main/java/org/apache/hadoop/util/JvmPauseMonitor.java
 *
 * Class which sets up a simple thread which runs in a loop sleeping
 * for a short interval of time. If the sleep takes significantly longer
 * than its target time, it implies that the JVM or host machine has
 * paused processing, which may cause other problems. If such a pause is
 * detected, the thread logs a message.
 */
public class JvmPauseMonitor {

    private static final Logger LOG = LoggerFactory.getLogger(JvmPauseMonitor.class);

    public static final String JVM_PAUSE_MONITOR_FEATURE_SWITCH_KEY = "jvm.pause.monitor";

    /** The target sleep time */
    protected long sleepTimeMs;
    public static final String SLEEP_TIME_MS_KEY = "jvm.pause.sleep.time.ms";
    public static final long SLEEP_TIME_MS_DEFAULT = 500;

    /** log WARN if we detect a pause longer than this threshold */
    protected long warnThresholdMs;
    public static final String WARN_THRESHOLD_KEY = "jvm.pause.warn-threshold.ms";
    public static final long WARN_THRESHOLD_DEFAULT = 10000;

    /** log INFO if we detect a pause longer than this threshold */
    protected long infoThresholdMs;
    public static final String INFO_THRESHOLD_KEY = "jvm.pause.info-threshold.ms";
    public static final long INFO_THRESHOLD_DEFAULT = 1000;

    private long numGcWarnThresholdExceeded = 0;
    private long numGcInfoThresholdExceeded = 0;
    private long totalGcExtraSleepTime = 0;

    private Thread monitorThread;
    private volatile boolean shouldRun = true;

    public JvmPauseMonitor(QuorumPeerConfig config) {
        this.warnThresholdMs = config.getJvmPauseWarnThresholdMs();
        this.infoThresholdMs = config.getJvmPauseInfoThresholdMs();
        this.sleepTimeMs = config.getJvmPauseSleepTimeMs();
    }

    public JvmPauseMonitor(ServerConfig config) {
        this.warnThresholdMs = config.getJvmPauseWarnThresholdMs();
        this.infoThresholdMs = config.getJvmPauseInfoThresholdMs();
        this.sleepTimeMs = config.getJvmPauseSleepTimeMs();
    }

    public void serviceStart() {
        monitorThread = new Thread(new JVMMonitor());
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    public void serviceStop() {
        shouldRun = false;
        if (monitorThread != null) {
            monitorThread.interrupt();
            try {
                monitorThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isStarted() {
        return monitorThread != null;
    }

    public long getNumGcWarnThresholdExceeded() {
        return numGcWarnThresholdExceeded;
    }

    public long getNumGcInfoThresholdExceeded() {
        return numGcInfoThresholdExceeded;
    }

    public long getTotalGcExtraSleepTime() {
        return totalGcExtraSleepTime;
    }

    private String formatMessage(long extraSleepTime, Map<String, GcTimes> gcTimesAfterSleep, Map<String, GcTimes> gcTimesBeforeSleep) {

        Set<String> gcBeanNames = new HashSet<>(gcTimesAfterSleep.keySet());
        gcBeanNames.retainAll(gcTimesBeforeSleep.keySet());
        List<String> gcDiffs = new ArrayList<>();

        for (String name : gcBeanNames) {
            GcTimes diff = gcTimesAfterSleep.get(name).subtract(gcTimesBeforeSleep.get(name));
            if (diff.gcCount != 0) {
                gcDiffs.add("GC pool '" + name + "' had collection(s): " + diff.toString());
            }
        }

        String ret = String.format("Detected pause in JVM or host machine (eg GC): pause of approximately %d ms, "
                                   + "total pause: info level: %d, warn level: %d %n",
                                   extraSleepTime,
                                   numGcInfoThresholdExceeded,
                                   numGcWarnThresholdExceeded);
        if (gcDiffs.isEmpty()) {
            ret += ("No GCs detected");
        } else {
            ret += String.join("\n", gcDiffs);
        }
        return ret;
    }

    private Map<String, GcTimes> getGcTimes() {
        Map<String, GcTimes> map = new HashMap<>();
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            map.put(gcBean.getName(), new GcTimes(gcBean));
        }
        return map;
    }

    private static class GcTimes {

        private long gcCount;
        private long gcTimeMillis;

        private GcTimes(GarbageCollectorMXBean gcBean) {
            gcCount = gcBean.getCollectionCount();
            gcTimeMillis = gcBean.getCollectionTime();
        }

        private GcTimes(long count, long time) {
            this.gcCount = count;
            this.gcTimeMillis = time;
        }

        private GcTimes subtract(GcTimes other) {
            return new GcTimes(this.gcCount - other.gcCount, this.gcTimeMillis - other.gcTimeMillis);
        }

        public String toString() {
            return "count=" + gcCount + " time=" + gcTimeMillis + "ms";
        }

    }

    private class JVMMonitor implements Runnable {

        @Override
        public void run() {
            Map<String, GcTimes> gcTimesBeforeSleep = getGcTimes();
            LOG.info("Starting JVM Pause Monitor with infoThresholdMs:{} warnThresholdMs:{} and sleepTimeMs:{}", infoThresholdMs, warnThresholdMs, sleepTimeMs);
            while (shouldRun) {
                long startTime = Instant.now().toEpochMilli();
                try {
                    Thread.sleep(sleepTimeMs);
                } catch (InterruptedException ie) {
                    return;
                }
                long endTime = Instant.now().toEpochMilli();
                long extraSleepTime = (endTime - startTime) - sleepTimeMs;
                if (extraSleepTime >= 0) {
                    ServerMetrics.getMetrics().JVM_PAUSE_TIME.add(extraSleepTime);
                }
                Map<String, GcTimes> gcTimesAfterSleep = getGcTimes();
                if (extraSleepTime > warnThresholdMs) {
                    ++numGcWarnThresholdExceeded;
                    LOG.warn(formatMessage(extraSleepTime, gcTimesAfterSleep, gcTimesBeforeSleep));
                } else if (extraSleepTime > infoThresholdMs) {
                    ++numGcInfoThresholdExceeded;
                    LOG.info(formatMessage(extraSleepTime, gcTimesAfterSleep, gcTimesBeforeSleep));
                }
                totalGcExtraSleepTime += extraSleepTime;
                gcTimesBeforeSleep = gcTimesAfterSleep;
            }
        }

    }

}
