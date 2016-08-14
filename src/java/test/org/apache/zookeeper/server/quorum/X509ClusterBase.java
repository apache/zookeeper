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
package org.apache.zookeeper.server.quorum;


import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import javax.security.auth.x500.X500Principal;

import org.apache.commons.lang3.tuple.Pair;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class X509ClusterBase {
    protected static final Logger LOG = LoggerFactory.getLogger(
            X509ClusterBase.class.getName());
    private static int DEFAULT_KEY_SIZE = 2048;
    private static String KEY_STORE_SUFFIX = ".jks";
    private static String DEFAULT_SIGN_ALGO = "SHA256withRSA";
    private static long VALIDITY_PERIOD =
            TimeUnit.DAYS.toMillis(10*365);
    protected static final String DIR_PREFIX = "x509";
    protected static final String NODE_PREFIX = "node";
    protected static final String KEY_STORE_PASS = "CertPassword1";
    protected static final String TRUST_STORE_NAME = "truststore";
    private static Random rndGen = new Random();

    private final String clusterName;
    protected final Path basePath;
    protected final int clusterSize;
    protected final List<Path> keyStoreList;
    protected final List<String> keyStorePasswordList;
    protected final List<Path> badKeyStoreList;
    protected final List<String> badKeyStorePasswordList;
    protected Path trustStore;
    protected String trustStorePassword;
    protected Path badTrustStore;
    protected String badTrustStorePassword;
    private boolean initDone;

    public X509ClusterBase(final String clusterName,
                           final Path basePath, final int clusterSize) {
        this.clusterName = clusterName;
        this.basePath = basePath;
        this.clusterSize = clusterSize;

        keyStoreList = new ArrayList<>();
        keyStorePasswordList = new ArrayList<>();
        badKeyStoreList = new ArrayList<>();
        badKeyStorePasswordList = new ArrayList<>();
        initDone = false;
    }

    public List<Path> getKeyStoreList() {
        initOnce();
        return keyStoreList;
    }

    public List<String> getKeyStorePasswordList() {
        initOnce();
        return keyStorePasswordList;
    }

    public List<Path> getBadKeyStoreList() {
        initOnce();
        return badKeyStoreList;
    }

    public List<String> getBadKeyStorePasswordList() {
        initOnce();
        return badKeyStorePasswordList;
    }

    public Path getTrustStore() {
        initOnce();
        return trustStore;
    }

    public String getTrustStorePassword() {
        initOnce();
        return trustStorePassword;
    }

    public Path getBadTrustStore() {
        initOnce();
        return badTrustStore;
    }

    public String getBadTrustStorePassword() {
        initOnce();
        return badTrustStorePassword;
    }

    protected abstract void initCerts();

    private void initOnce() {
        if (!initDone) {
            Security.addProvider(new BouncyCastleProvider());
            initCerts();
            initDone = true;
        }
    }

    protected static KeyPair createRSAKeyPair()
            throws NoSuchAlgorithmException {
        return createRSAKeyPair(DEFAULT_KEY_SIZE);
    }

    public static KeyPair createRSAKeyPair(final int keySize)
            throws NoSuchAlgorithmException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(keySize);
        return keyGen.generateKeyPair();
    }

    public static X509Certificate buildEndEntityCert(
            final String subjectName, final X509Certificate caCert,
            final PrivateKey caKey, final KeyPair keyPair)
            throws NoSuchAlgorithmException,
            CertificateException, OperatorCreationException, CertIOException {
        return  buildEndEntityCert(subjectName, caCert, caKey, keyPair,
                DEFAULT_SIGN_ALGO);
    }

    public static X509Certificate buildEndEntityCert(
            final String subjectName, final X509Certificate caCert,
            final PrivateKey caKey, final KeyPair keyPair,
            final String signatureAlgorithm) throws NoSuchAlgorithmException,
            CertificateException, OperatorCreationException, CertIOException {
        final X509v3CertificateBuilder certBldr = new
                JcaX509v3CertificateBuilder(
                caCert.getSubjectX500Principal(),
                BigInteger.valueOf(rndGen.nextInt()),
                new Date(System.currentTimeMillis()),
                new Date(System.currentTimeMillis() + VALIDITY_PERIOD),
                new X500Principal("CN=End Entity Cert " + subjectName),
                keyPair.getPublic());
        final JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();
        certBldr.addExtension(Extension.authorityKeyIdentifier,
                false, extUtils.createAuthorityKeyIdentifier(caCert))
                .addExtension(Extension.subjectKeyIdentifier,
                        false, extUtils.createSubjectKeyIdentifier(
                                keyPair.getPublic()))
                .addExtension(Extension.basicConstraints,
                        true, new BasicConstraints(false))
                .addExtension(Extension.keyUsage,
                        true, new KeyUsage(KeyUsage.digitalSignature
                                | KeyUsage.keyEncipherment));
        final ContentSigner signer = new JcaContentSignerBuilder
                (signatureAlgorithm).setProvider("BC").build(caKey);
        return new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(certBldr.build(signer));
    }

    public X509Certificate buildRootCert(final KeyPair keyPair)
            throws CertificateException, OperatorCreationException {
        return buildRootCert(clusterName, keyPair);
    }

    public static X509Certificate buildRootCert(final String subjectName,
                                                final KeyPair keyPair)
            throws OperatorCreationException, CertificateException {
        return buildRootCert(subjectName, keyPair, DEFAULT_SIGN_ALGO);
    }

    public static X509Certificate buildRootCert(
            final String subjectName, final KeyPair keyPair,
            final String signatureAlgorithm)
            throws OperatorCreationException, CertificateException {
        X509v3CertificateBuilder certBldr = new JcaX509v3CertificateBuilder(
                new X500Name("CN=" + subjectName + " Test Root Certificate"),
                BigInteger.valueOf(1),
                new Date(System.currentTimeMillis()),
                new Date(System.currentTimeMillis() + VALIDITY_PERIOD),
                new X500Name("CN=Test Root Certificate"),
                keyPair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder(signatureAlgorithm)
                .setProvider("BC").build(keyPair.getPrivate());
        return new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(certBldr.build(signer));
    }

    public Pair<Path, String> buildKeyStore(final String prefix,
            final int index, final KeyPair keyPair, final X509Certificate cert)
            throws CertificateException, NoSuchAlgorithmException,
            KeyStoreException, IOException {
        return Pair.of(buildKeyStore(basePath, prefix+"_"+NODE_PREFIX + index,
                prefix+"_"+NODE_PREFIX + index, KEY_STORE_PASS, keyPair, cert),
                KEY_STORE_PASS);
    }

    public Pair<Path, String> buildTrustStore(final String prefix,
            final KeyPair keyPair, final X509Certificate cert)
            throws CertificateException, NoSuchAlgorithmException,
            KeyStoreException, IOException {
        return Pair.of(buildKeyStore(basePath, prefix+"_"+TRUST_STORE_NAME,
                prefix+"_"+TRUST_STORE_NAME, KEY_STORE_PASS, keyPair, cert),
                KEY_STORE_PASS);
    }

    public static Path buildKeyStore(
            final Path basePath, final String name,
            final String alias, final String password,
            final KeyPair keyPair, final X509Certificate cert)
            throws KeyStoreException, CertificateException,
            NoSuchAlgorithmException, IOException {
        final KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        final char[] pass = password.toCharArray();
        ks.load(null, pass);

        KeyStore.PrivateKeyEntry entry = new KeyStore.PrivateKeyEntry(
                keyPair.getPrivate(),
                new java.security.cert.Certificate[]{cert});
        ks.setEntry(alias, entry ,new KeyStore.PasswordProtection(pass));

        final Path retPath = Paths.get(DIR_PREFIX).resolve(
                name + KEY_STORE_SUFFIX);
        final Path keyStorePath = basePath.resolve(retPath);
        if (!keyStorePath.getParent().toFile().exists()) {
            if (!keyStorePath.getParent().toFile().mkdirs()) {
                final String errStr = "Could not create dirs: " + keyStorePath;
                LOG.error(errStr);
                throw new IllegalAccessError(errStr);
            }
        }

        try(final FileOutputStream fs =
                    new FileOutputStream(keyStorePath.toString())) {
            ks.store(fs, pass);
        }

        return keyStorePath;
    }
}
