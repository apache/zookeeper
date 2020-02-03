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
package org.apache.zookeeper.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class AuditEvent {
    private static final char PAIR_SEPARATOR = '\t';
    private static final String KEY_VAL_SEPARATOR = "=";
    // Holds the entries which to be logged.
    private Map<String, String> logEntries = new LinkedHashMap<>();
    private Result result;

    AuditEvent(Result result) {
        this.result = result;
    }

    /**
     * Gives all entries to be logged.
     *
     * @return log entries
     */
    public Set<Map.Entry<String, String>> getLogEntries() {
        return logEntries.entrySet();
    }

    void addEntry(FieldName fieldName, String value) {
        if (value != null) {
            logEntries.put(fieldName.name().toLowerCase(), value);
        }
    }

    public String getValue(FieldName fieldName) {
        return logEntries.get(fieldName.name().toLowerCase());
    }

    public Result getResult() {
        return result;
    }

    /**
     * Gives the string to be logged, ignores fields with null values
     *
     * @return String
     */
    @Override
    public String toString() {
        StringBuilder buffer = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : logEntries.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (null != value) {
                // if first field then no need to add the tabs
                if (first) {
                    first = false;
                } else {
                    buffer.append(PAIR_SEPARATOR);
                }
                buffer.append(key).append(KEY_VAL_SEPARATOR)
                        .append(value);
            }
        }
        //add result field
        if (buffer.length() > 0) {
            buffer.append(PAIR_SEPARATOR);
        }
        buffer.append("result").append(KEY_VAL_SEPARATOR)
                .append(result.name().toLowerCase());
        return buffer.toString();
    }

    public enum FieldName {
        USER, OPERATION, IP, ACL, ZNODE, SESSION, ZNODE_TYPE
    }

    public enum Result {
        SUCCESS, FAILURE, INVOKED
    }
}

