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
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/**
 * An interface for an object that can load key stores or trust stores.
 */
interface KeyStoreLoader {
    /**
     * Loads a KeyStore which contains at least one private key and the
     * associated X509 cert chain.
     *
     * @return a new KeyStore
     * @throws IOException if loading the key store fails due to an IO error,
     *         such as "file not found".
     * @throws GeneralSecurityException if loading the key store fails due to
     *         a security error, such as "unsupported crypto algorithm".
     */
    KeyStore loadKeyStore() throws IOException, GeneralSecurityException;

    /**
     * Loads a KeyStore which contains at least one X509 cert chain for a
     * trusted Certificate Authority (CA).
     *
     * @return a new KeyStore
     * @throws IOException if loading the trust store fails due to an IO error,
     *         such as "file not found".
     * @throws GeneralSecurityException if loading the trust store fails due to
     *         a security error, such as "unsupported crypto algorithm".
     */
    KeyStore loadTrustStore() throws IOException, GeneralSecurityException;
}
