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
package org.apache.zookeeper.server.quorum.util;

import javax.net.ssl.SSLContext;

import org.apache.zookeeper.client.ClientX509Util;
import org.apache.zookeeper.common.X509Exception;
import org.apache.zookeeper.common.X509Util;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;

/**
 * Helps with KeyManager and TrustManager creation and handling
 * of certification verification for both ZAB and FLE
 */
public class QuorumX509Util extends X509Util {
    public static SSLContext createSSLContext(final ZKConfig zkConfig)
            throws X509Exception.SSLContextException {
        try {
            return createSSLContext(zkConfig,
                    new ZKX509TrustManager(zkConfig.getProperty(
                            ZKConfig.SSL_TRUSTSTORE_LOCATION),
                            zkConfig.getProperty(
                                    ZKConfig.SSL_TRUSTSTORE_PASSWD)));
        } catch (X509Exception.TrustManagerException exp) {
            throw new X509Exception.SSLContextException(exp);
        }
    }

    public static SSLContext createSSLContext(
            final QuorumPeerConfig quorumPeerConfig)
            throws X509Exception.SSLContextException {
        return ClientX509Util.createSSLContext(quorumPeerConfig);
    }
}
