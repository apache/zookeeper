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

package org.apache.zookeeper.server.backup;

import com.twitter.common.stats.Histogram;
import com.twitter.common.stats.Statistics;
import com.twitter.common.stats.WindowedApproxHistogram;
import com.twitter.common.stats.WindowedStatistics;

import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.zookeeper.server.StatsGroup;

/**
 * Backup process stats
 */
public class BackupStats {
    private boolean isBackupManagerActive = false;
    private long lastTxnLogBackupIterationStart = System.currentTimeMillis();
    private long lastSnapBackupIterationStart = System.currentTimeMillis();

    // Default last error free iteration to current time in order to trigger
    // confusion and/or alerts between start-up and completion of the first
    // backup iteration.
    private long lastErrorFreeTxnLogBackupIteration = System.currentTimeMillis();
    private long lastErrorFreeSnapBackupIteration = System.currentTimeMillis();

    private WindowedStatistics txnLogSizeStats = new WindowedStatistics();
    private WindowedStatistics snapSizeStats = new WindowedStatistics();
    private WindowedStatistics txnLogIterationDurationStats = new WindowedStatistics();
    private WindowedStatistics snapIterationDurationStats = new WindowedStatistics();

    private static final BackupStats singleton = new BackupStats();

    private BackupStats() {}

    public static BackupStats getSingleton() {
        return singleton;
    }

    // getters
    synchronized public boolean isBackupManagerActive() { return isBackupManagerActive; }

    synchronized public StatsGroup getTxnLogSizeGroup() {
        return new StatsGroup(txnLogSizeStats, null);
    }

    synchronized public StatsGroup getTxnLogLatencyGroup() {
        return new StatsGroup(txnLogIterationDurationStats, null);
    }

    synchronized public StatsGroup getSnapSizeGroup() {
        return new StatsGroup(snapSizeStats, null);
    }

    synchronized public StatsGroup getSnapLatencyGroup() {
        return new StatsGroup(snapIterationDurationStats, null);
    }

    synchronized public long getMinutesSinceLastTxnLogIterationStart() {
        return (System.currentTimeMillis() - lastTxnLogBackupIterationStart) / (60 * 1000);
    }
    synchronized public long getMinutesSinceLastSnapIterationStart() {
        return (System.currentTimeMillis() - lastSnapBackupIterationStart) / (60 * 1000);
    }

    synchronized public long getMinutesSinceLastErrorFreeTxnLogIteration() {
        return (System.currentTimeMillis() - lastErrorFreeTxnLogBackupIteration) / (60 * 1000);
    }
    synchronized public long getMinutesSinceLastErrorFreeSnapIteration() {
        return (System.currentTimeMillis() - lastErrorFreeSnapBackupIteration) / (60 * 1000);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Minutes since last txnlog iteration start: " +
            getMinutesSinceLastTxnLogIterationStart());
        sb.append("Minutes since last successful txnlog iteration: " +
            getMinutesSinceLastErrorFreeTxnLogIteration());
        sb.append("Minutes since last snap iteration start: " +
            getMinutesSinceLastSnapIterationStart());
        sb.append("Minutes since last successful snap iteration: " +
            getMinutesSinceLastErrorFreeTxnLogIteration());

        return sb.toString();
    }

    // mutators
    synchronized void updateBackupManagerState(boolean state) { isBackupManagerActive = state; }

    synchronized void setLastTxnLogBackupIterationStart() {
        lastTxnLogBackupIterationStart = System.currentTimeMillis();
    }
    synchronized void setLastSnapBackupIterationStart() {
        lastSnapBackupIterationStart = System.currentTimeMillis();
    }

    synchronized void updateTxnLogSent(long sizeInBytes) {
        txnLogSizeStats.accumulate(sizeInBytes);
    }

    synchronized void updateSnapSent(long sizeInBytes) {
        snapSizeStats.accumulate(sizeInBytes);
    }

    synchronized void txnLogIterationDone(boolean errorFree) {
        long duration = System.currentTimeMillis() - lastTxnLogBackupIterationStart;
        txnLogIterationDurationStats.accumulate(duration);

        if (errorFree) {
            lastErrorFreeTxnLogBackupIteration = System.currentTimeMillis();
        }
    }

    synchronized void snapIterationDone(boolean errorFree) {
        long duration = System.currentTimeMillis() - lastSnapBackupIterationStart;
        snapIterationDurationStats.accumulate(duration);

        if (errorFree) {
            lastErrorFreeSnapBackupIteration = System.currentTimeMillis();
        }
    }

    synchronized public void refresh() {
        txnLogSizeStats.refresh();
        snapSizeStats.refresh();
        txnLogIterationDurationStats.refresh();
        snapIterationDurationStats.refresh();
    }

    synchronized public void reset() {
        txnLogSizeStats = new WindowedStatistics();
        snapSizeStats = new WindowedStatistics();
        txnLogIterationDurationStats = new WindowedStatistics();
        snapIterationDurationStats = new WindowedStatistics();
    }
}
