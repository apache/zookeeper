/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.zookeeper.test;

import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.QuorumX509Util;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.CRLNumber;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CRLHolder;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509ExtensionUtils;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.bc.BcX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.crypto.util.PublicKeyFactory;
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.MiscPEMGenerator;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemWriter;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.apache.zookeeper.test.ClientBase.createTmpDir;

public class QuorumSSLTest extends QuorumPeerTestBase {

    private static final String SSL_QUORUM_ENABLED = "sslQuorum=true\n";
    private static final String PORT_UNIFICATION_ENABLED = "portUnification=true\n";
    private static final String PORT_UNIFICATION_DISABLED = "portUnification=false\n";

    private QuorumX509Util quorumX509Util = new QuorumX509Util();

    private int clientPortQp1;
    private int clientPortQp2;
    private int clientPortQp3;

    private String quorumConfiguration;
    private String validKeystorePath;
    private String badhostnameKeystorePath;
    private String revokedKeystorePath;
    private String truststorePath;
    private String crlPath;

    private KeyPair rootKeyPair;
    private X509Certificate rootCertificate;

    @Before
    public void setup() throws Exception {
        ClientBase.setupTestEnv();

        clientPortQp1 = PortAssignment.unique();
        clientPortQp2 = PortAssignment.unique();
        clientPortQp3 = PortAssignment.unique();

        File tmpDir = createTmpDir();
        validKeystorePath = tmpDir.getAbsolutePath() + "/valid.jks";
        truststorePath = tmpDir.getAbsolutePath() + "/truststore.jks";
        crlPath = tmpDir.getAbsolutePath() + "/crl.pem";

        quorumConfiguration = generateQuorumConfiguration();


        Security.addProvider(new BouncyCastleProvider());

        rootKeyPair = createKeyPair("RSA", 4096);
        rootCertificate = createSelfSignedCertifcate(rootKeyPair);

        KeyPair entityKeyPair = createKeyPair("RSA", 4096);
        X509Certificate entityCertificate = buildEndEntityCert(entityKeyPair, rootCertificate, rootKeyPair.getPrivate(), "localhost");

        KeyStore outStore = KeyStore.getInstance(KeyStore.getDefaultType());
        outStore.load(null, "testpass".toCharArray());
        outStore.setKeyEntry("mykey", entityKeyPair.getPrivate(), "testpass".toCharArray(), new Certificate[] { entityCertificate });
        FileOutputStream outputStream = new FileOutputStream(validKeystorePath);
        outStore.store(outputStream, "testpass".toCharArray());
        outputStream.flush();
        outputStream.close();


        badhostnameKeystorePath = tmpDir + "/badhost.jks";
        X509Certificate badHostCert = buildEndEntityCert(entityKeyPair, rootCertificate, rootKeyPair.getPrivate(), "bleepbloop");
        outStore = KeyStore.getInstance(KeyStore.getDefaultType());
        outStore.load(null, "testpass".toCharArray());
        outStore.setKeyEntry("mykey", entityKeyPair.getPrivate(), "testpass".toCharArray(), new Certificate[] { badHostCert });
        outputStream = new FileOutputStream(badhostnameKeystorePath);
        outStore.store(outputStream, "testpass".toCharArray());
        outputStream.flush();
        outputStream.close();

        revokedKeystorePath = tmpDir + "/revoked.jks";
        X509Certificate revokedCert = buildEndEntityCert(entityKeyPair, rootCertificate, rootKeyPair.getPrivate(), "localhost");
        outStore = KeyStore.getInstance(KeyStore.getDefaultType());
        outStore.load(null, "testpass".toCharArray());
        outStore.setKeyEntry("mykey", entityKeyPair.getPrivate(), "testpass".toCharArray(), new Certificate[] { revokedCert });
        outputStream = new FileOutputStream(revokedKeystorePath);
        outStore.store(outputStream, "testpass".toCharArray());
        outputStream.flush();
        outputStream.close();
        buildCRL(revokedCert);

        outStore = KeyStore.getInstance(KeyStore.getDefaultType());
        outStore.load(null, "testpass".toCharArray());
        outStore.setCertificateEntry(rootCertificate.getSubjectDN().toString(), rootCertificate);
        outputStream = new FileOutputStream(truststorePath);
        outStore.store(outputStream, "testpass".toCharArray());
        outputStream.flush();
        outputStream.close();

    }

    private X509Certificate createSelfSignedCertifcate(KeyPair keyPair) throws Exception {
        X500NameBuilder nameBuilder = new X500NameBuilder(BCStyle.INSTANCE);
        nameBuilder.addRDN(BCStyle.CN, "localhost");
        Date notBefore = new Date();              // time from which certificate is valid
        Calendar cal = Calendar.getInstance();
        cal.setTime(notBefore);
        cal.add(Calendar.YEAR, 1);
        Date notAfter = cal.getTime();
        BigInteger serialNumber = new BigInteger(128, new Random());

        X509v3CertificateBuilder certificateBuilder =
                new JcaX509v3CertificateBuilder(nameBuilder.build(), serialNumber, notBefore, notAfter, nameBuilder.build(), keyPair.getPublic());
        certificateBuilder
                .addExtension(Extension.basicConstraints, true, new BasicConstraints(0))
                .addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyCertSign | KeyUsage.cRLSign));

        ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256WithRSAEncryption").build(keyPair.getPrivate());


        return new JcaX509CertificateConverter().getCertificate(certificateBuilder.build(contentSigner));
    }

    private void buildCRL(X509Certificate x509Certificate) throws Exception {
        X509v2CRLBuilder builder = new JcaX509v2CRLBuilder(x509Certificate.getIssuerX500Principal(), new Date());
        Date notBefore = new Date();              // time from which certificate is valid
        Calendar cal = Calendar.getInstance();
        cal.setTime(notBefore);
        cal.add(Calendar.YEAR, 1);
        Date notAfter = cal.getTime();
        builder.setNextUpdate(notAfter);
        builder.addCRLEntry(x509Certificate.getSerialNumber(), new Date(), CRLReason.cACompromise);
        builder.addExtension(Extension.authorityKeyIdentifier, false, new JcaX509ExtensionUtils().createAuthorityKeyIdentifier(rootCertificate));
        builder.addExtension(Extension.cRLNumber, false, new CRLNumber(new BigInteger("1000")));

        JcaContentSignerBuilder contentSignerBuilder = new JcaContentSignerBuilder("SHA256WithRSAEncryption");

        X509CRLHolder cRLHolder = builder.build(contentSignerBuilder.build(rootKeyPair.getPrivate()));


        PemWriter pemWriter = new PemWriter(new FileWriter(crlPath));
        pemWriter.writeObject(new MiscPEMGenerator(cRLHolder));
        pemWriter.flush();
        pemWriter.close();
    }

    /**
     * Build a sample V3 certificate to use as an end entity certificate
     */
    public X509Certificate buildEndEntityCert(KeyPair keyPair, X509Certificate caCert, PrivateKey caPrivateKey, String hostname)
            throws Exception
    {
        X509CertificateHolder holder = new JcaX509CertificateHolder(caCert);
        ContentSigner signer =new JcaContentSignerBuilder("SHA256WithRSAEncryption").build(caPrivateKey);


        SubjectPublicKeyInfo entityKeyInfo =
                SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(PublicKeyFactory.createKey(keyPair.getPublic().getEncoded()));
        X509v3CertificateBuilder certBldr = new JcaX509v3CertificateBuilder(
                holder.getSubject(),
                new BigInteger(128, new Random()),
                new Date(System.currentTimeMillis()),
                new Date(System.currentTimeMillis() + 100000),
                new X500Name("CN=Test End Entity Certificate"),
                keyPair.getPublic());
        X509ExtensionUtils extUtils = new BcX509ExtensionUtils();
        certBldr.addExtension(Extension.authorityKeyIdentifier,
                false, extUtils.createAuthorityKeyIdentifier(holder))
                .addExtension(Extension.subjectKeyIdentifier,
                        false, extUtils.createSubjectKeyIdentifier(entityKeyInfo))
                .addExtension(Extension.basicConstraints,
                        true, new BasicConstraints(false))
                .addExtension(Extension.keyUsage,
                        true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment))
                .addExtension(Extension.subjectAlternativeName,  true,  new GeneralNames(new GeneralName(GeneralName.dNSName, hostname)));

        DistributionPointName distPointOne = new DistributionPointName(new GeneralNames(
                new GeneralName(GeneralName.uniformResourceIdentifier,"file://" + crlPath)));


        certBldr.addExtension(Extension.cRLDistributionPoints, false, new CRLDistPoint(new DistributionPoint[] { new DistributionPoint(distPointOne, null, null) }));
        return new JcaX509CertificateConverter().getCertificate(certBldr.build(signer));
    }


        private KeyPair createKeyPair(String encryptionType, int byteCount)
            throws NoSuchProviderException, NoSuchAlgorithmException
    {
        KeyPairGenerator keyPairGenerator = createKeyPairGenerator(encryptionType, byteCount);
        KeyPair keyPair = keyPairGenerator.genKeyPair();
        return keyPair;
    }


    private KeyPairGenerator createKeyPairGenerator(String algorithmIdentifier,
                                                    int bitCount) throws NoSuchProviderException,
            NoSuchAlgorithmException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                algorithmIdentifier, BouncyCastleProvider.PROVIDER_NAME);
        kpg.initialize(bitCount);
        return kpg;
    }

    private String generateQuorumConfiguration() {
        int portQp1 = PortAssignment.unique();
        int portQp2 = PortAssignment.unique();
        int portQp3 = PortAssignment.unique();

        int portLe1 = PortAssignment.unique();
        int portLe2 = PortAssignment.unique();
        int portLe3 = PortAssignment.unique();



        return "server.1=127.0.0.1:" + (portQp1) + ":" + (portLe1) + ";" +  clientPortQp1 + "\n" +
               "server.2=127.0.0.1:" + (portQp2) + ":" + (portLe2) + ";" + clientPortQp2 + "\n" +
               "server.3=127.0.0.1:" + (portQp3) + ":" + (portLe3) + ";" + clientPortQp3;
    }


    public void setSSLSystemProperties() {
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, "org.apache.zookeeper.server.NettyServerCnxnFactory");
        System.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET, "org.apache.zookeeper.ClientCnxnSocketNetty");
        System.setProperty(quorumX509Util.getSslKeystoreLocationProperty(), validKeystorePath);
        System.setProperty(quorumX509Util.getSslKeystorePasswdProperty(), "testpass");
        System.setProperty(quorumX509Util.getSslTruststoreLocationProperty(), truststorePath);
        System.setProperty(quorumX509Util.getSslTruststorePasswdProperty(), "testpass");
        System.setProperty(quorumX509Util.getSslHostnameVerificationEnabledProperty(), "false");
    }

    @After
    public void clearSSLSystemProperties() {
        System.clearProperty(quorumX509Util.getSslKeystoreLocationProperty());
        System.clearProperty(quorumX509Util.getSslKeystorePasswdProperty());
        System.clearProperty(quorumX509Util.getSslTruststoreLocationProperty());
        System.clearProperty(quorumX509Util.getSslTruststorePasswdProperty());
        System.clearProperty(quorumX509Util.getSslHostnameVerificationEnabledProperty());
    }

    @Test
    public void testQuorumSSL() throws Exception {
        setSSLSystemProperties();

        MainThread q1 = new MainThread(1, clientPortQp1, quorumConfiguration, SSL_QUORUM_ENABLED);
        MainThread q2 = new MainThread(2, clientPortQp2, quorumConfiguration, SSL_QUORUM_ENABLED);


        q1.start();
        q2.start();

        Assert.assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp1, CONNECTION_TIMEOUT));
        Assert.assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp2, CONNECTION_TIMEOUT));

        clearSSLSystemProperties();

        // This server should fail to join the quorum as it is not using ssl.
        MainThread q3 = new MainThread(3, clientPortQp3, quorumConfiguration);
        q3.start();

        Assert.assertFalse(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp3, CONNECTION_TIMEOUT));
    }



    @Test
    public void testRollingUpgrade() throws Exception {
        // Form a quorum without ssl
        Map<Integer, MainThread> members = new HashMap<>();
        members.put(clientPortQp1, new MainThread(1, clientPortQp1, quorumConfiguration));
        members.put(clientPortQp2, new MainThread(2, clientPortQp2, quorumConfiguration));
        members.put(clientPortQp3, new MainThread(3, clientPortQp3, quorumConfiguration));

        for (MainThread member : members.values()) {
            member.start();
        }

        for (int clientPort : members.keySet()) {
            Assert.assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPort, CONNECTION_TIMEOUT));
        }

        // Set SSL system properties and port unification, begin restarting servers
        setSSLSystemProperties();

        stopAppendConfigRestartAll(members, PORT_UNIFICATION_ENABLED);
        stopAppendConfigRestartAll(members, SSL_QUORUM_ENABLED);
        // TODO: Is appending a new line to config a fair assumption
        stopAppendConfigRestartAll(members, PORT_UNIFICATION_DISABLED);
    }

    private void stopAppendConfigRestartAll(Map<Integer, MainThread> members, String config) throws Exception {
        for (Map.Entry<Integer, MainThread> entry : members.entrySet()) {
            int clientPort = entry.getKey();
            MainThread member = entry.getValue();

            member.shutdown();
            Assert.assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + clientPort, CONNECTION_TIMEOUT));

            FileWriter fileWriter = new FileWriter(member.getConfFile(), true);
            fileWriter.write(config);
            fileWriter.flush();
            fileWriter.close();

            member.start();

            Assert.assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPort, CONNECTION_TIMEOUT));
        }
    }

    @Test
    public void testHostnameVerification() throws Exception {
        setSSLSystemProperties();

        MainThread q1 = new MainThread(1, clientPortQp1, quorumConfiguration, SSL_QUORUM_ENABLED);
        MainThread q2 = new MainThread(2, clientPortQp2, quorumConfiguration, SSL_QUORUM_ENABLED);

        q1.start();
        q2.start();

        Assert.assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp1, CONNECTION_TIMEOUT));
        Assert.assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp2, CONNECTION_TIMEOUT));

        System.setProperty(quorumX509Util.getSslKeystoreLocationProperty(), badhostnameKeystorePath);

        // This server should join successfully
        MainThread q3 = new MainThread(3, clientPortQp3, quorumConfiguration, SSL_QUORUM_ENABLED);
        q3.start();

        Assert.assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp3, CONNECTION_TIMEOUT));


        q1.shutdown();
        q2.shutdown();
        q3.shutdown();

        Assert.assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + clientPortQp1, CONNECTION_TIMEOUT));
        Assert.assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + clientPortQp2, CONNECTION_TIMEOUT));
        Assert.assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + clientPortQp3, CONNECTION_TIMEOUT));

        setSSLSystemProperties();
        System.setProperty(quorumX509Util.getSslHostnameVerificationEnabledProperty(), "true");

        q1.start();
        q2.start();

        Assert.assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp1, CONNECTION_TIMEOUT));
        Assert.assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp2, CONNECTION_TIMEOUT));

        System.setProperty(quorumX509Util.getSslKeystoreLocationProperty(), badhostnameKeystorePath);
        q3.start();

        Assert.assertFalse(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp3, CONNECTION_TIMEOUT));
    }


    @Test
    public void testCertificateRevocabtionList() throws Exception {
        System.setProperty("javax.net.debug", "all");
        System.setProperty("java.security.auth.debug", "all");
        System.setProperty("java.security.debug", "all");
        setSSLSystemProperties();

        MainThread q1 = new MainThread(1, clientPortQp1, quorumConfiguration, SSL_QUORUM_ENABLED);
        MainThread q2 = new MainThread(2, clientPortQp2, quorumConfiguration, SSL_QUORUM_ENABLED);

        q1.start();
        q2.start();

        Assert.assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp1, CONNECTION_TIMEOUT));
        Assert.assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp2, CONNECTION_TIMEOUT));

        System.setProperty(quorumX509Util.getSslKeystoreLocationProperty(), revokedKeystorePath);

        // This server should join successfully
        MainThread q3 = new MainThread(3, clientPortQp3, quorumConfiguration, SSL_QUORUM_ENABLED);
        q3.start();

        Assert.assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp3, CONNECTION_TIMEOUT));


        q1.shutdown();
        q2.shutdown();
        q3.shutdown();

        Assert.assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + clientPortQp1, CONNECTION_TIMEOUT));
        Assert.assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + clientPortQp2, CONNECTION_TIMEOUT));
        Assert.assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + clientPortQp3, CONNECTION_TIMEOUT));

        setSSLSystemProperties();
        System.setProperty(quorumX509Util.getSslCrlEnabledProperty(), "true");
        System.setProperty("com.sun.net.ssl.checkRevocation", "true");
        System.setProperty("com.sun.security.enableCRLDP", "true");

        q1.start();
        q2.start();

        Assert.assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp1, CONNECTION_TIMEOUT));
        Assert.assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp2, CONNECTION_TIMEOUT));

        System.setProperty(quorumX509Util.getSslKeystoreLocationProperty(), revokedKeystorePath);
        q3.start();

        Assert.assertFalse(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp3, CONNECTION_TIMEOUT));



    }

    // ocsp

}
