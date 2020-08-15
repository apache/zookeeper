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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.net.ssl.HandshakeCompletedEvent;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class X509UtilTest extends BaseX509ParameterizedTestCase {

    private X509Util x509Util;
    private static final String[] customCipherSuites = new String[]{
        "SSL_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA",
        "SSL_DH_anon_EXPORT_WITH_DES40_CBC_SHA"
    };

    public void init(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        super.init(caKeyType, certKeyType, keyPassword, paramIndex);
        try (X509Util x509util = new ClientX509Util()) {
            x509TestContext.setSystemProperties(x509util, KeyStoreFileType.JKS, KeyStoreFileType.JKS);
        }
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, "org.apache.zookeeper.server.NettyServerCnxnFactory");
        System.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET, "org.apache.zookeeper.ClientCnxnSocketNetty");
        x509Util = new ClientX509Util();
    }

    @AfterEach
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

    @ParameterizedTest
    @MethodSource("data")
    @Timeout(value = 5)
    public void testCreateSSLContextWithoutCustomProtocol(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        SSLContext sslContext = x509Util.getDefaultSSLContext();
        assertEquals(X509Util.DEFAULT_PROTOCOL, sslContext.getProtocol());
    }

    @ParameterizedTest
    @MethodSource("data")
    @Timeout(value = 5)
    public void testCreateSSLContextWithCustomProtocol(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        final String protocol = "TLSv1.1";
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        System.setProperty(x509Util.getSslProtocolProperty(), protocol);
        SSLContext sslContext = x509Util.getDefaultSSLContext();
        assertEquals(protocol, sslContext.getProtocol());
    }

    @ParameterizedTest
    @MethodSource("data")
    @Timeout(value = 5)
    public void testCreateSSLContextWithoutKeyStoreLocation(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        System.clearProperty(x509Util.getSslKeystoreLocationProperty());
        x509Util.getDefaultSSLContext();
    }

    @ParameterizedTest
    @MethodSource("data")
    @Timeout(value = 5)
    public void testCreateSSLContextWithoutKeyStorePassword(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        assertThrows(X509Exception.SSLContextException.class, () -> {
            if (!x509TestContext.isKeyStoreEncrypted()) {
                throw new X509Exception.SSLContextException("");
            }
            System.clearProperty(x509Util.getSslKeystorePasswdProperty());
            x509Util.getDefaultSSLContext();
        });
    }

    @ParameterizedTest
    @MethodSource("data")
    @Timeout(value = 5)
    public void testCreateSSLContextWithCustomCipherSuites(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        setCustomCipherSuites();
        SSLSocket sslSocket = x509Util.createSSLSocket();
        assertArrayEquals(customCipherSuites, sslSocket.getEnabledCipherSuites());
    }

    // It would be great to test the value of PKIXBuilderParameters#setRevocationEnabled but it does not appear to be
    // possible
    @ParameterizedTest
    @MethodSource("data")
    @Timeout(value = 5)
    public void testCRLEnabled(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        System.setProperty(x509Util.getSslCrlEnabledProperty(), "true");
        x509Util.getDefaultSSLContext();
        assertTrue(Boolean.valueOf(System.getProperty("com.sun.net.ssl.checkRevocation")));
        assertTrue(Boolean.valueOf(System.getProperty("com.sun.security.enableCRLDP")));
        assertFalse(Boolean.valueOf(Security.getProperty("ocsp.enable")));
    }

    @ParameterizedTest
    @MethodSource("data")
    @Timeout(value = 5)
    public void testCRLDisabled(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        x509Util.getDefaultSSLContext();
        assertFalse(Boolean.valueOf(System.getProperty("com.sun.net.ssl.checkRevocation")));
        assertFalse(Boolean.valueOf(System.getProperty("com.sun.security.enableCRLDP")));
        assertFalse(Boolean.valueOf(Security.getProperty("ocsp.enable")));
    }

    @ParameterizedTest
    @MethodSource("data")
    @Timeout(value = 5)
    public void testOCSPEnabled(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        System.setProperty(x509Util.getSslOcspEnabledProperty(), "true");
        x509Util.getDefaultSSLContext();
        assertTrue(Boolean.valueOf(System.getProperty("com.sun.net.ssl.checkRevocation")));
        assertTrue(Boolean.valueOf(System.getProperty("com.sun.security.enableCRLDP")));
        assertTrue(Boolean.valueOf(Security.getProperty("ocsp.enable")));
    }

    @ParameterizedTest
    @MethodSource("data")
    @Timeout(value = 5)
    public void testCreateSSLSocket(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        setCustomCipherSuites();
        SSLSocket sslSocket = x509Util.createSSLSocket();
        assertArrayEquals(customCipherSuites, sslSocket.getEnabledCipherSuites());
    }

    @ParameterizedTest
    @MethodSource("data")
    @Timeout(value = 5)
    public void testCreateSSLServerSocketWithoutPort(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        setCustomCipherSuites();
        SSLServerSocket sslServerSocket = x509Util.createSSLServerSocket();
        assertArrayEquals(customCipherSuites, sslServerSocket.getEnabledCipherSuites());
        assertTrue(sslServerSocket.getNeedClientAuth());
    }

    @ParameterizedTest
    @MethodSource("data")
    @Timeout(value = 5)
    public void testCreateSSLServerSocketWithPort(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        setCustomCipherSuites();
        int port = PortAssignment.unique();
        SSLServerSocket sslServerSocket = x509Util.createSSLServerSocket(port);
        assertEquals(sslServerSocket.getLocalPort(), port);
        assertArrayEquals(customCipherSuites, sslServerSocket.getEnabledCipherSuites());
        assertTrue(sslServerSocket.getNeedClientAuth());
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadPEMKeyStore(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        // Make sure we can instantiate a key manager from the PEM file on disk
        X509KeyManager km = X509Util.createKeyManager(
            x509TestContext.getKeyStoreFile(KeyStoreFileType.PEM).getAbsolutePath(),
            x509TestContext.getKeyStorePassword(),
            KeyStoreFileType.PEM.getPropertyValue());
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadPEMKeyStoreNullPassword(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        if (!x509TestContext.getKeyStorePassword().isEmpty()) {
            return;
        }
        // Make sure that empty password and null password are treated the same
        X509KeyManager km = X509Util.createKeyManager(
            x509TestContext.getKeyStoreFile(KeyStoreFileType.PEM).getAbsolutePath(),
            null,
            KeyStoreFileType.PEM.getPropertyValue());
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadPEMKeyStoreAutodetectStoreFileType(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        // Make sure we can instantiate a key manager from the PEM file on disk
        X509KeyManager km = X509Util.createKeyManager(
            x509TestContext.getKeyStoreFile(KeyStoreFileType.PEM).getAbsolutePath(),
            x509TestContext.getKeyStorePassword(),
            null /* null StoreFileType means 'autodetect from file extension' */);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadPEMKeyStoreWithWrongPassword(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        assertThrows(X509Exception.KeyManagerException.class, () -> {
            // Attempting to load with the wrong key password should fail
            X509KeyManager km = X509Util.createKeyManager(
                    x509TestContext.getKeyStoreFile(KeyStoreFileType.PEM).getAbsolutePath(),
                    "wrong password", // intentionally use the wrong password
                    KeyStoreFileType.PEM.getPropertyValue());
        });
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadPEMTrustStore(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        // Make sure we can instantiate a trust manager from the PEM file on disk
        X509TrustManager tm = X509Util.createTrustManager(
            x509TestContext.getTrustStoreFile(KeyStoreFileType.PEM).getAbsolutePath(),
            x509TestContext.getTrustStorePassword(), KeyStoreFileType.PEM.getPropertyValue(),
            false,
            false,
            true,
            true);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadPEMTrustStoreNullPassword(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
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

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadPEMTrustStoreAutodetectStoreFileType(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
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

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadJKSKeyStore(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        // Make sure we can instantiate a key manager from the JKS file on disk
        X509KeyManager km = X509Util.createKeyManager(
            x509TestContext.getKeyStoreFile(KeyStoreFileType.JKS).getAbsolutePath(),
            x509TestContext.getKeyStorePassword(),
            KeyStoreFileType.JKS.getPropertyValue());
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadJKSKeyStoreNullPassword(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        if (!x509TestContext.getKeyStorePassword().isEmpty()) {
            return;
        }
        // Make sure that empty password and null password are treated the same
        X509KeyManager km = X509Util.createKeyManager(
            x509TestContext.getKeyStoreFile(KeyStoreFileType.JKS).getAbsolutePath(),
            null,
            KeyStoreFileType.JKS.getPropertyValue());
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadJKSKeyStoreAutodetectStoreFileType(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        // Make sure we can instantiate a key manager from the JKS file on disk
        X509KeyManager km = X509Util.createKeyManager(
            x509TestContext.getKeyStoreFile(KeyStoreFileType.JKS).getAbsolutePath(),
            x509TestContext.getKeyStorePassword(),
            null /* null StoreFileType means 'autodetect from file extension' */);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadJKSKeyStoreWithWrongPassword(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        assertThrows(X509Exception.KeyManagerException.class, () -> {
            // Attempting to load with the wrong key password should fail
            X509KeyManager km = X509Util.createKeyManager(
                    x509TestContext.getKeyStoreFile(KeyStoreFileType.JKS).getAbsolutePath(),
                    "wrong password",
                    KeyStoreFileType.JKS.getPropertyValue());
        });
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadJKSTrustStore(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
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

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadJKSTrustStoreNullPassword(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
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

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadJKSTrustStoreAutodetectStoreFileType(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
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

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadJKSTrustStoreWithWrongPassword(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        assertThrows(X509Exception.TrustManagerException.class, () -> {
            // Attempting to load with the wrong key password should fail
            X509TrustManager tm = X509Util.createTrustManager(
                    x509TestContext.getTrustStoreFile(KeyStoreFileType.JKS).getAbsolutePath(),
                    "wrong password",
                    KeyStoreFileType.JKS.getPropertyValue(),
                    true,
                    true,
                    true,
                    true);
        });
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadPKCS12KeyStore(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        // Make sure we can instantiate a key manager from the PKCS12 file on disk
        X509KeyManager km = X509Util.createKeyManager(
            x509TestContext.getKeyStoreFile(KeyStoreFileType.PKCS12).getAbsolutePath(),
            x509TestContext.getKeyStorePassword(),
            KeyStoreFileType.PKCS12.getPropertyValue());
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadPKCS12KeyStoreNullPassword(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        if (!x509TestContext.getKeyStorePassword().isEmpty()) {
            return;
        }
        // Make sure that empty password and null password are treated the same
        X509KeyManager km = X509Util.createKeyManager(
            x509TestContext.getKeyStoreFile(KeyStoreFileType.PKCS12).getAbsolutePath(),
            null,
            KeyStoreFileType.PKCS12.getPropertyValue());
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadPKCS12KeyStoreAutodetectStoreFileType(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        // Make sure we can instantiate a key manager from the PKCS12 file on disk
        X509KeyManager km = X509Util.createKeyManager(
            x509TestContext.getKeyStoreFile(KeyStoreFileType.PKCS12).getAbsolutePath(),
            x509TestContext.getKeyStorePassword(),
            null /* null StoreFileType means 'autodetect from file extension' */);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadPKCS12KeyStoreWithWrongPassword(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        assertThrows(X509Exception.KeyManagerException.class, () -> {
            // Attempting to load with the wrong key password should fail
            X509KeyManager km = X509Util.createKeyManager(
                    x509TestContext.getKeyStoreFile(KeyStoreFileType.PKCS12).getAbsolutePath(),
                    "wrong password",
                    KeyStoreFileType.PKCS12.getPropertyValue());
        });
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadPKCS12TrustStore(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        // Make sure we can instantiate a trust manager from the PKCS12 file on disk
        X509TrustManager tm = X509Util.createTrustManager(
            x509TestContext.getTrustStoreFile(KeyStoreFileType.PKCS12).getAbsolutePath(),
            x509TestContext.getTrustStorePassword(), KeyStoreFileType.PKCS12.getPropertyValue(),
            true,
            true,
            true,
            true);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadPKCS12TrustStoreNullPassword(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        if (!x509TestContext.getTrustStorePassword().isEmpty()) {
            return;
        }
        // Make sure that empty password and null password are treated the same
        X509TrustManager tm = X509Util.createTrustManager(
            x509TestContext.getTrustStoreFile(KeyStoreFileType.PKCS12).getAbsolutePath(),
            null,
            KeyStoreFileType.PKCS12.getPropertyValue(),
            false,
            false,
            true,
            true);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadPKCS12TrustStoreAutodetectStoreFileType(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        // Make sure we can instantiate a trust manager from the PKCS12 file on disk
        X509TrustManager tm = X509Util.createTrustManager(
            x509TestContext.getTrustStoreFile(KeyStoreFileType.PKCS12).getAbsolutePath(),
            x509TestContext.getTrustStorePassword(),
            null,  // null StoreFileType means 'autodetect from file extension'
            true,
            true,
            true,
            true);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadPKCS12TrustStoreWithWrongPassword(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        assertThrows(X509Exception.TrustManagerException.class, () -> {
            // Attempting to load with the wrong key password should fail
            X509TrustManager tm = X509Util.createTrustManager(
                    x509TestContext.getTrustStoreFile(KeyStoreFileType.PKCS12).getAbsolutePath(),
                    "wrong password",
                    KeyStoreFileType.PKCS12.getPropertyValue(),
                    true,
                    true,
                    true,
                    true);
        });
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testGetSslHandshakeDetectionTimeoutMillisProperty(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        assertEquals(X509Util.DEFAULT_HANDSHAKE_DETECTION_TIMEOUT_MILLIS, x509Util.getSslHandshakeTimeoutMillis());
        // Note: need to create a new ClientX509Util each time to pick up modified property value
        String newPropertyString = Integer.toString(X509Util.DEFAULT_HANDSHAKE_DETECTION_TIMEOUT_MILLIS + 1);
        System.setProperty(x509Util.getSslHandshakeDetectionTimeoutMillisProperty(), newPropertyString);
        try (X509Util tempX509Util = new ClientX509Util()) {
            assertEquals(X509Util.DEFAULT_HANDSHAKE_DETECTION_TIMEOUT_MILLIS
                                        + 1, tempX509Util.getSslHandshakeTimeoutMillis());
        }
        // 0 value not allowed, will return the default
        System.setProperty(x509Util.getSslHandshakeDetectionTimeoutMillisProperty(), "0");
        try (X509Util tempX509Util = new ClientX509Util()) {
            assertEquals(X509Util.DEFAULT_HANDSHAKE_DETECTION_TIMEOUT_MILLIS, tempX509Util.getSslHandshakeTimeoutMillis());
        }
        // Negative value not allowed, will return the default
        System.setProperty(x509Util.getSslHandshakeDetectionTimeoutMillisProperty(), "-1");
        try (X509Util tempX509Util = new ClientX509Util()) {
            assertEquals(X509Util.DEFAULT_HANDSHAKE_DETECTION_TIMEOUT_MILLIS, tempX509Util.getSslHandshakeTimeoutMillis());
        }
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testCreateSSLContext_invalidCustomSSLContextClass(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        assertThrows(X509Exception.SSLContextException.class, () -> {
            ZKConfig zkConfig = new ZKConfig();
            ClientX509Util clientX509Util = new ClientX509Util();
            zkConfig.setProperty(clientX509Util.getSslContextSupplierClassProperty(), String.class.getCanonicalName());
            clientX509Util.createSSLContext(zkConfig);
        });
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testCreateSSLContext_validCustomSSLContextClass(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        ZKConfig zkConfig = new ZKConfig();
        ClientX509Util clientX509Util = new ClientX509Util();
        zkConfig.setProperty(clientX509Util.getSslContextSupplierClassProperty(), SslContextSupplier.class.getName());
        final SSLContext sslContext = clientX509Util.createSSLContext(zkConfig);
        assertEquals(SSLContext.getDefault(), sslContext);
    }

    private static void forceClose(Socket s) {
        if (s == null || s.isClosed()) {
            return;
        }
        try {
            s.close();
        } catch (IOException e) {
        }
    }

    private static void forceClose(ServerSocket s) {
        if (s == null || s.isClosed()) {
            return;
        }
        try {
            s.close();
        } catch (IOException e) {
        }
    }

    // This test makes sure that client-initiated TLS renegotiation does not
    // succeed. We explicitly disable it at the top of X509Util.java.
    @ParameterizedTest
    @MethodSource("data")
    public void testClientRenegotiationFails(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Throwable {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        assertThrows(SSLHandshakeException.class, () -> {
            int port = PortAssignment.unique();
            ExecutorService workerPool = Executors.newCachedThreadPool();
            final SSLServerSocket listeningSocket = x509Util.createSSLServerSocket();
            SSLSocket clientSocket = null;
            SSLSocket serverSocket = null;
            final AtomicInteger handshakesCompleted = new AtomicInteger(0);
            final CountDownLatch handshakeCompleted = new CountDownLatch(1);
            try {
                InetSocketAddress localServerAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
                listeningSocket.bind(localServerAddress);
                Future<SSLSocket> acceptFuture;
                acceptFuture = workerPool.submit(new Callable<SSLSocket>() {
                    @Override
                    public SSLSocket call() throws Exception {
                        SSLSocket sslSocket = (SSLSocket) listeningSocket.accept();
                        sslSocket.addHandshakeCompletedListener(new HandshakeCompletedListener() {
                            @Override
                            public void handshakeCompleted(HandshakeCompletedEvent handshakeCompletedEvent) {
                                handshakesCompleted.getAndIncrement();
                                handshakeCompleted.countDown();
                            }
                        });
                        assertEquals(1, sslSocket.getInputStream().read());
                        try {
                            // 2nd read is after the renegotiation attempt and will fail
                            sslSocket.getInputStream().read();
                            return sslSocket;
                        } catch (Exception e) {
                            forceClose(sslSocket);
                            throw e;
                        }
                    }
                });
                clientSocket = x509Util.createSSLSocket();
                clientSocket.connect(localServerAddress);
                clientSocket.getOutputStream().write(1);
                // Attempt to renegotiate after establishing the connection
                clientSocket.startHandshake();
                clientSocket.getOutputStream().write(1);
                // The exception is thrown on the server side, we need to unwrap it
                try {
                    serverSocket = acceptFuture.get();
                } catch (ExecutionException e) {
                    throw e.getCause();
                }
            } finally {
                forceClose(serverSocket);
                forceClose(clientSocket);
                forceClose(listeningSocket);
                workerPool.shutdown();
                // Make sure the first handshake completed and only the second
                // one failed.
                handshakeCompleted.await(5, TimeUnit.SECONDS);
                assertEquals(1, handshakesCompleted.get());
            }
        });
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testGetDefaultCipherSuitesJava8(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        String[] cipherSuites = X509Util.getDefaultCipherSuitesForJavaVersion("1.8");
        // Java 8 default should have the CBC suites first
        assertTrue(cipherSuites[0].contains("CBC"));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testGetDefaultCipherSuitesJava9(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        String[] cipherSuites = X509Util.getDefaultCipherSuitesForJavaVersion("9");
        // Java 9+ default should have the GCM suites first
        assertTrue(cipherSuites[0].contains("GCM"));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testGetDefaultCipherSuitesJava10(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        String[] cipherSuites = X509Util.getDefaultCipherSuitesForJavaVersion("10");
        // Java 9+ default should have the GCM suites first
        assertTrue(cipherSuites[0].contains("GCM"));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testGetDefaultCipherSuitesJava11(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        String[] cipherSuites = X509Util.getDefaultCipherSuitesForJavaVersion("11");
        // Java 9+ default should have the GCM suites first
        assertTrue(cipherSuites[0].contains("GCM"));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testGetDefaultCipherSuitesUnknownVersion(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        String[] cipherSuites = X509Util.getDefaultCipherSuitesForJavaVersion("notaversion");
        // If version can't be parsed, use the more conservative Java 8 default
        assertTrue(cipherSuites[0].contains("CBC"));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testGetDefaultCipherSuitesNullVersion(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        assertThrows(NullPointerException.class, () -> {
            X509Util.getDefaultCipherSuitesForJavaVersion(null);
        });
    }

    // Warning: this will reset the x509Util
    private void setCustomCipherSuites() {
        System.setProperty(x509Util.getCipherSuitesProperty(), customCipherSuites[0] + "," + customCipherSuites[1]);
        x509Util.close(); // remember to close old instance before replacing it
        x509Util = new ClientX509Util();
    }

    public static class SslContextSupplier implements Supplier<SSLContext> {

        @Override
        public SSLContext get() {
            try {
                return SSLContext.getDefault();
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }

    }

}
