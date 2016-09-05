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


public abstract class SslConfig {
    public String getSslVersionDefault() {
        return ZKConfig.SSL_VERSION_DEFAULT;
    }

    public String getSslVersion() {
        return getKey(ZKConfig.SSL_VERSION);
    }

    public String getSslKeyStoreLocation() {
        return getKey(ZKConfig.SSL_KEYSTORE_LOCATION);
    }

    public String getSslKeyStorePassword() {
        return getKey(ZKConfig.SSL_KEYSTORE_PASSWD);
    }

    public String getSslTrustStoreLocation() {
        return getKey(ZKConfig.SSL_TRUSTSTORE_LOCATION);
    }

    public String getSslTrustStorePassword() {
        return getKey(ZKConfig.SSL_TRUSTSTORE_PASSWD);
    }

    public String getSslAuthProvider() {
        return getKey(ZKConfig.SSL_AUTHPROVIDER);
    }

    public String getSslDefaultDigestAlgo() {
        return ZKConfig.SSL_DIGEST_DEFAULT_ALGO;
    }

    public String getSslDigestAlgos() {
        return getKey(ZKConfig.SSL_DIGEST_ALGOS);
    }

    protected abstract String getPrefix();

    private String getKey(final String suffix) {
        return getPrefix() + "." + suffix;
    }
}
