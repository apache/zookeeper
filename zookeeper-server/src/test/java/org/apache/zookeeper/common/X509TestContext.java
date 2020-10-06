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

import static java.util.Objects.requireNonNull;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import org.apache.commons.io.FileUtils;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.OperatorCreationException;

/**
 * This class simplifies the creation of certificates and private keys for SSL/TLS connections.
 */
public class X509TestContext {

    private static final String TRUST_STORE_PREFIX = "zk_test_ca";
    private static final String KEY_STORE_PREFIX = "zk_test_key";

    private final File tempDir;

    private final X509KeyType trustStoreKeyType;
    private final KeyPair trustStoreKeyPair;
    private final long trustStoreCertExpirationMillis;
    private final X509Certificate trustStoreCertificate;
    private final String trustStorePassword;
    private File trustStoreJksFile;
    private File trustStorePemFile;
    private File trustStorePkcs12File;
    private File trustStoreBcfksFile;

    private final X509KeyType keyStoreKeyType;
    private final KeyPair keyStoreKeyPair;
    private final long keyStoreCertExpirationMillis;
    private final X509Certificate keyStoreCertificate;
    private final String keyStorePassword;
    private File keyStoreJksFile;
    private File keyStorePemFile;
    private File keyStorePkcs12File;
    private File keyStoreBcfksFile;

    private final Boolean hostnameVerification;

    /**
     * Constructor is intentionally private, use the Builder class instead.
     * @param tempDir the directory in which key store and trust store temp files will be written.
     * @param trustStoreKeyPair the key pair for the trust store.
     * @param trustStoreCertExpirationMillis the expiration of the trust store cert, in milliseconds from now.
     * @param trustStorePassword the password to protect a JKS trust store (ignored for PEM trust stores).
     * @param keyStoreKeyPair the key pair for the key store.
     * @param keyStoreCertExpirationMillis the expiration of the key store cert, in milliseconds from now.
     * @param keyStorePassword the password to protect the key store private key.
     * @throws IOException
     * @throws GeneralSecurityException
     * @throws OperatorCreationException
     */
    private X509TestContext(File tempDir, KeyPair trustStoreKeyPair, long trustStoreCertExpirationMillis, String trustStorePassword, KeyPair keyStoreKeyPair, long keyStoreCertExpirationMillis, String keyStorePassword, Boolean hostnameVerification) throws IOException, GeneralSecurityException, OperatorCreationException {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            throw new IllegalStateException("BC Security provider was not found");
        }
        this.tempDir = requireNonNull(tempDir);
        if (!tempDir.isDirectory()) {
            throw new IllegalArgumentException("Not a directory: " + tempDir);
        }
        this.trustStoreKeyPair = requireNonNull(trustStoreKeyPair);
        this.trustStoreKeyType = keyPairToType(trustStoreKeyPair);
        this.trustStoreCertExpirationMillis = trustStoreCertExpirationMillis;
        this.trustStorePassword = requireNonNull(trustStorePassword);
        this.keyStoreKeyPair = requireNonNull(keyStoreKeyPair);
        this.keyStoreKeyType = keyPairToType(keyStoreKeyPair);
        this.keyStoreCertExpirationMillis = keyStoreCertExpirationMillis;
        this.keyStorePassword = requireNonNull(keyStorePassword);

        X500NameBuilder caNameBuilder = new X500NameBuilder(BCStyle.INSTANCE);
        caNameBuilder.addRDN(BCStyle.CN, MethodHandles.lookup().lookupClass().getCanonicalName() + " Root CA");
        trustStoreCertificate = X509TestHelpers.newSelfSignedCACert(caNameBuilder.build(), trustStoreKeyPair, trustStoreCertExpirationMillis);

        X500NameBuilder nameBuilder = new X500NameBuilder(BCStyle.INSTANCE);
        nameBuilder.addRDN(BCStyle.CN, MethodHandles.lookup().lookupClass().getCanonicalName() + " Zookeeper Test");
        keyStoreCertificate = X509TestHelpers.newCert(trustStoreCertificate, trustStoreKeyPair, nameBuilder.build(), keyStoreKeyPair.getPublic(), keyStoreCertExpirationMillis);
        trustStorePkcs12File = trustStorePemFile = trustStoreJksFile = null;
        keyStorePkcs12File = keyStorePemFile = keyStoreJksFile = null;

        this.hostnameVerification = hostnameVerification;
    }

    /**
     * Returns the X509KeyType of the given key pair.
     * @param keyPair the key pair.
     * @return <code>X509KeyType.RSA</code> if given an RSA key pair, and <code>X509KeyType.EC</code> otherwise.
     */
    private X509KeyType keyPairToType(KeyPair keyPair) {
        if (keyPair.getPrivate().getAlgorithm().contains("RSA")) {
            return X509KeyType.RSA;
        } else {
            return X509KeyType.EC;
        }
    }

    public File getTempDir() {
        return tempDir;
    }

    public X509KeyType getTrustStoreKeyType() {
        return trustStoreKeyType;
    }

    public KeyPair getTrustStoreKeyPair() {
        return trustStoreKeyPair;
    }

    public long getTrustStoreCertExpirationMillis() {
        return trustStoreCertExpirationMillis;
    }

    public X509Certificate getTrustStoreCertificate() {
        return trustStoreCertificate;
    }

    public String getTrustStorePassword() {
        return trustStorePassword;
    }

    /**
     * Returns the path to the trust store file in the given format (JKS or PEM). Note that the file is created lazily,
     * the first time this method is called. The trust store file is temporary and will be deleted on exit.
     * @param storeFileType the store file type (JKS or PEM).
     * @return the path to the trust store file.
     * @throws IOException if there is an error creating the trust store file.
     */
    public File getTrustStoreFile(KeyStoreFileType storeFileType) throws IOException {
        switch (storeFileType) {
        case JKS:
            return getTrustStoreJksFile();
        case PEM:
            return getTrustStorePemFile();
        case PKCS12:
            return getTrustStorePkcs12File();
        case BCFKS:
            return getTrustStoreBcfksFile();
            default:
            throw new IllegalArgumentException("Invalid trust store type: "
                                                       + storeFileType
                                                       + ", must be one of: "
                                                       + Arrays.toString(KeyStoreFileType.values()));
        }
    }

    private File getTrustStoreJksFile() throws IOException {
        if (trustStoreJksFile == null) {
            File trustStoreJksFile = File.createTempFile(TRUST_STORE_PREFIX, KeyStoreFileType.JKS.getDefaultFileExtension(), tempDir);
            trustStoreJksFile.deleteOnExit();
            try (final FileOutputStream trustStoreOutputStream = new FileOutputStream(trustStoreJksFile)) {
                byte[] bytes = X509TestHelpers.certToJavaTrustStoreBytes(trustStoreCertificate, trustStorePassword);
                trustStoreOutputStream.write(bytes);
                trustStoreOutputStream.flush();
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
            this.trustStoreJksFile = trustStoreJksFile;
        }
        return trustStoreJksFile;
    }

    private File getTrustStorePemFile() throws IOException {
        if (trustStorePemFile == null) {
            File trustStorePemFile = File.createTempFile(TRUST_STORE_PREFIX, KeyStoreFileType.PEM.getDefaultFileExtension(), tempDir);
            trustStorePemFile.deleteOnExit();
            FileUtils.writeStringToFile(trustStorePemFile, X509TestHelpers.pemEncodeX509Certificate(trustStoreCertificate), StandardCharsets.US_ASCII, false);
            this.trustStorePemFile = trustStorePemFile;
        }
        return trustStorePemFile;
    }

    private File getTrustStorePkcs12File() throws IOException {
        if (trustStorePkcs12File == null) {
            File trustStorePkcs12File = File.createTempFile(TRUST_STORE_PREFIX, KeyStoreFileType.PKCS12.getDefaultFileExtension(), tempDir);
            trustStorePkcs12File.deleteOnExit();
            try (final FileOutputStream trustStoreOutputStream = new FileOutputStream(trustStorePkcs12File)) {
                byte[] bytes = X509TestHelpers.certToPKCS12TrustStoreBytes(trustStoreCertificate, trustStorePassword);
                trustStoreOutputStream.write(bytes);
                trustStoreOutputStream.flush();
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
            this.trustStorePkcs12File = trustStorePkcs12File;
        }
        return trustStorePkcs12File;
    }

    private File getTrustStoreBcfksFile() throws IOException {
        if (trustStoreBcfksFile == null) {
            File trustStoreBcfksFile = File.createTempFile(
                TRUST_STORE_PREFIX, KeyStoreFileType.BCFKS.getDefaultFileExtension(), tempDir);
            trustStoreBcfksFile.deleteOnExit();
            try (final FileOutputStream trustStoreOutputStream = new FileOutputStream(trustStoreBcfksFile)) {
                byte[] bytes = X509TestHelpers.certToBCFKSTrustStoreBytes(trustStoreCertificate, trustStorePassword);
                trustStoreOutputStream.write(bytes);
                trustStoreOutputStream.flush();
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
            this.trustStoreBcfksFile = trustStoreBcfksFile;
        }
        return trustStoreBcfksFile;
    }

    public X509KeyType getKeyStoreKeyType() {
        return keyStoreKeyType;
    }

    public KeyPair getKeyStoreKeyPair() {
        return keyStoreKeyPair;
    }

    public long getKeyStoreCertExpirationMillis() {
        return keyStoreCertExpirationMillis;
    }

    public X509Certificate getKeyStoreCertificate() {
        return keyStoreCertificate;
    }

    public String getKeyStorePassword() {
        return keyStorePassword;
    }

    public boolean isKeyStoreEncrypted() {
        return keyStorePassword.length() > 0;
    }

    /**
     * Returns the path to the key store file in the given format (JKS, PEM, ...). Note that the file is created lazily,
     * the first time this method is called. The key store file is temporary and will be deleted on exit.
     * @param storeFileType the store file type (JKS, PEM, ...).
     * @return the path to the key store file.
     * @throws IOException if there is an error creating the key store file.
     */
    public File getKeyStoreFile(KeyStoreFileType storeFileType) throws IOException {
        switch (storeFileType) {
        case JKS:
            return getKeyStoreJksFile();
        case PEM:
            return getKeyStorePemFile();
        case PKCS12:
            return getKeyStorePkcs12File();
        case BCFKS:
            return getKeyStoreBcfksFile();
        default:
            throw new IllegalArgumentException("Invalid key store type: "
                                                       + storeFileType
                                                       + ", must be one of: "
                                                       + Arrays.toString(KeyStoreFileType.values()));
        }
    }

    private File getKeyStoreJksFile() throws IOException {
        if (keyStoreJksFile == null) {
            File keyStoreJksFile = File.createTempFile(KEY_STORE_PREFIX, KeyStoreFileType.JKS.getDefaultFileExtension(), tempDir);
            keyStoreJksFile.deleteOnExit();
            try (final FileOutputStream keyStoreOutputStream = new FileOutputStream(keyStoreJksFile)) {
                byte[] bytes = X509TestHelpers.certAndPrivateKeyToJavaKeyStoreBytes(keyStoreCertificate, keyStoreKeyPair.getPrivate(), keyStorePassword);
                keyStoreOutputStream.write(bytes);
                keyStoreOutputStream.flush();
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
            this.keyStoreJksFile = keyStoreJksFile;
        }
        return keyStoreJksFile;
    }

    private File getKeyStorePemFile() throws IOException {
        if (keyStorePemFile == null) {
            try {
                File keyStorePemFile = File.createTempFile(KEY_STORE_PREFIX, KeyStoreFileType.PEM.getDefaultFileExtension(), tempDir);
                keyStorePemFile.deleteOnExit();
                FileUtils.writeStringToFile(keyStorePemFile, X509TestHelpers.pemEncodeCertAndPrivateKey(keyStoreCertificate, keyStoreKeyPair.getPrivate(), keyStorePassword), StandardCharsets.US_ASCII, false);
                this.keyStorePemFile = keyStorePemFile;
            } catch (OperatorCreationException e) {
                throw new IOException(e);
            }
        }
        return keyStorePemFile;
    }

    private File getKeyStorePkcs12File() throws IOException {
        if (keyStorePkcs12File == null) {
            File keyStorePkcs12File = File.createTempFile(KEY_STORE_PREFIX, KeyStoreFileType.PKCS12.getDefaultFileExtension(), tempDir);
            keyStorePkcs12File.deleteOnExit();
            try (final FileOutputStream keyStoreOutputStream = new FileOutputStream(keyStorePkcs12File)) {
                byte[] bytes = X509TestHelpers.certAndPrivateKeyToPKCS12Bytes(keyStoreCertificate, keyStoreKeyPair.getPrivate(), keyStorePassword);
                keyStoreOutputStream.write(bytes);
                keyStoreOutputStream.flush();
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
            this.keyStorePkcs12File = keyStorePkcs12File;
        }
        return keyStorePkcs12File;
    }

    private File getKeyStoreBcfksFile() throws IOException {
        if (keyStoreBcfksFile == null) {
            File keyStoreBcfksFile = File.createTempFile(
              KEY_STORE_PREFIX, KeyStoreFileType.BCFKS.getDefaultFileExtension(), tempDir);
            keyStoreBcfksFile.deleteOnExit();
            try (final FileOutputStream keyStoreOutputStream = new FileOutputStream(keyStoreBcfksFile)) {
                byte[] bytes = X509TestHelpers.certAndPrivateKeyToBCFKSBytes(
                  keyStoreCertificate, keyStoreKeyPair.getPrivate(), keyStorePassword);
                keyStoreOutputStream.write(bytes);
                keyStoreOutputStream.flush();
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
            this.keyStoreBcfksFile = keyStoreBcfksFile;
        }
        return keyStoreBcfksFile;
    }

    /**
     * Sets the SSL system properties such that the given X509Util object can be used to create SSL Contexts that
     * will use the trust store and key store files created by this test context. Example usage:
     * <pre>
     *     X509TestContext testContext = ...; // create the test context
     *     X509Util x509Util = new QuorumX509Util();
     *     testContext.setSystemProperties(x509Util, KeyStoreFileType.JKS, KeyStoreFileType.JKS);
     *     // The returned context will use the key store and trust store created by the test context.
     *     SSLContext ctx = x509Util.getDefaultSSLContext();
     * </pre>
     * @param x509Util the X509Util.
     * @param keyStoreFileType the store file type to use for the key store (JKS, PEM, ...).
     * @param trustStoreFileType the store file type to use for the trust store (JKS, PEM, ...).
     * @throws IOException if there is an error creating the key store file or trust store file.
     */
    public void setSystemProperties(X509Util x509Util, KeyStoreFileType keyStoreFileType, KeyStoreFileType trustStoreFileType) throws IOException {
        System.setProperty(x509Util.getSslKeystoreLocationProperty(), this.getKeyStoreFile(keyStoreFileType).getAbsolutePath());
        System.setProperty(x509Util.getSslKeystorePasswdProperty(), this.getKeyStorePassword());
        System.setProperty(x509Util.getSslKeystoreTypeProperty(), keyStoreFileType.getPropertyValue());
        System.setProperty(x509Util.getSslTruststoreLocationProperty(), this.getTrustStoreFile(trustStoreFileType).getAbsolutePath());
        System.setProperty(x509Util.getSslTruststorePasswdProperty(), this.getTrustStorePassword());
        System.setProperty(x509Util.getSslTruststoreTypeProperty(), trustStoreFileType.getPropertyValue());
        if (hostnameVerification != null) {
            System.setProperty(x509Util.getSslHostnameVerificationEnabledProperty(), hostnameVerification.toString());
        } else {
            System.clearProperty(x509Util.getSslHostnameVerificationEnabledProperty());
        }
    }

    /**
     * Clears system properties set by
     * {@link #setSystemProperties(X509Util, KeyStoreFileType, KeyStoreFileType)}.
     * @param x509Util the X509Util to read property keys from.
     */
    public void clearSystemProperties(X509Util x509Util) {
        System.clearProperty(x509Util.getSslKeystoreLocationProperty());
        System.clearProperty(x509Util.getSslKeystorePasswdProperty());
        System.clearProperty(x509Util.getSslKeystoreTypeProperty());
        System.clearProperty(x509Util.getSslTruststoreLocationProperty());
        System.clearProperty(x509Util.getSslTruststorePasswdProperty());
        System.clearProperty(x509Util.getSslTruststoreTypeProperty());
        System.clearProperty(x509Util.getSslHostnameVerificationEnabledProperty());
    }

    /**
     * Builder class, used for creating new instances of X509TestContext.
     */
    public static class Builder {

        public static final long DEFAULT_CERT_EXPIRATION_MILLIS = 1000L * 60 * 60 * 24; // 1 day
        private File tempDir;
        private X509KeyType trustStoreKeyType;
        private String trustStorePassword;
        private long trustStoreCertExpirationMillis;
        private X509KeyType keyStoreKeyType;
        private String keyStorePassword;
        private long keyStoreCertExpirationMillis;
        private Boolean hostnameVerification;

        /**
         * Creates an empty builder.
         */
        public Builder() {
            trustStoreKeyType = X509KeyType.EC;
            trustStorePassword = "";
            trustStoreCertExpirationMillis = DEFAULT_CERT_EXPIRATION_MILLIS;
            keyStoreKeyType = X509KeyType.EC;
            keyStorePassword = "";
            keyStoreCertExpirationMillis = DEFAULT_CERT_EXPIRATION_MILLIS;
            hostnameVerification = null;
        }

        /**
         * Builds a new X509TestContext from this builder.
         * @return a new X509TestContext
         * @throws IOException
         * @throws GeneralSecurityException
         * @throws OperatorCreationException
         */
        public X509TestContext build() throws IOException, GeneralSecurityException, OperatorCreationException {
            KeyPair trustStoreKeyPair = X509TestHelpers.generateKeyPair(trustStoreKeyType);
            KeyPair keyStoreKeyPair = X509TestHelpers.generateKeyPair(keyStoreKeyType);
            return new X509TestContext(tempDir, trustStoreKeyPair, trustStoreCertExpirationMillis, trustStorePassword, keyStoreKeyPair, keyStoreCertExpirationMillis, keyStorePassword, hostnameVerification);
        }

        /**
         * Sets the temporary directory. Certificate and private key files will be created in this directory.
         * @param tempDir the temp directory.
         * @return this Builder.
         */
        public Builder setTempDir(File tempDir) {
            this.tempDir = tempDir;
            return this;
        }

        /**
         * Sets the trust store key type. The CA key generated for the test context will be of this type.
         * @param keyType the key type.
         * @return this Builder.
         */
        public Builder setTrustStoreKeyType(X509KeyType keyType) {
            trustStoreKeyType = keyType;
            return this;
        }

        /**
         * Sets the trust store password. Ignored for PEM trust stores, JKS trust stores will be encrypted with this
         * password.
         * @param password the password.
         * @return this Builder.
         */
        public Builder setTrustStorePassword(String password) {
            trustStorePassword = password;
            return this;
        }

        /**
         * Sets the trust store certificate's expiration, in milliseconds from when <code>build()</code> is called.
         * @param expirationMillis expiration in milliseconds.
         * @return this Builder.
         */
        public Builder setTrustStoreCertExpirationMillis(long expirationMillis) {
            trustStoreCertExpirationMillis = expirationMillis;
            return this;
        }

        /**
         * Sets the key store key type. The private key generated for the test context will be of this type.
         * @param keyType the key type.
         * @return this Builder.
         */
        public Builder setKeyStoreKeyType(X509KeyType keyType) {
            keyStoreKeyType = keyType;
            return this;
        }

        /**
         * Sets the key store password. The private key (PEM, JKS) and certificate (JKS only) will be encrypted with
         * this password.
         * @param password the password.
         * @return this Builder.
         */
        public Builder setKeyStorePassword(String password) {
            keyStorePassword = password;
            return this;
        }

        /**
         * Sets the key store certificate's expiration, in milliseconds from when <code>build()</code> is called.
         * @param expirationMillis expiration in milliseconds.
         * @return this Builder.
         */
        public Builder setKeyStoreCertExpirationMillis(long expirationMillis) {
            keyStoreCertExpirationMillis = expirationMillis;
            return this;
        }

        /**
         * Sets the hostname verification behavior. If null is provided, reverts the behavior to the default, otherwise
         * explicitly sets hostname verification to true or false.
         * @param hostnameVerification new value for the hostname verification setting.
         * @return this Builder.
         */
        public Builder setHostnameVerification(Boolean hostnameVerification) {
            this.hostnameVerification = hostnameVerification;
            return this;
        }

    }

    /**
     * Returns a new default-constructed Builder.
     * @return a new Builder.
     */
    public static Builder newBuilder() {
        return new Builder();
    }

}
