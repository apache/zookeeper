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

package org.apache.zookeeper.common.ssl;

import com.sun.net.httpserver.HttpServer;
import java.io.FileWriter;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.zookeeper.common.X509TestHelpers;
import org.bouncycastle.asn1.ASN1GeneralizedTime;
import org.bouncycastle.asn1.ocsp.RevokedInfo;
import org.bouncycastle.asn1.x509.CRLNumber;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.X509CRLHolder;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v2CRLBuilder;
import org.bouncycastle.openssl.MiscPEMGenerator;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemWriter;

public class Ca implements AutoCloseable {
    public static class CaBuilder {
        private final Path dir;
        private String name = "CA";
        private boolean ocsp = false;

        CaBuilder(Path dir) {
            this.dir = dir;
        }

        public CaBuilder withName(String name) {
            this.name = Objects.requireNonNull(name);
            return this;
        }

        public CaBuilder withOcsp() {
            this.ocsp = true;
            return this;
        }

        public Ca build() throws Exception {
            KeyPair caKey = X509TestHelpers.generateRSAKeyPair();
            X509Certificate caCert = X509TestHelpers.newSelfSignedCert(name, caKey);
            if (ocsp) {
                HttpServer ocspServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                Ca ca = new Ca(dir, name, caKey, caCert, ocspServer);
                ca.ocspServer.createContext("/", new OCSPHandler(ca));
                ca.ocspServer.start();
                return ca;
            }
            return new Ca(dir, name, caKey, caCert, null);
        }
    }

    public final Path dir;
    public final String name;
    public final KeyPair key;
    public final X509Certificate cert;
    public final Map<X509Certificate, RevokedInfo> crlRevokedCerts = Collections.synchronizedMap(new HashMap<>());
    public final Map<X509Certificate, RevokedInfo> ocspRevokedCerts = Collections.synchronizedMap(new HashMap<>());
    public final HttpServer ocspServer;
    public final AtomicLong crlNumber = new AtomicLong(1);
    public final PemFile pemFile;

    Ca(Path dir, String name, KeyPair key, X509Certificate cert, HttpServer ocspServer) throws Exception {
        this.dir = dir;
        this.name = name;
        this.key = key;
        this.cert = cert;
        this.ocspServer = ocspServer;
        this.pemFile = writePem();
    }

    private PemFile writePem() throws Exception {
        String pem = X509TestHelpers.pemEncodeX509Certificate(cert);
        Path file = Files.createTempFile(dir, name, ".pem");
        Files.write(file, pem.getBytes());
        return new PemFile(file, "");
    }

    // Check result of crldp could be cached, so use per-cert crl file.
    public void flush_crl(Cert cert) throws Exception {
        Objects.requireNonNull(cert.crl, "cert is signed with no crldp");
        Instant now = Instant.now();

        X509v2CRLBuilder builder = new JcaX509v2CRLBuilder(cert.cert.getIssuerX500Principal(), Date.from(now));
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

        Files.move(tmpFile, cert.crl, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public void revoke_through_crldp(Cert cert) throws Exception {
        Date now = new Date();
        RevokedInfo revokedInfo = new RevokedInfo(new ASN1GeneralizedTime(now), CRLReason.lookup(CRLReason.cACompromise));
        this.crlRevokedCerts.put(cert.cert, revokedInfo);
        flush_crl(cert);
    }

    public void revoke_through_ocsp(X509Certificate cert) throws Exception {
        Date now = new Date();
        RevokedInfo revokedInfo = new RevokedInfo(new ASN1GeneralizedTime(now), CRLReason.lookup(CRLReason.cACompromise));
        this.ocspRevokedCerts.put(cert, revokedInfo);
    }

    public CertSigner signer(String name) throws Exception {
        return new CertSigner(this, name);
    }

    public Cert sign(String name) throws Exception {
        return signer(name).sign();
    }

    public Cert sign_with_crldp(String name) throws Exception {
        return signer(name).withCrldp().sign();
    }

    public Cert sign_with_ocsp(String name) throws Exception {
        return signer(name).withOcsp().sign();
    }

    public static CaBuilder builder(Path dir) {
        return new CaBuilder(dir);
    }

    public static Ca create(Path dir) throws Exception {
        return Ca.builder(dir).build();
    }

    public static Ca create(String name, Path dir) throws Exception {
        return Ca.builder(dir).withName(name).build();
    }

    public String getOcspAddress() {
        if (ocspServer != null) {
            return String.format("http://127.0.0.1:%d", ocspServer.getAddress().getPort());
        }
        throw new IllegalStateException("No OCSP server available");
    }

    @Override
    public void close() throws Exception {
        if (ocspServer != null) {
            ocspServer.stop(0);
        }
    }
}
