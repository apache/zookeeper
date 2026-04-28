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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Properties;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.apache.zookeeper.metrics.MetricsProviderLifeCycleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PrometheusMetricsProviderConfigTest extends PrometheusMetricsTestBase {

    private static final String KEYSTORE_TYPE_JKS = "JKS";
    private static final String PASSWORD = "testpass";

    private String keyStorePath;
    private String trustStorePath;
    private PrometheusMetricsProvider provider;

    @BeforeEach
    public void setup() {
        String testDataPath = System.getProperty("test.data.dir", "src/test/resources/data");
        keyStorePath = testDataPath + "/ssl/server_keystore.jks";
        trustStorePath = testDataPath + "/ssl/server_truststore.jks";
    }

    @AfterEach
    public void tearDown() {
        if (provider != null) {
            provider.stop();
        }
    }

    @Test
    public void testInvalidPort() throws MetricsProviderLifeCycleException {
        provider = new PrometheusMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty(PrometheusMetricsProvider.HTTP_PORT, "65536");
        configuration.setProperty(PrometheusMetricsProvider.EXPORT_JVM_INFO, "false");
        provider.configure(configuration);

        MetricsProviderLifeCycleException exception =
                assertThrows(MetricsProviderLifeCycleException.class, provider::start);

        assertEquals("Failed to start Prometheus Jetty server", exception.getMessage());
        assertNotNull(exception.getCause());
        assertEquals("port out of range:65536", exception.getCause().getMessage());
    }

    @Test
    public void testNoPortSet() {
        provider = new PrometheusMetricsProvider();
        Properties configuration = new Properties();
        // Do not set HTTP port and HTTPS port here.

        MetricsProviderLifeCycleException exception =
                assertThrows(MetricsProviderLifeCycleException.class, () -> provider.configure(configuration));

        assertEquals("Either httpPort or httpsPort must be configured for Prometheus exporter.",
                exception.getMessage());
    }

    @Test
    public void testInvalidAddr() throws MetricsProviderLifeCycleException {
        provider = new PrometheusMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty(PrometheusMetricsProvider.HTTP_HOST, "master");
        configuration.setProperty(PrometheusMetricsProvider.HTTP_PORT, "0");
        provider.configure(configuration);

        MetricsProviderLifeCycleException exception =
                assertThrows(MetricsProviderLifeCycleException.class, provider::start);

        assertEquals("Failed to start Prometheus Jetty server", exception.getMessage());
        assertNotNull(exception.getCause());
        assertEquals("Failed to bind to master:0", exception.getCause().getMessage());
    }

    @Test
    public void testValidConfig() throws MetricsProviderLifeCycleException {
        provider = new PrometheusMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty(PrometheusMetricsProvider.HTTP_HOST, "0.0.0.0");
        configuration.setProperty(PrometheusMetricsProvider.HTTP_PORT, "0");
        provider.configure(configuration);
        provider.start();
    }

    @Test
    public void testValidSslConfig() throws MetricsProviderLifeCycleException {
        provider = new PrometheusMetricsProvider();
        Properties configuration = new Properties();

        configuration.setProperty(PrometheusMetricsProvider.HTTP_HOST, "127.0.0.1");
        configuration.setProperty(PrometheusMetricsProvider.HTTPS_PORT, "0");
        configuration.setProperty(PrometheusMetricsProvider.SSL_KEYSTORE_LOCATION, keyStorePath);
        configuration.setProperty(PrometheusMetricsProvider.SSL_KEYSTORE_PASSWORD, PASSWORD);
        configuration.setProperty(PrometheusMetricsProvider.SSL_TRUSTSTORE_LOCATION, trustStorePath);
        configuration.setProperty(PrometheusMetricsProvider.SSL_TRUSTSTORE_PASSWORD, PASSWORD);
        provider.configure(configuration);
        provider.start();
    }

    @Test
    public void testValidHttpsAndHttpConfig() throws MetricsProviderLifeCycleException {
        provider = new PrometheusMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty(PrometheusMetricsProvider.HTTP_PORT, "0");
        configuration.setProperty(PrometheusMetricsProvider.HTTPS_PORT, "0");
        configuration.setProperty(PrometheusMetricsProvider.SSL_KEYSTORE_LOCATION, keyStorePath);
        configuration.setProperty(PrometheusMetricsProvider.SSL_KEYSTORE_PASSWORD, PASSWORD);
        configuration.setProperty(PrometheusMetricsProvider.SSL_TRUSTSTORE_LOCATION, trustStorePath);
        configuration.setProperty(PrometheusMetricsProvider.SSL_TRUSTSTORE_PASSWORD, PASSWORD);
        provider.configure(configuration);
        provider.start();
    }

    @Test
    public void testInvalidSslConfig() throws MetricsProviderLifeCycleException {
        provider = new PrometheusMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty(PrometheusMetricsProvider.HTTPS_PORT, "50514");
        // keystore missing
        configuration.setProperty(PrometheusMetricsProvider.SSL_KEYSTORE_PASSWORD, PASSWORD);
        configuration.setProperty(PrometheusMetricsProvider.SSL_TRUSTSTORE_LOCATION, trustStorePath);
        configuration.setProperty(PrometheusMetricsProvider.SSL_TRUSTSTORE_PASSWORD, PASSWORD);
        provider.configure(configuration);

        MetricsProviderLifeCycleException exception =
                assertThrows(MetricsProviderLifeCycleException.class, provider::start);

        assertEquals("Failed to start Prometheus Jetty server", exception.getMessage());
        assertNotNull(exception.getCause());
        assertEquals(
                "SSL/TLS is enabled, but 'ssl.keyStore.location' is not set.",
                exception.getCause().getMessage());
    }

    @Test
    public void testHandshakeWithSupportedProtocol() throws Exception {
        provider = new PrometheusMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty(PrometheusMetricsProvider.HTTP_HOST, "127.0.0.1");
        configuration.setProperty(PrometheusMetricsProvider.HTTPS_PORT, "7000");
        configuration.setProperty(PrometheusMetricsProvider.SSL_KEYSTORE_LOCATION, keyStorePath);
        configuration.setProperty(PrometheusMetricsProvider.SSL_KEYSTORE_PASSWORD, PASSWORD);
        configuration.setProperty(PrometheusMetricsProvider.SSL_TRUSTSTORE_LOCATION, trustStorePath);
        configuration.setProperty(PrometheusMetricsProvider.SSL_TRUSTSTORE_PASSWORD, PASSWORD);
        configuration.setProperty(PrometheusMetricsProvider.SSL_ENABLED_PROTOCOLS, "TLSv1.3");
        provider.configure(configuration);
        provider.start();

        // Use a raw SSLSocket to verify the handshake
        SSLContext sslContext = createSSLContext(keyStorePath, PASSWORD.toCharArray(), "TLSv1.3");
        SSLSocketFactory factory = sslContext.getSocketFactory();

        try (SSLSocket socket = (SSLSocket) factory.createSocket("localhost", 7000)) {
            socket.startHandshake();
            String negotiatedProtocol = socket.getSession().getProtocol();

            // Verify that we actually landed on the protocol we expected
            assertEquals("TLSv1.3", negotiatedProtocol,
                    "The negotiated protocol should be TLSv1.3.");
        }
    }

    @Test
    public void testHandshakeWithUnsupportedProtocolFails() throws Exception {
        provider = new PrometheusMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty(PrometheusMetricsProvider.HTTP_HOST, "127.0.0.1");
        configuration.setProperty(PrometheusMetricsProvider.HTTPS_PORT, "7000");
        configuration.setProperty(PrometheusMetricsProvider.SSL_KEYSTORE_LOCATION, keyStorePath);
        configuration.setProperty(PrometheusMetricsProvider.SSL_KEYSTORE_PASSWORD, PASSWORD);
        configuration.setProperty(PrometheusMetricsProvider.SSL_TRUSTSTORE_LOCATION, trustStorePath);
        configuration.setProperty(PrometheusMetricsProvider.SSL_TRUSTSTORE_PASSWORD, PASSWORD);
        configuration.setProperty(PrometheusMetricsProvider.SSL_ENABLED_PROTOCOLS, "TLSv1.3");
        provider.configure(configuration);
        provider.start();

        SSLContext sslContext = createSSLContext(keyStorePath, PASSWORD.toCharArray(), "TLSv1.1");
        SSLSocketFactory factory = sslContext.getSocketFactory();

        try (SSLSocket socket = (SSLSocket) factory.createSocket("localhost", 7000)) {
            SSLHandshakeException exception = assertThrows(SSLHandshakeException.class, socket::startHandshake);
            assertEquals(
                    "No appropriate protocol (protocol is disabled or cipher suites are inappropriate)",
                    exception.getMessage(),
                    "The handshake should have failed due to a protocol mismatch.");
        }
    }

    @Test
    public void testCipherMismatchFails() throws Exception {
        provider = new PrometheusMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty(PrometheusMetricsProvider.HTTP_HOST, "127.0.0.1");
        configuration.setProperty(PrometheusMetricsProvider.HTTPS_PORT, "7000");
        configuration.setProperty(PrometheusMetricsProvider.SSL_KEYSTORE_LOCATION, keyStorePath);
        configuration.setProperty(PrometheusMetricsProvider.SSL_KEYSTORE_PASSWORD, PASSWORD);
        configuration.setProperty(PrometheusMetricsProvider.SSL_TRUSTSTORE_LOCATION, trustStorePath);
        configuration.setProperty(PrometheusMetricsProvider.SSL_TRUSTSTORE_PASSWORD, PASSWORD);
        System.setProperty(PrometheusMetricsProvider.SSL_ENABLED_CIPHERS,
                "TLS_AES_128_GCM_SHA256,TLS_AES_256_GCM_SHA384");
        provider.configure(configuration);
        provider.start();

        SSLContext sslContext = createSSLContext(keyStorePath, PASSWORD.toCharArray(), "TLSv1.2");
        SSLSocketFactory factory = sslContext.getSocketFactory();

        try (SSLSocket socket = (SSLSocket) factory.createSocket("localhost", 7000)) {
            // Force the client to use a cipher NOT enabled for the AdminServer
            String[] unsupportedCiphers = new String[]{"TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA"};
            socket.setEnabledCipherSuites(unsupportedCiphers);

            assertThrows(SSLHandshakeException.class, socket::startHandshake,
                    "The handshake should have failed due to a cipher mismatch.");
        }
    }

    private SSLContext createSSLContext(String keystorePath, char[] password, String protocol)
            throws Exception {
        KeyManager[] keyManagers = getKeyManagers(keystorePath, password);
        TrustManager[] trustAllCerts = getTrustAllCerts();

        SSLContext sslContext = SSLContext.getInstance(protocol);
        sslContext.init(keyManagers, trustAllCerts, null);

        return sslContext;
    }

    private static KeyManager[] getKeyManagers(String keystorePath, char[] password) throws KeyStoreException,
            IOException, NoSuchAlgorithmException, CertificateException, UnrecoverableKeyException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE_JKS);
        try (FileInputStream fis = new FileInputStream(keystorePath)) {
            keyStore.load(fis, password);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password);
        return kmf.getKeyManagers();
    }

    public TrustManager[] getTrustAllCerts() {
        // This is OK for testing.
        return new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }

                    public void checkClientTrusted(X509Certificate[] certs, String authType) {
                    }

                    public void checkServerTrusted(X509Certificate[] certs, String authType) {
                    }
                }
        };
    }

    @Test
    public void testPortUnification() throws Exception {
        int unifiedPort = 5400;
        Properties configuration = new Properties();
        configuration.setProperty("httpsPort", String.valueOf(unifiedPort));
        configuration.setProperty("httpPort", String.valueOf(unifiedPort));
        String testDataPath = System.getProperty("test.data.dir", "src/test/resources/data");
        configuration.setProperty("ssl.keyStore.location", testDataPath + "/ssl/server_keystore.jks");
        configuration.setProperty("ssl.keyStore.password", "testpass");
        configuration.setProperty("ssl.trustStore.location", testDataPath + "/ssl/server_truststore.jks");
        configuration.setProperty("ssl.trustStore.password", "testpass");
        PrometheusMetricsProvider provider = new PrometheusMetricsProvider();
        provider.configure(configuration);
        provider.start();
    }
}
