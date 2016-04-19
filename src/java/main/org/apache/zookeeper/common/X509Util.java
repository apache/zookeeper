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


import org.apache.zookeeper.SSLCertCfg;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.util.ZKDynamicX509TrustManager;
import org.apache.zookeeper.server.quorum.util.ZKPeerX509TrustManager;
import org.apache.zookeeper.server.quorum.util.ZKX509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.bind.DatatypeConverter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import static org.apache.zookeeper.common.X509Exception.KeyManagerException;
import static org.apache.zookeeper.common.X509Exception.SSLContextException;
import static org.apache.zookeeper.common.X509Exception.TrustManagerException;

/**
 * Utility code for X509 handling
 */
public class X509Util {
    private static final Logger LOG = LoggerFactory.getLogger(X509Util.class);

    public static final String SSL_VERSION_DEFAULT = "TLSv1";
    public static final String SSL_VERSION = "zookeeper.ssl.version";
    public static final String SSL_KEYSTORE_LOCATION = "zookeeper.ssl.keyStore.location";
    public static final String SSL_KEYSTORE_PASSWD = "zookeeper.ssl.keyStore.password";
    public static final String SSL_KEYSTORE_ALIAS
            = "zookeeper.ssl.keyStore.password.alias";
    public static final String SSL_TRUSTSTORE_LOCATION = "zookeeper.ssl.trustStore.location";
    public static final String SSL_TRUSTSTORE_PASSWD = "zookeeper.ssl.trustStore.password";
    public static final String SSL_AUTHPROVIDER = "zookeeper.ssl.authProvider";
    public static final String SSL_TRUSTSTORE_CA_ALIAS =
            "zookeeper.ssl.trustStore.rootCA.alias";
    public static final String SSL_DIGEST_DEFAULT_ALGO ="SHA-256";
    public static final String SSL_DIGEST_ALGOS = "quorum.ssl.digest.algos";

    public static SSLContext createSSLContext(
            final InetSocketAddress peerAddr,
            final SSLCertCfg peerCertCfg)
            throws SSLContextException {
        final KeyManager[] keyManagers = createKeyManagers();
        TrustManager[] trustManagers;
        if (peerCertCfg.isSelfSigned()) {
            trustManagers = createTrustManagers(peerAddr,
                    peerCertCfg.getCertFingerPrint());
        } else if (peerCertCfg.isCASigned()) {
            // Lets load the CA for truststore.
            trustManagers = createTrustManagers(null);
        } else {
            throw new IllegalArgumentException("Invalid argument, no SSL cfg " +
                    "provided");
        }

        return createSSLContext(keyManagers, trustManagers);
    }

    /**
     * SSL context which can be used by both client and server side which
     * depend on dynamic config for authentication. Hence we need quorumPeer.
     * @param quorumPeer Used for getting QuorumVerifier and certs from
     *                   QuorumPeerConfig. Both commited and last verified.
     * @return SSLContext which can perform authentication based on dynamic cfg.
     * @throws SSLContextException
     */
    public static SSLContext createSSLContext(final QuorumPeer quorumPeer)
            throws SSLContextException {
        final KeyManager[] keyManagers = createKeyManagers();
        final TrustManager[] trustManagers = createTrustManagers(quorumPeer);

        return createSSLContext(keyManagers, trustManagers);
    }

    private static KeyManager[] createKeyManagers() throws SSLContextException {
        final String keyStoreLocationProp =
                System.getProperty(SSL_KEYSTORE_LOCATION);
        final String keyStorePasswordProp =
                System.getProperty(SSL_KEYSTORE_PASSWD);

        // There are legal states in some use cases for null
        // KeyManager or TrustManager. But if a user wanna specify one,
        // location and password are required.

        if (keyStoreLocationProp == null && keyStorePasswordProp == null) {
            LOG.warn("keystore not specified for client connection");
            return null;
        } else {
            if (keyStoreLocationProp == null) {
                throw new SSLContextException("keystore location not " +
                        "specified for client connection");
            }
            if (keyStorePasswordProp == null) {
                throw new SSLContextException("keystore password not " +
                        "specified for client connection");
            }
            try {
                return new KeyManager[]{
                        createKeyManager(keyStoreLocationProp,
                                keyStorePasswordProp)};
            } catch (KeyManagerException e) {
                throw new SSLContextException("Failed to create KeyManager", e);
            }
        }
    }

    private static TrustManager[] createTrustManagers(
            final QuorumPeer quorumPeer) throws SSLContextException {
        String trustStoreLocationProp =
                System.getProperty(SSL_TRUSTSTORE_LOCATION);
        String trustStorePasswordProp =
                System.getProperty(SSL_TRUSTSTORE_PASSWD);

        if (trustStoreLocationProp == null && trustStorePasswordProp == null) {
            LOG.warn("keystore not specified for client connection");
            return null;
        } else {
            if (trustStoreLocationProp == null) {
                throw new SSLContextException("keystore location not " +
                        "specified for client connection");
            }
            if (trustStorePasswordProp == null) {
                throw new SSLContextException("keystore password not " +
                        "specified for client connection");
            }
            try {
                return new TrustManager[] {
                        createTrustManager(trustStoreLocationProp,
                                trustStorePasswordProp, quorumPeer)};
            } catch (TrustManagerException e) {
                throw new SSLContextException("Failed to create KeyManager", e);
            }
        }
    }

    private static TrustManager[] createTrustManagers(
            final InetSocketAddress peerAddr,
            final MessageDigest peerCertFingerPrint) {
        return new TrustManager[]{
                    createTrustManager(peerAddr, peerCertFingerPrint)};
    }

    private static SSLContext createSSLContext(
            final KeyManager[] keyManagers,
            final TrustManager[] trustManagers)
            throws SSLContextException {
        String sslVersion = System.getProperty(SSL_VERSION);
        if (sslVersion == null) {
            sslVersion = SSL_VERSION_DEFAULT;
        }
        SSLContext sslContext = null;
        try {
            sslContext = SSLContext.getInstance(sslVersion);
            sslContext.init(keyManagers, trustManagers, null);
        } catch (Exception e) {
            throw new SSLContextException(e);
        }
        return sslContext;
    }

    public static X509KeyManager createKeyManager(
            final String keyStoreLocation, final String keyStorePassword)
            throws KeyManagerException {
        FileInputStream inputStream = null;
        try {

            KeyStore ks = loadKeyStore(keyStoreLocation, keyStorePassword);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, keyStorePassword.toCharArray());

            for (KeyManager km : kmf.getKeyManagers()) {
                if (km instanceof X509KeyManager) {
                    return (X509KeyManager) km;
                }
            }
            throw new KeyManagerException("Couldn't find X509KeyManager");

        } catch (KeyStoreException | NoSuchAlgorithmException |
                CertificateException | UnrecoverableKeyException |
                IOException e) {
            throw new KeyManagerException(e);
        }
    }

    private static KeyStore loadKeyStore(final String keyStoreLocation,
                                  final String keyStorePassword)
    throws KeyStoreException, NoSuchAlgorithmException,
            CertificateException, IOException {
        char[] keyStorePasswordChars = keyStorePassword.toCharArray();
        File keyStoreFile = new File(keyStoreLocation);
        KeyStore ks = KeyStore.getInstance("JKS");
        try (final FileInputStream inputStream = new FileInputStream
                (keyStoreFile)) {
            ks.load(inputStream, keyStorePasswordChars);
        } catch (IOException exp) {
            throw exp;
        }
        return ks;
    }

    private static X509TrustManager createTrustManager(
            final InetSocketAddress peerAddr,
            final MessageDigest peerCertFingerPrint) {
        return new ZKPeerX509TrustManager(peerAddr, peerCertFingerPrint);
    }

    public static X509TrustManager createTrustManager(
            final String trustStoreLocation, final String trustStorePassword,
            final QuorumPeer quorumPeer)
            throws TrustManagerException, SSLContextException {
        FileInputStream inputStream = null;
        try {
            String trustStoreCAAlias =
                    System.getProperty(SSL_TRUSTSTORE_CA_ALIAS);
            if (trustStoreCAAlias != null) {
                char[] trustStorePasswordChars = trustStorePassword.toCharArray();
                File trustStoreFile = new File(trustStoreLocation);
                KeyStore ts = KeyStore.getInstance("JKS");
                inputStream = new FileInputStream(trustStoreFile);
                ts.load(inputStream, trustStorePasswordChars);
                TrustManagerFactory tmf =
                        TrustManagerFactory.getInstance("SunX509");
                tmf.init(ts);
                X509Certificate rootCA =
                        getCertWithAlias(ts, trustStoreCAAlias);
                if (rootCA == null) {
                    final String str = "failed to find root CA from: " +
                            trustStoreLocation + " with alias: " +
                            trustStoreCAAlias;
                    LOG.error(str);
                    throw new TrustManagerException(str);
                }

                return createTrustManager(rootCA);
            }

            LOG.info("No root CA using standard TrustManager");
            if (quorumPeer == null) {
                final String errStr = "QuorumPeer is not provided, and no CA " +
                        "is configured. Cannot perform authentication bailing!";
                LOG.error(errStr);
                throw new SSLContextException(errStr);
            }

            return createTrustManager(quorumPeer);

        } catch (Exception e) {
            throw new TrustManagerException(e);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {}
            }
        }
    }

    private static X509TrustManager createTrustManager(
            final X509Certificate rootCA) {
        return new ZKX509TrustManager(rootCA);
    }

    private static X509TrustManager createTrustManager(
            final QuorumPeer quorumPeer) {
        return new ZKDynamicX509TrustManager(quorumPeer);
    }
    private static X509Certificate getCertWithAlias(
            final KeyStore trustStore, final String alias)
            throws KeyStoreException {
        X509Certificate cert;
        try {
            cert = (X509Certificate) trustStore.getCertificate(alias);
        } catch (KeyStoreException exp) {
            LOG.error("failed to load CA cert, exp: " + exp);
            throw exp;
        }

        return cert;
    }

    /**
     * Parse parsed system property and find a valid algo that matches
     * the finger print passed. Will return null if it couldn't
     * @param fingerPrint
     * @return MessageDigest object, null on error.
     */
    public static MessageDigest getSupportedMessageDigestForFp(
            final String fingerPrint) {
        final String[] algos = getConfigureDigestAlgos();
        String validAlgo = null;

        for (int i = 0; i < algos.length; i++) {
            LOG.info("Trying available algo: " + algos[i]);
            if (fingerPrint.toLowerCase().startsWith(algos[i])) {
                validAlgo = algos[i];
                break;
            }
        }

        // If there is no valid algo then return null
        if (validAlgo == null) {
            LOG.error("Could not find valid algo str in fingerprint: " +
                    fingerPrint);
            return null;
        }

        MessageDigest md = getMessageDigestByAlgo(validAlgo);

        if (md == null) {
            return null;
        }

        // Validate that given input matches expected length for
        // the supported algorithm
        final String fp = fingerPrint.trim().toUpperCase()
                .replace(md.getAlgorithm(), "")
                .replace("-", "");
        byte[] b = DatatypeConverter.parseHexBinary(fp);
        if (b.length != md.getDigestLength()) {
            LOG.error("Invalid digest, length mismatch for fingerprint: " +
                    fingerPrint + "has length: " + b.length + "algo: " +
                    md.getAlgorithm() + " needs length: " +
                    md.getDigestLength());
            return null;
        }

        md.update(b);
        return md;
    }

    private static String[] getConfigureDigestAlgos() {
        String digest_algos = System.getProperty(SSL_DIGEST_ALGOS);
        if (digest_algos == null) {
            digest_algos = SSL_DIGEST_DEFAULT_ALGO;
        }

        return digest_algos.trim().toLowerCase().split(",");
    }

    private static MessageDigest getMessageDigestByAlgo(
            final String validAlgo) {
        MessageDigest md = null;
        try {
            LOG.info("Valid algo: " + validAlgo);
            md = MessageDigest.getInstance(validAlgo.toUpperCase());
        } catch (NoSuchAlgorithmException e) {
            LOG.error("Invalid algo: " + validAlgo + " support algos: " +
                    getConfigureDigestAlgos());
        }

        return md;
    }

    /**
     * Get the right MessageDigest i.e only if it is configured and validate
     * the cert with the given finger print.
     * @param fingerPrint
     * @param cert
     * @return True on success
     * @throws CertificateEncodingException
     */
    public static boolean validateCert(final String fingerPrint,
                                       final X509Certificate cert)
            throws CertificateEncodingException, NoSuchAlgorithmException {
        final MessageDigest fpMsgDigest =
                getSupportedMessageDigestForFp(fingerPrint);
        if (fpMsgDigest == null) {
            return false;
        }

        final MessageDigest certMsgDigest =
                MessageDigest.getInstance(fpMsgDigest.getAlgorithm());

        certMsgDigest.update(cert.getEncoded());
        return certMsgDigest.equals(fpMsgDigest);
    }

    public static boolean validateCert(final MessageDigest fingerPrint,
                                       final X509Certificate cert)
            throws CertificateEncodingException, NoSuchAlgorithmException {
        final MessageDigest certMsgDigest =
                MessageDigest.getInstance(fingerPrint.getAlgorithm());

        certMsgDigest.update(cert.getEncoded());
        return certMsgDigest.equals(fingerPrint);
    }

    /**
     * Checks whether given X.509 certificate is self-signed.
     */
    public static boolean verifySelfSigned(X509Certificate cert)
            throws CertificateException {
        try {
            // Try to verify certificate signature with its own public key
            final PublicKey key = cert.getPublicKey();
            cert.verify(key);
            return true;
        } catch (InvalidKeyException | SignatureException |
                NoSuchAlgorithmException | NoSuchProviderException exp) {
            // Invalid signature --> not self-signed
            final String errStr = "Invalid not self-signed";
            LOG.error("{}", errStr, exp);
            throw new CertificateException(exp);
        }
    }
}
