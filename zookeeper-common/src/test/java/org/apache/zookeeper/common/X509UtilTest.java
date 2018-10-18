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

import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.Date;
import java.util.Random;

import static org.apache.zookeeper.test.ClientBase.createTmpDir;

public class X509UtilTest extends ZKTestCase {

    private static final char[] PASSWORD = "password".toCharArray();
    private X509Certificate rootCertificate;

    private String truststorePath;
    private String keystorePath;
    private static KeyPair rootKeyPair;

    private X509Util x509Util;
    private String[] customCipherSuites = new String[]{"SSL_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA", "SSL_DH_anon_EXPORT_WITH_DES40_CBC_SHA"};

    @BeforeClass
    public static void createKeyPair() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
        keyPairGenerator.initialize(4096);
        rootKeyPair = keyPairGenerator.genKeyPair();
    }

    @AfterClass
    public static void removeBouncyCastleProvider() throws Exception {
        Security.removeProvider("BC");
    }

    @Before
    public void setUp() throws Exception {
        rootCertificate = createSelfSignedCertifcate(rootKeyPair);

        String tmpDir = createTmpDir().getAbsolutePath();
        truststorePath = tmpDir + "/truststore.jks";
        keystorePath = tmpDir + "/keystore.jks";

        x509Util = new ClientX509Util();

        writeKeystore(rootCertificate, rootKeyPair, keystorePath);

        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, "org.apache.zookeeper.server.NettyServerCnxnFactory");
        System.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET, "org.apache.zookeeper.ClientCnxnSocketNetty");
        System.setProperty(x509Util.getSslKeystoreLocationProperty(), keystorePath);
        System.setProperty(x509Util.getSslKeystorePasswdProperty(), new String(PASSWORD));
        System.setProperty(x509Util.getSslTruststoreLocationProperty(), truststorePath);
        System.setProperty(x509Util.getSslTruststorePasswdProperty(), new String(PASSWORD));
        System.setProperty(x509Util.getSslHostnameVerificationEnabledProperty(), "false");

        writeTrustStore(PASSWORD);
    }

    private void writeKeystore(X509Certificate certificate, KeyPair keyPair, String path) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, PASSWORD);
        keyStore.setKeyEntry("alias", keyPair.getPrivate(), PASSWORD, new Certificate[] { certificate });
        FileOutputStream outputStream = new FileOutputStream(path);
        keyStore.store(outputStream, PASSWORD);
        outputStream.flush();
        outputStream.close();
    }

    private void writeTrustStore(char[] password) throws Exception {
        KeyStore trustStore = KeyStore.getInstance("JKS");
        trustStore.load(null, password);
        trustStore.setCertificateEntry(rootCertificate.getSubjectDN().toString(), rootCertificate);
        FileOutputStream outputStream = new FileOutputStream(truststorePath);
        if (password == null) {
            trustStore.store(outputStream, new char[0]);
        } else {
            trustStore.store(outputStream, password);
        }
        outputStream.flush();
        outputStream.close();
    }

    private X509Certificate createSelfSignedCertifcate(KeyPair keyPair) throws Exception {
        X500NameBuilder nameBuilder = new X500NameBuilder(BCStyle.INSTANCE);
        nameBuilder.addRDN(BCStyle.CN, "localhost");
        Date notBefore = new Date();
        Calendar cal = Calendar.getInstance();
        cal.setTime(notBefore);
        cal.add(Calendar.YEAR, 1);
        Date notAfter = cal.getTime();
        BigInteger serialNumber = new BigInteger(128, new Random());

        X509v3CertificateBuilder certificateBuilder =
                new JcaX509v3CertificateBuilder(nameBuilder.build(), serialNumber, notBefore, notAfter, nameBuilder.build(), keyPair.getPublic())
                        .addExtension(Extension.basicConstraints, true, new BasicConstraints(0))
                        .addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyCertSign | KeyUsage.cRLSign));

        ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256WithRSAEncryption").build(keyPair.getPrivate());

        return new JcaX509CertificateConverter().getCertificate(certificateBuilder.build(contentSigner));
    }

    @After
    public void cleanUp() throws Exception {
        System.clearProperty(x509Util.getSslKeystoreLocationProperty());
        System.clearProperty(x509Util.getSslKeystorePasswdProperty());
        System.clearProperty(x509Util.getSslTruststoreLocationProperty());
        System.clearProperty(x509Util.getSslTruststorePasswdProperty());
        System.clearProperty(x509Util.getSslHostnameVerificationEnabledProperty());
        System.clearProperty(x509Util.getSslOcspEnabledProperty());
        System.clearProperty(x509Util.getSslCrlEnabledProperty());
        System.clearProperty(x509Util.getCipherSuitesProperty());
        System.clearProperty("com.sun.net.ssl.checkRevocation");
        System.clearProperty("com.sun.security.enableCRLDP");
        Security.setProperty("com.sun.security.enableCRLDP", "false");
    }

    @Test(timeout = 5000)
    public void testCreateSSLContextWithoutCustomProtocol() throws Exception {
        SSLContext sslContext = x509Util.getDefaultSSLContext();
        Assert.assertEquals(X509Util.DEFAULT_PROTOCOL, sslContext.getProtocol());
    }

    @Test(timeout = 5000)
    public void testCreateSSLContextWithCustomProtocol() throws Exception {
        final String protocol = "TLSv1.1";
        System.setProperty(x509Util.getSslProtocolProperty(), protocol);
        SSLContext sslContext = x509Util.getDefaultSSLContext();
        Assert.assertEquals(protocol, sslContext.getProtocol());
    }

    @Test(timeout = 5000)
    public void testCreateSSLContextWithoutTrustStorePassword() throws Exception {
        writeTrustStore(null);
        System.clearProperty(x509Util.getSslTruststorePasswdProperty());
        x509Util.getDefaultSSLContext();
    }

    @Test(timeout = 5000, expected = X509Exception.SSLContextException.class)
    public void testCreateSSLContextWithoutKeyStoreLocation() throws Exception {
        System.clearProperty(x509Util.getSslKeystoreLocationProperty());
        x509Util.getDefaultSSLContext();
    }

    @Test(timeout = 5000, expected = X509Exception.SSLContextException.class)
    public void testCreateSSLContextWithoutKeyStorePassword() throws Exception {
        System.clearProperty(x509Util.getSslKeystorePasswdProperty());
        x509Util.getDefaultSSLContext();
    }

    @Test(timeout = 5000)
    public void testCreateSSLContextWithCustomCipherSuites() throws Exception {
        setCustomCipherSuites();
        SSLSocket sslSocket = x509Util.createSSLSocket();
        Assert.assertArrayEquals(customCipherSuites, sslSocket.getEnabledCipherSuites());
    }

    // It would be great to test the value of PKIXBuilderParameters#setRevocationEnabled but it does not appear to be
    // possible
    @Test(timeout = 5000)
    public void testCRLEnabled() throws Exception {
        System.setProperty(x509Util.getSslCrlEnabledProperty(), "true");
        x509Util.getDefaultSSLContext();
        Assert.assertTrue(Boolean.valueOf(System.getProperty("com.sun.net.ssl.checkRevocation")));
        Assert.assertTrue(Boolean.valueOf(System.getProperty("com.sun.security.enableCRLDP")));
        Assert.assertFalse(Boolean.valueOf(Security.getProperty("ocsp.enable")));
    }

    @Test(timeout = 5000)
    public void testCRLDisabled() throws Exception {
        x509Util.getDefaultSSLContext();
        Assert.assertFalse(Boolean.valueOf(System.getProperty("com.sun.net.ssl.checkRevocation")));
        Assert.assertFalse(Boolean.valueOf(System.getProperty("com.sun.security.enableCRLDP")));
        Assert.assertFalse(Boolean.valueOf(Security.getProperty("ocsp.enable")));
    }

    @Test(timeout = 5000)
    public void testOCSPEnabled() throws Exception {
        System.setProperty(x509Util.getSslOcspEnabledProperty(), "true");
        x509Util.getDefaultSSLContext();
        Assert.assertTrue(Boolean.valueOf(System.getProperty("com.sun.net.ssl.checkRevocation")));
        Assert.assertTrue(Boolean.valueOf(System.getProperty("com.sun.security.enableCRLDP")));
        Assert.assertTrue(Boolean.valueOf(Security.getProperty("ocsp.enable")));
    }

    @Test(timeout = 5000)
    public void testCreateSSLSocket() throws Exception {
        setCustomCipherSuites();
        SSLSocket sslSocket = x509Util.createSSLSocket();
        Assert.assertArrayEquals(customCipherSuites, sslSocket.getEnabledCipherSuites());
    }

    @Test(timeout = 5000)
    public void testCreateSSLServerSocketWithoutPort() throws Exception {
        setCustomCipherSuites();
        SSLServerSocket sslServerSocket = x509Util.createSSLServerSocket();
        Assert.assertArrayEquals(customCipherSuites, sslServerSocket.getEnabledCipherSuites());
        Assert.assertTrue(sslServerSocket.getNeedClientAuth());
    }

    @Test(timeout = 5000)
    public void testCreateSSLServerSocketWithPort() throws Exception {
        int port = PortAssignment.unique();
        setCustomCipherSuites();
        SSLServerSocket sslServerSocket = x509Util.createSSLServerSocket(port);
        Assert.assertEquals(sslServerSocket.getLocalPort(), port);
        Assert.assertArrayEquals(customCipherSuites, sslServerSocket.getEnabledCipherSuites());
        Assert.assertTrue(sslServerSocket.getNeedClientAuth());
    }

    // Warning: this will reset the x509Util
    private void setCustomCipherSuites() {
        System.setProperty(x509Util.getCipherSuitesProperty(), customCipherSuites[0] + "," + customCipherSuites[1]);
        x509Util = new ClientX509Util();
    }
}
