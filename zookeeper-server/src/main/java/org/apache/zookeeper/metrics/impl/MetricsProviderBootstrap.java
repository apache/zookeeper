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

package org.apache.zookeeper.metrics.impl;

import java.lang.reflect.InvocationTargetException;
import java.util.Properties;
import org.apache.zookeeper.metrics.MetricsProvider;
import org.apache.zookeeper.metrics.MetricsProviderLifeCycleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for bootstrap process of MetricsProviders
 */
public abstract class MetricsProviderBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(MetricsProviderBootstrap.class);

    public static MetricsProvider startMetricsProvider(
        String metricsProviderClassName,
        Properties configuration) throws MetricsProviderLifeCycleException {
        try {
            Class<?> clazz = Class.forName(
                metricsProviderClassName,
                true,
                Thread.currentThread().getContextClassLoader());
            MetricsProvider metricsProvider = (MetricsProvider) clazz.getConstructor().newInstance();
            metricsProvider.configure(configuration);
            metricsProvider.start();
            return metricsProvider;
        } catch (ClassNotFoundException
            | IllegalAccessException
            | InvocationTargetException
            | NoSuchMethodException
            | InstantiationException error) {
            LOG.error("Cannot boot MetricsProvider {}", metricsProviderClassName, error);
            throw new MetricsProviderLifeCycleException("Cannot boot MetricsProvider " + metricsProviderClassName, error);
        } catch (MetricsProviderLifeCycleException error) {
            LOG.error("Cannot boot MetricsProvider {}", metricsProviderClassName, error);
            throw error;
        }
    }

}
