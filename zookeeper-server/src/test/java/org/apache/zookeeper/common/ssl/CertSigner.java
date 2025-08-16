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

import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;
import org.apache.zookeeper.common.X509TestHelpers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.X509ObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

public class CertSigner {
    private final Ca ca;
    private final String name;

    private Path crldp;
    private boolean ocsp;

    private final List<String> dnsNames = new ArrayList<>();
    private final List<String> ipAddresses = new ArrayList<>();
    private Duration expiration = Duration.ofDays(1);
    private X509CertBuilder certBuilder;

    CertSigner(Ca ca, String name) {
        this.ca = ca;
        this.name = name;
    }

    public CertSigner withCrldp() throws Exception {
        this.crldp = Files.createTempFile(ca.dir, String.format("%s-crldp-", name), ".pem");
        return this;
    }

    public CertSigner withOcsp() {
        this.ocsp = true;
        return this;
    }

    public CertSigner withDnsName(String name) {
        dnsNames.add(name);
        return this;
    }

    public CertSigner withResolvedDns(String name) throws Exception {
        dnsNames.add(name);
        InetAddress[] localAddresses = InetAddress.getAllByName("localhost");
        for (InetAddress addr : localAddresses) {
            ipAddresses.add(addr.getHostAddress());
        }
        return this;
    }

    public CertSigner withIpAddress(String ipAddress) {
        ipAddresses.add(ipAddress);
        return this;
    }

    /**
     * Default to {@code Duration.ofDays(1)}.
     */
    public CertSigner withExpiration(Duration expiration) {
        this.expiration = expiration;
        return this;
    }

    public CertSigner withCertBuilder(X509CertBuilder certBuilder) {
        this.certBuilder = certBuilder;
        return this;
    }

    public Cert sign() throws Exception {
        X509CertificateHolder holder = new JcaX509CertificateHolder(ca.cert);
        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSAEncryption").build(ca.key.getPrivate());

        List<GeneralName> generalNames = new ArrayList<>();
        for (String dnsName : dnsNames) {
            generalNames.add(new GeneralName(GeneralName.dNSName, dnsName));
        }
        for (String ipAddress : ipAddresses) {
            generalNames.add(new GeneralName(GeneralName.iPAddress, ipAddress));
        }

        Instant now = Instant.now();
        KeyPair key = X509TestHelpers.generateRSAKeyPair();
        JcaX509v3CertificateBuilder jcaX509v3CertificateBuilder = new JcaX509v3CertificateBuilder(
                holder.getSubject(),
                new BigInteger(128, new Random()),
                Date.from(now.minus(Duration.ofSeconds(10))),
                Date.from(now.plus(expiration)),
                new X500Name(String.format("CN=%s", name)),
                key.getPublic());
        X509v3CertificateBuilder certificateBuilder = jcaX509v3CertificateBuilder
                .addExtension(Extension.basicConstraints, true, new BasicConstraints(false))
                .addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));

        if (!generalNames.isEmpty()) {
            certificateBuilder.addExtension(
                    Extension.subjectAlternativeName,
                    true,
                    new GeneralNames(generalNames.toArray(new GeneralName[]{})));
        }

        if (crldp != null) {
            DistributionPointName distPointOne = new DistributionPointName(
                    new GeneralNames(new GeneralName(GeneralName.uniformResourceIdentifier, "file://" + crldp.toAbsolutePath())));

            certificateBuilder.addExtension(
                    Extension.cRLDistributionPoints,
                    false,
                    new CRLDistPoint(new DistributionPoint[]{new DistributionPoint(distPointOne, null, null)}));
        }

        if (ocsp) {
            certificateBuilder.addExtension(
                    Extension.authorityInfoAccess,
                    false,
                    new AuthorityInformationAccess(
                            X509ObjectIdentifiers.ocspAccessMethod,
                            new GeneralName(GeneralName.uniformResourceIdentifier, ca.getOcspAddress())));
        }

        if (certBuilder != null) {
            certBuilder.build(certificateBuilder);
        }

        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(certificateBuilder.build(signer));
        Cert cert = new Cert(name, key, certificate, ca.dir, crldp);
        if (crldp != null) {
            ca.flush_crl(cert);
        }
        return cert;
    }
}
