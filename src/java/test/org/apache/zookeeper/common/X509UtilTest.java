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
import org.apache.zookeeper.test.ClientBase;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.security.Security;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

@RunWith(Parameterized.class)
public class X509UtilTest extends ZKTestCase {

    @Parameterized.Parameters
    public static Collection<Object[]> params() {
        ArrayList<Object[]> result = new ArrayList<>();
        int paramIndex = 0;
        for (X509KeyType caKeyType : X509KeyType.values()) {
            for (X509KeyType certKeyType : X509KeyType.values()) {
                for (String keyPassword : new String[]{"", "pa$$w0rd"}) {
                    result.add(new Object[]{caKeyType, certKeyType, keyPassword, paramIndex++});
                }
            }
        }
        return result;
    }

    /**
     * Because key generation and writing / deleting files is kind of expensive, we cache the certs and on-disk files
     * between test cases. None of the test cases modify any of this data so it's safe to reuse between tests. This
     * caching makes all test cases after the first one for a given parameter combination complete almost instantly.
     */
    private static Map<Integer, X509TestContext> cachedTestContexts;
    private static File tmpDir;

    private X509TestContext x509TestContext;
    private X509Util x509Util;
    private static final String[] customCipherSuites = new String[]{
            "SSL_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA",
            "SSL_DH_anon_EXPORT_WITH_DES40_CBC_SHA"};

    @BeforeClass
    public static void setUpClass() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        cachedTestContexts = new HashMap<>();
        tmpDir = ClientBase.createEmptyTestDir();
    }

    @AfterClass
    public static void cleanUpClass() {
        Security.removeProvider("BC");
        cachedTestContexts.clear();
        cachedTestContexts = null;
    }

    public X509UtilTest(
            X509KeyType caKeyType,
            X509KeyType certKeyType,
            String keyPassword,
            Integer paramIndex) throws Exception {
        if (cachedTestContexts.containsKey(paramIndex)) {
            x509TestContext = cachedTestContexts.get(paramIndex);
        } else {
            x509TestContext = X509TestContext.newBuilder()
                    .setTempDir(tmpDir)
                    .setKeyStorePassword(keyPassword)
                    .setKeyStoreKeyType(certKeyType)
                    .setTrustStorePassword(keyPassword)
                    .setTrustStoreKeyType(caKeyType)
                    .build();
            cachedTestContexts.put(paramIndex, x509TestContext);
        }
    }

    @Before
    public void setUp() throws Exception {
        x509Util = new ClientX509Util();
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, "org.apache.zookeeper.server.NettyServerCnxnFactory");
        System.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET, "org.apache.zookeeper.ClientCnxnSocketNetty");
        x509TestContext.setSystemProperties(x509Util, X509Util.StoreFileType.JKS, X509Util.StoreFileType.JKS);
    }

    @After
    public void cleanUp() {
        System.clearProperty(x509Util.getSslKeystoreLocationProperty());
        System.clearProperty(x509Util.getSslKeystorePasswdProperty());
        System.clearProperty(x509Util.getSslKeystoreTypeProperty());
        System.clearProperty(x509Util.getSslTruststoreLocationProperty());
        System.clearProperty(x509Util.getSslTruststorePasswdProperty());
        System.clearProperty(x509Util.getSslTruststoreTypeProperty());
        System.clearProperty(x509Util.getSslHostnameVerificationEnabledProperty());
        System.clearProperty(x509Util.getSslOcspEnabledProperty());
        System.clearProperty(x509Util.getSslCrlEnabledProperty());
        System.clearProperty(x509Util.getCipherSuitesProperty());
        System.clearProperty(x509Util.getSslProtocolProperty());
        System.clearProperty("com.sun.net.ssl.checkRevocation");
        System.clearProperty("com.sun.security.enableCRLDP");
        Security.setProperty("ocsp.enable", Boolean.FALSE.toString());
        Security.setProperty("com.sun.security.enableCRLDP", Boolean.FALSE.toString());
        System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
        System.clearProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET);
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
    public void testCreateSSLContextWithoutKeyStoreLocation() throws Exception {
        System.clearProperty(x509Util.getSslKeystoreLocationProperty());
        x509Util.getDefaultSSLContext();
    }

    @Test(timeout = 5000, expected = X509Exception.SSLContextException.class)
    public void testCreateSSLContextWithoutKeyStorePassword() throws Exception {
        if (!x509TestContext.isKeyStoreEncrypted()) {
            throw new X509Exception.SSLContextException("");
        }
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

    @Test
    public void testLoadPEMKeyStore() throws Exception {
        // Make sure we can instantiate a key manager from the PEM file on disk
        X509KeyManager km = X509Util.createKeyManager(
                x509TestContext.getKeyStoreFile(X509Util.StoreFileType.PEM).getAbsolutePath(),
                x509TestContext.getKeyStorePassword(),
                X509Util.StoreFileType.PEM);
    }

    @Test
    public void testLoadPEMKeyStoreNullPassword() throws Exception {
        if (!x509TestContext.getKeyStorePassword().isEmpty()) {
            return;
        }
        // Make sure that empty password and null password are treated the same
        X509KeyManager km = X509Util.createKeyManager(
                x509TestContext.getKeyStoreFile(X509Util.StoreFileType.PEM).getAbsolutePath(),
                null,
                X509Util.StoreFileType.PEM);
    }

    @Test
    public void testLoadPEMKeyStoreAutodetectStoreFileType() throws Exception {
        // Make sure we can instantiate a key manager from the PEM file on disk
        X509KeyManager km = X509Util.createKeyManager(
                x509TestContext.getKeyStoreFile(X509Util.StoreFileType.PEM).getAbsolutePath(),
                x509TestContext.getKeyStorePassword(),
                null /* null StoreFileType means 'autodetect from file extension' */);
    }

    @Test(expected = X509Exception.KeyManagerException.class)
    public void testLoadPEMKeyStoreWithWrongPassword() throws Exception {
        // Attempting to load with the wrong key password should fail
        X509KeyManager km = X509Util.createKeyManager(
                x509TestContext.getKeyStoreFile(X509Util.StoreFileType.PEM).getAbsolutePath(),
                "wrong password", // intentionally use the wrong password
                X509Util.StoreFileType.PEM);
    }

    @Test
    public void testLoadPEMTrustStore() throws Exception {
        // Make sure we can instantiate a trust manager from the PEM file on disk
        X509TrustManager tm = X509Util.createTrustManager(
                x509TestContext.getTrustStoreFile(X509Util.StoreFileType.PEM).getAbsolutePath(),
                x509TestContext.getTrustStorePassword(),
                X509Util.StoreFileType.PEM,
                false,
                false,
                true,
                true);
    }

    @Test
    public void testLoadPEMTrustStoreNullPassword() throws Exception {
        if (!x509TestContext.getTrustStorePassword().isEmpty()) {
            return;
        }
        // Make sure that empty password and null password are treated the same
        X509TrustManager tm = X509Util.createTrustManager(
                x509TestContext.getTrustStoreFile(X509Util.StoreFileType.PEM).getAbsolutePath(),
                null,
                X509Util.StoreFileType.PEM,
                false,
                false,
                true,
                true);

    }

    @Test
    public void testLoadPEMTrustStoreAutodetectStoreFileType() throws Exception {
        // Make sure we can instantiate a trust manager from the PEM file on disk
        X509TrustManager tm = X509Util.createTrustManager(
                x509TestContext.getTrustStoreFile(X509Util.StoreFileType.PEM).getAbsolutePath(),
                x509TestContext.getTrustStorePassword(),
                null,  // null StoreFileType means 'autodetect from file extension'
                false,
                false,
                true,
                true);
    }

    @Test
    public void testLoadJKSKeyStore() throws Exception {
        // Make sure we can instantiate a key manager from the JKS file on disk
        X509KeyManager km = X509Util.createKeyManager(
                x509TestContext.getKeyStoreFile(X509Util.StoreFileType.JKS).getAbsolutePath(),
                x509TestContext.getKeyStorePassword(),
                X509Util.StoreFileType.JKS);
    }

    @Test
    public void testLoadJKSKeyStoreNullPassword() throws Exception {
        if (!x509TestContext.getKeyStorePassword().isEmpty()) {
            return;
        }
        // Make sure that empty password and null password are treated the same
        X509KeyManager km = X509Util.createKeyManager(
                x509TestContext.getKeyStoreFile(X509Util.StoreFileType.JKS).getAbsolutePath(),
                null,
                X509Util.StoreFileType.JKS);
    }

    @Test
    public void testLoadJKSKeyStoreAutodetectStoreFileType() throws Exception {
        // Make sure we can instantiate a key manager from the JKS file on disk
        X509KeyManager km = X509Util.createKeyManager(
                x509TestContext.getKeyStoreFile(X509Util.StoreFileType.JKS).getAbsolutePath(),
                x509TestContext.getKeyStorePassword(),
                null /* null StoreFileType means 'autodetect from file extension' */);
    }

    @Test(expected = X509Exception.KeyManagerException.class)
    public void testLoadJKSKeyStoreWithWrongPassword() throws Exception {
        // Attempting to load with the wrong key password should fail
        X509KeyManager km = X509Util.createKeyManager(
                x509TestContext.getKeyStoreFile(X509Util.StoreFileType.JKS).getAbsolutePath(),
                "wrong password",
                X509Util.StoreFileType.JKS);
    }

    @Test
    public void testLoadJKSTrustStore() throws Exception {
        // Make sure we can instantiate a trust manager from the JKS file on disk
        X509TrustManager tm = X509Util.createTrustManager(
                x509TestContext.getTrustStoreFile(X509Util.StoreFileType.JKS).getAbsolutePath(),
                x509TestContext.getTrustStorePassword(),
                X509Util.StoreFileType.JKS,
                true,
                true,
                true,
                true);
    }

    @Test
    public void testLoadJKSTrustStoreNullPassword() throws Exception {
        if (!x509TestContext.getTrustStorePassword().isEmpty()) {
            return;
        }
        // Make sure that empty password and null password are treated the same
        X509TrustManager tm = X509Util.createTrustManager(
                x509TestContext.getTrustStoreFile(X509Util.StoreFileType.JKS).getAbsolutePath(),
                null,
                X509Util.StoreFileType.JKS,
                false,
                false,
                true,
                true);
    }

    @Test
    public void testLoadJKSTrustStoreAutodetectStoreFileType() throws Exception {
        // Make sure we can instantiate a trust manager from the JKS file on disk
        X509TrustManager tm = X509Util.createTrustManager(
                x509TestContext.getTrustStoreFile(X509Util.StoreFileType.JKS).getAbsolutePath(),
                x509TestContext.getTrustStorePassword(),
                null,  // null StoreFileType means 'autodetect from file extension'
                true,
                true,
                true,
                true);
    }

    @Test(expected = X509Exception.TrustManagerException.class)
    public void testLoadJKSTrustStoreWithWrongPassword() throws Exception {
        // Attempting to load with the wrong key password should fail
        X509TrustManager tm = X509Util.createTrustManager(
                x509TestContext.getTrustStoreFile(X509Util.StoreFileType.JKS).getAbsolutePath(),
                "wrong password",
                X509Util.StoreFileType.JKS,
                true,
                true,
                true,
                true);
    }

    // Warning: this will reset the x509Util
    private void setCustomCipherSuites() {
        System.setProperty(x509Util.getCipherSuitesProperty(), customCipherSuites[0] + "," + customCipherSuites[1]);
        x509Util = new ClientX509Util();
    }
}
