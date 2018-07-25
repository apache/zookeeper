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

package org.apache.zookeeper.metrics;

/**
 * Container of metrics.
 * Contexts are organized in a hierarchy.
 */
public interface MetricsContext {

    /**
     * Returns a sub context.
     *
     * @param name the name of the subcontext
     *
     * @return a new metrics context.
     */
    public MetricsContext getContext(String name);

    /**
     * Returns a counter.
     *
     * @param name
     * @return the counter identified by name in this context.
     */
    public Counter getCounter(String name);

    /**
     * Registers an user provided {@link Gauge} which will be called by the MetricsProvider in order to sample
     * an integer value.
     *
     * @param name
     * @param gauge
     */
    public void registerGauge(String name, Gauge gauge);

    /**
     * Returns a summary.
     *
     * @param name
     * @return the summary identified by name in this context.
     */
    public Summary getSummary(String name);

}
