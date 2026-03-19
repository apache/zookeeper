/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.metrics.prometheus;

import io.prometheus.metrics.core.metrics.GaugeWithCallback;
import io.prometheus.metrics.exporter.servlet.javax.PrometheusMetricsServlet;
import io.prometheus.metrics.instrumentation.jvm.JvmMetrics;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.zookeeper.metrics.Counter;
import org.apache.zookeeper.metrics.CounterSet;
import org.apache.zookeeper.metrics.Gauge;
import org.apache.zookeeper.metrics.GaugeSet;
import org.apache.zookeeper.metrics.MetricsContext;
import org.apache.zookeeper.metrics.MetricsProvider;
import org.apache.zookeeper.metrics.MetricsProviderLifeCycleException;
import org.apache.zookeeper.metrics.Summary;
import org.apache.zookeeper.metrics.SummarySet;
import org.apache.zookeeper.server.admin.UnifiedConnectionFactory;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.KeyStoreScanner;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Metrics Provider implementation based on https://prometheus.io.
 * This implementation uses prometheus-metrics-core interfaces and exposes metrics via an embedded Jetty server
 * @since 3.6.0
 */
public class PrometheusMetricsProvider implements MetricsProvider {

    private static final Logger LOG = LoggerFactory.getLogger(PrometheusMetricsProvider.class);
    private static final String LABEL = "key";

    private final PrometheusRegistry registry = PrometheusRegistry.defaultRegistry;
    private int httpPort = -1;
    private int httpsPort = -1;
    private boolean exportJvmInfo = true;
    private final Context rootContext = new Context();
    private PrometheusRegistryDumper dumper;
    private CustomPrometheusMetricsServlet servlet;

    private Server server;
    private int numWorkerThreads;
    private String host;

    // SSL Configuration fields
    private String keyStorePath;
    private String keyStorePassword;
    private String keyStoreType;
    private String trustStorePath;
    private String trustStorePassword;
    private String trustStoreType;
    private boolean needClientAuth = true; // Secure default
    private boolean wantClientAuth = true; // Secure default
    private String enabledProtocols;
    private String cipherSuites;
    private int httpVersion;

    // Constants for configuration
    public static final String HTTP_HOST = "httpHost";
    public static final String HTTP_PORT = "httpPort";
    public static final String EXPORT_JVM_INFO = "exportJvmInfo";
    public static final String HTTPS_PORT = "httpsPort";
    public static final String NUM_WORKER_THREADS = "numWorkerThreads";
    public static final String SSL_KEYSTORE_LOCATION = "ssl.keyStore.location";
    public static final String SSL_KEYSTORE_PASSWORD = "ssl.keyStore.password";
    public static final String SSL_KEYSTORE_TYPE = "ssl.keyStore.type";
    public static final String SSL_TRUSTSTORE_LOCATION = "ssl.trustStore.location";
    public static final String SSL_TRUSTSTORE_PASSWORD = "ssl.trustStore.password";
    public static final String SSL_TRUSTSTORE_TYPE = "ssl.trustStore.type";
    public static final String SSL_NEED_CLIENT_AUTH = "ssl.need.client.auth";
    public static final String SSL_WANT_CLIENT_AUTH = "ssl.want.client.auth";
    public static final String SSL_ENABLED_PROTOCOLS = "ssl.enabledProtocols";
    public static final String SSL_ENABLED_CIPHERS = "ssl.ciphersuites";
    public static final String HTTP_VERSION = "httpVersion";
    public static final int SCAN_INTERVAL = 60 * 10; // 10 minutes
    public static final int DEFAULT_HTTP_VERSION = 11;  // based on HttpVersion.java in jetty
    public static final int DEFAULT_STS_MAX_AGE = 1 * 24 * 60 * 60;  // seconds in a day

    /**
     * Custom servlet to disable the TRACE method for security reasons.
     */
    private static class CustomPrometheusMetricsServlet extends PrometheusMetricsServlet {
        public CustomPrometheusMetricsServlet(PrometheusRegistry registry) {
            super(registry);
        }

        @Override
        protected void doTrace(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        }
    }

    @Override
    public void configure(Properties configuration) throws MetricsProviderLifeCycleException {
        LOG.info("Initializing Prometheus metrics with Jetty, configuration: {}", configuration);

        this.host = configuration.getProperty(HTTP_HOST, "0.0.0.0");
        this.httpPort = Integer.parseInt(configuration.getProperty(HTTP_PORT, "-1"));
        this.httpsPort = Integer.parseInt(configuration.getProperty(HTTPS_PORT, "-1"));
        this.exportJvmInfo = Boolean.parseBoolean(configuration.getProperty(EXPORT_JVM_INFO, "true"));
        this.numWorkerThreads = Integer.parseInt(configuration.getProperty(NUM_WORKER_THREADS, "10"));

        // If httpsPort is specified, parse all SSL properties
        if (this.httpsPort != -1) {
            this.keyStorePath = configuration.getProperty(SSL_KEYSTORE_LOCATION);
            this.keyStorePassword = configuration.getProperty(SSL_KEYSTORE_PASSWORD);
            this.keyStoreType = configuration.getProperty(SSL_KEYSTORE_TYPE, "PKCS12");
            this.trustStorePath = configuration.getProperty(SSL_TRUSTSTORE_LOCATION);
            this.trustStorePassword = configuration.getProperty(SSL_TRUSTSTORE_PASSWORD);
            this.trustStoreType = configuration.getProperty(SSL_TRUSTSTORE_TYPE, "PKCS12");
            this.needClientAuth = Boolean.parseBoolean(configuration.getProperty(SSL_NEED_CLIENT_AUTH, "true"));
            this.wantClientAuth = Boolean.parseBoolean(configuration.getProperty(SSL_WANT_CLIENT_AUTH, "true"));
            this.enabledProtocols = configuration.getProperty(SSL_ENABLED_PROTOCOLS);
            this.cipherSuites = configuration.getProperty(SSL_ENABLED_CIPHERS);
            this.httpVersion = Integer.getInteger(HTTP_VERSION, DEFAULT_HTTP_VERSION);
        }

        // Validate that at least one port is configured.
        if (httpPort == -1 && httpsPort == -1) {
            throw new MetricsProviderLifeCycleException(
                    "Either httpPort or httpsPort must be configured for Prometheus exporter.");
        }

        this.dumper = new PrometheusRegistryDumper(this.registry);
        this.servlet = new CustomPrometheusMetricsServlet(this.registry);
    }

    @Override
    public void start() throws MetricsProviderLifeCycleException {
        // Register JVM metrics if enabled
        if (exportJvmInfo) {
            JvmMetrics.builder().register(this.registry);
        }
        try {
            LOG.info("Starting Prometheus Jetty server...");

            // QueuedThreadPool needs a minimum of 4 threads for stable operation
            QueuedThreadPool threadPool = new QueuedThreadPool(Math.max(this.numWorkerThreads + 3, 4));
            threadPool.setReservedThreads(0);
            threadPool.setName("prometheus-jetty-server");

            this.server = new Server(threadPool);

            // Define number of acceptors and selectors for connectors
            int acceptors = 1;
            int selectors = 1;

            ServerConnector connector = null;

            if (this.httpPort != -1 && this.httpsPort != -1 && this.httpPort == this.httpsPort) {
                SecureRequestCustomizer customizer = new SecureRequestCustomizer();
                customizer.setStsMaxAge(DEFAULT_STS_MAX_AGE);
                customizer.setStsIncludeSubDomains(true);

                HttpConfiguration config = new HttpConfiguration();
                config.setSecureScheme("https");
                config.addCustomizer(customizer);

                SslContextFactory.Server sslContextFactory = createSslContextFactory();
                setKeyStoreScanner(sslContextFactory);

                String nextProtocol = HttpVersion.fromVersion(httpVersion).asString();
                connector = new ServerConnector(server,
                        new UnifiedConnectionFactory(sslContextFactory, nextProtocol),
                        new HttpConnectionFactory(config));
                connector.setPort(this.httpPort);
                connector.setHost(this.host);
                LOG.debug("Created unified ServerConnector for host: {}, httpPort: {}", host, httpPort);
            } else {
                // Configure HTTP connector if enabled
                if (this.httpPort != -1) {
                    connector = new ServerConnector(server, acceptors, selectors);
                    connector.setPort(this.httpPort);
                    connector.setHost(this.host);
                    LOG.debug("Created ServerConnector for host: {}, httpPort: {}", host, httpPort);
                }

                // Configure HTTPS connector if enabled
                if (this.httpsPort != -1) {
                    SslContextFactory.Server sslContextFactory = createSslContextFactory();
                    setKeyStoreScanner(sslContextFactory);
                    connector = createSslConnector(server, acceptors, selectors, sslContextFactory);
                    LOG.debug("Created HTTPS ServerConnector for host: {}, httpsPort: {}", host, httpsPort);
                }
            }

            server.addConnector(connector);

            // Set up the servlet context handler
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            server.setHandler(context);
            context.addServlet(new ServletHolder(servlet), "/metrics");

            server.start();

            LOG.info("Prometheus metrics provider with Jetty started. HTTP port: {}, HTTPS port: {}",
                    httpPort != -1 ? httpPort : "disabled", httpsPort != -1 ? httpsPort : "disabled");

        } catch (Exception e) {
            LOG.error("Failed to start Prometheus Jetty server", e);
            // Ensure server is stopped on startup failure
            stop();
            throw new MetricsProviderLifeCycleException("Failed to start Prometheus Jetty server", e);
        }
    }

    private void setKeyStoreScanner(SslContextFactory.Server sslContextFactory) {
        KeyStoreScanner keystoreScanner = new KeyStoreScanner(sslContextFactory);
        keystoreScanner.setScanInterval(SCAN_INTERVAL);
        server.addBean(keystoreScanner);
    }

    /**
     * Creates and configures the SslContextFactory for the server.
     *
     * @return A configured SslContextFactory.Server instance.
     */
    private SslContextFactory.Server createSslContextFactory() {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();

        // Validate and set KeyStore properties
        if (this.keyStorePath == null || this.keyStorePath.isEmpty()) {
            throw new IllegalArgumentException("SSL/TLS is enabled, but '" + SSL_KEYSTORE_LOCATION + "' is not set.");
        }
        sslContextFactory.setKeyStorePath(this.keyStorePath);
        sslContextFactory.setKeyStorePassword(this.keyStorePassword);
        if (this.keyStoreType != null) {
            sslContextFactory.setKeyStoreType(this.keyStoreType);
        }

        // Validate and set TrustStore properties (often needed for client auth)
        if (this.needClientAuth && (this.trustStorePath == null || this.trustStorePath.isEmpty())) {
            throw new IllegalArgumentException(
                    "'" + SSL_NEED_CLIENT_AUTH + "' is true, but '" + SSL_TRUSTSTORE_LOCATION + "' is not set.");
        }
        if (this.trustStorePath != null) {
            sslContextFactory.setTrustStorePath(this.trustStorePath);
            sslContextFactory.setTrustStorePassword(this.trustStorePassword);
            if (this.trustStoreType != null) {
                sslContextFactory.setTrustStoreType(this.trustStoreType);
            }
        }

        sslContextFactory.setNeedClientAuth(this.needClientAuth);
        sslContextFactory.setWantClientAuth(this.wantClientAuth);

        if (enabledProtocols != null) {
            LOG.debug("Setting enabled protocols: '{}'", enabledProtocols);
            String[] enabledProtocolsArray = enabledProtocols.split(",");
            sslContextFactory.setIncludeProtocols(enabledProtocolsArray);
        }

        if (cipherSuites != null) {
            LOG.debug("Setting enabled cipherSuites: '{}'", cipherSuites);
            String[] cipherSuitesArray = cipherSuites.split(",");
            sslContextFactory.setIncludeCipherSuites(cipherSuitesArray);
        }

        return sslContextFactory;
    }

    /**
     * Creates and configures an SSL/TLS connector for the Jetty server.
     *
     * @param server
     *            The server instance.
     * @param acceptors
     *            The number of acceptor threads.
     * @param selectors
     *            The number of selector threads.
     * @param sslContextFactory
     *            The pre-configured SslContextFactory.
     *
     * @return A configured ServerConnector for HTTPS.
     */
    private ServerConnector createSslConnector(Server server, int acceptors, int selectors,
            SslContextFactory.Server sslContextFactory) {
        ServerConnector sslConnector = new ServerConnector(server, acceptors, selectors, sslContextFactory);
        sslConnector.setPort(this.httpsPort);
        sslConnector.setHost(this.host);
        return sslConnector;
    }

    @Override
    public void stop() {
        if (server != null) {
            try {
                LOG.info("Stopping Prometheus Jetty server.");
                server.stop();
            } catch (Exception err) {
                LOG.error("Cannot safely stop Prometheus Jetty server", err);
            } finally {
                server = null;
            }
        }
        registry.clear();
    }

    /**
     * Returns a Prometheus servlet for integration with existing web applications. This is primarily used for testing
     * purposes.
     */
    public PrometheusMetricsServlet getServlet() {
        return this.servlet;
    }

    @Override
    public MetricsContext getRootContext() {
        return rootContext;
    }

    @Override
    public void dump(BiConsumer<String, Object> sink) {
        dumper.dump().forEach(sink);
    }

    @Override
    public void resetAllValues() {
        // The new prometheus client does not support resetting metric values.
        LOG.debug("resetAllValues is a no-op for PrometheusMetricsProvider");
    }

    /**
     * Inner class implementing the MetricsContext interface. It handles the creation and registration of different
     * metric types.
     */
    private class Context implements MetricsContext {

        private final ConcurrentMap<String, PrometheusCounterWrapper> counters =
            new ConcurrentHashMap<>();
        private final ConcurrentMap<String, PrometheusLabelledCounterWrapper> counterSets =
            new ConcurrentHashMap<>();
        private final ConcurrentMap<String, GaugeWithCallback> registeredGauges =
            new ConcurrentHashMap<>();
        private final ConcurrentMap<String, PrometheusSummaryWrapper> basicSummaries =
            new ConcurrentHashMap<>();
        private final ConcurrentMap<String, PrometheusSummaryWrapper> advancedSummaries =
            new ConcurrentHashMap<>();
        private final ConcurrentMap<String, PrometheusLabelledSummaryWrapper> basicSummarySets =
            new ConcurrentHashMap<>();
        private final ConcurrentMap<String, PrometheusLabelledSummaryWrapper> advancedSummarySets =
            new ConcurrentHashMap<>();

        @Override
        public MetricsContext getContext(String name) {
            // This provider uses a flat namespace, so sub-contexts are not needed.
            return this;
        }

        @Override
        public Counter getCounter(String name) {
            return counters.computeIfAbsent(name, key -> {
                io.prometheus.metrics.core.metrics.Counter prometheusCounter =
                        io.prometheus.metrics.core.metrics.Counter
                        .builder().name(key).help(key + " counter").register(registry);
                return new PrometheusCounterWrapper(prometheusCounter);
            });
        }

        @Override
        public CounterSet getCounterSet(String name) {
            return counterSets.computeIfAbsent(name, key -> {
                Objects.requireNonNull(name, "Cannot register a CounterSet with null name");
                io.prometheus.metrics.core.metrics.Counter prometheusCounter =
                        io.prometheus.metrics.core.metrics.Counter
                        .builder().name(key).help(key + " counter set").labelNames(LABEL).register(registry);
                return new PrometheusLabelledCounterWrapper(prometheusCounter);
            });
        }

        @Override
        public void registerGaugeSet(final String name, final GaugeSet gaugeSet) {
            Objects.requireNonNull(name, "Cannot register a GaugeSet with null name");
            Objects.requireNonNull(gaugeSet, "Cannot register a null GaugeSet for " + name);

            GaugeWithCallback oldGauge = registeredGauges.get(name);
            if (oldGauge != null) {
                registry.unregister(oldGauge);
            }

            GaugeWithCallback newGauge = GaugeWithCallback.builder().name(name).help(name).labelNames(LABEL)
                    .callback(callback -> {
                        Map<String, Number> values = gaugeSet.values();
                        if (values != null) {
                            for (Map.Entry<String, Number> value : values.entrySet()) {
                                if (value.getKey() == null) {
                                    throw new IllegalArgumentException("GaugeSet key cannot be null.");
                                }
                                callback.call(value.getValue().doubleValue(), value.getKey());
                            }
                        }
                    }).register(registry);
            registeredGauges.put(name, newGauge);
        }

        @Override
        public void registerGauge(String name, Gauge gauge) {
            if (name == null) {
                throw new IllegalArgumentException("Gauge name cannot be null.");
            }
            if (gauge == null) {
                throw new IllegalArgumentException("Cannot register a null Gauge for " + name);
            }

            GaugeWithCallback oldGauge = registeredGauges.get(name);
            if (oldGauge != null) {
                registry.unregister(oldGauge);
            }

            GaugeWithCallback newGauge = GaugeWithCallback.builder().name(name).help(name).callback(callback -> {
                Number value = gauge.get();
                if (value != null) {
                    callback.call(value.doubleValue());
                }
            }).register(registry);
            registeredGauges.put(name, newGauge);
        }

        @Override
        public void unregisterGauge(String name) {
            GaugeWithCallback gauge = registeredGauges.remove(name);
            if (gauge != null) {
                registry.unregister(gauge);
            }
        }

        @Override
        public void unregisterGaugeSet(final String name) {
            Objects.requireNonNull(name, "Cannot unregister GaugeSet with null name");
            unregisterGauge(name);
        }

        private io.prometheus.metrics.core.metrics.Summary createPrometheusSummary(String name, DetailLevel detailLevel,
                String... labelNames) {
            io.prometheus.metrics.core.metrics.Summary.Builder builder = io.prometheus.metrics.core.metrics.Summary
                    .builder().name(name).help(name + " summary").quantile(0.5, 0.05); // Median

            if (detailLevel == DetailLevel.ADVANCED) {
                builder.quantile(0.95, 0.05)   // 95th percentile
                        .quantile(0.99, 0.05); // 99th percentile
            }

            if (labelNames.length > 0) {
                builder.labelNames(labelNames);
            }
            return builder.register(registry);
        }

        @Override
        public Summary getSummary(String name, DetailLevel detailLevel) {
            ConcurrentMap<String, PrometheusSummaryWrapper> map = detailLevel == DetailLevel.BASIC ? basicSummaries
                    : advancedSummaries;
            return map.computeIfAbsent(name, key -> {
                if ((detailLevel == DetailLevel.BASIC && advancedSummaries.containsKey(key))
                        || (detailLevel == DetailLevel.ADVANCED && basicSummaries.containsKey(key))) {
                    throw new IllegalArgumentException(
                            "Already registered a summary as " + key + " with a different detail level");
                }
                io.prometheus.metrics.core.metrics.Summary prometheusSummary = createPrometheusSummary(key,
                        detailLevel);
                return new PrometheusSummaryWrapper(prometheusSummary);
            });
        }

        @Override
        public SummarySet getSummarySet(String name, DetailLevel detailLevel) {
            ConcurrentMap<String, PrometheusLabelledSummaryWrapper> map = detailLevel == DetailLevel.BASIC
                    ? basicSummarySets : advancedSummarySets;
            return map.computeIfAbsent(name, key -> {
                if ((detailLevel == DetailLevel.BASIC && advancedSummarySets.containsKey(key))
                        || (detailLevel == DetailLevel.ADVANCED && basicSummarySets.containsKey(key))) {
                    throw new IllegalArgumentException(
                            "Already registered a summary set as " + key + " with a different detail level");
                }
                io.prometheus.metrics.core.metrics.Summary prometheusSummary = createPrometheusSummary(key, detailLevel,
                        LABEL);
                return new PrometheusLabelledSummaryWrapper(prometheusSummary);
            });
        }
    }

    // --- Wrapper classes to adapt Prometheus metrics to ZooKeeper's metric interfaces ---

    private static class PrometheusCounterWrapper implements Counter {
        private final io.prometheus.metrics.core.metrics.Counter prometheusCounter;

        public PrometheusCounterWrapper(io.prometheus.metrics.core.metrics.Counter prometheusCounter) {
            this.prometheusCounter = prometheusCounter;
        }

        @Override
        public void add(long delta) {
            try {
                this.prometheusCounter.inc(delta);
            } catch (final IllegalArgumentException e) {
                LOG.error("invalid delta {} for metric {}", delta, prometheusCounter.getPrometheusName(), e);
            }
        }

        @Override
        public long get() {
            return (long) this.prometheusCounter.get();
        }
    }

    private static class PrometheusLabelledCounterWrapper implements CounterSet {
        private final io.prometheus.metrics.core.metrics.Counter prometheusCounter;

        public PrometheusLabelledCounterWrapper(io.prometheus.metrics.core.metrics.Counter prometheusCounter) {
            this.prometheusCounter = prometheusCounter;
        }

        @Override
        public void add(String key, long delta) {
            try {
                this.prometheusCounter.labelValues(key).inc(delta);
            } catch (final IllegalArgumentException e) {
                LOG.error("invalid delta {} for metric {} with key {}", delta, prometheusCounter.getPrometheusName(),
                        key, e);
            }
        }

        @Override
        public void inc(String key) {
            add(key, 1);
        }
    }

    private static class PrometheusSummaryWrapper implements Summary {
        private final io.prometheus.metrics.core.metrics.Summary prometheusSummary;

        public PrometheusSummaryWrapper(io.prometheus.metrics.core.metrics.Summary prometheusSummary) {
            this.prometheusSummary = prometheusSummary;
        }

        @Override
        public void add(long value) {
            this.prometheusSummary.observe(value);
        }
    }

    private static class PrometheusLabelledSummaryWrapper implements SummarySet {
        private final io.prometheus.metrics.core.metrics.Summary prometheusSummary;

        public PrometheusLabelledSummaryWrapper(io.prometheus.metrics.core.metrics.Summary prometheusSummary) {
            this.prometheusSummary = prometheusSummary;
        }

        @Override
        public void add(String key, long value) {
            this.prometheusSummary.labelValues(key).observe(value);
        }
    }
}
