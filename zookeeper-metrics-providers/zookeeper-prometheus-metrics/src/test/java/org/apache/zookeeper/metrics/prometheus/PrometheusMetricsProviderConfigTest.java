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

import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.Properties;
import org.apache.zookeeper.metrics.MetricsProviderLifeCycleException;
import org.junit.jupiter.api.Test;

public class PrometheusMetricsProviderConfigTest extends PrometheusMetricsTestBase {

    @Test
    public void testInvalidPort() {
        assertThrows(MetricsProviderLifeCycleException.class, () -> {
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
        assertThrows(MetricsProviderLifeCycleException.class, () -> {
            PrometheusMetricsProvider provider = new PrometheusMetricsProvider();
            Properties configuration = new Properties();
            configuration.setProperty("httpHost", "master");
            provider.configure(configuration);
            provider.start();
        });
    }

    @Test
    public void testValidConfig() throws MetricsProviderLifeCycleException {
        PrometheusMetricsProvider provider = new PrometheusMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty("httpHost", "0.0.0.0");
        configuration.setProperty("httpPort", "0");
        provider.configure(configuration);
        provider.start();
    }

    @Test
    public void testValidSslConfig() throws MetricsProviderLifeCycleException {
        PrometheusMetricsProvider provider = new PrometheusMetricsProvider();
        Properties configuration = new Properties();
        String testDataPath = System.getProperty("test.data.dir", "src/test/resources/data");
        configuration.setProperty("httpHost", "127.0.0.1");
        configuration.setProperty("httpsPort", "50511");
        configuration.setProperty("ssl.keyStore.location", testDataPath + "/ssl/server_keystore.jks");
        configuration.setProperty("ssl.keyStore.password", "testpass");
        configuration.setProperty("ssl.trustStore.location", testDataPath + "/ssl/server_truststore.jks");
        configuration.setProperty("ssl.trustStore.password", "testpass");
        provider.configure(configuration);
        provider.start();
    }

    @Test
    public void testValidHttpsAndHttpConfig() throws MetricsProviderLifeCycleException {
        PrometheusMetricsProvider provider = new PrometheusMetricsProvider();
        Properties configuration = new Properties();
        String testDataPath = System.getProperty("test.data.dir", "src/test/resources/data");
        configuration.setProperty("httpPort", "50512");
        configuration.setProperty("httpsPort", "50513");
        configuration.setProperty("ssl.keyStore.location", testDataPath + "/ssl/server_keystore.jks");
        configuration.setProperty("ssl.keyStore.password", "testpass");
        configuration.setProperty("ssl.trustStore.location", testDataPath + "/ssl/server_truststore.jks");
        configuration.setProperty("ssl.trustStore.password", "testpass");
        provider.configure(configuration);
        provider.start();
    }


    @Test
    public void testInvalidSslConfig() throws MetricsProviderLifeCycleException {
        assertThrows(MetricsProviderLifeCycleException.class, () -> {
            PrometheusMetricsProvider provider = new PrometheusMetricsProvider();
            Properties configuration = new Properties();
            String testDataPath = System.getProperty("test.data.dir", "src/test/resources/data");
            configuration.setProperty("httpsPort", "50514");
            //keystore missing
            configuration.setProperty("ssl.keyStore.password", "testpass");
            configuration.setProperty("ssl.trustStore.location", testDataPath + "/ssl/server_truststore.jks");
            configuration.setProperty("ssl.trustStore.password", "testpass");
            provider.configure(configuration);
            provider.start();
        });
    }
}
