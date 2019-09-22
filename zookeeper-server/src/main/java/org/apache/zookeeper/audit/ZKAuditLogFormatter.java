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
package org.apache.zookeeper.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

public class ZKAuditLogFormatter {
    private Map<String, String> fieldVsValue = new LinkedHashMap<String, String>();

    /**
     * Add fields to be logged
     */
    public void addField(String key, String value) {
        fieldVsValue.put(key, value);
    }

    /**
     * Gives the string to be logged, ignores fields with null values
     */
    public String format() {
        StringBuilder buffer = new StringBuilder();
        boolean first = true;
        for (Entry<String, String> entry : fieldVsValue.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (null != value) {
                // if first field then no need to add the tabs
                if (first) {
                    first = false;
                } else {
                    buffer.append(AuditConstants.PAIR_SEPARATOR);
                }
                buffer.append(key.toLowerCase()).append(AuditConstants.KEY_VAL_SEPARATOR)
                        .append(value);
            }
        }
        return buffer.toString();
    }
}
