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

package org.apache.zookeeper.metrics.prometheus;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.prometheus.client.CollectorRegistry;
import java.util.Properties;
import org.apache.zookeeper.metrics.MetricsProviderLifeCycleException;
import org.junit.Assert;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.Test;
import org.slf4j.LoggerFactory;


public class PrometheusMetricsProviderConfigTest {

    @Test
    public void testInvalidPort() {
        Assert.assertThrows(MetricsProviderLifeCycleException.class, () -> {
            CollectorRegistry.defaultRegistry.clear();
            PrometheusMetricsProvider provider = new PrometheusMetricsProvider();
            Properties configuration = new Properties();
            configuration.setProperty("httpPort", "65536");
            configuration.setProperty("exportJvmInfo", "false");
            provider.configure(configuration);
            provider.start();
        });
    }

    @Test
    public void testInvalidAddr() {
        Assert.assertThrows(MetricsProviderLifeCycleException.class, () -> {
            CollectorRegistry.defaultRegistry.clear();
            PrometheusMetricsProvider provider = new PrometheusMetricsProvider();
            Properties configuration = new Properties();
            configuration.setProperty("httpHost", "master");
            provider.configure(configuration);
            provider.start();
        });
    }

    @Test
    public void testValidConfig() throws MetricsProviderLifeCycleException {
        CollectorRegistry.defaultRegistry.clear();
        PrometheusMetricsProvider provider = new PrometheusMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty("httpHost", "0.0.0.0");
        configuration.setProperty("httpPort", "0");
        provider.configure(configuration);
        provider.start();
    }

    @Test
    public void testLogRedactorRedactsPasswords() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(PrometheusMetricsProvider.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);

        try {
            PrometheusMetricsProvider provider = new PrometheusMetricsProvider();
            Properties configuration = new Properties();
            configuration.setProperty("httpHost", "0.0.0.0");
            configuration.setProperty("httpPort", "65536");
            configuration.setProperty("some.password", "SuperSecret123!");
            configuration.setProperty("some-other.password", "AnotherSecret456!");
            provider.configure(configuration);

            String logOutput = listAppender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(msg -> msg.contains("configuration"))
                    .findFirst()
                    .orElse("");

            assertFalse(logOutput.contains("SuperSecret123!"),
                    "Logs should not contain some password");
            assertFalse(logOutput.contains("AnotherSecret456!"),
                    "Logs should not contain other password");
            assertTrue(logOutput.contains("0.0.0.0"),
                    "Logs should still contain non-sensitive config like host");
        } finally {
            logger.detachAppender(listAppender);
        }
    }
}
