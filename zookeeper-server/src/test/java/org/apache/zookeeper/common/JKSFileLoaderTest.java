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
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.io.IOException;
import java.security.KeyStore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class JKSFileLoaderTest extends BaseX509ParameterizedTestCase {

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadKeyStore(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        String path = x509TestContext.getKeyStoreFile(KeyStoreFileType.JKS).getAbsolutePath();
        KeyStore ks = new JKSFileLoader.Builder().setKeyStorePath(path).setKeyStorePassword(x509TestContext.getKeyStorePassword()).build().loadKeyStore();
        assertEquals(1, ks.size());
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadKeyStoreWithWrongPassword(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        assertThrows(Exception.class, () -> {
            String path = x509TestContext.getKeyStoreFile(KeyStoreFileType.JKS).getAbsolutePath();
            new JKSFileLoader.Builder().setKeyStorePath(path).setKeyStorePassword("wrong password").build().loadKeyStore();
        });
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadKeyStoreWithWrongFilePath(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        assertThrows(IOException.class, () -> {
            String path = x509TestContext.getKeyStoreFile(KeyStoreFileType.JKS).getAbsolutePath();
            new JKSFileLoader.Builder().setKeyStorePath(path
                    + ".does_not_exist").setKeyStorePassword(x509TestContext.getKeyStorePassword()).build().loadKeyStore();
        });
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadKeyStoreWithNullFilePath(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        assertThrows(NullPointerException.class, () -> {
            new JKSFileLoader.Builder().setKeyStorePassword(x509TestContext.getKeyStorePassword()).build().loadKeyStore();
        });
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadKeyStoreWithWrongFileType(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        assertThrows(IOException.class, () -> {
            // Trying to load a PEM file with JKS loader should fail
            String path = x509TestContext.getKeyStoreFile(KeyStoreFileType.PEM).getAbsolutePath();
            new JKSFileLoader.Builder().setKeyStorePath(path).setKeyStorePassword(x509TestContext.getKeyStorePassword()).build().loadKeyStore();
        });
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadTrustStore(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        String path = x509TestContext.getTrustStoreFile(KeyStoreFileType.JKS).getAbsolutePath();
        KeyStore ts = new JKSFileLoader.Builder().setTrustStorePath(path).setTrustStorePassword(x509TestContext.getTrustStorePassword()).build().loadTrustStore();
        assertEquals(1, ts.size());
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadTrustStoreWithWrongPassword(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        assertThrows(Exception.class, () -> {
            String path = x509TestContext.getTrustStoreFile(KeyStoreFileType.JKS).getAbsolutePath();
            new JKSFileLoader.Builder().setTrustStorePath(path).setTrustStorePassword("wrong password").build().loadTrustStore();
        });
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadTrustStoreWithWrongFilePath(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        assertThrows(IOException.class, () -> {
            String path = x509TestContext.getTrustStoreFile(KeyStoreFileType.JKS).getAbsolutePath();
            new JKSFileLoader.Builder().setTrustStorePath(path
                    + ".does_not_exist").setTrustStorePassword(x509TestContext.getTrustStorePassword()).build().loadTrustStore();
        });
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadTrustStoreWithNullFilePath(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        assertThrows(NullPointerException.class, () -> {
            new JKSFileLoader.Builder().setTrustStorePassword(x509TestContext.getTrustStorePassword()).build().loadTrustStore();
        });
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLoadTrustStoreWithWrongFileType(
            X509KeyType caKeyType, X509KeyType certKeyType, String keyPassword, Integer paramIndex)
            throws Exception {
        init(caKeyType, certKeyType, keyPassword, paramIndex);
        assertThrows(IOException.class, () -> {
            // Trying to load a PEM file with JKS loader should fail
            String path = x509TestContext.getTrustStoreFile(KeyStoreFileType.PEM).getAbsolutePath();
            new JKSFileLoader.Builder().setTrustStorePath(path).setTrustStorePassword(x509TestContext.getTrustStorePassword()).build().loadTrustStore();
        });
    }

}
