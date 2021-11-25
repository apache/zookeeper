/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.quorum;

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.apache.zookeeper.test.ClientBase.createTmpDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLServerSocketFactory;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.QuorumX509Util;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.test.ClientBase;
import org.bouncycastle.asn1.ocsp.OCSPResponse;
import org.bouncycastle.asn1.ocsp.OCSPResponseStatus;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
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
import org.bouncycastle.asn1.x509.X509ObjectIdentifiers;
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
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.BasicOCSPRespBuilder;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPException;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.OCSPRespBuilder;
import org.bouncycastle.cert.ocsp.Req;
import org.bouncycastle.cert.ocsp.UnknownStatus;
import org.bouncycastle.cert.ocsp.jcajce.JcaBasicOCSPRespBuilder;
import org.bouncycastle.cert.ocsp.jcajce.JcaCertificateID;
import org.bouncycastle.crypto.util.PublicKeyFactory;
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.MiscPEMGenerator;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.OperatorException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.util.io.pem.PemWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class QuorumSSLTest extends QuorumPeerTestBase {

    private static final String SSL_QUORUM_ENABLED = "sslQuorum=true\n";
    private static final String PORT_UNIFICATION_ENABLED = "portUnification=true\n";
    private static final String PORT_UNIFICATION_DISABLED = "portUnification=false\n";

    private static final char[] PASSWORD = "testpass".toCharArray();
    private static final String HOSTNAME = "localhost";

    private QuorumX509Util quorumX509Util;

    private MainThread q1;
    private MainThread q2;
    private MainThread q3;

    private int clientPortQp1;
    private int clientPortQp2;
    private int clientPortQp3;

    private String tmpDir;

    private String quorumConfiguration;
    private String validKeystorePath;
    private String truststorePath;

    private KeyPair rootKeyPair;
    private X509Certificate rootCertificate;

    private KeyPair defaultKeyPair;

    private ContentSigner contentSigner;

    private Date certStartTime;
    private Date certEndTime;

    @BeforeEach
    public void setup() throws Exception {
        quorumX509Util = new QuorumX509Util();
        ClientBase.setupTestEnv();

        tmpDir = createTmpDir().getAbsolutePath();

        clientPortQp1 = PortAssignment.unique();
        clientPortQp2 = PortAssignment.unique();
        clientPortQp3 = PortAssignment.unique();

        validKeystorePath = tmpDir + "/valid.jks";
        truststorePath = tmpDir + "/truststore.jks";

        quorumConfiguration = generateQuorumConfiguration();

        Security.addProvider(new BouncyCastleProvider());

        certStartTime = new Date();
        Calendar cal = Calendar.getInstance();
        cal.setTime(certStartTime);
        cal.add(Calendar.YEAR, 1);
        certEndTime = cal.getTime();

        rootKeyPair = createKeyPair();
        contentSigner = new JcaContentSignerBuilder("SHA256WithRSAEncryption").build(rootKeyPair.getPrivate());
        rootCertificate = createSelfSignedCertifcate(rootKeyPair);

        // Write the truststore
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, PASSWORD);
        trustStore.setCertificateEntry(rootCertificate.getSubjectDN().toString(), rootCertificate);
        FileOutputStream outputStream = new FileOutputStream(truststorePath);
        trustStore.store(outputStream, PASSWORD);
        outputStream.flush();
        outputStream.close();

        defaultKeyPair = createKeyPair();
        X509Certificate validCertificate = buildEndEntityCert(
            defaultKeyPair,
            rootCertificate,
            rootKeyPair.getPrivate(),
            HOSTNAME,
            "127.0.0.1",
            null,
            null);
        writeKeystore(validCertificate, defaultKeyPair, validKeystorePath);

        setSSLSystemProperties();
    }

    private void writeKeystore(X509Certificate certificate, KeyPair entityKeyPair, String path) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, PASSWORD);
        keyStore.setKeyEntry("alias", entityKeyPair.getPrivate(), PASSWORD, new Certificate[]{certificate});
        FileOutputStream outputStream = new FileOutputStream(path);
        keyStore.store(outputStream, PASSWORD);
        outputStream.flush();
        outputStream.close();
    }

    private class OCSPHandler implements HttpHandler {

        private X509Certificate revokedCert;

        // Builds an OCSPHandler that responds with a good status for all certificates
        // except revokedCert.
        public OCSPHandler(X509Certificate revokedCert) {
            this.revokedCert = revokedCert;
        }

        @Override
        public void handle(com.sun.net.httpserver.HttpExchange httpExchange) throws IOException {
            byte[] responseBytes;
            try {
                String uri = httpExchange.getRequestURI().toString();
                LOG.info("OCSP request: {} {}", httpExchange.getRequestMethod(), uri);
                httpExchange.getRequestHeaders().entrySet().forEach((e) -> {
                    LOG.info("OCSP request header: {} {}", e.getKey(), e.getValue());
                });
                InputStream request = httpExchange.getRequestBody();
                byte[] requestBytes = new byte[10000];
                int len = request.read(requestBytes);
                LOG.info("OCSP request size {}", len);

                if (len < 0) {
                    String removedUriEncoding = URLDecoder.decode(uri.substring(1), "utf-8");
                    LOG.info("OCSP request from URI no encoding {}", removedUriEncoding);
                    requestBytes = Base64.getDecoder().decode(removedUriEncoding);
                }
                OCSPReq ocspRequest = new OCSPReq(requestBytes);
                Req[] requestList = ocspRequest.getRequestList();
                LOG.info("requestList {}", Arrays.toString(requestList));

                DigestCalculator digestCalculator = new JcaDigestCalculatorProviderBuilder().build().get(CertificateID.HASH_SHA1);

                BasicOCSPRespBuilder responseBuilder = new JcaBasicOCSPRespBuilder(rootKeyPair.getPublic(), digestCalculator);
                for (Req req : requestList) {
                    CertificateID certId = req.getCertID();
                    CertificateID revokedCertId = new JcaCertificateID(digestCalculator, rootCertificate, revokedCert.getSerialNumber());
                    CertificateStatus certificateStatus;
                    if (revokedCertId.equals(certId)) {
                        certificateStatus = new UnknownStatus();
                    } else {
                        certificateStatus = CertificateStatus.GOOD;
                    }
                    LOG.info("addResponse {} {}", certId, certificateStatus);
                    responseBuilder.addResponse(certId, certificateStatus, null);
                }

                X509CertificateHolder[] chain = new X509CertificateHolder[]{new JcaX509CertificateHolder(rootCertificate)};
                ContentSigner signer = new JcaContentSignerBuilder("SHA1withRSA").setProvider("BC").build(rootKeyPair.getPrivate());
                BasicOCSPResp ocspResponse = responseBuilder.build(signer, chain, Calendar.getInstance().getTime());
                LOG.info("response {}", ocspResponse);
                responseBytes = new OCSPRespBuilder().build(OCSPRespBuilder.SUCCESSFUL, ocspResponse).getEncoded();
                LOG.error("OCSP server response OK");
            } catch (OperatorException | CertificateEncodingException | OCSPException exception) {
                LOG.error("Internal OCSP server error", exception);
                responseBytes = new OCSPResp(new OCSPResponse(new OCSPResponseStatus(OCSPRespBuilder.INTERNAL_ERROR), null)).getEncoded();
            } catch (Throwable exception) {
                LOG.error("Internal OCSP server error", exception);
                responseBytes = new OCSPResp(new OCSPResponse(new OCSPResponseStatus(OCSPRespBuilder.INTERNAL_ERROR), null)).getEncoded();
            }

            Headers rh = httpExchange.getResponseHeaders();
            rh.set("Content-Type", "application/ocsp-response");
            httpExchange.sendResponseHeaders(200, responseBytes.length);

            OutputStream os = httpExchange.getResponseBody();
            os.write(responseBytes);
            os.close();
        }

    }

    private X509Certificate createSelfSignedCertifcate(KeyPair keyPair) throws Exception {
        X500NameBuilder nameBuilder = new X500NameBuilder(BCStyle.INSTANCE);
        nameBuilder.addRDN(BCStyle.CN, HOSTNAME);
        BigInteger serialNumber = new BigInteger(128, new Random());

        JcaX509v3CertificateBuilder jcaX509v3CertificateBuilder = new JcaX509v3CertificateBuilder(
            nameBuilder.build(),
            serialNumber,
            certStartTime,
            certEndTime,
            nameBuilder.build(),
            keyPair.getPublic());
        X509v3CertificateBuilder certificateBuilder = jcaX509v3CertificateBuilder
            .addExtension(Extension.basicConstraints, true, new BasicConstraints(0))
            .addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyCertSign | KeyUsage.cRLSign));

        return new JcaX509CertificateConverter().getCertificate(certificateBuilder.build(contentSigner));
    }

    private void buildCRL(X509Certificate x509Certificate, String crlPath) throws Exception {
        X509v2CRLBuilder builder = new JcaX509v2CRLBuilder(x509Certificate.getIssuerX500Principal(), certStartTime);
        builder.addCRLEntry(x509Certificate.getSerialNumber(), certStartTime, CRLReason.cACompromise);
        builder.setNextUpdate(certEndTime);
        builder.addExtension(Extension.authorityKeyIdentifier, false, new JcaX509ExtensionUtils().createAuthorityKeyIdentifier(rootCertificate));
        builder.addExtension(Extension.cRLNumber, false, new CRLNumber(new BigInteger("1000")));

        X509CRLHolder cRLHolder = builder.build(contentSigner);

        PemWriter pemWriter = new PemWriter(new FileWriter(crlPath));
        pemWriter.writeObject(new MiscPEMGenerator(cRLHolder));
        pemWriter.flush();
        pemWriter.close();
    }

    public X509Certificate buildEndEntityCert(
        KeyPair keyPair,
        X509Certificate caCert,
        PrivateKey caPrivateKey,
        String hostname,
        String ipAddress,
        String crlPath,
        Integer ocspPort) throws Exception {
        X509CertificateHolder holder = new JcaX509CertificateHolder(caCert);
        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSAEncryption").build(caPrivateKey);

        List<GeneralName> generalNames = new ArrayList<>();
        if (hostname != null) {
            generalNames.add(new GeneralName(GeneralName.dNSName, hostname));
        }

        if (ipAddress != null) {
            generalNames.add(new GeneralName(GeneralName.iPAddress, ipAddress));
        }

        SubjectPublicKeyInfo entityKeyInfo = SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(
            PublicKeyFactory.createKey(keyPair.getPublic().getEncoded()));
        X509ExtensionUtils extensionUtils = new BcX509ExtensionUtils();
        JcaX509v3CertificateBuilder jcaX509v3CertificateBuilder = new JcaX509v3CertificateBuilder(
            holder.getSubject(),
            new BigInteger(128, new Random()),
            certStartTime,
            certEndTime,
            new X500Name("CN=Test End Entity Certificate"),
            keyPair.getPublic());
        X509v3CertificateBuilder certificateBuilder = jcaX509v3CertificateBuilder
            .addExtension(Extension.authorityKeyIdentifier, false, extensionUtils.createAuthorityKeyIdentifier(holder))
            .addExtension(Extension.subjectKeyIdentifier, false, extensionUtils.createSubjectKeyIdentifier(entityKeyInfo))
            .addExtension(Extension.basicConstraints, true, new BasicConstraints(false))
            .addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));

        if (!generalNames.isEmpty()) {
            certificateBuilder.addExtension(
                Extension.subjectAlternativeName,
                true,
                new GeneralNames(generalNames.toArray(new GeneralName[]{})));
        }

        if (crlPath != null) {
            DistributionPointName distPointOne = new DistributionPointName(
                new GeneralNames(new GeneralName(GeneralName.uniformResourceIdentifier, "file://" + crlPath)));

            certificateBuilder.addExtension(
                Extension.cRLDistributionPoints,
                false,
                new CRLDistPoint(new DistributionPoint[]{new DistributionPoint(distPointOne, null, null)}));
        }

        if (ocspPort != null) {
            certificateBuilder.addExtension(
                Extension.authorityInfoAccess,
                false,
                new AuthorityInformationAccess(
                    X509ObjectIdentifiers.ocspAccessMethod,
                    new GeneralName(GeneralName.uniformResourceIdentifier, "http://" + hostname + ":" + ocspPort)));
        }

        return new JcaX509CertificateConverter().getCertificate(certificateBuilder.build(signer));
    }

    private KeyPair createKeyPair() throws NoSuchProviderException, NoSuchAlgorithmException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
        keyPairGenerator.initialize(4096);
        KeyPair keyPair = keyPairGenerator.genKeyPair();
        return keyPair;
    }

    private String generateQuorumConfiguration() {
        StringBuilder sb = new StringBuilder();

        int portQp1 = PortAssignment.unique();
        int portQp2 = PortAssignment.unique();
        int portQp3 = PortAssignment.unique();

        int portLe1 = PortAssignment.unique();
        int portLe2 = PortAssignment.unique();
        int portLe3 = PortAssignment.unique();

        sb.append(String.format("server.1=127.0.0.1:%d:%d;%d\n", portQp1, portLe1, clientPortQp1));
        sb.append(String.format("server.2=127.0.0.1:%d:%d;%d\n", portQp2, portLe2, clientPortQp2));
        sb.append(String.format("server.3=127.0.0.1:%d:%d;%d\n", portQp3, portLe3, clientPortQp3));

        return sb.toString();
    }

    private String generateMultiAddressQuorumConfiguration() {
        StringBuilder sb = new StringBuilder();

        int portQp1a = PortAssignment.unique();
        int portQp1b = PortAssignment.unique();
        int portQp2a = PortAssignment.unique();
        int portQp2b = PortAssignment.unique();
        int portQp3a = PortAssignment.unique();
        int portQp3b = PortAssignment.unique();

        int portLe1a = PortAssignment.unique();
        int portLe1b = PortAssignment.unique();
        int portLe2a = PortAssignment.unique();
        int portLe2b = PortAssignment.unique();
        int portLe3a = PortAssignment.unique();
        int portLe3b = PortAssignment.unique();

        sb.append(String.format("server.1=127.0.0.1:%d:%d|127.0.0.1:%d:%d;%d\n", portQp1a, portLe1a, portQp1b, portLe1b, clientPortQp1));
        sb.append(String.format("server.2=127.0.0.1:%d:%d|127.0.0.1:%d:%d;%d\n", portQp2a, portLe2a, portQp2b, portLe2b, clientPortQp2));
        sb.append(String.format("server.3=127.0.0.1:%d:%d|127.0.0.1:%d:%d;%d\n", portQp3a, portLe3a, portQp3b, portLe3b, clientPortQp3));

        return sb.toString();
    }

    public void setSSLSystemProperties() {
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, "org.apache.zookeeper.server.NettyServerCnxnFactory");
        System.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET, "org.apache.zookeeper.ClientCnxnSocketNetty");
        System.setProperty(quorumX509Util.getSslKeystoreLocationProperty(), validKeystorePath);
        System.setProperty(quorumX509Util.getSslKeystorePasswdProperty(), "testpass");
        System.setProperty(quorumX509Util.getSslTruststoreLocationProperty(), truststorePath);
        System.setProperty(quorumX509Util.getSslTruststorePasswdProperty(), "testpass");
    }

    @AfterEach
    public void cleanUp() throws Exception {
        System.clearProperty(QuorumPeer.CONFIG_KEY_MULTI_ADDRESS_ENABLED);
        clearSSLSystemProperties();
        if (q1 != null) {
            q1.shutdown();
        }
        if (q2 != null) {
            q2.shutdown();
        }
        if (q3 != null) {
            q3.shutdown();
        }

        Security.removeProvider("BC");
        quorumX509Util.close();
    }

    private void clearSSLSystemProperties() {
        System.clearProperty(quorumX509Util.getSslKeystoreLocationProperty());
        System.clearProperty(quorumX509Util.getSslKeystorePasswdProperty());
        System.clearProperty(quorumX509Util.getSslTruststoreLocationProperty());
        System.clearProperty(quorumX509Util.getSslTruststorePasswdProperty());
        System.clearProperty(quorumX509Util.getSslHostnameVerificationEnabledProperty());
        System.clearProperty(quorumX509Util.getSslOcspEnabledProperty());
        System.clearProperty(quorumX509Util.getSslCrlEnabledProperty());
        System.clearProperty(quorumX509Util.getCipherSuitesProperty());
        System.clearProperty(quorumX509Util.getSslProtocolProperty());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    public void testQuorumSSL() throws Exception {
        q1 = new MainThread(1, clientPortQp1, quorumConfiguration, SSL_QUORUM_ENABLED);
        q2 = new MainThread(2, clientPortQp2, quorumConfiguration, SSL_QUORUM_ENABLED);

        q1.start();
        q2.start();

        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp1, CONNECTION_TIMEOUT));
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp2, CONNECTION_TIMEOUT));

        clearSSLSystemProperties();

        // This server should fail to join the quorum as it is not using ssl.
        q3 = new MainThread(3, clientPortQp3, quorumConfiguration);
        q3.start();

        assertFalse(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp3, CONNECTION_TIMEOUT));
    }


    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    public void testQuorumSSLWithMultipleAddresses() throws Exception {
        System.setProperty(QuorumPeer.CONFIG_KEY_MULTI_ADDRESS_ENABLED, "true");
        quorumConfiguration = generateMultiAddressQuorumConfiguration();

        q1 = new MainThread(1, clientPortQp1, quorumConfiguration, SSL_QUORUM_ENABLED);
        q2 = new MainThread(2, clientPortQp2, quorumConfiguration, SSL_QUORUM_ENABLED);

        q1.start();
        q2.start();

        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp1, CONNECTION_TIMEOUT));
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp2, CONNECTION_TIMEOUT));

        clearSSLSystemProperties();

        // This server should fail to join the quorum as it is not using ssl.
        q3 = new MainThread(3, clientPortQp3, quorumConfiguration);
        q3.start();

        assertFalse(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp3, CONNECTION_TIMEOUT));
    }


    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    public void testRollingUpgrade() throws Exception {
        // Form a quorum without ssl
        q1 = new MainThread(1, clientPortQp1, quorumConfiguration);
        q2 = new MainThread(2, clientPortQp2, quorumConfiguration);
        q3 = new MainThread(3, clientPortQp3, quorumConfiguration);

        Map<Integer, MainThread> members = new HashMap<>();
        members.put(clientPortQp1, q1);
        members.put(clientPortQp2, q2);
        members.put(clientPortQp3, q3);

        for (MainThread member : members.values()) {
            member.start();
        }

        for (int clientPort : members.keySet()) {
            assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPort, CONNECTION_TIMEOUT));
        }

        // Set SSL system properties and port unification, begin restarting servers
        setSSLSystemProperties();

        stopAppendConfigRestartAll(members, PORT_UNIFICATION_ENABLED);
        stopAppendConfigRestartAll(members, SSL_QUORUM_ENABLED);
        stopAppendConfigRestartAll(members, PORT_UNIFICATION_DISABLED);
    }

    private void stopAppendConfigRestartAll(Map<Integer, MainThread> members, String config) throws Exception {
        for (Map.Entry<Integer, MainThread> entry : members.entrySet()) {
            int clientPort = entry.getKey();
            MainThread member = entry.getValue();

            member.shutdown();
            assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + clientPort, CONNECTION_TIMEOUT));

            FileWriter fileWriter = new FileWriter(member.getConfFile(), true);
            fileWriter.write(config);
            fileWriter.flush();
            fileWriter.close();

            member.start();

            assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPort, CONNECTION_TIMEOUT));
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    public void testHostnameVerificationWithInvalidHostname() throws Exception {
        String badhostnameKeystorePath = tmpDir + "/badhost.jks";
        X509Certificate badHostCert = buildEndEntityCert(
            defaultKeyPair,
            rootCertificate,
            rootKeyPair.getPrivate(),
            "bleepbloop",
            null,
            null,
            null);
        writeKeystore(badHostCert, defaultKeyPair, badhostnameKeystorePath);

        testHostnameVerification(badhostnameKeystorePath, false);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    public void testHostnameVerificationWithInvalidIPAddress() throws Exception {
        String badhostnameKeystorePath = tmpDir + "/badhost.jks";
        X509Certificate badHostCert = buildEndEntityCert(
            defaultKeyPair,
            rootCertificate,
            rootKeyPair.getPrivate(),
            null,
            "140.211.11.105",
            null,
            null);
        writeKeystore(badHostCert, defaultKeyPair, badhostnameKeystorePath);

        testHostnameVerification(badhostnameKeystorePath, false);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    public void testHostnameVerificationWithInvalidIpAddressAndInvalidHostname() throws Exception {
        String badhostnameKeystorePath = tmpDir + "/badhost.jks";
        X509Certificate badHostCert = buildEndEntityCert(
            defaultKeyPair,
            rootCertificate,
            rootKeyPair.getPrivate(),
            "bleepbloop",
            "140.211.11.105",
            null,
            null);
        writeKeystore(badHostCert, defaultKeyPair, badhostnameKeystorePath);

        testHostnameVerification(badhostnameKeystorePath, false);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    public void testHostnameVerificationForInvalidMultiAddressServerConfig() throws Exception {
        System.setProperty(QuorumPeer.CONFIG_KEY_MULTI_ADDRESS_ENABLED, "true");
        quorumConfiguration = generateMultiAddressQuorumConfiguration();

        String badhostnameKeystorePath = tmpDir + "/badhost.jks";
        X509Certificate badHostCert = buildEndEntityCert(
            defaultKeyPair,
            rootCertificate,
            rootKeyPair.getPrivate(),
            "bleepbloop",
            "140.211.11.105",
            null,
            null);
        writeKeystore(badHostCert, defaultKeyPair, badhostnameKeystorePath);

        testHostnameVerification(badhostnameKeystorePath, false);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    public void testHostnameVerificationWithInvalidIpAddressAndValidHostname() throws Exception {
        String badhostnameKeystorePath = tmpDir + "/badhost.jks";
        X509Certificate badHostCert = buildEndEntityCert(
            defaultKeyPair,
            rootCertificate,
            rootKeyPair.getPrivate(),
            "localhost",
            "140.211.11.105",
            null,
            null);
        writeKeystore(badHostCert, defaultKeyPair, badhostnameKeystorePath);

        testHostnameVerification(badhostnameKeystorePath, true);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    public void testHostnameVerificationWithValidIpAddressAndInvalidHostname() throws Exception {
        String badhostnameKeystorePath = tmpDir + "/badhost.jks";
        X509Certificate badHostCert = buildEndEntityCert(
            defaultKeyPair,
            rootCertificate,
            rootKeyPair.getPrivate(),
            "bleepbloop",
            "127.0.0.1",
            null,
            null);
        writeKeystore(badHostCert, defaultKeyPair, badhostnameKeystorePath);

        testHostnameVerification(badhostnameKeystorePath, true);
    }

    /**
     * @param keystorePath The keystore to use
     * @param expectSuccess True for expecting the keystore to pass hostname verification, false for expecting failure
     * @throws Exception
     */
    private void testHostnameVerification(String keystorePath, boolean expectSuccess) throws Exception {
        System.setProperty(quorumX509Util.getSslHostnameVerificationEnabledProperty(), "false");

        q1 = new MainThread(1, clientPortQp1, quorumConfiguration, SSL_QUORUM_ENABLED);
        q2 = new MainThread(2, clientPortQp2, quorumConfiguration, SSL_QUORUM_ENABLED);

        q1.start();
        q2.start();

        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp1, CONNECTION_TIMEOUT));
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp2, CONNECTION_TIMEOUT));

        System.setProperty(quorumX509Util.getSslKeystoreLocationProperty(), keystorePath);

        // This server should join successfully
        q3 = new MainThread(3, clientPortQp3, quorumConfiguration, SSL_QUORUM_ENABLED);
        q3.start();

        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp3, CONNECTION_TIMEOUT));

        q1.shutdown();
        q2.shutdown();
        q3.shutdown();

        assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + clientPortQp1, CONNECTION_TIMEOUT));
        assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + clientPortQp2, CONNECTION_TIMEOUT));
        assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + clientPortQp3, CONNECTION_TIMEOUT));

        setSSLSystemProperties();
        System.clearProperty(quorumX509Util.getSslHostnameVerificationEnabledProperty());

        q1.start();
        q2.start();

        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp1, CONNECTION_TIMEOUT));
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp2, CONNECTION_TIMEOUT));

        System.setProperty(quorumX509Util.getSslKeystoreLocationProperty(), keystorePath);
        q3.start();

        assertEquals(
            expectSuccess,
            ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp3, CONNECTION_TIMEOUT));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    public void testCertificateRevocationList() throws Exception {
        q1 = new MainThread(1, clientPortQp1, quorumConfiguration, SSL_QUORUM_ENABLED);
        q2 = new MainThread(2, clientPortQp2, quorumConfiguration, SSL_QUORUM_ENABLED);

        q1.start();
        q2.start();

        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp1, CONNECTION_TIMEOUT));
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp2, CONNECTION_TIMEOUT));

        String revokedInCRLKeystorePath = tmpDir + "/crl_revoked.jks";
        String crlPath = tmpDir + "/crl.pem";
        X509Certificate revokedInCRLCert = buildEndEntityCert(
            defaultKeyPair,
            rootCertificate,
            rootKeyPair.getPrivate(),
            HOSTNAME,
            null,
            crlPath,
            null);
        writeKeystore(revokedInCRLCert, defaultKeyPair, revokedInCRLKeystorePath);
        buildCRL(revokedInCRLCert, crlPath);

        System.setProperty(quorumX509Util.getSslKeystoreLocationProperty(), revokedInCRLKeystorePath);

        // This server should join successfully
        q3 = new MainThread(3, clientPortQp3, quorumConfiguration, SSL_QUORUM_ENABLED);
        q3.start();

        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp3, CONNECTION_TIMEOUT));

        q1.shutdown();
        q2.shutdown();
        q3.shutdown();

        assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + clientPortQp1, CONNECTION_TIMEOUT));
        assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + clientPortQp2, CONNECTION_TIMEOUT));
        assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + clientPortQp3, CONNECTION_TIMEOUT));

        setSSLSystemProperties();
        System.setProperty(quorumX509Util.getSslCrlEnabledProperty(), "true");

        X509Certificate validCertificate = buildEndEntityCert(
            defaultKeyPair,
            rootCertificate,
            rootKeyPair.getPrivate(),
            HOSTNAME,
            null,
            crlPath,
            null);
        writeKeystore(validCertificate, defaultKeyPair, validKeystorePath);

        q1.start();
        q2.start();

        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp1, CONNECTION_TIMEOUT));
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp2, CONNECTION_TIMEOUT));

        System.setProperty(quorumX509Util.getSslKeystoreLocationProperty(), revokedInCRLKeystorePath);
        q3.start();

        assertFalse(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp3, CONNECTION_TIMEOUT));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    public void testOCSP() throws Exception {
        Integer ocspPort = PortAssignment.unique();

        q1 = new MainThread(1, clientPortQp1, quorumConfiguration, SSL_QUORUM_ENABLED);
        q2 = new MainThread(2, clientPortQp2, quorumConfiguration, SSL_QUORUM_ENABLED);

        q1.start();
        q2.start();

        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp1, CONNECTION_TIMEOUT));
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp2, CONNECTION_TIMEOUT));

        String revokedInOCSPKeystorePath = tmpDir + "/ocsp_revoked.jks";
        X509Certificate revokedInOCSPCert = buildEndEntityCert(
            defaultKeyPair,
            rootCertificate,
            rootKeyPair.getPrivate(),
            HOSTNAME,
            null,
            null,
            ocspPort);
        writeKeystore(revokedInOCSPCert, defaultKeyPair, revokedInOCSPKeystorePath);

        HttpServer ocspServer = HttpServer.create(new InetSocketAddress(ocspPort), 0);
        try {
            ocspServer.createContext("/", new OCSPHandler(revokedInOCSPCert));
            ocspServer.start();

            System.setProperty(quorumX509Util.getSslKeystoreLocationProperty(), revokedInOCSPKeystorePath);

            // This server should join successfully
            q3 = new MainThread(3, clientPortQp3, quorumConfiguration, SSL_QUORUM_ENABLED);
            q3.start();

            assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp3, CONNECTION_TIMEOUT));

            q1.shutdown();
            q2.shutdown();
            q3.shutdown();

            assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + clientPortQp1, CONNECTION_TIMEOUT));
            assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + clientPortQp2, CONNECTION_TIMEOUT));
            assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + clientPortQp3, CONNECTION_TIMEOUT));

            setSSLSystemProperties();
            System.setProperty(quorumX509Util.getSslOcspEnabledProperty(), "true");

            X509Certificate validCertificate = buildEndEntityCert(
                defaultKeyPair,
                rootCertificate,
                rootKeyPair.getPrivate(),
                HOSTNAME,
                null,
                null,
                ocspPort);
            writeKeystore(validCertificate, defaultKeyPair, validKeystorePath);

            q1.start();
            q2.start();

            assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp1, CONNECTION_TIMEOUT));
            assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp2, CONNECTION_TIMEOUT));

            System.setProperty(quorumX509Util.getSslKeystoreLocationProperty(), revokedInOCSPKeystorePath);
            q3.start();

            assertFalse(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp3, CONNECTION_TIMEOUT));
        } finally {
            ocspServer.stop(0);
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    public void testCipherSuites() throws Exception {
        // Get default cipher suites from JDK
        SSLServerSocketFactory ssf = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
        List<String> defaultCiphers = new ArrayList<String>();
        for (String cipher : ssf.getDefaultCipherSuites()) {
            if (!cipher.matches(".*EMPTY.*") && cipher.startsWith("TLS") && cipher.contains("RSA")) {
                defaultCiphers.add(cipher);
            }
        }

        if (defaultCiphers.size() < 2) {
            fail("JDK has to support at least 2 valid (RSA) cipher suites for this test to run");
        }

        // Use them all except one to build the ensemble
        String suitesOfEnsemble = String.join(",", defaultCiphers.subList(1, defaultCiphers.size()));
        System.setProperty(quorumX509Util.getCipherSuitesProperty(), suitesOfEnsemble);

        q1 = new MainThread(1, clientPortQp1, quorumConfiguration, SSL_QUORUM_ENABLED);
        q2 = new MainThread(2, clientPortQp2, quorumConfiguration, SSL_QUORUM_ENABLED);

        q1.start();
        q2.start();

        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp1, CONNECTION_TIMEOUT));
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp2, CONNECTION_TIMEOUT));

        // Use the odd one out for the client
        String suiteOfClient = defaultCiphers.get(0);
        System.setProperty(quorumX509Util.getCipherSuitesProperty(), suiteOfClient);

        // This server should fail to join the quorum as it is not using one of the supported suites from the other
        // quorum members
        q3 = new MainThread(3, clientPortQp3, quorumConfiguration, SSL_QUORUM_ENABLED);
        q3.start();

        assertFalse(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp3, CONNECTION_TIMEOUT));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    public void testProtocolVersion() throws Exception {
        System.setProperty(quorumX509Util.getSslProtocolProperty(), "TLSv1.2");

        q1 = new MainThread(1, clientPortQp1, quorumConfiguration, SSL_QUORUM_ENABLED);
        q2 = new MainThread(2, clientPortQp2, quorumConfiguration, SSL_QUORUM_ENABLED);

        q1.start();
        q2.start();

        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp1, CONNECTION_TIMEOUT));
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp2, CONNECTION_TIMEOUT));

        System.setProperty(quorumX509Util.getSslProtocolProperty(), "TLSv1.1");

        // This server should fail to join the quorum as it is not using TLSv1.2
        q3 = new MainThread(3, clientPortQp3, quorumConfiguration, SSL_QUORUM_ENABLED);
        q3.start();

        assertFalse(ClientBase.waitForServerUp("127.0.0.1:" + clientPortQp3, CONNECTION_TIMEOUT));
    }

}
