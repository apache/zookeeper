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

package org.apache.zookeeper.common;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
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
import java.security.cert.CertPathValidator;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXRevocationChecker;
import java.security.cert.X509CertSelector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.net.ssl.CertPathTrustManagerParameters;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
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
import org.apache.zookeeper.server.NettyServerCnxnFactory;
import org.apache.zookeeper.server.auth.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility code for X509 handling
 */
public abstract class X509Util implements Closeable, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(X509Util.class);

    private static final String REJECT_CLIENT_RENEGOTIATION_PROPERTY = "jdk.tls.rejectClientInitiatedRenegotiation";
    public static final String FIPS_MODE_PROPERTY = "zookeeper.fips-mode";
    private static final boolean FIPS_MODE_DEFAULT = true;
    public static final String TLS_1_1 = "TLSv1.1";
    public static final String TLS_1_2 = "TLSv1.2";
    public static final String TLS_1_3 = "TLSv1.3";

    static {
        // Client-initiated renegotiation in TLS is unsafe and
        // allows MITM attacks, so we should disable it unless
        // it was explicitly enabled by the user.
        // A brief summary of the issue can be found at
        // https://www.ietf.org/proceedings/76/slides/tls-7.pdf
        if (System.getProperty(REJECT_CLIENT_RENEGOTIATION_PROPERTY) == null) {
            LOG.info("Setting -D {}=true to disable client-initiated TLS renegotiation", REJECT_CLIENT_RENEGOTIATION_PROPERTY);
            System.setProperty(REJECT_CLIENT_RENEGOTIATION_PROPERTY, Boolean.TRUE.toString());
        }
    }

    public static final String DEFAULT_PROTOCOL = defaultTlsProtocol();

    /**
     * Return TLSv1.3 or TLSv1.2 depending on Java runtime version being used.
     * TLSv1.3 was first introduced in JDK11 and back-ported to OpenJDK 8u272.
     */
    private static String defaultTlsProtocol() {
        String defaultProtocol = TLS_1_2;
        List<String> supported = new ArrayList<>();
        try {
            supported = Arrays.asList(SSLContext.getDefault().getSupportedSSLParameters().getProtocols());
            // We cannot use the default protocols directly, because the SSLContext factory methods
            // only accept a single protocol
            if (supported.contains(TLS_1_3)) {
                defaultProtocol = TLS_1_3;
            }
        } catch (NoSuchAlgorithmException e) {
            // Ignore.
        }
        LOG.info("Default TLS protocol is {}, supported TLS protocols are {}", defaultProtocol, supported);
        return defaultProtocol;
    }

    public static final int DEFAULT_HANDSHAKE_DETECTION_TIMEOUT_MILLIS = 5000;

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
        NONE(io.netty.handler.ssl.ClientAuth.NONE),
        WANT(io.netty.handler.ssl.ClientAuth.OPTIONAL),
        NEED(io.netty.handler.ssl.ClientAuth.REQUIRE);

        private final io.netty.handler.ssl.ClientAuth nettyAuth;

        ClientAuth(io.netty.handler.ssl.ClientAuth nettyAuth) {
            this.nettyAuth = nettyAuth;
        }

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

        public io.netty.handler.ssl.ClientAuth toNettyClientAuth() {
            return nettyAuth;
        }
    }

    private final String sslProtocolProperty = getConfigPrefix() + "protocol";
    private final String sslEnabledProtocolsProperty = getConfigPrefix() + "enabledProtocols";
    private final String cipherSuitesProperty = getConfigPrefix() + "ciphersuites";
    private final String sslKeystoreLocationProperty = getConfigPrefix() + "keyStore.location";
    private final String sslKeystorePasswdProperty = getConfigPrefix() + "keyStore.password";
    private final String sslKeystorePasswdPathProperty = getConfigPrefix() + "keyStore.passwordPath";
    private final String sslKeystoreTypeProperty = getConfigPrefix() + "keyStore.type";
    private final String sslTruststoreLocationProperty = getConfigPrefix() + "trustStore.location";
    private final String sslTruststorePasswdProperty = getConfigPrefix() + "trustStore.password";
    private final String sslTruststorePasswdPathProperty = getConfigPrefix() + "trustStore.passwordPath";
    private final String sslTruststoreTypeProperty = getConfigPrefix() + "trustStore.type";
    private final String sslContextSupplierClassProperty = getConfigPrefix() + "context.supplier.class";
    private final String sslHostnameVerificationEnabledProperty = getConfigPrefix() + "hostnameVerification";
    private final String sslClientHostnameVerificationEnabledProperty = getConfigPrefix() + "clientHostnameVerification";
    private final String sslCrlEnabledProperty = getConfigPrefix() + "crl";
    private final String sslOcspEnabledProperty = getConfigPrefix() + "ocsp";
    private final String sslClientAuthProperty = getConfigPrefix() + "clientAuth";
    private final String sslHandshakeDetectionTimeoutMillisProperty = getConfigPrefix() + "handshakeDetectionTimeoutMillis";

    private final AtomicReference<SSLContextAndOptions> defaultSSLContextAndOptions = new AtomicReference<>(null);

    private FileChangeWatcher keyStoreFileWatcher;
    private FileChangeWatcher trustStoreFileWatcher;

    public X509Util() {
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

    public String getSslKeystorePasswdPathProperty() {
        return sslKeystorePasswdPathProperty;
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

    public String getSslTruststorePasswdPathProperty() {
        return sslTruststorePasswdPathProperty;
    }

    public String getSslTruststoreTypeProperty() {
        return sslTruststoreTypeProperty;
    }

    public String getSslContextSupplierClassProperty() {
        return sslContextSupplierClassProperty;
    }

    public String getSslHostnameVerificationEnabledProperty() {
        return sslHostnameVerificationEnabledProperty;
    }

    public String getSslClientHostnameVerificationEnabledProperty() {
        return sslClientHostnameVerificationEnabledProperty;
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

    public String getFipsModeProperty() {
        return FIPS_MODE_PROPERTY;
    }

    public static boolean getFipsMode(ZKConfig config) {
        return config.getBoolean(FIPS_MODE_PROPERTY, FIPS_MODE_DEFAULT);
    }

    public boolean isServerHostnameVerificationEnabled(ZKConfig config) {
        return config.getBoolean(this.getSslHostnameVerificationEnabledProperty(), true);
    }

    public boolean isClientHostnameVerificationEnabled(ZKConfig config) {
        return isServerHostnameVerificationEnabled(config)
            && config.getBoolean(this.getSslClientHostnameVerificationEnabledProperty(), shouldVerifyClientHostname());
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

        if (Boolean.getBoolean(NettyServerCnxnFactory.CLIENT_CERT_RELOAD_KEY)) {
            ProviderRegistry.addOrUpdateProvider(ProviderRegistry.AUTHPROVIDER_PROPERTY_PREFIX + "x509");
        }
    }

    private SSLContextAndOptions createSSLContextAndOptions() throws SSLContextException {
        /*
         * Since Configuration initializes the key store and trust store related
         * configuration from system property. Reading property from
         * configuration will be same reading from system property
         */
        return createSSLContextAndOptions(new ZKConfig());
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
            return ctx.getHandshakeDetectionTimeoutMillis();
        } catch (SSLContextException e) {
            LOG.error("Error creating SSL context and options", e);
            return DEFAULT_HANDSHAKE_DETECTION_TIMEOUT_MILLIS;
        } catch (Exception e) {
            LOG.error("Error parsing config property {}", getSslHandshakeDetectionTimeoutMillisProperty(), e);
            return DEFAULT_HANDSHAKE_DETECTION_TIMEOUT_MILLIS;
        }
    }

    @SuppressWarnings("unchecked")
    public SSLContextAndOptions createSSLContextAndOptions(ZKConfig config) throws SSLContextException {
        final String supplierContextClassName = config.getProperty(sslContextSupplierClassProperty);
        if (supplierContextClassName != null) {
            LOG.debug("Loading SSLContext supplier from property '{}'", sslContextSupplierClassProperty);

            try {
                Class<?> sslContextClass = Class.forName(supplierContextClassName);
                Supplier<SSLContext> sslContextSupplier = (Supplier<SSLContext>) sslContextClass.getConstructor().newInstance();
                return new SSLContextAndOptions(this, config, sslContextSupplier.get());
            } catch (ClassNotFoundException
                | ClassCastException
                | NoSuchMethodException
                | InvocationTargetException
                | InstantiationException
                | IllegalAccessException e) {
                throw new SSLContextException("Could not retrieve the SSLContext from supplier source '"
                                              + supplierContextClassName
                                              + "' provided in the property '"
                                              + sslContextSupplierClassProperty
                                              + "'", e);
            }
        } else {
            return createSSLContextAndOptionsFromConfig(config);
        }
    }

    public SSLContextAndOptions createSSLContextAndOptionsFromConfig(ZKConfig config) throws SSLContextException {
        KeyManager[] keyManagers = null;
        TrustManager[] trustManagers = null;

        String keyStoreLocationProp = config.getProperty(sslKeystoreLocationProperty, "");
        String keyStorePasswordProp = getPasswordFromConfigPropertyOrFile(config, sslKeystorePasswdProperty, sslKeystorePasswdPathProperty);
        String keyStoreTypeProp = config.getProperty(sslKeystoreTypeProperty);

        // There are legal states in some use cases for null KeyManager or TrustManager.
        // But if a user wanna specify one, location is required. Password defaults to empty string if it is not
        // specified by the user.

        if (keyStoreLocationProp.isEmpty()) {
            LOG.warn("{} not specified", getSslKeystoreLocationProperty());
        } else {
            try {
                keyManagers = new KeyManager[]{createKeyManager(keyStoreLocationProp, keyStorePasswordProp, keyStoreTypeProp)};
            } catch (KeyManagerException keyManagerException) {
                throw new SSLContextException("Failed to create KeyManager", keyManagerException);
            } catch (IllegalArgumentException e) {
                throw new SSLContextException("Bad value for " + sslKeystoreTypeProperty + ": " + keyStoreTypeProp, e);
            }
        }

        String trustStoreLocationProp = config.getProperty(sslTruststoreLocationProperty, "");
        String trustStorePasswordProp = getPasswordFromConfigPropertyOrFile(config, sslTruststorePasswdProperty, sslTruststorePasswdPathProperty);
        String trustStoreTypeProp = config.getProperty(sslTruststoreTypeProperty);

        boolean sslCrlEnabled = config.getBoolean(this.sslCrlEnabledProperty, Boolean.getBoolean("com.sun.net.ssl.checkRevocation"));
        boolean sslOcspEnabled = config.getBoolean(this.sslOcspEnabledProperty, Boolean.parseBoolean(Security.getProperty("ocsp.enable")));

        boolean sslServerHostnameVerificationEnabled = isServerHostnameVerificationEnabled(config);
        boolean sslClientHostnameVerificationEnabled = isClientHostnameVerificationEnabled(config);
        boolean fipsMode = getFipsMode(config);

        if (trustStoreLocationProp.isEmpty()) {
            LOG.warn("{} not specified", getSslTruststoreLocationProperty());
        } else {
            try {
                trustManagers = new TrustManager[]{
                    createTrustManager(trustStoreLocationProp, trustStorePasswordProp, trustStoreTypeProp, sslCrlEnabled,
                        sslOcspEnabled, sslServerHostnameVerificationEnabled, sslClientHostnameVerificationEnabled,
                        fipsMode)};
            } catch (TrustManagerException trustManagerException) {
                throw new SSLContextException("Failed to create TrustManager", trustManagerException);
            } catch (IllegalArgumentException e) {
                throw new SSLContextException("Bad value for "
                                              + sslTruststoreTypeProperty
                                              + ": "
                                              + trustStoreTypeProp, e);
            }
        }

        String protocol = config.getProperty(sslProtocolProperty, DEFAULT_PROTOCOL);
        try {
            SSLContext sslContext = SSLContext.getInstance(protocol);
            sslContext.init(keyManagers, trustManagers, null);
            return new SSLContextAndOptions(this, config, sslContext);
        } catch (NoSuchAlgorithmException | KeyManagementException sslContextInitException) {
            throw new SSLContextException(sslContextInitException);
        }
    }

    public static KeyStore loadKeyStore(
        String keyStoreLocation,
        String keyStorePassword,
        String keyStoreTypeProp) throws IOException, GeneralSecurityException {
        KeyStoreFileType storeFileType = KeyStoreFileType.fromPropertyValueOrFileName(keyStoreTypeProp, keyStoreLocation);
        return FileKeyStoreLoaderBuilderProvider
            .getBuilderForKeyStoreFileType(storeFileType)
            .setKeyStorePath(keyStoreLocation)
            .setKeyStorePassword(keyStorePassword)
            .build()
            .loadKeyStore();
    }

    public static KeyStore loadTrustStore(
        String trustStoreLocation,
        String trustStorePassword,
        String trustStoreTypeProp) throws IOException, GeneralSecurityException {
        KeyStoreFileType storeFileType = KeyStoreFileType.fromPropertyValueOrFileName(trustStoreTypeProp, trustStoreLocation);
        return FileKeyStoreLoaderBuilderProvider
            .getBuilderForKeyStoreFileType(storeFileType)
            .setTrustStorePath(trustStoreLocation)
            .setTrustStorePassword(trustStorePassword)
            .build()
            .loadTrustStore();
    }

    /**
     * Returns the password specified by the given property or from the file specified by the given path property.
     * If both are specified, the value stored in the file will be returned.
     *
     * @param config  Zookeeper configuration
     * @param propertyName  property name
     * @param pathPropertyName path property name
     * @return the password value
     */
    public String getPasswordFromConfigPropertyOrFile(final ZKConfig config,
                                                      final String propertyName,
                                                      final String pathPropertyName) {
        String value = config.getProperty(propertyName, "");
        final String pathProperty = config.getProperty(pathPropertyName, "");
        if (!pathProperty.isEmpty()) {
            value = String.valueOf(SecretUtils.readSecret(pathProperty));
        }
        return value;
    }

    /**
     * Creates a key manager by loading the key store from the given file of
     * the given type, optionally decrypting it using the given password.
     * @param keyStoreLocation the location of the key store file.
     * @param keyStorePassword optional password to decrypt the key store. If
     *                         empty, assumes the key store is not encrypted.
     * @param keyStoreTypeProp must be JKS, PEM, PKCS12, BCFKS or null. If null,
     *                         attempts to autodetect the key store type from
     *                         the file extension (e.g. .jks / .pem).
     * @return the key manager.
     * @throws KeyManagerException if something goes wrong.
     */
    public static X509KeyManager createKeyManager(
        String keyStoreLocation,
        String keyStorePassword,
        String keyStoreTypeProp) throws KeyManagerException {
        if (keyStorePassword == null) {
            keyStorePassword = "";
        }
        try {
            KeyStore ks = loadKeyStore(keyStoreLocation, keyStorePassword, keyStoreTypeProp);
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
     *
     * @param trustStoreLocation                the location of the trust store file.
     * @param trustStorePassword                optional password to decrypt the trust store
     *                                          (only applies to JKS trust stores). If empty,
     *                                          assumes the trust store is not encrypted.
     * @param trustStoreTypeProp                must be JKS, PEM, PKCS12, BCFKS or null. If
     *                                          null, attempts to autodetect the trust store
     *                                          type from the file extension (e.g. .jks / .pem).
     * @param crlEnabled                        enable CRL (certificate revocation list) checks.
     * @param ocspEnabled                       enable OCSP (online certificate status protocol)
     *                                          checks.
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
        final boolean clientHostnameVerificationEnabled,
        final boolean fipsMode) throws TrustManagerException {
        if (trustStorePassword == null) {
            trustStorePassword = "";
        }
        try {
            KeyStore ts = loadTrustStore(trustStoreLocation, trustStorePassword, trustStoreTypeProp);
            PKIXBuilderParameters pbParams = new PKIXBuilderParameters(ts, new X509CertSelector());
            if (crlEnabled) {
                // See [RevocationChecker][1] for details. Basically, we are mimicking the legacy path,
                // which relies significantly on jvm wide properties[2], as that is the path we are routing
                // before (i.e. no explicit `PKIXRevocationChecker`).
                //
                // By reading but not writing these properties, we conform to but not interfere with what
                // admin set while still keep backward compatibility.
                // 1. Default "zookeeper.ssl.crl" to jvm property "com.sun.net.ssl.checkRevocation" if it is unset.
                // 2. Default "zookeeper.ssl.ocsp" to jvm security property "ocsp.enable" if it is unset.
                // 3. Set `Option.ONLY_END_ENTITY` for jvm security property "com.sun.security.onlyCheckRevocationOfEECert".
                // 4. Don't set "com.sun.security.enableCRLDP" as it is always enabled in no legacy path.
                //
                // See also:
                // * https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/ocsp.html
                // * https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html
                // * https://docs.oracle.com/javase/8/docs/technotes/guides/security/certpath/CertPathProgGuide.html#PKIXRevocationChecker
                //
                // [1]: https://github.com/openjdk/jdk/blob/jdk-11%2B28/src/java.base/share/classes/sun/security/provider/certpath/RevocationChecker.java#L124
                // [2]: https://github.com/openjdk/jdk/blob/jdk-11%2B28/src/java.base/share/classes/sun/security/provider/certpath/RevocationChecker.java#L179
                Set<PKIXRevocationChecker.Option> options = new HashSet<>();
                if (!ocspEnabled) {
                    options.add(PKIXRevocationChecker.Option.NO_FALLBACK);
                    options.add(PKIXRevocationChecker.Option.PREFER_CRLS);
                }
                if (Boolean.parseBoolean(Security.getProperty("com.sun.security.onlyCheckRevocationOfEECert")))  {
                    options.add(PKIXRevocationChecker.Option.ONLY_END_ENTITY);
                }

                PKIXRevocationChecker revocationChecker = (PKIXRevocationChecker) CertPathValidator.getInstance("PKIX").getRevocationChecker();
                revocationChecker.setOptions(options);
                pbParams.addCertPathChecker(revocationChecker);
            } else {
                pbParams.setRevocationEnabled(false);
            }

            // Revocation checking is only supported with the PKIX algorithm
            TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
            tmf.init(new CertPathTrustManagerParameters(pbParams));

            for (final TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509ExtendedTrustManager) {
                    if (fipsMode) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("FIPS mode is ON: selecting standard x509 trust manager {}", tm);
                        }
                        return (X509TrustManager) tm;
                    }
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("FIPS mode is OFF: creating ZKTrustManager");
                    }
                    return new ZKTrustManager((X509ExtendedTrustManager) tm, serverHostnameVerificationEnabled,
                        clientHostnameVerificationEnabled);
                }
            }
            throw new TrustManagerException("Couldn't find X509TrustManager");
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            throw new TrustManagerException(e);
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

    private FileChangeWatcher newFileChangeWatcher(String fileLocation) throws IOException {
        if (fileLocation == null || fileLocation.isEmpty()) {
            return null;
        }
        final Path filePath = Paths.get(fileLocation).toAbsolutePath();
        Path parentPath = filePath.getParent();
        if (parentPath == null) {
            throw new IOException("Key/trust store path does not have a parent: " + filePath);
        }
        return new FileChangeWatcher(parentPath, watchEvent -> {
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
        FileChangeWatcher newKeyStoreFileWatcher = newFileChangeWatcher(config.getProperty(sslKeystoreLocationProperty));
        if (newKeyStoreFileWatcher != null) {
            // stop old watcher if there is one
            if (keyStoreFileWatcher != null) {
                keyStoreFileWatcher.stop();
            }
            keyStoreFileWatcher = newKeyStoreFileWatcher;
            keyStoreFileWatcher.start();
        }
        FileChangeWatcher newTrustStoreFileWatcher = newFileChangeWatcher(config.getProperty(sslTruststoreLocationProperty));
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
        defaultSSLContextAndOptions.set(null);
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
        } else if (event.kind().equals(StandardWatchEventKinds.ENTRY_MODIFY)
                   || event.kind().equals(StandardWatchEventKinds.ENTRY_CREATE)) {
            Path eventFilePath = dirPath.resolve((Path) event.context());
            if (filePath.equals(eventFilePath)) {
                shouldResetContext = true;
            }
        }
        // Note: we don't care about delete events
        if (shouldResetContext) {
            LOG.debug(
                "Attempting to reset default SSL context after receiving watch event: {} with context: {}",
                event.kind(),
                event.context());
            try {
                this.resetDefaultSSLContextAndOptions();
            } catch (SSLContextException e) {
                throw new RuntimeException(e);
            }
        } else {
            LOG.debug(
                "Ignoring watch event and keeping previous default SSL context. Event kind: {} with context: {}",
                event.kind(),
                event.context());
        }
    }

}
