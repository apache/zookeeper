/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class LogRedactorTest {

    @Test
    void testRedactValueWithPasswordKey() {
        assertEquals("***", LogRedactor.redactValue("ssl.keyStore.password", "secret"));
        assertEquals("***", LogRedactor.redactValue("ssl.trustStore.password", "secret"));
        assertEquals("***", LogRedactor.redactValue("somePassword", "secret"));
    }

    @Test
    void testRedactValueCaseInsensitive() {
        assertEquals("***", LogRedactor.redactValue("ssl.keyStore.PASSWORD", "secret"));
        assertEquals("***", LogRedactor.redactValue("ssl.keyStore.Password", "secret"));
        assertEquals("***", LogRedactor.redactValue("ssl.keyStore.passWORD", "secret"));
    }

    @Test
    void testRedactValueNonSensitiveKey() {
        assertEquals("localhost", LogRedactor.redactValue("server.host", "localhost"));
        assertEquals("8080", LogRedactor.redactValue("httpPort", "8080"));
        assertEquals("/path/to/keystore", LogRedactor.redactValue("ssl.keyStore.location", "/path/to/keystore"));
    }

    @Test
    void testRedactValueNullKey() {
        assertEquals("someValue", LogRedactor.redactValue(null, "someValue"));
    }

    @Test
    void testRedactSensitiveValuesWithStringMap() {
        Map<String, String> config = new HashMap<>();
        config.put("ssl.keyStore.location", "/path/to/keystore.jks");
        config.put("ssl.keyStore.password", "SuperSecret");
        config.put("httpPort", "9141");

        Properties redacted = LogRedactor.redactSensitiveValues(config);

        assertEquals("/path/to/keystore.jks", redacted.get("ssl.keyStore.location"));
        assertEquals("***", redacted.get("ssl.keyStore.password"));
        assertEquals("9141", redacted.get("httpPort"));
    }

    @Test
    void testRedactSensitiveValuesWithProperties() {
        Properties config = new Properties();
        config.setProperty("ssl.trustStore.password", "TrustSecret");
        config.setProperty("ssl.trustStore.location", "/path/to/truststore.jks");

        Properties redacted = LogRedactor.redactSensitiveValues(config);

        assertEquals("***", redacted.get("ssl.trustStore.password"));
        assertEquals("/path/to/truststore.jks", redacted.get("ssl.trustStore.location"));
    }

    @Test
    void testRedactSensitiveValuesSkipsNullValues() {
        Map<String, String> config = new HashMap<>();
        config.put("ssl.keyStore.location", null);
        config.put("httpPort", "9141");

        Properties redacted = LogRedactor.redactSensitiveValues(config);

        assertFalse(redacted.containsKey("ssl.keyStore.location"));
        assertEquals("9141", redacted.get("httpPort"));
    }

    @Test
    void testRedactSensitiveValuesEmptyMap() {
        Properties redacted = LogRedactor.redactSensitiveValues(new HashMap<>());
        assertTrue(redacted.isEmpty());
    }
}
