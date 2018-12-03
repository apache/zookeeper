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
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import javax.net.ssl.X509ExtendedTrustManager;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Random;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// We can only test calls to ZKTrustManager using Sockets (not SSLEngines). This can be fine since the logic is the same.
public class ZKTrustManagerTest extends ZKTestCase {

    private static KeyPair keyPair;

    private X509ExtendedTrustManager mockX509ExtendedTrustManager;
    private static final String IP_ADDRESS = "127.0.0.1";
    private static final String HOSTNAME = "localhost";

    private InetAddress mockInetAddress;
    private Socket mockSocket;

    @BeforeClass
    public static void createKeyPair() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
        keyPairGenerator.initialize(4096);
        keyPair = keyPairGenerator.genKeyPair();
    }

    @AfterClass
    public static void removeBouncyCastleProvider() throws Exception {
        Security.removeProvider("BC");
    }

    @Before
    public void setup() throws Exception {
        mockX509ExtendedTrustManager = mock(X509ExtendedTrustManager.class);

        mockInetAddress = mock(InetAddress.class);
        when(mockInetAddress.getHostAddress()).thenAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                return IP_ADDRESS;
            }
        });

        when(mockInetAddress.getHostName()).thenAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                return HOSTNAME;
            }
        });

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

        X509v3CertificateBuilder certificateBuilder =
                new JcaX509v3CertificateBuilder(nameBuilder.build(), serialNumber, notBefore, notAfter, nameBuilder.build(), keyPair.getPublic())
                        .addExtension(Extension.basicConstraints, true, new BasicConstraints(0))
                        .addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyCertSign | KeyUsage.cRLSign));

        List<GeneralName> generalNames = new ArrayList<>();
        if (ipAddress != null) {
            generalNames.add(new GeneralName(GeneralName.iPAddress, ipAddress));
        }
        if (hostname != null) {
            generalNames.add(new GeneralName(GeneralName.dNSName, hostname));
        }

        if (!generalNames.isEmpty()) {
            certificateBuilder.addExtension(Extension.subjectAlternativeName,  true,  new GeneralNames(generalNames.toArray(new GeneralName[] {})));
        }

        ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256WithRSAEncryption").build(keyPair.getPrivate());

        return new X509Certificate[] { new JcaX509CertificateConverter().getCertificate(certificateBuilder.build(contentSigner)) };
    }

    @Test
    public void testServerHostnameVerificationWithHostnameVerificationDisabled() throws Exception {
        ZKTrustManager zkTrustManager = new ZKTrustManager(mockX509ExtendedTrustManager, false, false);

        X509Certificate[] certificateChain = createSelfSignedCertifcateChain(IP_ADDRESS, HOSTNAME);
        zkTrustManager.checkServerTrusted(certificateChain, null, mockSocket);

        verify(mockInetAddress, times(0)).getHostAddress();
        verify(mockInetAddress, times(0)).getHostName();

        verify(mockX509ExtendedTrustManager, times(1)).checkServerTrusted(certificateChain, null, mockSocket);
    }

    @Test
    public void testServerHostnameVerificationWithHostnameVerificationDisabledAndClientHostnameVerificationEnabled() throws Exception {
        ZKTrustManager zkTrustManager = new ZKTrustManager(mockX509ExtendedTrustManager, false, true);

        X509Certificate[] certificateChain = createSelfSignedCertifcateChain(IP_ADDRESS, HOSTNAME);
        zkTrustManager.checkServerTrusted(certificateChain, null, mockSocket);

        verify(mockInetAddress, times(0)).getHostAddress();
        verify(mockInetAddress, times(0)).getHostName();

        verify(mockX509ExtendedTrustManager, times(1)).checkServerTrusted(certificateChain, null, mockSocket);
    }

    @Test
    public void testServerHostnameVerificationWithIPAddress() throws Exception {
        ZKTrustManager zkTrustManager = new ZKTrustManager(mockX509ExtendedTrustManager, true, false);

        X509Certificate[] certificateChain = createSelfSignedCertifcateChain(IP_ADDRESS, null);
        zkTrustManager.checkServerTrusted(certificateChain, null, mockSocket);

        verify(mockInetAddress, times(1)).getHostAddress();
        verify(mockInetAddress, times(0)).getHostName();

        verify(mockX509ExtendedTrustManager, times(1)).checkServerTrusted(certificateChain, null, mockSocket);
    }

    @Test
    public void testServerHostnameVerificationWithHostname() throws Exception {
        ZKTrustManager zkTrustManager = new ZKTrustManager(mockX509ExtendedTrustManager, true, false);

        X509Certificate[] certificateChain = createSelfSignedCertifcateChain(null, HOSTNAME);
        zkTrustManager.checkServerTrusted(certificateChain, null, mockSocket);

        verify(mockInetAddress, times(1)).getHostAddress();
        verify(mockInetAddress, times(1)).getHostName();

        verify(mockX509ExtendedTrustManager, times(1)).checkServerTrusted(certificateChain, null, mockSocket);
    }

    @Test
    public void testClientHostnameVerificationWithHostnameVerificationDisabled() throws Exception {
        ZKTrustManager zkTrustManager = new ZKTrustManager(mockX509ExtendedTrustManager, false, true);

        X509Certificate[] certificateChain = createSelfSignedCertifcateChain(null, HOSTNAME);
        zkTrustManager.checkClientTrusted(certificateChain, null, mockSocket);

        verify(mockInetAddress, times(1)).getHostAddress();
        verify(mockInetAddress, times(1)).getHostName();

        verify(mockX509ExtendedTrustManager, times(1)).checkClientTrusted(certificateChain, null, mockSocket);
    }

    @Test
    public void testClientHostnameVerificationWithClientHostnameVerificationDisabled() throws Exception {
        ZKTrustManager zkTrustManager = new ZKTrustManager(mockX509ExtendedTrustManager, true, false);

        X509Certificate[] certificateChain = createSelfSignedCertifcateChain(null, HOSTNAME);
        zkTrustManager.checkClientTrusted(certificateChain, null, mockSocket);

        verify(mockInetAddress, times(0)).getHostAddress();
        verify(mockInetAddress, times(0)).getHostName();

        verify(mockX509ExtendedTrustManager, times(1)).checkClientTrusted(certificateChain, null, mockSocket);
    }

    @Test
    public void testClientHostnameVerificationWithIPAddress() throws Exception {
        ZKTrustManager zkTrustManager = new ZKTrustManager(mockX509ExtendedTrustManager, true, true);

        X509Certificate[] certificateChain = createSelfSignedCertifcateChain(IP_ADDRESS, null);
        zkTrustManager.checkClientTrusted(certificateChain, null, mockSocket);

        verify(mockInetAddress, times(1)).getHostAddress();
        verify(mockInetAddress, times(0)).getHostName();

        verify(mockX509ExtendedTrustManager, times(1)).checkClientTrusted(certificateChain, null, mockSocket);
    }

    @Test
    public void testClientHostnameVerificationWithHostname() throws Exception {
        ZKTrustManager zkTrustManager = new ZKTrustManager(mockX509ExtendedTrustManager, true, true);

        X509Certificate[] certificateChain = createSelfSignedCertifcateChain(null, HOSTNAME);
        zkTrustManager.checkClientTrusted(certificateChain, null, mockSocket);

        verify(mockInetAddress, times(1)).getHostAddress();
        verify(mockInetAddress, times(1)).getHostName();

        verify(mockX509ExtendedTrustManager, times(1)).checkClientTrusted(certificateChain, null, mockSocket);
    }
}
