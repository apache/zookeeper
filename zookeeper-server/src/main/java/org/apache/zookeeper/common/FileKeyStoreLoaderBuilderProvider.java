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

public class FileKeyStoreLoaderBuilderProvider {
    /**
     * Returns a {@link FileKeyStoreLoader.Builder} that can build a loader
     * which loads keys and certs from files of the given
     * {@link KeyStoreFileType}.
     *
     * @param type the file type to load keys/certs from.
     * @return a new Builder.
     */
    static FileKeyStoreLoader.Builder<? extends FileKeyStoreLoader>
    getBuilderForKeyStoreFileType(KeyStoreFileType type) {
        switch (Objects.requireNonNull(type)) {
            case JKS:
                return new JKSFileLoader.Builder();
            case PEM:
                return new PEMFileLoader.Builder();
            default:
                throw new AssertionError(
                        "Unexpected StoreFileType: " + type.name());
        }
    }

}
