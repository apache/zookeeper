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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link FileKeyStoreLoader} that loads from JKS files.
 */
class JKSFileLoader extends FileKeyStoreLoader {
    private static final Logger LOG = LoggerFactory.getLogger(JKSFileLoader.class);

    private static final char[] EMPTY_CHAR_ARRAY = new char[0];
    private static final String JKS_KEY_STORE_TYPE = "JKS";

    private JKSFileLoader(String keyStorePath,
                          String trustStorePath,
                          String keyStorePassword,
                          String trustStorePassword) {
        super(keyStorePath, trustStorePath, keyStorePassword, trustStorePassword);
    }

    @Override
    public KeyStore loadKeyStore() throws IOException, GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance(JKS_KEY_STORE_TYPE);
        InputStream inputStream = null;
        try {
            inputStream = new FileInputStream(new File(keyStorePath));
            ks.load(inputStream, passwordStringToCharArray(keyStorePassword));
            return ks;
        } finally {
            forceClose(inputStream);
        }
    }

    @Override
    public KeyStore loadTrustStore() throws IOException, GeneralSecurityException {
        KeyStore ts = KeyStore.getInstance(JKS_KEY_STORE_TYPE);
        InputStream inputStream = null;
        try {
            inputStream = new FileInputStream(new File(trustStorePath));
            ts.load(inputStream, passwordStringToCharArray(trustStorePassword));
            return ts;
        } finally {
            forceClose(inputStream);
        }
    }

    private char[] passwordStringToCharArray(String password) {
        return password == null ? EMPTY_CHAR_ARRAY : password.toCharArray();
    }

    private void forceClose(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException e) {
            LOG.info("Failed to close key store input stream", e);
        }
    }

    static class Builder extends FileKeyStoreLoader.Builder<JKSFileLoader> {
        @Override
        JKSFileLoader build() {
            return new JKSFileLoader(keyStorePath, trustStorePath, keyStorePassword, trustStorePassword);
        }
    }
}
