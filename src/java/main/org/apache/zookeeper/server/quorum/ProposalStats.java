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

package org.apache.zookeeper.server.quorum;

import com.codahale.metrics.ExponentiallyDecayingReservoir;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.JmxReporter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Reservoir;
import com.codahale.metrics.Snapshot;
import org.apache.zookeeper.jmx.CommonNames;
import org.apache.zookeeper.jmx.MBeanRegistry;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * Provides real-time metrics on Leader's proposal size.
 * The class uses a histogram included in Dropwizard metrics library with ExponentiallyDecayingReservoir.
 * It provides stats of proposal sizes from the last 5 minutes with acceptable cpu/memory footprint optimized for streaming data.
 */
public class ProposalStats {
    private final Histogram proposalSizes;

    ProposalStats() {
        final MetricRegistry metrics = new MetricRegistry();
        Reservoir reservoir = new ExponentiallyDecayingReservoir();
        proposalSizes = new Histogram(reservoir);
        metrics.register(name(CommonNames.DOMAIN, "Leader", "proposalSize"), proposalSizes);
        final JmxReporter jmxReporter = JmxReporter.forRegistry(metrics).registerWith(MBeanRegistry.getInstance().getPlatformMBeanServer()).build();
        jmxReporter.start();
    }

    void updateProposalSize(int value) {
        proposalSizes.update(value);
    }

    public double getAverage() {
        return proposalSizes.getSnapshot().getMean();
    }

    public long getMin() {
        return proposalSizes.getSnapshot().getMin();
    }

    public long getMax() {
        return proposalSizes.getSnapshot().getMax();
    }

    @Override
    public String toString() {
        Snapshot s = proposalSizes.getSnapshot();
        return String.format("%d/%s/%d", s.getMin(), s.getMean(), s.getMax());
    }
}
