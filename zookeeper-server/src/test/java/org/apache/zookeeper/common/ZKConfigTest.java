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
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.apache.zookeeper.common.X509Exception.SSLContextException;
import org.apache.zookeeper.common.ZKConfig.SslRevocationEnabled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class ZKConfigTest {

    X509Util x509Util = new ClientX509Util();

    @AfterEach
    public void tearDown() throws Exception {
        System.clearProperty(x509Util.getSslProtocolProperty());
    }

    // property is not set we should get the default value
    @Test
    @Timeout(value = 10)
    public void testBooleanRetrievalFromPropertyDefault() {
        ZKConfig conf = new ZKConfig();
        String prop = "UnSetProperty" + System.currentTimeMillis();
        boolean defaultValue = false;
        boolean result = conf.getBoolean(prop, defaultValue);
        assertEquals(defaultValue, result);
    }

    // property is set to an valid boolean, we should get the set value
    @Test
    @Timeout(value = 10)
    public void testBooleanRetrievalFromProperty() {
        boolean value = true;
        boolean defaultValue = false;
        System.setProperty(x509Util.getSslProtocolProperty(), Boolean.toString(value));
        ZKConfig conf = new ZKConfig();
        boolean result = conf.getBoolean(x509Util.getSslProtocolProperty(), defaultValue);
        assertEquals(value, result);
    }

    // property is set but with white spaces in the beginning
    @Test
    @Timeout(value = 10)
    public void testBooleanRetrievalFromPropertyWithWhitespacesInBeginning() {
        boolean value = true;
        boolean defaultValue = false;
        System.setProperty(x509Util.getSslProtocolProperty(), " " + value);
        ZKConfig conf = new ZKConfig();
        boolean result = conf.getBoolean(x509Util.getSslProtocolProperty(), defaultValue);
        assertEquals(value, result);
    }

    // property is set but with white spaces at the end
    @Test
    @Timeout(value = 10)
    public void testBooleanRetrievalFromPropertyWithWhitespacesAtEnd() {
        boolean value = true;
        boolean defaultValue = false;
        System.setProperty(x509Util.getSslProtocolProperty(), value + " ");
        ZKConfig conf = new ZKConfig();
        boolean result = conf.getBoolean(x509Util.getSslProtocolProperty(), defaultValue);
        assertEquals(value, result);
    }

    // property is set but with white spaces at the beginning and the end
    @Test
    @Timeout(value = 10)
    public void testBooleanRetrievalFromPropertyWithWhitespacesAtBeginningAndEnd() {
        boolean value = true;
        boolean defaultValue = false;
        System.setProperty(x509Util.getSslProtocolProperty(), " " + value + " ");
        ZKConfig conf = new ZKConfig();
        boolean result = conf.getBoolean(x509Util.getSslProtocolProperty(), defaultValue);
        assertEquals(value, result);
    }

    @Test
    @Timeout(value = 10)
    public void testSslRevocationEnabledDefault() throws SSLContextException {
        SslRevocationEnabled value = SslRevocationEnabled.LEGACY;
        SslRevocationEnabled defaultValue = SslRevocationEnabled.LEGACY;
        System.clearProperty(x509Util.getSslRevocationEnabledProperty());
        ZKConfig conf = new ZKConfig();
        SslRevocationEnabled result = conf.getSslRevocationEnabled(x509Util.getSslRevocationEnabledProperty(), defaultValue);
        assertEquals(value, result);
    }

    @Test
    @Timeout(value = 10)
    public void testSslRevocationEnabledInvalid() throws SSLContextException {
        SslRevocationEnabled defaultValue = SslRevocationEnabled.LEGACY;
        System.setProperty(x509Util.getSslRevocationEnabledProperty(), "invalid");
        ZKConfig conf = new ZKConfig();
        try {
            conf.getSslRevocationEnabled(x509Util.getSslRevocationEnabledProperty(), defaultValue);
            assertTrue(false);
        } catch (SSLContextException e) {
            //Expected
        }
    }

    @Test
    @Timeout(value = 10)
    public void testSslRevocationEnabledTrimAndCase() throws SSLContextException {
        SslRevocationEnabled value = SslRevocationEnabled.TRUE;
        SslRevocationEnabled defaultValue = SslRevocationEnabled.LEGACY;
        System.setProperty(x509Util.getSslRevocationEnabledProperty(), " true ");
        ZKConfig conf = new ZKConfig();
        SslRevocationEnabled result = conf.getSslRevocationEnabled(x509Util.getSslRevocationEnabledProperty(), defaultValue);
        assertEquals(value, result);
    }
}
