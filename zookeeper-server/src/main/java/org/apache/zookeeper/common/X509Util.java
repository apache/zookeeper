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


import java.io.ByteArrayInputStream;
import java.io.Closeable;
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
import java.util.concurrent.atomic.AtomicReference;

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

import org.apache.zookeeper.common.X509Exception.KeyManagerException;
import org.apache.zookeeper.common.X509Exception.SSLContextException;
import org.apache.zookeeper.common.X509Exception.TrustManagerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility code for X509 handling
 *
 * Default cipher suites:
 *
 *   Performance testing done by Facebook engineers shows that on Intel x86_64 machines, Java9 performs better with
 *   GCM and Java8 performs better with CBC, so these seem like reasonable defaults.
 */
public abstract class X509Util implements Closeable, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(X509Util.class);

    private static final String REJECT_CLIENT_RENEGOTIATION_PROPERTY =
            "jdk.tls.rejectClientInitiatedRenegotiation";
    static {
        // Client-initiated renegotiation in TLS is unsafe and
        // allows MITM attacks, so we should disable it unless
        // it was explicitly enabled by the user.
        // A brief summary of the issue can be found at
        // https://www.ietf.org/proceedings/76/slides/tls-7.pdf
        if (System.getProperty(REJECT_CLIENT_RENEGOTIATION_PROPERTY) == null) {
            LOG.info("Setting -D {}=true to disable client-initiated TLS renegotiation",
                    REJECT_CLIENT_RENEGOTIATION_PROPERTY);
            System.setProperty(REJECT_CLIENT_RENEGOTIATION_PROPERTY, Boolean.TRUE.toString());
        }
    }

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

    private String sslProtocolProperty = getConfigPrefix() + "protocol";
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
    private String sslHandshakeDetectionTimeoutMillisProperty = getConfigPrefix() + "handshakeDetectionTimeoutMillis";

    private String[] cipherSuites;

    private AtomicReference<SSLContext> defaultSSLContext = new AtomicReference<>(null);
    private FileChangeWatcher keyStoreFileWatcher;
    private FileChangeWatcher trustStoreFileWatcher;

    public X509Util() {
        String cipherSuitesInput = System.getProperty(cipherSuitesProperty);
        if (cipherSuitesInput == null) {
            cipherSuites = getDefaultCipherSuites();
        } else {
            cipherSuites = cipherSuitesInput.split(",");
        }
    }

    protected abstract String getConfigPrefix();
    protected abstract boolean shouldVerifyClientHostname();

    public String getSslProtocolProperty() {
        return sslProtocolProperty;
    }

    public String getCipherSuitesProperty() {
        return cipherSuitesProperty;
    }

    public String getSslKeystoreLocationProperty() {
        return sslKeystoreLocationProperty;
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
        SSLContext result = defaultSSLContext.get();
        if (result == null) {
            result = createSSLContext();
            if (!defaultSSLContext.compareAndSet(null, result)) {
                // lost the race, another thread already set the value
                result = defaultSSLContext.get();
            }
        }
        return result;
    }

    private void resetDefaultSSLContext() throws X509Exception.SSLContextException {
        SSLContext newContext = createSSLContext();
        defaultSSLContext.set(newContext);
    }

    private SSLContext createSSLContext() throws SSLContextException {
        /*
         * Since Configuration initializes the key store and trust store related
         * configuration from system property. Reading property from
         * configuration will be same reading from system property
         */
        ZKConfig config=new ZKConfig();
        return createSSLContext(config);
    }

    /**
     * Returns the max amount of time, in milliseconds, that the first UnifiedServerSocket read() operation should
     * block for when trying to detect the client mode (TLS or PLAINTEXT).
     * Defaults to {@link X509Util#DEFAULT_HANDSHAKE_DETECTION_TIMEOUT_MILLIS}.
     *
     * @return the handshake detection timeout, in milliseconds.
     */
    public int getSslHandshakeTimeoutMillis() {
        String propertyString = System.getProperty(getSslHandshakeDetectionTimeoutMillisProperty());
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

    public SSLContext createSSLContext(ZKConfig config) throws SSLContextException {
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
                keyManagers = new KeyManager[]{
                        createKeyManager(keyStoreLocationProp, keyStorePasswordProp, keyStoreTypeProp)};
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
        boolean sslServerHostnameVerificationEnabled =
                config.getBoolean(this.getSslHostnameVerificationEnabledProperty(), true);
        boolean sslClientHostnameVerificationEnabled =
                sslServerHostnameVerificationEnabled && shouldVerifyClientHostname();

        if (trustStoreLocationProp.isEmpty()) {
            LOG.warn(getSslTruststoreLocationProperty() + " not specified");
        } else {
            try {
                trustManagers = new TrustManager[]{
                        createTrustManager(trustStoreLocationProp, trustStorePasswordProp, trustStoreTypeProp, sslCrlEnabled, sslOcspEnabled,
                                sslServerHostnameVerificationEnabled, sslClientHostnameVerificationEnabled)};
            } catch (TrustManagerException trustManagerException) {
                throw new SSLContextException("Failed to create TrustManager", trustManagerException);
            } catch (IllegalArgumentException e) {
                throw new SSLContextException("Bad value for " + sslTruststoreTypeProperty + ": " + trustStoreTypeProp, e);
            }
        }

        String protocol = System.getProperty(sslProtocolProperty, DEFAULT_PROTOCOL);
        try {
            SSLContext sslContext = SSLContext.getInstance(protocol);
            sslContext.init(keyManagers, trustManagers, null);
            return sslContext;
        } catch (NoSuchAlgorithmException|KeyManagementException sslContextInitException) {
            throw new SSLContextException(sslContextInitException);
        }
    }

    /**
     * Creates a key manager by loading the key store from the given file of
     * the given type, optionally decrypting it using the given password.
     * @param keyStoreLocation the location of the key store file.
     * @param keyStorePassword optional password to decrypt the key store. If
     *                         empty, assumes the key store is not encrypted.
     * @param keyStoreTypeProp must be JKS, PEM, or null. If null, attempts to
     *                         autodetect the key store type from the file
     *                         extension (.jks / .pem).
     * @return the key manager.
     * @throws KeyManagerException if something goes wrong.
     */
    public static X509KeyManager createKeyManager(
            String keyStoreLocation,
            String keyStorePassword,
            String keyStoreTypeProp)
            throws KeyManagerException {
        if (keyStorePassword == null) {
            keyStorePassword = "";
        }
        try {
            KeyStoreFileType storeFileType =
                    KeyStoreFileType.fromPropertyValueOrFileName(
                            keyStoreTypeProp, keyStoreLocation);
            KeyStore ks = FileKeyStoreLoaderBuilderProvider
                    .getBuilderForKeyStoreFileType(storeFileType)
                    .setKeyStorePath(keyStoreLocation)
                    .setKeyStorePassword(keyStorePassword)
                    .build()
                    .loadKeyStore();
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("PKIX");
            kmf.init(ks, keyStorePassword.toCharArray());

            for (KeyManager km : kmf.getKeyManagers()) {
                if (km instanceof X509KeyManager) {
                    return (X509KeyManager) km;
                }
            }
            throw new KeyManagerException("Couldn't find X509KeyManager");
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            throw new KeyManagerException(e);
        }
    }

    /**
     * Creates a trust manager by loading the trust store from the given file
     * of the given type, optionally decrypting it using the given password.
     * @param trustStoreLocation the location of the trust store file.
     * @param trustStorePassword optional password to decrypt the trust store
     *                           (only applies to JKS trust stores). If empty,
     *                           assumes the trust store is not encrypted.
     * @param trustStoreTypeProp must be JKS, PEM, or null. If null, attempts
     *                           to autodetect the trust store type from the
     *                           file extension (.jks / .pem).
     * @param crlEnabled enable CRL (certificate revocation list) checks.
     * @param ocspEnabled enable OCSP (online certificate status protocol)
     *                    checks.
     * @param serverHostnameVerificationEnabled if true, verify hostnames of
     *                                          remote servers that client
     *                                          sockets created by this
     *                                          X509Util connect to.
     * @param clientHostnameVerificationEnabled if true, verify hostnames of
     *                                          remote clients that server
     *                                          sockets created by this
     *                                          X509Util accept connections
     *                                          from.
     * @return the trust manager.
     * @throws TrustManagerException if something goes wrong.
     */
    public static X509TrustManager createTrustManager(
            String trustStoreLocation,
            String trustStorePassword,
            String trustStoreTypeProp,
            boolean crlEnabled,
            boolean ocspEnabled,
            final boolean serverHostnameVerificationEnabled,
            final boolean clientHostnameVerificationEnabled)
            throws TrustManagerException {
        if (trustStorePassword == null) {
            trustStorePassword = "";
        }
        try {
            KeyStoreFileType storeFileType =
                    KeyStoreFileType.fromPropertyValueOrFileName(
                            trustStoreTypeProp, trustStoreLocation);
            KeyStore ts = FileKeyStoreLoaderBuilderProvider
                    .getBuilderForKeyStoreFileType(storeFileType)
                    .setTrustStorePath(trustStoreLocation)
                    .setTrustStorePassword(trustStorePassword)
                    .build()
                    .loadTrustStore();
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
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            throw new TrustManagerException(e);
        }
    }

    public SSLSocket createSSLSocket() throws X509Exception, IOException {
        SSLSocket sslSocket = (SSLSocket) getDefaultSSLContext().getSocketFactory().createSocket();
        configureSSLSocket(sslSocket);
        sslSocket.setUseClientMode(true);
        return sslSocket;
    }

    public SSLSocket createSSLSocket(Socket socket, byte[] pushbackBytes) throws X509Exception, IOException {
        SSLSocket sslSocket;
        if (pushbackBytes != null && pushbackBytes.length > 0) {
            sslSocket = (SSLSocket) getDefaultSSLContext().getSocketFactory().createSocket(
                    socket, new ByteArrayInputStream(pushbackBytes), true);
        } else {
            sslSocket = (SSLSocket) getDefaultSSLContext().getSocketFactory().createSocket(
                    socket, null, socket.getPort(), true);
        }
        configureSSLSocket(sslSocket);
        sslSocket.setUseClientMode(false);
        sslSocket.setNeedClientAuth(true);
        return sslSocket;
    }

    private void configureSSLSocket(SSLSocket sslSocket) {
        SSLParameters sslParameters = sslSocket.getSSLParameters();
        LOG.debug("Setup cipher suites for client socket: {}", Arrays.toString(cipherSuites));
        sslParameters.setCipherSuites(cipherSuites);
        sslSocket.setSSLParameters(sslParameters);
    }

    public SSLServerSocket createSSLServerSocket() throws X509Exception, IOException {
        SSLServerSocket sslServerSocket = (SSLServerSocket) getDefaultSSLContext().getServerSocketFactory().createServerSocket();
        configureSSLServerSocket(sslServerSocket);

        return sslServerSocket;
    }

    public SSLServerSocket createSSLServerSocket(int port) throws X509Exception, IOException {
        SSLServerSocket sslServerSocket = (SSLServerSocket) getDefaultSSLContext().getServerSocketFactory().createServerSocket(port);
        configureSSLServerSocket(sslServerSocket);

        return sslServerSocket;
    }

    private void configureSSLServerSocket(SSLServerSocket sslServerSocket) {
        SSLParameters sslParameters = sslServerSocket.getSSLParameters();
        sslParameters.setNeedClientAuth(true);
        LOG.debug("Setup cipher suites for server socket: {}", Arrays.toString(cipherSuites));
        sslParameters.setCipherSuites(cipherSuites);
        sslServerSocket.setSSLParameters(sslParameters);
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

    private FileChangeWatcher newFileChangeWatcher(String fileLocation) throws IOException {
        if (fileLocation == null || fileLocation.isEmpty()) {
            return null;
        }
        final Path filePath = Paths.get(fileLocation).toAbsolutePath();
        Path parentPath = filePath.getParent();
        if (parentPath == null) {
            throw new IOException(
                    "Key/trust store path does not have a parent: " + filePath);
        }
        return new FileChangeWatcher(
                parentPath,
                watchEvent -> {
                    handleWatchEvent(filePath, watchEvent);
                });
    }

    /**
     * Enables automatic reloading of the trust store and key store files when they change on disk.
     *
     * @throws IOException if creating the FileChangeWatcher objects fails.
     */
    public void enableCertFileReloading() throws IOException {
        LOG.info("enabling cert file reloading");
        ZKConfig config = new ZKConfig();
        FileChangeWatcher newKeyStoreFileWatcher =
                newFileChangeWatcher(config.getProperty(sslKeystoreLocationProperty));
        if (newKeyStoreFileWatcher != null) {
            // stop old watcher if there is one
            if (keyStoreFileWatcher != null) {
                keyStoreFileWatcher.stop();
            }
            keyStoreFileWatcher = newKeyStoreFileWatcher;
            keyStoreFileWatcher.start();
        }
        FileChangeWatcher newTrustStoreFileWatcher =
                newFileChangeWatcher(config.getProperty(sslTruststoreLocationProperty));
        if (newTrustStoreFileWatcher != null) {
            // stop old watcher if there is one
            if (trustStoreFileWatcher != null) {
                trustStoreFileWatcher.stop();
            }
            trustStoreFileWatcher = newTrustStoreFileWatcher;
            trustStoreFileWatcher.start();
        }
    }

    /**
     * Disables automatic reloading of the trust store and key store files when they change on disk.
     * Stops background threads and closes WatchService instances.
     */
    @Override
    public void close() {
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
                this.resetDefaultSSLContext();
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
}
