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

    private String[] cipherSuites;

    private AtomicReference<SSLContext> defaultSSLContext = new AtomicReference<>(null);

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
        String cipherSuitesInput = System.getProperty(cipherSuitesProperty);
        if (cipherSuitesInput == null) {
            cipherSuites = getDefaultCipherSuites();
        } else {
            cipherSuites = cipherSuitesInput.split(",");
        }
        keyStoreFileWatcher = trustStoreFileWatcher = null;
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
        ZKConfig config = new ZKConfig();
        return createSSLContext(config);
    }

    public SSLContext createSSLContext(ZKConfig config) throws SSLContextException {
        KeyManager[] keyManagers = null;
        TrustManager[] trustManagers = null;

        String keyStoreLocationProp = config.getProperty(sslKeystoreLocationProperty);
        String keyStorePasswordProp = config.getProperty(sslKeystorePasswdProperty);
        String keyStoreTypeProp = config.getProperty(sslKeystoreTypeProperty);

        // There are legal states in some use cases for null KeyManager or TrustManager.
        // But if a user wanna specify one, location and password are required.

        if (keyStoreLocationProp == null && keyStorePasswordProp == null) {
            LOG.warn(getSslKeystoreLocationProperty() + " not specified");
        } else {
            if (keyStoreLocationProp == null) {
                throw new SSLContextException(getSslKeystoreLocationProperty() + " not specified");
            }
            if (keyStorePasswordProp == null) {
                throw new SSLContextException(getSslKeystorePasswdProperty() + " not specified");
            }
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

        String trustStoreLocationProp = config.getProperty(sslTruststoreLocationProperty);
        String trustStorePasswordProp = config.getProperty(sslTruststorePasswdProperty);
        String trustStoreTypeProp = config.getProperty(sslTruststoreTypeProperty);

        boolean sslCrlEnabled = config.getBoolean(this.sslCrlEnabledProperty);
        boolean sslOcspEnabled = config.getBoolean(this.sslOcspEnabledProperty);
        boolean sslServerHostnameVerificationEnabled = config.getBoolean(this.getSslHostnameVerificationEnabledProperty(), true);
        boolean sslClientHostnameVerificationEnabled = sslServerHostnameVerificationEnabled && shouldVerifyClientHostname();

        if (trustStoreLocationProp == null) {
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
            return sslContext;
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
                    if (trustStorePassword != null) {
                        char[] trustStorePasswordChars = trustStorePassword.toCharArray();
                        ts.load(inputStream, trustStorePasswordChars);
                    } else {
                        ts.load(inputStream, null);
                    }
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
        SSLSocket sslSocket = (SSLSocket) getDefaultSSLContext().getSocketFactory().createSocket();
        configureSSLSocket(sslSocket);

        return sslSocket;
    }

    public SSLSocket createSSLSocket(Socket socket, byte[] pushbackBytes) throws X509Exception, IOException {
        SSLSocket sslSocket = null;
        if (pushbackBytes != null && pushbackBytes.length > 0) {
            sslSocket = (SSLSocket) getDefaultSSLContext().getSocketFactory().createSocket(
                    socket, new ByteArrayInputStream(pushbackBytes), true);
        } else {
            sslSocket = (SSLSocket) getDefaultSSLContext().getSocketFactory().createSocket(
                    socket, null, socket.getPort(), true);
        }
        configureSSLSocket(sslSocket);

        return sslSocket;
    }

    private void configureSSLSocket(SSLSocket sslSocket) {
        if (cipherSuites != null) {
            SSLParameters sslParameters = sslSocket.getSSLParameters();
            LOG.debug("Setup cipher suites for client socket: {}", Arrays.toString(cipherSuites));
            sslParameters.setCipherSuites(cipherSuites);
            sslSocket.setSSLParameters(sslParameters);
        }
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
        if (cipherSuites != null) {
            LOG.debug("Setup cipher suites for server socket: {}", Arrays.toString(cipherSuites));
            sslParameters.setCipherSuites(cipherSuites);
        }

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

    /**
     * Enables automatic reloading of the trust store and key store files when they change on disk.
     *
     * @throws IOException if creating the FileChangeWatcher objects fails.
     */
    public void enableCertFileReloading() throws IOException {
        ZKConfig config = new ZKConfig();
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
