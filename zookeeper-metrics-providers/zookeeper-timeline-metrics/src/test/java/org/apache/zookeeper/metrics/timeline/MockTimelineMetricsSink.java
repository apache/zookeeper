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

package org.apache.zookeeper.metrics.timeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Mock implementation of TimelineMetricsSink for testing purposes.
 *
 * <p>This mock captures all snapshots sent to it and provides methods
 * to verify the snapshots in tests.</p>
 */
public class MockTimelineMetricsSink implements TimelineMetricsSink {

    private final List<MetricSnapshot> snapshots = new ArrayList<>();
    private Properties configuration;
    private boolean configured = false;
    private boolean closed = false;
    private volatile boolean throwOnSend = false;
    private volatile boolean throwOnConfigure = false;

    @Override
    public void configure(Properties configuration) throws Exception {
        if (throwOnConfigure) {
            throw new Exception("Mock configuration failure");
        }
        this.configuration = configuration;
        this.configured = true;
    }

    @Override
    public void send(MetricSnapshot snapshot) throws Exception {
        if (throwOnSend) {
            throw new Exception("Mock send failure");
        }
        synchronized (snapshots) {
            snapshots.add(snapshot);
        }
    }

    @Override
    public void close() throws Exception {
        this.closed = true;
    }

    /**
     * Returns all captured snapshots.
     */
    public List<MetricSnapshot> getSnapshots() {
        synchronized (snapshots) {
            return new ArrayList<>(snapshots);
        }
    }

    /**
     * Returns the last captured snapshot, or null if none.
     */
    public MetricSnapshot getLastSnapshot() {
        synchronized (snapshots) {
            return snapshots.isEmpty() ? null : snapshots.get(snapshots.size() - 1);
        }
    }

    /**
     * Returns the number of snapshots captured.
     */
    public int getSnapshotCount() {
        synchronized (snapshots) {
            return snapshots.size();
        }
    }

    /**
     * Clears all captured snapshots.
     */
    public void clearSnapshots() {
        synchronized (snapshots) {
            snapshots.clear();
        }
    }

    /**
     * Returns the configuration passed to configure().
     */
    public Properties getConfiguration() {
        return configuration;
    }

    /**
     * Returns whether configure() was called.
     */
    public boolean isConfigured() {
        return configured;
    }

    /**
     * Returns whether close() was called.
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Sets whether send() should throw an exception.
     */
    public void setThrowOnSend(boolean throwOnSend) {
        this.throwOnSend = throwOnSend;
    }

    /**
     * Sets whether configure() should throw an exception.
     */
    public void setThrowOnConfigure(boolean throwOnConfigure) {
        this.throwOnConfigure = throwOnConfigure;
    }
}
