
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
package org.apache.zookeeper.server.util;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509ExtendedTrustManager;

import org.apache.zookeeper.common.X509Exception;
import org.apache.zookeeper.common.X509Util;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.server.ZookeeperServerConfig;
import org.apache.zookeeper.server.quorum.util.ZKX509TrustManager;

public class ServerX509Util extends X509Util {
    public static SSLContext createSSLContext()
            throws X509Exception.SSLContextException {
        final ZKConfig zkConfig = new ZookeeperServerConfig();
        try {
            return createSSLContext(zkConfig,
                    getTrustManager(zkConfig));
        } catch (X509Exception.TrustManagerException exp) {
            throw new X509Exception.SSLContextException(exp);
        }
    }

    public static X509ExtendedTrustManager getTrustManager(
            final ZKConfig zkConfig)
    throws X509Exception.TrustManagerException {
        return new ZKX509TrustManager(zkConfig.getProperty(
                        ZKConfig.SSL_TRUSTSTORE_LOCATION),
                        zkConfig.getProperty(
                                ZKConfig.SSL_TRUSTSTORE_PASSWD));
    }
}
