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

package org.apache.zookeeper.test;

import static org.junit.Assert.assertEquals;
import com.google.common.annotations.VisibleForTesting;
import java.math.BigInteger;
import java.net.Socket;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.MockServerCnxn;
import org.apache.zookeeper.server.auth.X509AuthenticationConfig;
import org.apache.zookeeper.server.auth.X509AuthenticationProvider;
import org.junit.Before;
import org.junit.Test;

public class X509AuthTest extends ZKTestCase {

    private static TestCertificate clientCert;
    private static TestCertificate superCert;
    private static TestCertificate unknownCert;

    @Before
    public void setUp() {
        System.setProperty("zookeeper.X509AuthenticationProvider.superUser", "CN=SUPER");
        System.setProperty("zookeeper.ssl.keyManager", "org.apache.zookeeper.test.X509AuthTest.TestKeyManager");
        System.setProperty("zookeeper.ssl.trustManager", "org.apache.zookeeper.test.X509AuthTest.TestTrustManager");

        clientCert = new TestCertificate("CLIENT");
        superCert = new TestCertificate("SUPER");
        unknownCert = new TestCertificate("UNKNOWN");
    }

    @Test
    public void testTrustedAuth() {
        X509AuthenticationProvider provider = createProvider(clientCert);
        MockServerCnxn cnxn = new MockServerCnxn();
        cnxn.clientChain = new X509Certificate[]{clientCert};
        assertEquals(KeeperException.Code.OK, provider.handleAuthentication(cnxn, null));
    }

    @Test
    public void testSuperAuth() {
        X509AuthenticationProvider provider = createProvider(superCert);
        MockServerCnxn cnxn = new MockServerCnxn();
        cnxn.clientChain = new X509Certificate[]{superCert};
        assertEquals(KeeperException.Code.OK, provider.handleAuthentication(cnxn, null));
        assertEquals("super", cnxn.getAuthInfo().get(0).getScheme());
    }

    @Test
    public void testUntrustedAuth() {
        X509AuthenticationProvider provider = createProvider(clientCert);
        MockServerCnxn cnxn = new MockServerCnxn();
        cnxn.clientChain = new X509Certificate[]{unknownCert};
        assertEquals(KeeperException.Code.AUTHFAILED, provider.handleAuthentication(cnxn, null));
    }

    @Test
    public void testSANBasedAuth() {
        String clientCertIdType = "SAN";
        String clientCertIdSANMatchType = "6";
        // The following clientCertIdSANMatchRegex matches the entire SAN String
        String clientCertIdSANMatchRegex = ".*";
        // TEST_SAN_STR = "a:b:c(d;e;f)" in the test. The following clientCertIdSANExtractRegex
        // extracts the first element in the parentheses excluding "a:b:c(" and trailing ";*"
        String clientCertIdSANExtractRegex = "^a:b:c\\((.+);.+;.+\\)$";
        // The following clientCertIdSANExtractMatcherGroupIndex specifies the first index in the
        // Matcher group, which is "d"
        String clientCertIdSANExtractMatcherGroupIndex = "1";
        String expectedClientIdFromSANExtraction = "d";

    // Set JVM properties to enable SAN-based client id extraction
    System.setProperty(X509AuthenticationConfig.SSL_X509_CLIENT_CERT_ID_TYPE, clientCertIdType);
    System.setProperty(X509AuthenticationConfig.SSL_X509_CLIENT_CERT_ID_SAN_MATCH_TYPE, clientCertIdSANMatchType);
    System.setProperty(X509AuthenticationConfig.SSL_X509_CLIENT_CERT_ID_SAN_MATCH_REGEX, clientCertIdSANMatchRegex);
    System.setProperty(X509AuthenticationConfig.SSL_X509_CLIENT_CERT_ID_SAN_EXTRACT_REGEX, clientCertIdSANExtractRegex);
    System.setProperty(X509AuthenticationConfig.SSL_X509_CLIENT_CERT_ID_SAN_EXTRACT_MATCHER_GROUP_INDEX,
        clientCertIdSANExtractMatcherGroupIndex);

        X509AuthenticationProvider provider = createProvider(clientCert);
        MockServerCnxn cnxn = new MockServerCnxn();
        cnxn.clientChain = new X509Certificate[]{clientCert};
        assertEquals(KeeperException.Code.OK, provider.handleAuthentication(cnxn, null));
        assertEquals(expectedClientIdFromSANExtraction, cnxn.getAuthInfo().get(0).getId());

    // Remove JVM properties so they don't interfere with other tests
    System.clearProperty(X509AuthenticationConfig.SSL_X509_CLIENT_CERT_ID_TYPE);
    System.clearProperty(X509AuthenticationConfig.SSL_X509_CLIENT_CERT_ID_SAN_MATCH_TYPE);
    System.clearProperty(X509AuthenticationConfig.SSL_X509_CLIENT_CERT_ID_SAN_MATCH_REGEX);
    System.clearProperty(X509AuthenticationConfig.SSL_X509_CLIENT_CERT_ID_SAN_EXTRACT_REGEX);
    System.clearProperty(X509AuthenticationConfig.SSL_X509_CLIENT_CERT_ID_SAN_EXTRACT_MATCHER_GROUP_INDEX);
    X509AuthenticationConfig.reset();
  }

  protected static class TestPublicKey implements PublicKey {

        private static final long serialVersionUID = 1L;
        @Override
        public String getAlgorithm() {
            return null;
        }
        @Override
        public String getFormat() {
            return null;
        }
        @Override
        public byte[] getEncoded() {
            return null;
        }

    }

    public static class TestCertificate extends X509Certificate {
        @VisibleForTesting
        static final String TEST_SAN_STR = "a:b:c(d;e;f)";
        private byte[] encoded;
        private X500Principal principal;
        private PublicKey publicKey;
        private String subjectAlternativeName;

        public TestCertificate(String name) {
          this(name, TEST_SAN_STR);
        }

        public TestCertificate(String name, String sanVal) {
          encoded = name.getBytes();
          principal = new X500Principal("CN=" + name);
          publicKey = new TestPublicKey();
          subjectAlternativeName = sanVal;
        }
          @Override
        public boolean hasUnsupportedCriticalExtension() {
            return false;
        }
        @Override
        public Set<String> getCriticalExtensionOIDs() {
            return null;
        }
        @Override
        public Set<String> getNonCriticalExtensionOIDs() {
            return null;
        }
        @Override
        public byte[] getExtensionValue(String oid) {
            return null;
        }
        @Override
        public void checkValidity() throws CertificateExpiredException, CertificateNotYetValidException {
        }
        @Override
        public void checkValidity(Date date) throws CertificateExpiredException, CertificateNotYetValidException {
        }
        @Override
        public int getVersion() {
            return 0;
        }
        @Override
        public BigInteger getSerialNumber() {
            return null;
        }
        @Override
        public Principal getIssuerDN() {
            return null;
        }
        @Override
        public Principal getSubjectDN() {
            return null;
        }
        @Override
        public Date getNotBefore() {
            return null;
        }
        @Override
        public Date getNotAfter() {
            return null;
        }
        @Override
        public byte[] getTBSCertificate() throws CertificateEncodingException {
            return null;
        }
        @Override
        public byte[] getSignature() {
            return null;
        }
        @Override
        public String getSigAlgName() {
            return null;
        }
        @Override
        public String getSigAlgOID() {
            return null;
        }
        @Override
        public byte[] getSigAlgParams() {
            return null;
        }
        @Override
        public boolean[] getIssuerUniqueID() {
            return null;
        }
        @Override
        public boolean[] getSubjectUniqueID() {
            return null;
        }
        @Override
        public boolean[] getKeyUsage() {
            return null;
        }
        @Override
        public int getBasicConstraints() {
            return 0;
        }
        @Override
        public byte[] getEncoded() throws CertificateEncodingException {
            return encoded;
        }
        @Override
        public void verify(PublicKey key) throws CertificateException, NoSuchAlgorithmException, InvalidKeyException, NoSuchProviderException, SignatureException {
        }
        @Override
        public void verify(PublicKey key, String sigProvider) throws CertificateException, NoSuchAlgorithmException, InvalidKeyException, NoSuchProviderException, SignatureException {
        }
        @Override
        public String toString() {
            return null;
        }
        @Override
        public PublicKey getPublicKey() {
            return publicKey;
        }
        @Override
        public X500Principal getSubjectX500Principal() {
            return principal;
        }
        @Override
        public Collection<List<?>> getSubjectAlternativeNames() {
            List<Object> subjectAlternativeNamePair = new ArrayList<>();
            subjectAlternativeNamePair.add(6);
            subjectAlternativeNamePair.add(subjectAlternativeName);
            return Collections.singletonList(subjectAlternativeNamePair);
        }
    }

    public static class TestKeyManager implements X509KeyManager {

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return null;
        }
        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return null;
        }
        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return null;
        }
        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return null;
        }
        @Override
        public PrivateKey getPrivateKey(String alias) {
            return null;
        }
        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return null;
        }

    }

    public static class TestTrustManager implements X509TrustManager {

        X509Certificate cert;
        public TestTrustManager(X509Certificate testCert) {
            cert = testCert;
        }
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (!Arrays.equals(cert.getEncoded(), chain[0].getEncoded())) {
                throw new CertificateException("Client cert not trusted");
            }
        }
        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (!Arrays.equals(cert.getEncoded(), chain[0].getEncoded())) {
                throw new CertificateException("Server cert not trusted");
            }
        }
        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return null;
        }

    }

    protected X509AuthenticationProvider createProvider(X509Certificate trustedCert) {
        return new X509AuthenticationProvider(new TestTrustManager(trustedCert), new TestKeyManager());
    }

}
