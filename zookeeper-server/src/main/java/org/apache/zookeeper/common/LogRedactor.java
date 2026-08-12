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

import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public class LogRedactor {

    private LogRedactor() {}

    /**
     * Redacts all values which ends with "password" from the given properties / map.
     * Other values are not changed.
     *
     * @param properties the properties in which all passwords should be redacted.
     * @return new Properties object containing all values, passwords redacted.
     */
    public static Properties redactSensitiveValues(Map<?, ?> properties) {
        Properties redactedConfig = new Properties();
        properties.forEach((k, v) -> {
            if (k != null && v != null) {
                redactedConfig.put(k, redactValue((String) k, (String) v));
            }
        });
        return redactedConfig;
    }

    /**
     * Returns redacted value when the key ends with "password".
     * Otherwise, just returns the value.
     *
     * @param key the key to check if it ends with "password".
     * @return redacted value when the key ends with "password". Otherwise, the value.
     */
    public static String redactValue(String key, String value) {
        if (key == null) {
            return value;
        }
        if (key.toLowerCase(Locale.ROOT).endsWith("password")) {
            return "***";
        }
        return value;
    }
}
