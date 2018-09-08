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
package org.apache.zookeeper.common;


import org.apache.zookeeper.common.X509Exception.KeyManagerException;
import org.apache.zookeeper.common.X509Exception.SSLContextException;
import org.apache.zookeeper.common.X509Exception.TrustManagerException;
import org.apache.zookeeper.util.PemReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.CertPathTrustManagerParameters;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.X509CertSelector;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Utility code for X509 handling
 *
 * Default cipher suites:
 *
 *   Performance testing done by Facebook engineers shows that on Intel x86_64 machines, Java9 performs better with
 *   GCM and Java8 performs better with CBC, so these seem like reasonable defaults.
 */
public abstract class X509Util {
    private static final Logger LOG = LoggerFactory.getLogger(X509Util.class);

    static final String DEFAULT_PROTOCOL = "TLSv1.2";
    private static final String[] DEFAULT_CIPHERS_JAVA8 = {
            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"
    };
    private static final String[] DEFAULT_CIPHERS_JAVA9 = {
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256"
    };

    public static final int DEFAULT_HANDSHAKE_DETECTION_TIMEOUT_MILLIS = 5000;

    /**
     * This enum represents the file type of a KeyStore or TrustStore. Currently, JKS (java keystore) and PEM types
     * are supported.
     */
    public enum StoreFileType {
        JKS(".jks"), PEM(".pem");

        private final String defaultFileExtension;

        StoreFileType(String defaultFileExtension) {
            this.defaultFileExtension = defaultFileExtension;
        }

        /**
         * The property string that specifies that a key store or trust store should use this store file type.
         */
        public String getPropertyValue() {
            return this.name();
        }

        /**
         * The file extension that is associated with this file type.
         */
        public String getDefaultFileExtension() {
            return defaultFileExtension;
        }

        /**
         * Converts a property value to a StoreFileType enum. If the property value is not set or is empty, returns
         * null.
         * @param prop the property value.
         * @return the StoreFileType.
         * @throws IllegalArgumentException if the property value is not "JKS", "PEM", or empty/null.
         */
        public static StoreFileType fromPropertyValue(String prop) {
            if (prop == null || prop.length() == 0) {
                return null;
            }
            return StoreFileType.valueOf(prop.toUpperCase());
        }
    }

    /**
     * Enum specifying the client auth requirement of server-side TLS sockets created by this X509Util.
     * <ul>
     *     <li>NONE - do not request a client certificate.</li>
     *     <li>WANT - request a client certificate, but allow anonymous clients to connect.</li>
     *     <li>NEED - require a client certificate, disconnect anonymous clients.</li>
     * </ul>
     *
     * If the config property is not set, the default value is NEED.
     */
    public enum ClientAuth {
        NONE,
        WANT,
        NEED;

        /**
         * Converts a property value to a ClientAuth enum. If the input string is empty or null, returns
         * <code>ClientAuth.NEED</code>.
         * @param prop the property string.
         * @return the ClientAuth.
         * @throws IllegalArgumentException if the property value is not "NONE", "WANT", "NEED", or empty/null.
         */
        public static ClientAuth fromPropertyValue(String prop) {
            if (prop == null || prop.length() == 0) {
                return NEED;
            }
            return ClientAuth.valueOf(prop.toUpperCase());
        }
    }

    /**
     * Wrapper class for an SSLContext + some config options that can't be set on the context when it is created but
     * must be set on a secure socket created by the context after the socket creation. By wrapping the options in this
     * class we avoid reading from global system properties during socket configuration. This makes testing easier
     * since we can create different X509Util instances with different configurations in a single test process, and
     * unit test interactions between them.
     */
    public class SSLContextAndOptions {
        private final String[] enabledProtocols;
        private final String[] cipherSuites;
        private final ClientAuth clientAuth;
        private final SSLContext sslContext;
        private final int handshakeDetectionTimeoutMillis;

        /**
         * Note: constructor is intentionally private, only the enclosing X509Util should be creating instances of this
         * class.
         * @param config
         * @param sslContext
         */
        private SSLContextAndOptions(final ZKConfig config,
                                     final SSLContext sslContext) {
            this.sslContext = sslContext;
            this.enabledProtocols = getEnabledProtocols(config, sslContext);
            this.cipherSuites = getCipherSuites(config);
            this.clientAuth = getClientAuth(config);
            this.handshakeDetectionTimeoutMillis = getHandshakeDetectionTimeoutMillis(config);
        }

        public SSLContext getSSLContext() {
            return sslContext;
        }

        public SSLSocket createSSLSocket() throws IOException {
            return configureSSLSocket((SSLSocket) sslContext.getSocketFactory().createSocket(), true);
        }

        public SSLSocket createSSLSocket(Socket socket, byte[] pushbackBytes) throws IOException {
            SSLSocket sslSocket;
            if (pushbackBytes != null && pushbackBytes.length > 0) {
                sslSocket = (SSLSocket) sslContext.getSocketFactory().createSocket(
                        socket, new ByteArrayInputStream(pushbackBytes), true);
            } else {
                sslSocket = (SSLSocket) sslContext.getSocketFactory().createSocket(
                        socket, null, socket.getPort(), true);
            }
            return configureSSLSocket(sslSocket, false);
        }

        public SSLServerSocket createSSLServerSocket() throws IOException {
            SSLServerSocket sslServerSocket =
                    (SSLServerSocket) sslContext.getServerSocketFactory().createServerSocket();
            return configureSSLServerSocket(sslServerSocket);
        }

        public SSLServerSocket createSSLServerSocket(int port) throws IOException {
            SSLServerSocket sslServerSocket =
                    (SSLServerSocket) sslContext.getServerSocketFactory().createServerSocket(port);
            return configureSSLServerSocket(sslServerSocket);
        }

        private SSLSocket configureSSLSocket(SSLSocket socket, boolean isClientSocket) {
            SSLParameters sslParameters = socket.getSSLParameters();
            configureSslParameters(sslParameters, isClientSocket);
            socket.setSSLParameters(sslParameters);
            socket.setUseClientMode(isClientSocket);
            return socket;
        }

        private SSLServerSocket configureSSLServerSocket(SSLServerSocket socket) {
            SSLParameters sslParameters = socket.getSSLParameters();
            configureSslParameters(sslParameters, false);
            socket.setSSLParameters(sslParameters);
            socket.setUseClientMode(false);
            return socket;
        }

        private void configureSslParameters(SSLParameters sslParameters, boolean isClientSocket) {
            if (cipherSuites != null) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Setup cipher suites for {} socket: {}",
                            isClientSocket ? "client" : "server",
                            Arrays.toString(cipherSuites));
                }
                sslParameters.setCipherSuites(cipherSuites);
            }
            if (enabledProtocols != null) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Setup enabled protocols for {} socket: {}",
                            isClientSocket ? "client" : "server",
                            Arrays.toString(enabledProtocols));
                }
                sslParameters.setProtocols(enabledProtocols);
            }
            if (!isClientSocket) {
                switch (clientAuth) {
                    case NEED:
                        sslParameters.setNeedClientAuth(true);
                        break;
                    case WANT:
                        sslParameters.setWantClientAuth(true);
                        break;
                    default:
                        sslParameters.setNeedClientAuth(false); // also clears the wantClientAuth flag according to docs
                        break;
                }
            }
        }

        private String[] getEnabledProtocols(final ZKConfig config, final SSLContext sslContext) {
            String enabledProtocolsInput = config.getProperty(getSslEnabledProtocolsProperty());
            if (enabledProtocolsInput == null) {
                return new String[] { sslContext.getProtocol() };
            }
            return enabledProtocolsInput.split(",");
        }

        private String[] getCipherSuites(final ZKConfig config) {
            String cipherSuitesInput = config.getProperty(getSslCipherSuitesProperty());
            if (cipherSuitesInput == null) {
                return getDefaultCipherSuites();
            } else {
                return cipherSuitesInput.split(",");
            }
        }

        private String[] getDefaultCipherSuites() {
            String javaVersion = System.getProperty("java.specification.version");
            if ("9".equals(javaVersion)) {
                LOG.debug("Using Java9-optimized cipher suites for Java version {}", javaVersion);
                return DEFAULT_CIPHERS_JAVA9;
            }
            LOG.debug("Using Java8-optimized cipher suites for Java version {}", javaVersion);
            return DEFAULT_CIPHERS_JAVA8;
        }

        private ClientAuth getClientAuth(final ZKConfig config) {
            return ClientAuth.fromPropertyValue(config.getProperty(getSslClientAuthProperty()));
        }

        private int getHandshakeDetectionTimeoutMillis(final ZKConfig config) {
            String propertyString = config.getProperty(getSslHandshakeDetectionTimeoutMillisProperty());
            int result;
            if (propertyString == null) {
                result = DEFAULT_HANDSHAKE_DETECTION_TIMEOUT_MILLIS;
            } else {
                result = Integer.parseInt(propertyString);
                if (result < 1) {
                    // Timeout of 0 is not allowed, since an infinite timeout can permanently lock up an
                    // accept() thread.
                    LOG.warn("Invalid value for " + getSslHandshakeDetectionTimeoutMillisProperty() + ": " + result +
                            ", using the default value of " + DEFAULT_HANDSHAKE_DETECTION_TIMEOUT_MILLIS);
                    result = DEFAULT_HANDSHAKE_DETECTION_TIMEOUT_MILLIS;
                }
            }
            return result;
        }
    }

    private String sslProtocolProperty = getConfigPrefix() + "protocol";
    private String sslEnabledProtocolsProperty = getConfigPrefix() + "enabledProtocols";
    private String cipherSuitesProperty = getConfigPrefix() + "ciphersuites";
    private String sslKeystoreLocationProperty = getConfigPrefix() + "keyStore.location";
    private String sslKeystorePasswdProperty = getConfigPrefix() + "keyStore.password";
    private String sslKeystoreTypeProperty = getConfigPrefix() + "keyStore.type";
    private String sslTruststoreLocationProperty = getConfigPrefix() + "trustStore.location";
    private String sslTruststorePasswdProperty = getConfigPrefix() + "trustStore.password";
    private String sslTruststoreTypeProperty = getConfigPrefix() + "trustStore.type";
    private String sslHostnameVerificationEnabledProperty = getConfigPrefix() + "hostnameVerification";
    private String sslCrlEnabledProperty = getConfigPrefix() + "crl";
    private String sslOcspEnabledProperty = getConfigPrefix() + "ocsp";
    private String sslClientAuthProperty = getConfigPrefix() + "clientAuth";
    private String sslHandshakeDetectionTimeoutMillisProperty = getConfigPrefix() + "handshakeDetectionTimeoutMillis";

    private ZKConfig zkConfig;
    private AtomicReference<SSLContextAndOptions> defaultSSLContextAndOptions = new AtomicReference<>(null);

    private FileChangeWatcher keyStoreFileWatcher;
    private FileChangeWatcher trustStoreFileWatcher;

    // Finalizer guardian object, see Effective Java item 7
    @SuppressWarnings("unused")
    private final Object finalizerGuardian = new Object() {
        @Override
        protected void finalize() {
            disableCertFileReloading();
        }
    };

    public X509Util() {
        this(null);
    }

    public X509Util(ZKConfig zkConfig) {
        this.zkConfig = zkConfig;
        keyStoreFileWatcher = trustStoreFileWatcher = null;
    }

    protected abstract String getConfigPrefix();

    protected abstract boolean shouldVerifyClientHostname();

    public String getSslProtocolProperty() {
        return sslProtocolProperty;
    }

    public String getSslEnabledProtocolsProperty() {
        return sslEnabledProtocolsProperty;
    }

    public String getCipherSuitesProperty() {
        return cipherSuitesProperty;
    }

    public String getSslKeystoreLocationProperty() {
        return sslKeystoreLocationProperty;
    }

    public String getSslCipherSuitesProperty() {
        return cipherSuitesProperty;
    }

    public String getSslKeystorePasswdProperty() {
        return sslKeystorePasswdProperty;
    }

    public String getSslKeystoreTypeProperty() {
        return sslKeystoreTypeProperty;
    }

    public String getSslTruststoreLocationProperty() {
        return sslTruststoreLocationProperty;
    }

    public String getSslTruststorePasswdProperty() {
        return sslTruststorePasswdProperty;
    }

    public String getSslTruststoreTypeProperty() {
        return sslTruststoreTypeProperty;
    }

    public String getSslHostnameVerificationEnabledProperty() {
        return sslHostnameVerificationEnabledProperty;
    }

    public String getSslCrlEnabledProperty() {
        return sslCrlEnabledProperty;
    }

    public String getSslOcspEnabledProperty() {
        return sslOcspEnabledProperty;
    }

    public String getSslClientAuthProperty() {
        return sslClientAuthProperty;
    }

    /**
     * Returns the config property key that controls the amount of time, in milliseconds, that the first
     * UnifiedServerSocket read operation will block for when trying to detect the client mode (TLS or PLAINTEXT).
     *
     * @return the config property key.
     */
    public String getSslHandshakeDetectionTimeoutMillisProperty() {
        return sslHandshakeDetectionTimeoutMillisProperty;
    }

    public SSLContext getDefaultSSLContext() throws X509Exception.SSLContextException {
        return getDefaultSSLContextAndOptions().getSSLContext();
    }

    public SSLContext createSSLContext(ZKConfig config) throws SSLContextException {
        return createSSLContextAndOptions(config).getSSLContext();
    }

    public SSLContextAndOptions getDefaultSSLContextAndOptions() throws X509Exception.SSLContextException {
        SSLContextAndOptions result = defaultSSLContextAndOptions.get();
        if (result == null) {
            result = createSSLContextAndOptions();
            if (!defaultSSLContextAndOptions.compareAndSet(null, result)) {
                // lost the race, another thread already set the value
                result = defaultSSLContextAndOptions.get();
            }
        }
        return result;
    }

    private void resetDefaultSSLContextAndOptions() throws X509Exception.SSLContextException {
        SSLContextAndOptions newContext = createSSLContextAndOptions();
        defaultSSLContextAndOptions.set(newContext);
    }

    private SSLContextAndOptions createSSLContextAndOptions() throws SSLContextException {
        /*
         * Since Configuration initializes the key store and trust store related
         * configuration from system property. Reading property from
         * configuration will be same reading from system property
         */
        return createSSLContextAndOptions(zkConfig == null ? new ZKConfig() : zkConfig);
    }

    /**
     * Returns the max amount of time, in milliseconds, that the first UnifiedServerSocket read() operation should
     * block for when trying to detect the client mode (TLS or PLAINTEXT).
     * Defaults to {@link X509Util#DEFAULT_HANDSHAKE_DETECTION_TIMEOUT_MILLIS}.
     *
     * @return the handshake detection timeout, in milliseconds.
     */
    public int getSslHandshakeTimeoutMillis() {
        try {
            SSLContextAndOptions ctx = getDefaultSSLContextAndOptions();
            return ctx.handshakeDetectionTimeoutMillis;
        } catch (SSLContextException e) {
            LOG.error("Error creating SSL context and options", e);
            return DEFAULT_HANDSHAKE_DETECTION_TIMEOUT_MILLIS;
        } catch (Exception e) {
            LOG.error("Error parsing config property " + getSslHandshakeDetectionTimeoutMillisProperty(), e);
            return DEFAULT_HANDSHAKE_DETECTION_TIMEOUT_MILLIS;
        }
    }

    public SSLContextAndOptions createSSLContextAndOptions(ZKConfig config) throws SSLContextException {
        KeyManager[] keyManagers = null;
        TrustManager[] trustManagers = null;

        String keyStoreLocationProp = config.getProperty(sslKeystoreLocationProperty, "");
        String keyStorePasswordProp = config.getProperty(sslKeystorePasswdProperty, "");
        String keyStoreTypeProp = config.getProperty(sslKeystoreTypeProperty);

        // There are legal states in some use cases for null KeyManager or TrustManager.
        // But if a user wanna specify one, location is required. Password defaults to empty string if it is not
        // specified by the user.

        if (keyStoreLocationProp.isEmpty()) {
            LOG.warn(getSslKeystoreLocationProperty() + " not specified");
        } else {
            try {
                StoreFileType keyStoreType = StoreFileType.fromPropertyValue(keyStoreTypeProp);
                keyManagers = new KeyManager[]{
                        createKeyManager(keyStoreLocationProp, keyStorePasswordProp, keyStoreType)};
            } catch (KeyManagerException keyManagerException) {
                throw new SSLContextException("Failed to create KeyManager", keyManagerException);
            } catch (IllegalArgumentException e) {
                throw new SSLContextException("Bad value for " + sslKeystoreTypeProperty + ": " + keyStoreTypeProp, e);
            }
        }

        String trustStoreLocationProp = config.getProperty(sslTruststoreLocationProperty, "");
        String trustStorePasswordProp = config.getProperty(sslTruststorePasswdProperty, "");
        String trustStoreTypeProp = config.getProperty(sslTruststoreTypeProperty);

        boolean sslCrlEnabled = config.getBoolean(this.sslCrlEnabledProperty);
        boolean sslOcspEnabled = config.getBoolean(this.sslOcspEnabledProperty);
        boolean sslServerHostnameVerificationEnabled = config.getBoolean(this.getSslHostnameVerificationEnabledProperty(), true);
        boolean sslClientHostnameVerificationEnabled = sslServerHostnameVerificationEnabled && shouldVerifyClientHostname();

        if (trustStoreLocationProp.isEmpty()) {
            LOG.warn(getSslTruststoreLocationProperty() + " not specified");
        } else {
            try {
                StoreFileType trustStoreType = StoreFileType.fromPropertyValue(trustStoreTypeProp);
                trustManagers = new TrustManager[]{
                        createTrustManager(trustStoreLocationProp, trustStorePasswordProp, trustStoreType, sslCrlEnabled, sslOcspEnabled,
                                sslServerHostnameVerificationEnabled, sslClientHostnameVerificationEnabled)};
            } catch (TrustManagerException trustManagerException) {
                throw new SSLContextException("Failed to create TrustManager", trustManagerException);
            } catch (IllegalArgumentException e) {
                throw new SSLContextException("Bad value for " + sslTruststoreTypeProperty + ": " + trustStoreTypeProp, e);
            }
        }

        String protocol = config.getProperty(sslProtocolProperty, DEFAULT_PROTOCOL);
        try {
            SSLContext sslContext = SSLContext.getInstance(protocol);
            sslContext.init(keyManagers, trustManagers, null);
            return new SSLContextAndOptions(config, sslContext);
        } catch (NoSuchAlgorithmException | KeyManagementException sslContextInitException) {
            throw new SSLContextException(sslContextInitException);
        }
    }

    /**
     * Creates a key manager by loading the key store from the given file of the given type, optionally decrypting it
     * using the given password.
     * @param keyStoreLocation the location of the key store file.
     * @param keyStorePassword optional password to decrypt the key store. If empty, assumes the key store is not
     *                         encrypted.
     * @param keyStoreType must be JKS, PEM, or null. If null, attempts to autodetect the key store type from the file
     *                     extension (.jks / .pem).
     * @return the key manager.
     * @throws KeyManagerException if something goes wrong.
     */
    public static X509KeyManager createKeyManager(String keyStoreLocation, String keyStorePassword, StoreFileType keyStoreType)
            throws KeyManagerException {
        FileInputStream inputStream = null;
        if (keyStorePassword == null) {
            keyStorePassword = "";
        }
        try {
            char[] keyStorePasswordChars = keyStorePassword.toCharArray();
            File keyStoreFile = new File(keyStoreLocation);
            if (keyStoreType == null) {
                keyStoreType = detectStoreFileTypeFromFileExtension(keyStoreFile);
            }
            KeyStore ks;
            switch (keyStoreType) {
                case JKS:
                    ks = KeyStore.getInstance("JKS");
                    inputStream = new FileInputStream(keyStoreFile);
                    ks.load(inputStream, keyStorePasswordChars);
                    break;
                case PEM:
                    Optional<String> passwordOption =
                            keyStorePassword.length() > 0 ? Optional.of(keyStorePassword) : Optional.empty();
                    ks = PemReader.loadKeyStore(keyStoreFile, keyStoreFile, passwordOption);
                    break;
                default:
                    throw new KeyManagerException("Invalid key store type: " + keyStoreType + ", must be one of: " +
                            Arrays.toString(StoreFileType.values()));
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("PKIX");
            kmf.init(ks, keyStorePasswordChars);

            for (KeyManager km : kmf.getKeyManagers()) {
                if (km instanceof X509KeyManager) {
                    return (X509KeyManager) km;
                }
            }
            throw new KeyManagerException("Couldn't find X509KeyManager");
        } catch (IOException | GeneralSecurityException keyManagerCreationException) {
            throw new KeyManagerException(keyManagerCreationException);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ioException) {
                    LOG.info("Failed to close key store input stream", ioException);
                }
            }
        }
    }

    /**
     * Creates a trust manager by loading the trust store from the given file of the given type, optionally decrypting
     * it using the given password.
     * @param trustStoreLocation the location of the trust store file.
     * @param trustStorePassword optional password to decrypt the trust store (only applies to JKS trust stores). If
     *                           empty, assumes the trust store is not encrypted.
     * @param trustStoreType must be JKS, PEM, or null. If null, attempts to autodetect the trust store type from the
     *                       file extension (.jks / .pem).
     * @param crlEnabled enable CRL (certificate revocation list) checks.
     * @param ocspEnabled enable OCSP (online certificate status protocol) checks.
     * @param serverHostnameVerificationEnabled if true, verify hostnames of remote servers that client sockets created
     *                                          by this X509Util connect to.
     * @param clientHostnameVerificationEnabled if true, verify hostnames of remote clients that server sockets created
     *                                          by this X509Util accept connections from.
     * @return the trust manager.
     * @throws TrustManagerException if something goes wrong.
     */
    public static X509TrustManager createTrustManager(String trustStoreLocation, String trustStorePassword,
                                                      StoreFileType trustStoreType,
                                                      boolean crlEnabled, boolean ocspEnabled,
                                                      final boolean serverHostnameVerificationEnabled,
                                                      final boolean clientHostnameVerificationEnabled)
            throws TrustManagerException {
        FileInputStream inputStream = null;
        if (trustStorePassword == null) {
            trustStorePassword = "";
        }
        try {
            File trustStoreFile = new File(trustStoreLocation);
            if (trustStoreType == null) {
                trustStoreType = detectStoreFileTypeFromFileExtension(trustStoreFile);
            }
            KeyStore ts;
            switch (trustStoreType) {
                case JKS:
                    ts = KeyStore.getInstance("JKS");
                    inputStream = new FileInputStream(trustStoreFile);
                    char[] trustStorePasswordChars = trustStorePassword.toCharArray();
                    ts.load(inputStream, trustStorePasswordChars);
                    break;
                case PEM:
                    ts = PemReader.loadTrustStore(trustStoreFile);
                    break;
                default:
                    throw new TrustManagerException("Invalid trust store type: " + trustStoreType + ", must be one of: " +
                            Arrays.toString(StoreFileType.values()));
            }

            PKIXBuilderParameters pbParams = new PKIXBuilderParameters(ts, new X509CertSelector());
            if (crlEnabled || ocspEnabled) {
                pbParams.setRevocationEnabled(true);
                System.setProperty("com.sun.net.ssl.checkRevocation", "true");
                System.setProperty("com.sun.security.enableCRLDP", "true");
                if (ocspEnabled) {
                    Security.setProperty("ocsp.enable", "true");
                }

            } else {
                pbParams.setRevocationEnabled(false);
            }

            // Revocation checking is only supported with the PKIX algorithm
            TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
            tmf.init(new CertPathTrustManagerParameters(pbParams));

            for (final TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509ExtendedTrustManager) {
                    return new ZKTrustManager((X509ExtendedTrustManager) tm,
                            serverHostnameVerificationEnabled, clientHostnameVerificationEnabled);
                }
            }
            throw new TrustManagerException("Couldn't find X509TrustManager");
        } catch (IOException | GeneralSecurityException trustManagerCreationException) {
            throw new TrustManagerException(trustManagerCreationException);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ioException) {
                    LOG.info("failed to close TrustStore input stream", ioException);
                }
            }
        }
    }

    public SSLSocket createSSLSocket() throws X509Exception, IOException {
        return getDefaultSSLContextAndOptions().createSSLSocket();
    }

    public SSLSocket createSSLSocket(Socket socket, byte[] pushbackBytes) throws X509Exception, IOException {
        return getDefaultSSLContextAndOptions().createSSLSocket(socket, pushbackBytes);
    }

    public SSLServerSocket createSSLServerSocket() throws X509Exception, IOException {
        return getDefaultSSLContextAndOptions().createSSLServerSocket();
    }

    public SSLServerSocket createSSLServerSocket(int port) throws X509Exception, IOException {
        return getDefaultSSLContextAndOptions().createSSLServerSocket(port);
    }

    /**
     * Enables automatic reloading of the trust store and key store files when they change on disk.
     *
     * @throws IOException if creating the FileChangeWatcher objects fails.
     */
    public void enableCertFileReloading() throws IOException {
        ZKConfig config = zkConfig == null ? new ZKConfig() : zkConfig;
        String keyStoreLocation = config.getProperty(sslKeystoreLocationProperty);
        if (keyStoreLocation != null && !keyStoreLocation.isEmpty()) {
            final Path filePath = Paths.get(keyStoreLocation).toAbsolutePath();
            keyStoreFileWatcher = new FileChangeWatcher(
                    filePath.getParent(),
                    new Consumer<WatchEvent<?>>() {
                        @Override
                        public void accept(WatchEvent<?> watchEvent) {
                            handleWatchEvent(filePath, watchEvent);
                        }
                    });
        }
        String trustStoreLocation = config.getProperty(sslTruststoreLocationProperty);
        if (trustStoreLocation != null && !trustStoreLocation.isEmpty()) {
            final Path filePath = Paths.get(trustStoreLocation).toAbsolutePath();
            trustStoreFileWatcher = new FileChangeWatcher(
                    filePath.getParent(),
                    new Consumer<WatchEvent<?>>() {
                        @Override
                        public void accept(WatchEvent<?> watchEvent) {
                            handleWatchEvent(filePath, watchEvent);
                        }
                    });
        }
    }

    /**
     * Disables automatic reloading of the trust store and key store files when they change on disk.
     * Stops background threads and closes WatchService instances.
     */
    public void disableCertFileReloading() {
        if (keyStoreFileWatcher != null) {
            keyStoreFileWatcher.stop();
            keyStoreFileWatcher = null;
        }
        if (trustStoreFileWatcher != null) {
            trustStoreFileWatcher.stop();
            trustStoreFileWatcher = null;
        }
    }

    /**
     * Handler for watch events that let us know a file we may care about has changed on disk.
     *
     * @param filePath the path to the file we are watching for changes.
     * @param event    the WatchEvent.
     */
    private void handleWatchEvent(Path filePath, WatchEvent<?> event) {
        boolean shouldResetContext = false;
        Path dirPath = filePath.getParent();
        if (event.kind().equals(StandardWatchEventKinds.OVERFLOW)) {
            // If we get notified about possibly missed events, reload the key store / trust store just to be sure.
            shouldResetContext = true;
        } else if (event.kind().equals(StandardWatchEventKinds.ENTRY_MODIFY) ||
                event.kind().equals(StandardWatchEventKinds.ENTRY_CREATE)) {
            Path eventFilePath = dirPath.resolve((Path) event.context());
            if (filePath.equals(eventFilePath)) {
                shouldResetContext = true;
            }
        }
        // Note: we don't care about delete events
        if (shouldResetContext) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Attempting to reset default SSL context after receiving watch event: " +
                        event.kind() + " with context: " + event.context());
            }
            try {
                this.resetDefaultSSLContextAndOptions();
            } catch (SSLContextException e) {
                throw new RuntimeException(e);
            }
        } else {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Ignoring watch event and keeping previous default SSL context. Event kind: " +
                        event.kind() + " with context: " + event.context());
            }
        }
    }

    /**
     * Detects the type of KeyStore / TrustStore file from the file extension. If the file name ends with
     * ".jks", returns <code>StoreFileType.JKS</code>. If the file name ends with ".pem", returns
     * <code>StoreFileType.PEM</code>. Otherwise, throws an IOException.
     * @param filename the filename of the key store or trust store file.
     * @return a StoreFileType.
     * @throws IOException if the filename does not end with ".jks" or ".pem".
     */
    public static StoreFileType detectStoreFileTypeFromFileExtension(File filename) throws IOException {
        String name = filename.getName();
        int i = name.lastIndexOf('.');
        if (i >= 0) {
            String extension = name.substring(i);
            for (StoreFileType storeFileType : StoreFileType.values()) {
                if (storeFileType.getDefaultFileExtension().equals(extension)) {
                    return storeFileType;
                }
            }
        }
        throw new IOException("Unable to auto-detect store file type from file name: " + filename);
    }
}
