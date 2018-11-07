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

import java.util.Objects;

/**
 * Base class for instances of {@link KeyStoreLoader} which load the key/trust
 * stores from files on a filesystem.
 */
abstract class FileKeyStoreLoader implements KeyStoreLoader {
    final String keyStorePath;
    final String trustStorePath;
    final String keyStorePassword;
    final String trustStorePassword;

    FileKeyStoreLoader(String keyStorePath,
                       String trustStorePath,
                       String keyStorePassword,
                       String trustStorePassword) {
        this.keyStorePath = keyStorePath;
        this.trustStorePath = trustStorePath;
        this.keyStorePassword = keyStorePassword;
        this.trustStorePassword = trustStorePassword;
    }

    /**
     * Base class for builder pattern used by subclasses.
     * @param <T> the subtype of FileKeyStoreLoader created by the Builder.
     */
    static abstract class Builder<T extends FileKeyStoreLoader> {
        String keyStorePath;
        String trustStorePath;
        String keyStorePassword;
        String trustStorePassword;

        Builder() {}

        Builder<T> setKeyStorePath(String keyStorePath) {
            this.keyStorePath = Objects.requireNonNull(keyStorePath);
            return this;
        }

        Builder<T> setTrustStorePath(String trustStorePath) {
            this.trustStorePath = Objects.requireNonNull(trustStorePath);
            return this;
        }

        Builder<T> setKeyStorePassword(String keyStorePassword) {
            this.keyStorePassword = Objects.requireNonNull(keyStorePassword);
            return this;
        }

        Builder<T> setTrustStorePassword(String trustStorePassword) {
            this.trustStorePassword = Objects.requireNonNull(trustStorePassword);
            return this;
        }

        abstract T build();
    }
}
