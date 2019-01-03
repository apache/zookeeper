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

import java.security.Security;
import java.util.Collection;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class X509UtilTest extends BaseX509ParameterizedTestCase {
    private X509Util x509Util;
    private static final String[] customCipherSuites = new String[]{
            "SSL_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA",
            "SSL_DH_anon_EXPORT_WITH_DES40_CBC_SHA"};

    @Parameterized.Parameters
    public static Collection<Object[]> params() {
        return BaseX509ParameterizedTestCase.defaultParams();
    }

    public X509UtilTest(
            X509KeyType caKeyType,
            X509KeyType certKeyType,
            String keyPassword,
            Integer paramIndex) {
        super(paramIndex, () -> {
            try {
                return X509TestContext.newBuilder()
                        .setTempDir(tempDir)
                        .setKeyStorePassword(keyPassword)
                        .setKeyStoreKeyType(certKeyType)
                        .setTrustStorePassword(keyPassword)
                        .setTrustStoreKeyType(caKeyType)
                        .build();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Before
    public void setUp() throws Exception {
        try (X509Util x509util = new ClientX509Util()) {
            x509TestContext.setSystemProperties(x509util, KeyStoreFileType.JKS, KeyStoreFileType.JKS);
        }
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, "org.apache.zookeeper.server.NettyServerCnxnFactory");
        System.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET, "org.apache.zookeeper.ClientCnxnSocketNetty");
        x509Util = new ClientX509Util();
    }

    @After
    public void cleanUp() {
        x509TestContext.clearSystemProperties(x509Util);
        System.clearProperty(x509Util.getSslOcspEnabledProperty());
        System.clearProperty(x509Util.getSslCrlEnabledProperty());
        System.clearProperty(x509Util.getCipherSuitesProperty());
        System.clearProperty(x509Util.getSslProtocolProperty());
        System.clearProperty(x509Util.getSslHandshakeDetectionTimeoutMillisProperty());
        System.clearProperty("com.sun.net.ssl.checkRevocation");
        System.clearProperty("com.sun.security.enableCRLDP");
        Security.setProperty("ocsp.enable", Boolean.FALSE.toString());
        Security.setProperty("com.sun.security.enableCRLDP", Boolean.FALSE.toString());
        System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
        System.clearProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET);
        x509Util.close();
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
                x509TestContext.getKeyStoreFile(KeyStoreFileType.PEM).getAbsolutePath(),
                x509TestContext.getKeyStorePassword(),
                KeyStoreFileType.PEM.getPropertyValue());
    }

    @Test
    public void testLoadPEMKeyStoreNullPassword() throws Exception {
        if (!x509TestContext.getKeyStorePassword().isEmpty()) {
            return;
        }
        // Make sure that empty password and null password are treated the same
        X509KeyManager km = X509Util.createKeyManager(
                x509TestContext.getKeyStoreFile(KeyStoreFileType.PEM).getAbsolutePath(),
                null,
                KeyStoreFileType.PEM.getPropertyValue());
    }

    @Test
    public void testLoadPEMKeyStoreAutodetectStoreFileType() throws Exception {
        // Make sure we can instantiate a key manager from the PEM file on disk
        X509KeyManager km = X509Util.createKeyManager(
                x509TestContext.getKeyStoreFile(KeyStoreFileType.PEM).getAbsolutePath(),
                x509TestContext.getKeyStorePassword(),
                null /* null StoreFileType means 'autodetect from file extension' */);
    }

    @Test(expected = X509Exception.KeyManagerException.class)
    public void testLoadPEMKeyStoreWithWrongPassword() throws Exception {
        // Attempting to load with the wrong key password should fail
        X509KeyManager km = X509Util.createKeyManager(
                x509TestContext.getKeyStoreFile(KeyStoreFileType.PEM).getAbsolutePath(),
                "wrong password", // intentionally use the wrong password
                KeyStoreFileType.PEM.getPropertyValue());
    }

    @Test
    public void testLoadPEMTrustStore() throws Exception {
        // Make sure we can instantiate a trust manager from the PEM file on disk
        X509TrustManager tm = X509Util.createTrustManager(
                x509TestContext.getTrustStoreFile(KeyStoreFileType.PEM).getAbsolutePath(),
                x509TestContext.getTrustStorePassword(),
                KeyStoreFileType.PEM.getPropertyValue(),
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
                x509TestContext.getTrustStoreFile(KeyStoreFileType.PEM).getAbsolutePath(),
                null,
                KeyStoreFileType.PEM.getPropertyValue(),
                false,
                false,
                true,
                true);

    }

    @Test
    public void testLoadPEMTrustStoreAutodetectStoreFileType() throws Exception {
        // Make sure we can instantiate a trust manager from the PEM file on disk
        X509TrustManager tm = X509Util.createTrustManager(
                x509TestContext.getTrustStoreFile(KeyStoreFileType.PEM).getAbsolutePath(),
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
                x509TestContext.getKeyStoreFile(KeyStoreFileType.JKS).getAbsolutePath(),
                x509TestContext.getKeyStorePassword(),
                KeyStoreFileType.JKS.getPropertyValue());
    }

    @Test
    public void testLoadJKSKeyStoreNullPassword() throws Exception {
        if (!x509TestContext.getKeyStorePassword().isEmpty()) {
            return;
        }
        // Make sure that empty password and null password are treated the same
        X509KeyManager km = X509Util.createKeyManager(
                x509TestContext.getKeyStoreFile(KeyStoreFileType.JKS).getAbsolutePath(),
                null,
                KeyStoreFileType.JKS.getPropertyValue());
    }

    @Test
    public void testLoadJKSKeyStoreAutodetectStoreFileType() throws Exception {
        // Make sure we can instantiate a key manager from the JKS file on disk
        X509KeyManager km = X509Util.createKeyManager(
                x509TestContext.getKeyStoreFile(KeyStoreFileType.JKS).getAbsolutePath(),
                x509TestContext.getKeyStorePassword(),
                null /* null StoreFileType means 'autodetect from file extension' */);
    }

    @Test(expected = X509Exception.KeyManagerException.class)
    public void testLoadJKSKeyStoreWithWrongPassword() throws Exception {
        // Attempting to load with the wrong key password should fail
        X509KeyManager km = X509Util.createKeyManager(
                x509TestContext.getKeyStoreFile(KeyStoreFileType.JKS).getAbsolutePath(),
                "wrong password",
                KeyStoreFileType.JKS.getPropertyValue());
    }

    @Test
    public void testLoadJKSTrustStore() throws Exception {
        // Make sure we can instantiate a trust manager from the JKS file on disk
        X509TrustManager tm = X509Util.createTrustManager(
                x509TestContext.getTrustStoreFile(KeyStoreFileType.JKS).getAbsolutePath(),
                x509TestContext.getTrustStorePassword(),
                KeyStoreFileType.JKS.getPropertyValue(),
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
                x509TestContext.getTrustStoreFile(KeyStoreFileType.JKS).getAbsolutePath(),
                null,
                KeyStoreFileType.JKS.getPropertyValue(),
                false,
                false,
                true,
                true);
    }

    @Test
    public void testLoadJKSTrustStoreAutodetectStoreFileType() throws Exception {
        // Make sure we can instantiate a trust manager from the JKS file on disk
        X509TrustManager tm = X509Util.createTrustManager(
                x509TestContext.getTrustStoreFile(KeyStoreFileType.JKS).getAbsolutePath(),
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
                x509TestContext.getTrustStoreFile(KeyStoreFileType.JKS).getAbsolutePath(),
                "wrong password",
                KeyStoreFileType.JKS.getPropertyValue(),
                true,
                true,
                true,
                true);
    }

    @Test
    public void testGetSslHandshakeDetectionTimeoutMillisProperty() {
        Assert.assertEquals(
                X509Util.DEFAULT_HANDSHAKE_DETECTION_TIMEOUT_MILLIS,
                x509Util.getSslHandshakeTimeoutMillis());
        // Note: need to create a new ClientX509Util each time to pick up modified property value
        String newPropertyString = Integer.toString(X509Util.DEFAULT_HANDSHAKE_DETECTION_TIMEOUT_MILLIS + 1);
        System.setProperty(x509Util.getSslHandshakeDetectionTimeoutMillisProperty(), newPropertyString);
        try (X509Util tempX509Util = new ClientX509Util()) {
            Assert.assertEquals(
                    X509Util.DEFAULT_HANDSHAKE_DETECTION_TIMEOUT_MILLIS + 1,
                    tempX509Util.getSslHandshakeTimeoutMillis());
        }
        // 0 value not allowed, will return the default
        System.setProperty(x509Util.getSslHandshakeDetectionTimeoutMillisProperty(), "0");
        try (X509Util tempX509Util = new ClientX509Util()) {
            Assert.assertEquals(
                    X509Util.DEFAULT_HANDSHAKE_DETECTION_TIMEOUT_MILLIS,
                    tempX509Util.getSslHandshakeTimeoutMillis());
        }
        // Negative value not allowed, will return the default
        System.setProperty(x509Util.getSslHandshakeDetectionTimeoutMillisProperty(), "-1");
        try (X509Util tempX509Util = new ClientX509Util()) {
            Assert.assertEquals(
                    X509Util.DEFAULT_HANDSHAKE_DETECTION_TIMEOUT_MILLIS,
                    tempX509Util.getSslHandshakeTimeoutMillis());
        }
    }

    // Warning: this will reset the x509Util
    private void setCustomCipherSuites() {
        System.setProperty(x509Util.getCipherSuitesProperty(), customCipherSuites[0] + "," + customCipherSuites[1]);
        x509Util.close(); // remember to close old instance before replacing it
        x509Util = new ClientX509Util();
    }
}
