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

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.util.Properties;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.apache.zookeeper.metrics.Counter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests about Prometheus Metrics Provider. Please note that we are not testing
 * Prometheus but only our integration.
 */
public class PrometheusHttpsMetricsProviderTest extends PrometheusMetricsTestBase {

    private PrometheusMetricsProvider provider;
    private String httpHost = "127.0.0.1";
    private int httpsPort = 4443;
    private int httpPort = 4000;
    private String testDataPath = System.getProperty("test.data.dir", "src/test/resources/data");

    public void initializeProviderWithCustomConfig(Properties inputConfiguration) throws Exception {
        provider = new PrometheusMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty("httpHost", httpHost);
        configuration.setProperty("exportJvmInfo", "false");
        configuration.setProperty("ssl.keyStore.location", testDataPath + "/ssl/server_keystore.jks");
        configuration.setProperty("ssl.keyStore.password", "testpass");
        configuration.setProperty("ssl.trustStore.location", testDataPath + "/ssl/server_truststore.jks");
        configuration.setProperty("ssl.trustStore.password", "testpass");
        configuration.putAll(inputConfiguration);
        provider.configure(configuration);
        provider.start();
    }

    @AfterEach
    public void tearDown() {
        if (provider != null) {
            provider.stop();
        }
    }

    @Test
    void testHttpResponse() throws Exception {
        Properties configuration = new Properties();
        configuration.setProperty("httpPort", String.valueOf(httpPort));
        initializeProviderWithCustomConfig(configuration);
        simulateMetricIncrement();
        validateMetricResponse(callHttpServlet("http://" + httpHost + ":" + httpPort + "/metrics"));
    }

    @Test
    void testHttpsResponse() throws Exception {
        Properties configuration = new Properties();
        configuration.setProperty("httpsPort", String.valueOf(httpsPort));
        initializeProviderWithCustomConfig(configuration);
        simulateMetricIncrement();
        validateMetricResponse(callHttpsServlet("https://" + httpHost + ":" + httpsPort + "/metrics"));
    }

    @Test
    void testHttpAndHttpsResponse() throws Exception {
        Properties configuration = new Properties();
        configuration.setProperty("httpsPort", String.valueOf(httpsPort));
        configuration.setProperty("httpPort", String.valueOf(httpPort));
        initializeProviderWithCustomConfig(configuration);
        simulateMetricIncrement();
        validateMetricResponse(callHttpServlet("http://" + httpHost + ":" + httpPort + "/metrics"));
        validateMetricResponse(callHttpsServlet("https://" + httpHost + ":" + httpsPort + "/metrics"));
    }

    private String callHttpsServlet(String urlString) throws Exception {
        // Load and configure the SSL context from the keystore and truststore
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        try (FileInputStream keystoreStream = new FileInputStream(testDataPath + "/ssl/client_keystore.jks")) {
            keyStore.load(keystoreStream, "testpass".toCharArray());
        }

        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        try (FileInputStream trustStoreStream = new FileInputStream(testDataPath + "/ssl/client_truststore.jks")) {
            trustStore.load(trustStoreStream, "testpass".toCharArray());
        }

        SSLContext sslContext = SSLContext.getInstance("TLS");
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, "testpass".toCharArray());
        TrustManagerFactory trustManagerFactory = TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);
        sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(),
                new java.security.SecureRandom());

        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
        URL url = new URL(urlString);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setRequestMethod("GET");

        return readResponse(connection);
    }

    private String callHttpServlet(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        return readResponse(connection);
    }

    private String readResponse(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(status > 299 ? connection.getErrorStream() : connection.getInputStream()))) {
            StringBuilder content = new StringBuilder();
            String inputLine;
            while ((inputLine = reader.readLine()) != null) {
                content.append(inputLine).append("\n");
            }
            return content.toString().trim();
        } finally {
            connection.disconnect();
        }
    }

    public void simulateMetricIncrement() {
        Counter counter = provider.getRootContext().getCounter("cc");
        counter.add(10);
    }

    private void validateMetricResponse(String response) throws IOException {
        assertThat(response, containsString("# TYPE cc counter"));
        assertThat(response, containsString("cc 10.0"));
    }
}