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

package org.apache.zookeeper.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.X509TestHelpers;
import org.apache.zookeeper.server.embedded.ExitHandler;
import org.apache.zookeeper.server.embedded.ZooKeeperServerEmbedded;
import org.apache.zookeeper.test.ClientBase;
import org.bouncycastle.asn1.ASN1GeneralizedTime;
import org.bouncycastle.asn1.ocsp.OCSPResponse;
import org.bouncycastle.asn1.ocsp.OCSPResponseStatus;
import org.bouncycastle.asn1.ocsp.RevokedInfo;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.CRLNumber;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.X509ObjectIdentifiers;
import org.bouncycastle.cert.X509CRLHolder;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v2CRLBuilder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.BasicOCSPRespBuilder;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.OCSPRespBuilder;
import org.bouncycastle.cert.ocsp.Req;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.jcajce.JcaBasicOCSPRespBuilder;
import org.bouncycastle.cert.ocsp.jcajce.JcaCertificateID;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.MiscPEMGenerator;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.util.io.pem.PemWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientSSLRevocationTest {
    private static final Logger LOG = LoggerFactory.getLogger(ClientSSLRevocationTest.class);

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private static class OCSPHandler implements HttpHandler {
        private final Ca ca;

        public OCSPHandler(Ca ca) {
            this.ca = ca;
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

                Map<CertificateID, RevokedInfo> revokedCerts = ca.ocspRevokedCerts.entrySet().stream().collect(Collectors.toMap(entry -> {
                        try {
                            return new JcaCertificateID(digestCalculator, ca.cert,  entry.getKey().getSerialNumber());
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    }, Map.Entry::getValue));

                BasicOCSPRespBuilder responseBuilder = new JcaBasicOCSPRespBuilder(ca.key.getPublic(), digestCalculator);
                for (Req req : requestList) {
                    CertificateID certId = req.getCertID();
                    CertificateStatus certificateStatus = CertificateStatus.GOOD;
                    RevokedInfo revokedInfo = revokedCerts.get(certId);
                    if (revokedInfo != null) {
                        certificateStatus = new RevokedStatus(revokedInfo);
                    }
                    responseBuilder.addResponse(certId, certificateStatus, null);
                }

                X509CertificateHolder[] chain = new X509CertificateHolder[]{new JcaX509CertificateHolder(ca.cert)};
                ContentSigner signer = new JcaContentSignerBuilder("SHA1withRSA").setProvider("BC").build(ca.key.getPrivate());
                BasicOCSPResp ocspResponse = responseBuilder.build(signer, chain, Calendar.getInstance().getTime());
                LOG.info("response {}", ocspResponse);
                responseBytes = new OCSPRespBuilder().build(OCSPRespBuilder.SUCCESSFUL, ocspResponse).getEncoded();
                LOG.error("OCSP server response OK");
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

    private static class PemFile {
        private final Path file;
        private final String password;

        public PemFile(Path file, String password) {
            this.file = file;
            this.password = password;
        }
    }

    private static class CertWithCrl extends Cert {
        public final Path crl;

        public CertWithCrl(String name, KeyPair key, X509Certificate cert, Path crl) {
            super(name, key, cert, crl.getParent());
            this.crl = crl;
        }
    }

    private static class Cert {
        public final String name;
        public final KeyPair key;
        public final X509Certificate cert;
        public final Path dir;

        public Cert(String name, KeyPair key, X509Certificate cert, Path dir) {
            this.name = name;
            this.key = key;
            this.cert = cert;
            this.dir = dir;
        }

        public PemFile writePem() throws Exception {
            String password = UUID.randomUUID().toString();
            String pem = X509TestHelpers.pemEncodeCertAndPrivateKey(cert, key.getPrivate(), password);
            Path file = Files.createTempFile(dir, name, ".pem");
            Files.write(file, pem.getBytes());
            return new PemFile(file, password);
        }
    }

    private static class Ca implements AutoCloseable {
        private final String name;
        private final KeyPair key;
        private final X509Certificate cert;
        private final Path dir;
        private final Map<X509Certificate, RevokedInfo> crlRevokedCerts = Collections.synchronizedMap(new HashMap<>());
        private final Map<X509Certificate, RevokedInfo> ocspRevokedCerts = Collections.synchronizedMap(new HashMap<>());
        private final HttpServer ocspServer;
        private final AtomicLong crlNumber = new AtomicLong(1);

        private Ca(String name, KeyPair key, X509Certificate cert, Path dir, HttpServer ocspServer) throws Exception {
            this.name = name;
            this.key = key;
            this.cert = cert;
            this.dir = dir;
            this.ocspServer = ocspServer;
        }

        public PemFile writePem() throws Exception {
            String pem = X509TestHelpers.pemEncodeX509Certificate(cert);
            Path file = Files.createTempFile(dir, name, ".pem");
            Files.write(file, pem.getBytes());
            return new PemFile(file, "");
        }

        // Check result of crldp could be cached, so use per-cert crl file.
        public void flush_crl(Path crl) throws Exception {
            Instant now = Instant.now();

            X509v2CRLBuilder builder = new JcaX509v2CRLBuilder(cert.getIssuerX500Principal(), Date.from(now));
            builder.setNextUpdate(Date.from(now.plusSeconds(2)));

            builder.addExtension(Extension.authorityKeyIdentifier, false, new JcaX509ExtensionUtils().createAuthorityKeyIdentifier(this.cert));
            builder.addExtension(Extension.cRLNumber, false, new CRLNumber(BigInteger.valueOf(crlNumber.getAndAdd(1L))));

            for (Map.Entry<X509Certificate, RevokedInfo> entry : crlRevokedCerts.entrySet()) {
                builder.addCRLEntry(entry.getKey().getSerialNumber(), entry.getValue().getRevocationTime().getDate(), CRLReason.cACompromise);
            }

            ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256WithRSAEncryption").build(this.key.getPrivate());
            X509CRLHolder crlHolder = builder.build(contentSigner);

            Path tmpFile = Files.createTempFile(dir, "crldp-", ".pem.tmp");
            PemWriter pemWriter = new PemWriter(new FileWriter(tmpFile.toFile()));
            pemWriter.writeObject(new MiscPEMGenerator(crlHolder));
            pemWriter.flush();
            pemWriter.close();

            Files.move(tmpFile, crl, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }

        public void revoke_through_crldp(CertWithCrl cert) throws Exception {
            Date now = new Date();
            RevokedInfo revokedInfo = new RevokedInfo(new ASN1GeneralizedTime(now), CRLReason.lookup(CRLReason.cACompromise));
            this.crlRevokedCerts.put(cert.cert, revokedInfo);
            flush_crl(cert.crl);
        }

        public void revoke_through_ocsp(X509Certificate cert) throws Exception {
            Date now = new Date();
            RevokedInfo revokedInfo = new RevokedInfo(new ASN1GeneralizedTime(now), CRLReason.lookup(CRLReason.cACompromise));
            this.ocspRevokedCerts.put(cert, revokedInfo);
        }

        public Cert sign(String name) throws Exception {
            KeyPair key = X509TestHelpers.generateRSAKeyPair();
            X509Certificate cert = X509TestHelpers.newCert(this.cert, this.key, name, key.getPublic());
            return new Cert(name, key, cert, dir);
        }

        public CertWithCrl sign_with_crl(String name) throws Exception {
            KeyPair key = X509TestHelpers.generateRSAKeyPair();
            Path crl = Files.createTempFile(dir, String.format("%s-crldp-", name), ".pem");
            X509Certificate cert = X509TestHelpers.newCert(this.cert, this.key, name, key.getPublic(), builder -> {
                DistributionPointName distPointOne = new DistributionPointName(
                        new GeneralNames(new GeneralName(GeneralName.uniformResourceIdentifier, "file://" + crl)));
                builder.addExtension(
                        Extension.cRLDistributionPoints,
                        false,
                        new CRLDistPoint(new DistributionPoint[]{new DistributionPoint(distPointOne, null, null)}));

            });
            flush_crl(crl);
            return new CertWithCrl(name, key, cert, crl);
        }

        public Cert sign_with_ocsp(String name) throws Exception {
            KeyPair key = X509TestHelpers.generateRSAKeyPair();
            X509Certificate cert = X509TestHelpers.newCert(this.cert, this.key, name, key.getPublic(), builder -> {
                String addr = "http://127.0.0.1:" + ocspServer.getAddress().getPort();
                builder.addExtension(
                        Extension.authorityInfoAccess,
                        false,
                        new AuthorityInformationAccess(
                                X509ObjectIdentifiers.ocspAccessMethod,
                                new GeneralName(GeneralName.uniformResourceIdentifier, addr)));
            });
            return new Cert(name, key, cert, dir);
        }

        public static Ca create(Path dir) throws Exception {
            return create(dir, "CA");
        }

        public static Ca create(Path dir, String name) throws Exception {
            KeyPair caKey = X509TestHelpers.generateRSAKeyPair();
            X509Certificate caCert = X509TestHelpers.newSelfSignedCert(name, caKey);
            HttpServer ocspServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            Ca ca = new Ca(name, caKey, caCert, dir, ocspServer);
            ca.ocspServer.createContext("/", new OCSPHandler(ca));
            ca.ocspServer.start();
            return ca;
        }

        @Override
        public void close() throws Exception {
            ocspServer.stop(0);
        }
    }

    @AfterEach
    public void cleanup() throws Exception {
        Security.setProperty("ocsp.enable", "false");
        System.clearProperty("com.sun.net.ssl.checkRevocation");
        System.clearProperty("zookeeper.ssl.crl");
        System.clearProperty("zookeeper.ssl.ocsp");
    }

    @Test
    public void testCrlDisabled(@TempDir Path tmpDir) throws Exception {
        // given: crl not enabled
        try (Ca ca = Ca.create(tmpDir)) {
            PemFile caPem = ca.writePem();

            Cert serverCert = ca.sign_with_ocsp("server");
            final Properties config = getServerConfig(caPem, serverCert);
            // given: revoked server cert
            ca.revoke_through_ocsp(serverCert.cert);
            try (ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded
                    .builder()
                    .baseDir(Files.createTempDirectory(tmpDir, "server.data"))
                    .configuration(config)
                    .exitHandler(ExitHandler.LOG_ONLY)
                    .build()) {
                server.start();

                CertWithCrl client1Cert = ca.sign_with_crl("client1");
                ca.revoke_through_crldp(client1Cert);

                assertTrue(ClientBase.waitForServerUp(server.getConnectionString(), 60000));

                // when: connect with revoked cert.
                // then: connected
                assertTrue(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, getZKClientConfig(caPem, client1Cert)));
            }
        }
    }

    @Test
    public void testServerRevocationWithCrldp(@TempDir Path tmpDir) throws Exception {
        try (Ca ca = Ca.create(tmpDir)) {
            PemFile caPem = ca.writePem();
            // given: server cert with crldp
            CertWithCrl server_cert = ca.sign_with_crl("server1");
            try (ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded
                    .builder()
                    .baseDir(Files.createTempDirectory(tmpDir, "server.data"))
                    .configuration(getServerConfig(caPem, server_cert))
                    .exitHandler(ExitHandler.LOG_ONLY)
                    .build()) {
                server.start();

                assertTrue(ClientBase.waitForServerUp(server.getConnectionString(), 60000));

                // when: server cert get revoked
                ca.revoke_through_crldp(server_cert);

                Cert client_cert = ca.sign("client");

                // then: ssl authentication succeed when crl is disabled
                assertTrue(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, getZKClientConfig(caPem, client_cert)));

                // then: ssl authentication failed when crl is enabled
                System.setProperty("com.sun.net.ssl.checkRevocation", "true");
                assertFalse(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, getZKClientConfig(caPem, client_cert)));

                // then: ssl authentication failed when crl is enabled
                System.setProperty("com.sun.net.ssl.checkRevocation", "false");
                System.setProperty("zookeeper.ssl.crl", "true");
                assertFalse(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, getZKClientConfig(caPem, client_cert)));
            }
        }
    }

    @Test
    public void testServerRevocationWithOCSP(@TempDir Path tmpDir) throws Exception {
        try (Ca ca = Ca.create(tmpDir)) {
            PemFile caPem = ca.writePem();
            // given: server cert with crldp
            Cert server_cert = ca.sign_with_ocsp("server1");
            try (ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded
                    .builder()
                    .baseDir(Files.createTempDirectory(tmpDir, "server.data"))
                    .configuration(getServerConfig(caPem, server_cert))
                    .exitHandler(ExitHandler.LOG_ONLY)
                    .build()) {
                server.start();

                assertTrue(ClientBase.waitForServerUp(server.getConnectionString(), 60000));

                // when: server cert get revoked
                ca.revoke_through_ocsp(server_cert.cert);

                Cert client_cert = ca.sign("client");

                // then: ssl authentication succeed when crl is disabled
                Security.setProperty("ocsp.enable", "true");
                assertTrue(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, getZKClientConfig(caPem, client_cert)));

                // then: ssl authentication failed when crl is enabled
                System.setProperty("zookeeper.ssl.crl", "true");
                assertFalse(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, getZKClientConfig(caPem, client_cert)));

                // then: ssl authentication failed when crl is enabled
                Security.setProperty("ocsp.enable", "false");
                System.setProperty("zookeeper.ssl.ocsp", "true");
                assertFalse(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, getZKClientConfig(caPem, client_cert)));
            }
        }
    }

    @Test
    public void testClientRevocationWithCrldp(@TempDir Path tmpDir) throws Exception {
        try (Ca ca = Ca.create(tmpDir)) {
            PemFile caPem = ca.writePem();
            Cert server_cert = ca.sign("server1");
            // given: server with crl enabled
            System.setProperty("zookeeper.ssl.crl", "true");
            try (ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded
                    .builder()
                    .baseDir(Files.createTempDirectory(tmpDir, "server.data"))
                    .configuration(getServerConfig(caPem, server_cert))
                    .exitHandler(ExitHandler.LOG_ONLY)
                    .build()) {
                server.start();

                assertTrue(ClientBase.waitForServerUp(server.getConnectionString(), 60000));

                // when: valid client cert with crldp
                // then: ssl authentication failed when crl is enabled
                Cert client1Cert = ca.sign_with_crl("client1");
                ZKClientConfig client1Config = getZKClientConfig(caPem, client1Cert);
                client1Config.setProperty("zookeeper.ssl.crl", "false");
                assertTrue(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, client1Config));

                CertWithCrl client2Cert = ca.sign_with_crl("client2");
                ca.revoke_through_crldp(client2Cert);

                // when: revoked client cert with crldp
                // then: ssl authentication failed when crl is enabled
                ZKClientConfig client2Config = getZKClientConfig(caPem, client2Cert);
                client2Config.setProperty("zookeeper.ssl.crl", "false");
                assertFalse(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, client2Config));
            }
        }
    }

    @Test
    public void testClientRevocationWithOCSP(@TempDir Path tmpDir) throws Exception {
        try (Ca ca = Ca.create(tmpDir)) {
            PemFile caPem = ca.writePem();
            Cert server_cert = ca.sign("server1");
            // given: server with crl and ocsp enabled
            System.setProperty("com.sun.net.ssl.checkRevocation", "true");
            System.setProperty("zookeeper.ssl.ocsp", "true");
            try (ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded
                    .builder()
                    .baseDir(Files.createTempDirectory(tmpDir, "server.data"))
                    .configuration(getServerConfig(caPem, server_cert))
                    .exitHandler(ExitHandler.LOG_ONLY)
                    .build()) {
                server.start();

                assertTrue(ClientBase.waitForServerUp(server.getConnectionString(), 60000));

                // when: valid client cert with crldp
                // then: ssl authentication failed when crl is enabled
                Cert client1Cert = ca.sign_with_ocsp("client1");
                ZKClientConfig client1Config = getZKClientConfig(caPem, client1Cert);
                client1Config.setProperty("zookeeper.ssl.crl", "false");
                assertTrue(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, client1Config));

                ca.revoke_through_ocsp(client1Cert.cert);
                assertFalse(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, client1Config));
            }
        }
    }

    private Properties getServerConfig(PemFile ca, Cert identity) throws Exception {
        final Properties config = new Properties();
        config.put("clientPort", "0");
        config.put("secureClientPort", "0");
        config.put("host", "localhost");
        config.put("ticktime", "4000");

        PemFile serverPem = identity.writePem();

        // TLS config fields
        //config.put("ssl.clientAuth", "need");
        config.put("ssl.keyStore.location", serverPem.file.toString());
        config.put("ssl.keyStore.password", serverPem.password);
        config.put("ssl.trustStore.location", ca.file.toString());

        // Netty is required for TLS
        config.put("serverCnxnFactory", org.apache.zookeeper.server.NettyServerCnxnFactory.class.getName());
        config.put("4lw.commands.whitelist", "*");
        return config;
    }

    private ZKClientConfig getZKClientConfig(PemFile ca, Cert cert) throws Exception {
        PemFile pemFile = cert.writePem();

        ZKClientConfig config = new ZKClientConfig();
        config.setProperty("zookeeper.client.secure", "true");
        config.setProperty("zookeeper.ssl.keyStore.password", pemFile.password);
        config.setProperty("zookeeper.ssl.keyStore.location", pemFile.file.toString());
        config.setProperty("zookeeper.ssl.trustStore.location", ca.file.toString());
        // only netty supports TLS
        config.setProperty("zookeeper.clientCnxnSocket", org.apache.zookeeper.ClientCnxnSocketNetty.class.getName());
        return config;
    }
}
