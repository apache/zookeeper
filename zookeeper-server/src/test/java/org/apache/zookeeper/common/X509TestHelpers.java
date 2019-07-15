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

import org.bouncycastle.asn1.DERIA5String;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder;
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.OutputEncryptor;
import org.bouncycastle.operator.bc.BcContentSignerBuilder;
import org.bouncycastle.operator.bc.BcECContentSignerBuilder;
import org.bouncycastle.operator.bc.BcRSAContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.Date;

/**
 * This class contains helper methods for creating X509 certificates and key pairs, and for serializing them
 * to JKS or PEM files.
 */
public class X509TestHelpers {
    private static final Logger LOG = LoggerFactory.getLogger(X509TestHelpers.class);

    private static final SecureRandom PRNG = new SecureRandom();
    private static final int DEFAULT_RSA_KEY_SIZE_BITS = 2048;
    private static final BigInteger DEFAULT_RSA_PUB_EXPONENT = RSAKeyGenParameterSpec.F4; // 65537
    private static final String DEFAULT_ELLIPTIC_CURVE_NAME = "secp256r1";
    // Per RFC 5280 section 4.1.2.2, X509 certificates can use up to 20 bytes == 160 bits for serial numbers.
    private static final int SERIAL_NUMBER_MAX_BITS = 20 * Byte.SIZE;

    /**
     * Uses the private key of the given key pair to create a self-signed CA certificate with the public half of the
     * key pair and the given subject and expiration. The issuer of the new cert will be equal to the subject.
     * Returns the new certificate.
     * The returned certificate should be used as the trust store. The private key of the input key pair should be
     * used to sign certificates that are used by test peers to establish TLS connections to each other.
     * @param subject the subject of the new certificate being created.
     * @param keyPair the key pair to use. The public key will be embedded in the new certificate, and the private key
     *                will be used to self-sign the certificate.
     * @param expirationMillis expiration of the new certificate, in milliseconds from now.
     * @return a new self-signed CA certificate.
     * @throws IOException
     * @throws OperatorCreationException
     * @throws GeneralSecurityException
     */
    public static X509Certificate newSelfSignedCACert(
            X500Name subject,
            KeyPair keyPair,
            long expirationMillis) throws IOException, OperatorCreationException, GeneralSecurityException {
        Date now = new Date();
        X509v3CertificateBuilder builder = initCertBuilder(
                subject, // for self-signed certs, issuer == subject
                now,
                new Date(now.getTime() + expirationMillis),
                subject,
                keyPair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true)); // is a CA
        builder.addExtension(
                Extension.keyUsage,
                true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyCertSign | KeyUsage.cRLSign));
        return buildAndSignCertificate(keyPair.getPrivate(), builder);
    }

    /**
     * Using the private key of the given CA key pair and the Subject of the given CA cert as the Issuer, issues a
     * new cert with the given subject and public key. The returned certificate, combined with the private key half
     * of the <code>certPublicKey</code>, should be used as the key store.
     * @param caCert the certificate of the CA that's doing the signing.
     * @param caKeyPair the key pair of the CA. The private key will be used to sign. The public key must match the
     *                  public key in the <code>caCert</code>.
     * @param certSubject the subject field of the new cert being issued.
     * @param certPublicKey the public key of the new cert being issued.
     * @param expirationMillis the expiration of the cert being issued, in milliseconds from now.
     * @return a new certificate signed by the CA's private key.
     * @throws IOException
     * @throws OperatorCreationException
     * @throws GeneralSecurityException
     */
    public static X509Certificate newCert(
            X509Certificate caCert,
            KeyPair caKeyPair,
            X500Name certSubject,
            PublicKey certPublicKey,
            long expirationMillis) throws IOException, OperatorCreationException, GeneralSecurityException {
        if (!caKeyPair.getPublic().equals(caCert.getPublicKey())) {
            throw new IllegalArgumentException("CA private key does not match the public key in the CA cert");
        }
        Date now = new Date();
        X509v3CertificateBuilder builder = initCertBuilder(
                new X500Name(caCert.getIssuerDN().getName()),
                now,
                new Date(now.getTime() + expirationMillis),
                certSubject,
                certPublicKey);
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false)); // not a CA
        builder.addExtension(
                Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        builder.addExtension(
                Extension.extendedKeyUsage,
                true,
                new ExtendedKeyUsage(new KeyPurposeId[] { KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth }));

        builder.addExtension(
                Extension.subjectAlternativeName,
                false,
                getLocalhostSubjectAltNames());
        return buildAndSignCertificate(caKeyPair.getPrivate(), builder);
    }

    /**
     * Returns subject alternative names for "localhost".
     * @return the subject alternative names for "localhost".
     */
    private static GeneralNames getLocalhostSubjectAltNames() throws UnknownHostException {
        InetAddress[] localAddresses = InetAddress.getAllByName("localhost");
        GeneralName[] generalNames = new GeneralName[localAddresses.length + 1];
        for (int i = 0; i < localAddresses.length; i++) {
            generalNames[i] = new GeneralName(GeneralName.iPAddress, new DEROctetString(localAddresses[i].getAddress()));
        }
        generalNames[generalNames.length - 1] = new GeneralName(GeneralName.dNSName, new DERIA5String("localhost"));
        return new GeneralNames(generalNames);
    }

    /**
     * Helper method for newSelfSignedCACert() and newCert(). Initializes a X509v3CertificateBuilder with
     * logic that's common to both methods.
     * @param issuer Issuer field of the new cert.
     * @param notBefore date before which the new cert is not valid.
     * @param notAfter date after which the new cert is not valid.
     * @param subject Subject field of the new cert.
     * @param subjectPublicKey public key to store in the new cert.
     * @return a X509v3CertificateBuilder that can be further customized to finish creating the new cert.
     */
    private static X509v3CertificateBuilder initCertBuilder(
            X500Name issuer,
            Date notBefore,
            Date notAfter,
            X500Name subject,
            PublicKey subjectPublicKey) {
        return new X509v3CertificateBuilder(
                issuer,
                new BigInteger(SERIAL_NUMBER_MAX_BITS, PRNG),
                notBefore,
                notAfter,
                subject,
                SubjectPublicKeyInfo.getInstance(subjectPublicKey.getEncoded()));
    }

    /**
     * Signs the certificate being built by the given builder using the given private key and returns the certificate.
     * @param privateKey the private key to sign the certificate with.
     * @param builder the cert builder that contains the certificate data.
     * @return the signed certificate.
     * @throws IOException
     * @throws OperatorCreationException
     * @throws CertificateException
     */
    private static X509Certificate buildAndSignCertificate(
            PrivateKey privateKey,
            X509v3CertificateBuilder builder) throws IOException, OperatorCreationException, CertificateException {
        BcContentSignerBuilder signerBuilder;
        if (privateKey.getAlgorithm().contains("RSA")) { // a little hacky way to detect key type, but it works
            AlgorithmIdentifier signatureAlgorithm = new DefaultSignatureAlgorithmIdentifierFinder().find(
                    "SHA256WithRSAEncryption");
            AlgorithmIdentifier digestAlgorithm = new DefaultDigestAlgorithmIdentifierFinder().find(signatureAlgorithm);
            signerBuilder = new BcRSAContentSignerBuilder(signatureAlgorithm, digestAlgorithm);
        } else { // if not RSA, assume EC
            AlgorithmIdentifier signatureAlgorithm = new DefaultSignatureAlgorithmIdentifierFinder().find(
                    "SHA256withECDSA");
            AlgorithmIdentifier digestAlgorithm = new DefaultDigestAlgorithmIdentifierFinder().find(signatureAlgorithm);
            signerBuilder = new BcECContentSignerBuilder(signatureAlgorithm, digestAlgorithm);
        }
        AsymmetricKeyParameter privateKeyParam = PrivateKeyFactory.createKey(privateKey.getEncoded());
        ContentSigner signer = signerBuilder.build(privateKeyParam);
        return toX509Cert(builder.build(signer));
    }

    /**
     * Generates a new asymmetric key pair of the given type.
     * @param keyType the type of key pair to generate.
     * @return the new key pair.
     * @throws GeneralSecurityException if your java crypto providers are messed up.
     */
    public static KeyPair generateKeyPair(X509KeyType keyType) throws GeneralSecurityException {
        switch (keyType) {
            case RSA:
                return generateRSAKeyPair();
            case EC:
                return generateECKeyPair();
            default:
                throw new IllegalArgumentException("Invalid X509KeyType");
        }
    }

    /**
     * Generates an RSA key pair with a 2048-bit private key and F4 (65537) as the public exponent.
     * @return the key pair.
     */
    public static KeyPair generateRSAKeyPair() throws GeneralSecurityException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        RSAKeyGenParameterSpec keyGenSpec = new RSAKeyGenParameterSpec(
                DEFAULT_RSA_KEY_SIZE_BITS, DEFAULT_RSA_PUB_EXPONENT);
        keyGen.initialize(keyGenSpec, PRNG);
        return keyGen.generateKeyPair();
    }

    /**
     * Generates an elliptic curve key pair using the "secp256r1" aka "prime256v1" aka "NIST P-256" curve.
     * @return the key pair.
     */
    public static KeyPair generateECKeyPair() throws GeneralSecurityException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        keyGen.initialize(new ECGenParameterSpec(DEFAULT_ELLIPTIC_CURVE_NAME), PRNG);
        return keyGen.generateKeyPair();
    }

    /**
     * PEM-encodes the given X509 certificate and private key (compatible with OpenSSL), optionally protecting the
     * private key with a password. Concatenates them both and returns the result as a single string.
     * This creates the PEM encoding of a key store.
     * @param cert the X509 certificate to PEM-encode.
     * @param privateKey the private key to PEM-encode.
     * @param keyPassword an optional key password. If empty or null, the private key will not be encrypted.
     * @return a String containing the PEM encodings of the certificate and private key.
     * @throws IOException if converting the certificate or private key to PEM format fails.
     * @throws OperatorCreationException if constructing the encryptor from the given password fails.
     */
    public static String pemEncodeCertAndPrivateKey(
            X509Certificate cert,
            PrivateKey privateKey,
            String keyPassword) throws IOException, OperatorCreationException {
        return pemEncodeX509Certificate(cert) +
                "\n" +
                pemEncodePrivateKey(privateKey, keyPassword);
    }

    /**
     * PEM-encodes the given private key (compatible with OpenSSL), optionally protecting it with a password, and
     * returns the result as a String.
     * @param key the private key.
     * @param password an optional key password. If empty or null, the private key will not be encrypted.
     * @return a String containing the PEM encoding of the private key.
     * @throws IOException if converting the key to PEM format fails.
     * @throws OperatorCreationException if constructing the encryptor from the given password fails.
     */
    public static String pemEncodePrivateKey(
            PrivateKey key,
            String password) throws IOException, OperatorCreationException {
        StringWriter stringWriter = new StringWriter();
        JcaPEMWriter pemWriter = new JcaPEMWriter(stringWriter);
        OutputEncryptor encryptor = null;
        if (password != null && password.length() > 0) {
            encryptor = new JceOpenSSLPKCS8EncryptorBuilder(PKCSObjectIdentifiers.pbeWithSHAAnd3_KeyTripleDES_CBC)
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .setRandom(PRNG)
                    .setPasssword(password.toCharArray())
                    .build();
        }
        pemWriter.writeObject(new JcaPKCS8Generator(key, encryptor));
        pemWriter.close();
        return stringWriter.toString();
    }

    /**
     * PEM-encodes the given X509 certificate (compatible with OpenSSL) and returns the result as a String.
     * @param cert the certificate.
     * @return a String containing the PEM encoding of the certificate.
     * @throws IOException if converting the certificate to PEM format fails.
     */
    public static String pemEncodeX509Certificate(X509Certificate cert) throws IOException {
        StringWriter stringWriter = new StringWriter();
        JcaPEMWriter pemWriter = new JcaPEMWriter(stringWriter);
        pemWriter.writeObject(cert);
        pemWriter.close();
        return stringWriter.toString();
    }

    /**
     * Encodes the given X509Certificate as a JKS TrustStore, optionally protecting the cert with a password (though
     * it's unclear why one would do this since certificates only contain public information and do not need to be
     * kept secret). Returns the byte array encoding of the trust store, which may be written to a file and loaded to
     * instantiate the trust store at a later point or in another process.
     * @param cert the certificate to serialize.
     * @param keyPassword an optional password to encrypt the trust store. If empty or null, the cert will not be encrypted.
     * @return the serialized bytes of the JKS trust store.
     * @throws IOException
     * @throws GeneralSecurityException
     */
    public static byte[] certToJavaTrustStoreBytes(
            X509Certificate cert,
            String keyPassword) throws IOException, GeneralSecurityException {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        return certToTrustStoreBytes(cert, keyPassword, trustStore);
    }

    /**
     * Encodes the given X509Certificate as a PKCS12 TrustStore, optionally protecting the cert with a password (though
     * it's unclear why one would do this since certificates only contain public information and do not need to be
     * kept secret). Returns the byte array encoding of the trust store, which may be written to a file and loaded to
     * instantiate the trust store at a later point or in another process.
     * @param cert the certificate to serialize.
     * @param keyPassword an optional password to encrypt the trust store. If empty or null, the cert will not be encrypted.
     * @return the serialized bytes of the PKCS12 trust store.
     * @throws IOException
     * @throws GeneralSecurityException
     */
    public static byte[] certToPKCS12TrustStoreBytes(
            X509Certificate cert,
            String keyPassword) throws IOException, GeneralSecurityException {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        return certToTrustStoreBytes(cert, keyPassword, trustStore);
    }

    private static byte[] certToTrustStoreBytes(X509Certificate cert,
                                                String keyPassword,
                                                KeyStore trustStore) throws IOException, GeneralSecurityException {
        char[] keyPasswordChars = keyPassword == null ? new char[0] : keyPassword.toCharArray();
        trustStore.load(null, keyPasswordChars);
        trustStore.setCertificateEntry(cert.getSubjectDN().toString(), cert);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        trustStore.store(outputStream, keyPasswordChars);
        outputStream.flush();
        byte[] result = outputStream.toByteArray();
        outputStream.close();
        return result;
    }

    /**
     * Encodes the given X509Certificate and private key as a JKS KeyStore, optionally protecting the private key
     * (and possibly the cert?) with a password. Returns the byte array encoding of the key store, which may be written
     * to a file and loaded to instantiate the key store at a later point or in another process.
     * @param cert the X509 certificate to serialize.
     * @param privateKey the private key to serialize.
     * @param keyPassword an optional key password. If empty or null, the private key will not be encrypted.
     * @return the serialized bytes of the JKS key store.
     * @throws IOException
     * @throws GeneralSecurityException
     */
    public static byte[] certAndPrivateKeyToJavaKeyStoreBytes(
            X509Certificate cert,
            PrivateKey privateKey,
            String keyPassword) throws IOException, GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        return certAndPrivateKeyToPKCS12Bytes(cert, privateKey, keyPassword, keyStore);
    }

    /**
     * Encodes the given X509Certificate and private key as a PKCS12 KeyStore, optionally protecting the private key
     * (and possibly the cert?) with a password. Returns the byte array encoding of the key store, which may be written
     * to a file and loaded to instantiate the key store at a later point or in another process.
     * @param cert the X509 certificate to serialize.
     * @param privateKey the private key to serialize.
     * @param keyPassword an optional key password. If empty or null, the private key will not be encrypted.
     * @return the serialized bytes of the PKCS12 key store.
     * @throws IOException
     * @throws GeneralSecurityException
     */
    public static byte[] certAndPrivateKeyToPKCS12Bytes(
            X509Certificate cert,
            PrivateKey privateKey,
            String keyPassword) throws IOException, GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        return certAndPrivateKeyToPKCS12Bytes(cert, privateKey, keyPassword, keyStore);
    }

    private static byte[] certAndPrivateKeyToPKCS12Bytes(
            X509Certificate cert,
            PrivateKey privateKey,
            String keyPassword,
            KeyStore keyStore) throws IOException, GeneralSecurityException {
        char[] keyPasswordChars = keyPassword == null ? new char[0] : keyPassword.toCharArray();
        keyStore.load(null, keyPasswordChars);
        keyStore.setKeyEntry(
                "key",
                privateKey,
                keyPasswordChars,
                new Certificate[] { cert });
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        keyStore.store(outputStream, keyPasswordChars);
        outputStream.flush();
        byte[] result = outputStream.toByteArray();
        outputStream.close();
        return result;
    }

    /**
     * Convenience method to convert a bouncycastle X509CertificateHolder to a java X509Certificate.
     * @param certHolder a bouncycastle X509CertificateHolder.
     * @return a java X509Certificate
     * @throws CertificateException if the conversion fails.
     */
    public static X509Certificate toX509Cert(X509CertificateHolder certHolder) throws CertificateException {
        return new JcaX509CertificateConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME).getCertificate(certHolder);
    }
}
