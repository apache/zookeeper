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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509ExtendedTrustManager;
import org.apache.zookeeper.ZKTestCase;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.burningwave.tools.net.DefaultHostResolver;
import org.burningwave.tools.net.HostResolutionRequestInterceptor;
import org.burningwave.tools.net.MappedHostResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// We can only test calls to ZKTrustManager using Sockets (not SSLEngines). This can be fine since the logic is the same.
public class ZKTrustManagerTest extends ZKTestCase {

    protected static final Logger LOG = LoggerFactory.getLogger(ZKTrustManagerTest.class);

    private static KeyPair keyPair;

    private X509ExtendedTrustManager mockX509ExtendedTrustManager;
    private static final String IP_ADDRESS = "127.0.0.1";
    private static final String HOSTNAME = "localhost";
    private Socket mockSocket;

    @BeforeAll
    public static void setupDNSMocks() {
        Map<String, String> hostAliases = new LinkedHashMap<>();
        hostAliases.put(HOSTNAME, IP_ADDRESS);

        HostResolutionRequestInterceptor.INSTANCE.install(
                new MappedHostResolver(hostAliases),
                DefaultHostResolver.INSTANCE
        );
    }

    @AfterAll
    public static void clearDNSMocks() {
        HostResolutionRequestInterceptor.INSTANCE.uninstall();
    }

    @BeforeAll
    public static void createKeyPair() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
        keyPairGenerator.initialize(4096);
        keyPair = keyPairGenerator.genKeyPair();
    }

    @AfterAll
    public static void removeBouncyCastleProvider() throws Exception {
        Security.removeProvider("BC");
    }

    @BeforeEach
    public void setup() throws Exception {
        mockX509ExtendedTrustManager = mock(X509ExtendedTrustManager.class);

        InetAddress mockInetAddress = InetAddress.getByName(HOSTNAME);

        mockSocket = mock(Socket.class);
        when(mockSocket.getInetAddress()).thenAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                return mockInetAddress;
            }
        });
    }

    private X509Certificate[] createSelfSignedCertifcateChain(String ipAddress, String hostname) throws Exception {
        X500NameBuilder nameBuilder = new X500NameBuilder(BCStyle.INSTANCE);
        nameBuilder.addRDN(BCStyle.CN, "NOT_LOCALHOST");
        Date notBefore = new Date();
        Calendar cal = Calendar.getInstance();
        cal.setTime(notBefore);
        cal.add(Calendar.YEAR, 1);
        Date notAfter = cal.getTime();
        BigInteger serialNumber = new BigInteger(128, new Random());

        X509v3CertificateBuilder certificateBuilder = new JcaX509v3CertificateBuilder(nameBuilder.build(), serialNumber, notBefore, notAfter, nameBuilder.build(), keyPair.getPublic()).addExtension(Extension.basicConstraints, true, new BasicConstraints(0)).addExtension(Extension.keyUsage, true, new KeyUsage(
                KeyUsage.digitalSignature
                        | KeyUsage.keyCertSign
                        | KeyUsage.cRLSign));

        List<GeneralName> generalNames = new ArrayList<>();
        if (ipAddress != null) {
            generalNames.add(new GeneralName(GeneralName.iPAddress, ipAddress));
        }
        if (hostname != null) {
            generalNames.add(new GeneralName(GeneralName.dNSName, hostname));
        }

        if (!generalNames.isEmpty()) {
            certificateBuilder.addExtension(Extension.subjectAlternativeName, true, new GeneralNames(generalNames.toArray(new GeneralName[]{})));
        }

        ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256WithRSAEncryption").build(keyPair.getPrivate());

        return new X509Certificate[]{new JcaX509CertificateConverter().getCertificate(certificateBuilder.build(contentSigner))};
    }

    @Test
    public void testServerHostnameVerificationWithHostnameVerificationDisabled() throws Exception {
        VerifiableHostnameVerifier hostnameVerifier = new VerifiableHostnameVerifier();
        ZKTrustManager zkTrustManager = new ZKTrustManager(mockX509ExtendedTrustManager, false, false,
                hostnameVerifier);

        X509Certificate[] certificateChain = createSelfSignedCertifcateChain(IP_ADDRESS, HOSTNAME);
        zkTrustManager.checkServerTrusted(certificateChain, null, mockSocket);
        verify(mockSocket, times(0)).getInetAddress();
        assertTrue(hostnameVerifier.hosts.isEmpty());

        verify(mockX509ExtendedTrustManager, times(1)).checkServerTrusted(certificateChain, null, mockSocket);
    }

    @Test
    public void testServerHostnameVerificationWithHostnameVerificationDisabledAndClientHostnameVerificationEnabled() throws Exception {
        VerifiableHostnameVerifier hostnameVerifier = new VerifiableHostnameVerifier();
        ZKTrustManager zkTrustManager = new ZKTrustManager(mockX509ExtendedTrustManager, false, true,
                hostnameVerifier);

        X509Certificate[] certificateChain = createSelfSignedCertifcateChain(IP_ADDRESS, HOSTNAME);
        zkTrustManager.checkServerTrusted(certificateChain, null, mockSocket);
        verify(mockSocket, times(0)).getInetAddress();

        assertTrue(hostnameVerifier.hosts.isEmpty());

        verify(mockX509ExtendedTrustManager, times(1)).checkServerTrusted(certificateChain, null, mockSocket);
    }

    @Test
    public void testServerHostnameVerificationWithIPAddress() throws Exception {
        VerifiableHostnameVerifier hostnameVerifier = new VerifiableHostnameVerifier();
        ZKTrustManager zkTrustManager = new ZKTrustManager(mockX509ExtendedTrustManager, true, false,
                hostnameVerifier);

        X509Certificate[] certificateChain = createSelfSignedCertifcateChain(IP_ADDRESS, null);
        zkTrustManager.checkServerTrusted(certificateChain, null, mockSocket);
        verify(mockSocket, times(1)).getInetAddress();

        assertEquals(Arrays.asList(IP_ADDRESS), hostnameVerifier.hosts);

        verify(mockX509ExtendedTrustManager, times(1)).checkServerTrusted(certificateChain, null, mockSocket);
    }

    @Test
    public void testServerHostnameVerificationWithHostname() throws Exception {
        VerifiableHostnameVerifier hostnameVerifier = new VerifiableHostnameVerifier();
        ZKTrustManager zkTrustManager = new ZKTrustManager(mockX509ExtendedTrustManager, true, false,
                hostnameVerifier);

        X509Certificate[] certificateChain = createSelfSignedCertifcateChain(null, HOSTNAME);
        zkTrustManager.checkServerTrusted(certificateChain, null, mockSocket);
        verify(mockSocket, times(1)).getInetAddress();

        assertEquals(Arrays.asList(IP_ADDRESS, HOSTNAME), hostnameVerifier.hosts);

        verify(mockX509ExtendedTrustManager, times(1)).checkServerTrusted(certificateChain, null, mockSocket);
    }

    @Test
    public void testClientHostnameVerificationWithHostnameVerificationDisabled() throws Exception {
        VerifiableHostnameVerifier hostnameVerifier = new VerifiableHostnameVerifier();
        ZKTrustManager zkTrustManager = new ZKTrustManager(mockX509ExtendedTrustManager, false, true,
                hostnameVerifier);

        X509Certificate[] certificateChain = createSelfSignedCertifcateChain(null, HOSTNAME);
        zkTrustManager.checkClientTrusted(certificateChain, null, mockSocket);
        verify(mockSocket, times(1)).getInetAddress();

        assertEquals(Arrays.asList(IP_ADDRESS, HOSTNAME), hostnameVerifier.hosts);

        verify(mockX509ExtendedTrustManager, times(1)).checkClientTrusted(certificateChain, null, mockSocket);
    }

    @Test
    public void testClientHostnameVerificationWithClientHostnameVerificationDisabled() throws Exception {
        VerifiableHostnameVerifier hostnameVerifier = new VerifiableHostnameVerifier();
        ZKTrustManager zkTrustManager = new ZKTrustManager(mockX509ExtendedTrustManager, true,
                false, hostnameVerifier);

        X509Certificate[] certificateChain = createSelfSignedCertifcateChain(null, HOSTNAME);
        zkTrustManager.checkClientTrusted(certificateChain, null, mockSocket);
        verify(mockSocket, times(0)).getInetAddress();

        assertTrue(hostnameVerifier.hosts.isEmpty());

        verify(mockX509ExtendedTrustManager, times(1)).checkClientTrusted(certificateChain, null, mockSocket);
    }

    @Test
    public void testClientHostnameVerificationWithIPAddress() throws Exception {
        VerifiableHostnameVerifier hostnameVerifier = new VerifiableHostnameVerifier();
        ZKTrustManager zkTrustManager = new ZKTrustManager(mockX509ExtendedTrustManager, true, true,
                hostnameVerifier);

        X509Certificate[] certificateChain = createSelfSignedCertifcateChain(IP_ADDRESS, null);
        zkTrustManager.checkClientTrusted(certificateChain, null, mockSocket);
        verify(mockSocket, times(1)).getInetAddress();

        assertEquals(Arrays.asList(IP_ADDRESS), hostnameVerifier.hosts);

        verify(mockX509ExtendedTrustManager, times(1)).checkClientTrusted(certificateChain, null, mockSocket);
    }

    @Test
    public void testClientHostnameVerificationWithHostname() throws Exception {
        VerifiableHostnameVerifier hostnameVerifier = new VerifiableHostnameVerifier();
        ZKTrustManager zkTrustManager = new ZKTrustManager(mockX509ExtendedTrustManager, true, true,
                hostnameVerifier);

        X509Certificate[] certificateChain = createSelfSignedCertifcateChain(null, HOSTNAME);
        zkTrustManager.checkClientTrusted(certificateChain, null, mockSocket);
        verify(mockSocket, times(1)).getInetAddress();

        assertEquals(Arrays.asList(IP_ADDRESS, HOSTNAME), hostnameVerifier.hosts);

        verify(mockX509ExtendedTrustManager, times(1)).checkClientTrusted(certificateChain, null, mockSocket);
    }


    static class VerifiableHostnameVerifier extends ZKHostnameVerifier {

        List<String> hosts = new CopyOnWriteArrayList<>();

        @Override
        public boolean verify(String host, SSLSession session) {
            throw new IllegalArgumentException("not expected to be called by these tests");
        }

        @Override
        void verify(String host, X509Certificate cert) throws SSLException {
            LOG.info("verifyWithX509Certificate {} {}", host, cert);
            hosts.add(host);
            super.verify(host, cert);
        }
    }


}
