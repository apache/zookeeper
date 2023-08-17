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

package org.apache.zookeeper.metrics;

import java.util.Properties;
import java.util.function.BiConsumer;

/**
 * A MetricsProvider is a system which collects Metrics and publishes current values to external facilities.
 *
 * The system will create an instance of the configured class using the default constructor, which must be public.<br>
 * After the instantiation of the provider, the system will call {@link #configure(java.util.Properties) } in order to provide configuration,
 * and then when the system is ready to work it will call {@link #start() }.
 * <br>
 * Providers can be used both on ZooKeeper servers and on ZooKeeper clients.
 */
public interface MetricsProvider {

    /**
     * Configure the provider.
     *
     * @param configuration the configuration.
     *
     * @throws MetricsProviderLifeCycleException in case of invalid configuration.
     */
    void configure(Properties configuration) throws MetricsProviderLifeCycleException;

    /**
     * Start the provider.
     * For instance such method will start a network endpoint.
     *
     * @throws MetricsProviderLifeCycleException in case of failure
     */
    void start() throws MetricsProviderLifeCycleException;

    /**
     * Provides access to the root context.
     *
     * @return the root context
     */
    MetricsContext getRootContext();

    /**
     * Releases resources held by the provider.<br>
     * This method must not throw exceptions.<br>
     * This method can be called more than once.
     */
    void stop();

    /**
     * Dumps all metrics as a key-value pair.
     * This method will be used in legacy monitor command.
     * @param sink the receiver of all of the current values.
     */
    void dump(BiConsumer<String, Object> sink);

    /**
     * Reset all values.
     * This method is optional and can be noop, depending
     * on the underlying implementation.
     */
    void resetAllValues();

}
