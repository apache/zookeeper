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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class X509UtilCrlPropertyTest {
    private static class TrustManagerCrlProperties implements X509TrustManager {
        private final boolean crlEnabled;
        private final boolean ocspEnabled;

        public TrustManagerCrlProperties(boolean crlEnabled, boolean ocspEnabled) {
            this.crlEnabled = crlEnabled;
            this.ocspEnabled = ocspEnabled;
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        }
    }

    @ParameterizedTest
    @ValueSource(classes = {ClientX509Util.class, QuorumX509Util.class})
    public void testCrlProperties(Class<? extends X509Util> clazz) throws Exception {
        try {
            X509Util util = spy(clazz.getDeclaredConstructor().newInstance());

            doAnswer(mock -> new TrustManagerCrlProperties(mock.getArgument(3, Boolean.class), mock.getArgument(4, Boolean.class)))
                .when(util)
                .createTrustManagerInternal(any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean());

            ZKConfig config = new ZKConfig();
            config.setProperty(util.getSslTruststoreLocationProperty(), "no-empty");

            TrustManagerCrlProperties properties = (TrustManagerCrlProperties) util.buildTrustManager(config);

            assertFalse(properties.crlEnabled);
            assertFalse(properties.ocspEnabled);

            // given: "com.sun.net.ssl.checkRevocation"
            System.setProperty("com.sun.net.ssl.checkRevocation", "true");
            properties = (TrustManagerCrlProperties) util.buildTrustManager(config);
            // then: default to "com.sun.net.ssl.checkRevocation"
            assertTrue(properties.crlEnabled);

            // given: security property "ocsp.enable"
            Security.setProperty("ocsp.enable", "true");
            properties = (TrustManagerCrlProperties) util.buildTrustManager(config);
            // then: default to "ocsp.enable"
            assertTrue(properties.ocspEnabled);

            // given: both "zookeeper.ssl.crl" and "com.sun.net.ssl.checkRevocation"
            config.setProperty(util.getSslCrlEnabledProperty(), "false");
            properties = (TrustManagerCrlProperties) util.buildTrustManager(config);
            // then: "zookeeper.ssl.crl" take precedence
            assertFalse(properties.crlEnabled);

            // given: both "zookeeper.ssl.ocsp" and "ocsp.enable"
            config.setProperty(util.getSslOcspEnabledProperty(), "false");
            properties = (TrustManagerCrlProperties) util.buildTrustManager(config);
            // then: "zookeeper.ssl.ocsp" take precedence
            assertFalse(properties.ocspEnabled);
        } finally {
            System.clearProperty("com.sun.net.ssl.checkRevocation");
            Security.setProperty("ocsp.enable", "false");
        }
    }
}
