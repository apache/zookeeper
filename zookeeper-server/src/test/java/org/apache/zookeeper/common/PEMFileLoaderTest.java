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

import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.util.Collection;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class PEMFileLoaderTest extends BaseX509ParameterizedTestCase {

    @Parameterized.Parameters
    public static Collection<Object[]> params() {
        return BaseX509ParameterizedTestCase.defaultParams();
    }

    public PEMFileLoaderTest(
            final X509KeyType caKeyType,
            final X509KeyType certKeyType,
            final String keyPassword,
            final Integer paramIndex) {
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

    @Test
    public void testLoadKeyStore() throws Exception {
        String path = x509TestContext.getKeyStoreFile(KeyStoreFileType.PEM)
                .getAbsolutePath();
        KeyStore ks = new PEMFileLoader.Builder()
                .setKeyStorePath(path)
                .setKeyStorePassword(x509TestContext.getKeyStorePassword())
                .build()
                .loadKeyStore();
        Assert.assertEquals(1, ks.size());
    }

    @Test(expected = Exception.class)
    public void testLoadKeyStoreWithWrongPassword() throws Exception {
        String path = x509TestContext.getKeyStoreFile(KeyStoreFileType.PEM)
                .getAbsolutePath();
        new PEMFileLoader.Builder()
                .setKeyStorePath(path)
                .setKeyStorePassword("wrong password")
                .build()
                .loadKeyStore();
    }

    @Test(expected = IOException.class)
    public void testLoadKeyStoreWithWrongFilePath() throws Exception {
        String path = x509TestContext.getKeyStoreFile(KeyStoreFileType.PEM)
                .getAbsolutePath();
        new PEMFileLoader.Builder()
                .setKeyStorePath(path + ".does_not_exist")
                .setKeyStorePassword(x509TestContext.getKeyStorePassword())
                .build()
                .loadKeyStore();
    }

    @Test(expected = NullPointerException.class)
    public void testLoadKeyStoreWithNullFilePath() throws Exception {
        new PEMFileLoader.Builder()
                .setKeyStorePassword(x509TestContext.getKeyStorePassword())
                .build()
                .loadKeyStore();
    }

    @Test(expected = KeyStoreException.class)
    public void testLoadKeyStoreWithWrongFileType() throws Exception {
        // Trying to load a JKS file with PEM loader should fail
        String path = x509TestContext.getKeyStoreFile(KeyStoreFileType.JKS)
                .getAbsolutePath();
        new PEMFileLoader.Builder()
                .setKeyStorePath(path)
                .setKeyStorePassword(x509TestContext.getKeyStorePassword())
                .build()
                .loadKeyStore();
    }

    @Test
    public void testLoadTrustStore() throws Exception {
        String path = x509TestContext.getTrustStoreFile(KeyStoreFileType.PEM)
                .getAbsolutePath();
        KeyStore ts = new PEMFileLoader.Builder()
                .setTrustStorePath(path)
                .setTrustStorePassword(x509TestContext.getTrustStorePassword())
                .build()
                .loadTrustStore();
        Assert.assertEquals(1, ts.size());
    }

    @Test(expected = IOException.class)
    public void testLoadTrustStoreWithWrongFilePath() throws Exception {
        String path = x509TestContext.getTrustStoreFile(KeyStoreFileType.PEM)
                .getAbsolutePath();
        new PEMFileLoader.Builder()
                .setTrustStorePath(path + ".does_not_exist")
                .setTrustStorePassword(x509TestContext.getTrustStorePassword())
                .build()
                .loadTrustStore();
    }

    @Test(expected = NullPointerException.class)
    public void testLoadTrustStoreWithNullFilePath() throws Exception {
        new PEMFileLoader.Builder()
                .setTrustStorePassword(x509TestContext.getTrustStorePassword())
                .build()
                .loadTrustStore();
    }

    @Test
    public void testLoadTrustStoreWithWrongFileType() throws Exception {
        // Trying to load a JKS file with PEM loader should fail
        String path = x509TestContext.getTrustStoreFile(KeyStoreFileType.JKS)
                .getAbsolutePath();
        KeyStore ts = new PEMFileLoader.Builder()
                .setTrustStorePath(path)
                .setTrustStorePassword(x509TestContext.getTrustStorePassword())
                .build()
                .loadTrustStore();
        Assert.assertEquals(0, ts.size());
    }
}
