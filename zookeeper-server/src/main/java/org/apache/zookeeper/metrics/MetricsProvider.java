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

import java.util.Properties;
import java.util.function.BiConsumer;

/**
 * A MetricsProvider is a system which collects Metrics and publishes current values to external facilities.
 * MetricsProvider是一个收集度量标准并将当前值发布到外部设施的系统。
 *
 * The system will create an instance of the configured class using the default constructor, which must be public.<br>
 * After the instantiation of the provider, the system will call {@link #configure(java.util.Map) } in order to provide configuration,
 * and then when the system is ready to work it will call {@link #start() }.
 * <br>
 *     系统将使用默认构造函数创建已配置类的实例，默认构造函数必须是公共的。<br> *在提供程序实例化后，
 *     系统将按顺序调用{@link #configure（java.util.Map）}提供配置，*然后当系统准备好工作时，它将调用{@link #start（）}。 * <br>
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
    public void dump(BiConsumer<String, Object> sink);

    /**
     * Reset all values.
     * This method is optional and can be noop, depending
     * on the underlying implementation.
     * 重置所有值。 *此方法是可选的，可以是noop，取决于底层实现。
     */
    public void resetAllValues();
}
