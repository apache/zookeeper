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

package org.apache.zookeeper.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import org.apache.zookeeper.common.BaseX509ParameterizedTestCase;
import org.apache.zookeeper.common.KeyStoreFileType;
import org.apache.zookeeper.common.X509KeyType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class PemReaderTest extends BaseX509ParameterizedTestCase {

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadPrivateKeyFromKeyStore(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        Optional<String> optPassword = x509TestContext.getKeyStorePassword().length() > 0
                                               ? Optional.of(x509TestContext.getKeyStorePassword())
                                               : Optional.empty();
        PrivateKey privateKey = PemReader.loadPrivateKey(x509TestContext.getKeyStoreFile(KeyStoreFileType.PEM), optPassword);
        assertEquals(x509TestContext.getKeyStoreKeyPair().getPrivate(), privateKey);
    }

    // Try to load a password-protected private key without providing a password
    @ParameterizedTest
    @MethodSource("data")
    public void testLoadEncryptedPrivateKeyFromKeyStoreWithoutPassword(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        assertThrows(GeneralSecurityException.class, () -> {
            if (!x509TestContext.isKeyStoreEncrypted()) {
                throw new GeneralSecurityException(); // this case is not tested so throw the expected exception
            }
            PemReader.loadPrivateKey(x509TestContext.getKeyStoreFile(KeyStoreFileType.PEM), Optional.empty());
        });
    }

    // Try to load a password-protected private key with the wrong password
    @ParameterizedTest
    @MethodSource("data")
    public void testLoadEncryptedPrivateKeyFromKeyStoreWithWrongPassword(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        assertThrows(GeneralSecurityException.class, () -> {
            if (!x509TestContext.isKeyStoreEncrypted()) {
                throw new GeneralSecurityException(); // this case is not tested so throw the expected exception
            }
            PemReader.loadPrivateKey(x509TestContext.getKeyStoreFile(KeyStoreFileType.PEM), Optional.of("wrong password"));
        });
    }

    // Try to load a non-protected private key while providing a password
    @ParameterizedTest
    @MethodSource("data")
    public void testLoadUnencryptedPrivateKeyFromKeyStoreWithWrongPassword(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        assertThrows(IOException.class, () -> {
            if (x509TestContext.isKeyStoreEncrypted()) {
                throw new IOException();
            }
            PemReader.loadPrivateKey(x509TestContext.getKeyStoreFile(KeyStoreFileType.PEM), Optional.of("wrong password"));
        });
    }

    // Expect this to fail, the trust store does not contain a private key
    @ParameterizedTest
    @MethodSource("data")
    public void testLoadPrivateKeyFromTrustStore(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        assertThrows(KeyStoreException.class, () -> {
            PemReader.loadPrivateKey(x509TestContext.getTrustStoreFile(KeyStoreFileType.PEM), Optional.empty());
        });
    }

    // Expect this to fail, the trust store does not contain a private key
    @ParameterizedTest
    @MethodSource("data")
    public void testLoadPrivateKeyFromTrustStoreWithPassword(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        assertThrows(KeyStoreException.class, () -> {
            PemReader.loadPrivateKey(x509TestContext.getTrustStoreFile(KeyStoreFileType.PEM), Optional.of("foobar"));
        });
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadCertificateFromKeyStore(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        List<X509Certificate> certs = PemReader.readCertificateChain(x509TestContext.getKeyStoreFile(KeyStoreFileType.PEM));
        assertEquals(1, certs.size());
        assertEquals(x509TestContext.getKeyStoreCertificate(), certs.get(0));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadCertificateFromTrustStore(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        List<X509Certificate> certs = PemReader.readCertificateChain(x509TestContext.getTrustStoreFile(KeyStoreFileType.PEM));
        assertEquals(1, certs.size());
        assertEquals(x509TestContext.getTrustStoreCertificate(), certs.get(0));
    }

}
