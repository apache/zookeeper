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

package org.apache.zookeeper.server.instrument;

import java.util.HashMap;
import java.util.Map;

/**
 * TraceField contains the full list of trace field definitions.
 * Each field definition specifies its name and data type.
 *
 * The data type is denoted by character:
 *
 * ':' == integer
 * '$' == string
 * '@' == list of strings
 *
 */
public enum TraceField {
    TIME("time", Types.INTEGER),
    ZXID("zxid", Types.INTEGER),
    CXID("cxid", Types.INTEGER),
    SESSION_ID("session_id", Types.INTEGER),
    CLIENT_ID("client_id", Types.STRING),
    CLIENT_IP("client_ip", Types.STRING),
    CLIENT_PORT("client_port", Types.INTEGER),
    PEER_ID("peer_id", Types.INTEGER),
    STAGE("stage", Types.STRING),
    REQUEST_SIZE("request_size", Types.INTEGER),
    REQUEST_TYPE("request_type", Types.STRING),
    IS_EPHEMERAL("is_ephemeral", Types.INTEGER),
    HAS_WATCH("has_watch", Types.INTEGER),
    PATH("path", Types.STRING_ARRAY),
    PACKET_DIRECTION("packet_direction", Types.STRING),
    PACKET_TYPE("packet_type", Types.STRING),
    LATENCY("latency_ms", Types.INTEGER),
    TRACE_TYPE("trace_type", Types.STRING),
    // Return code
    ERROR("error", Types.INTEGER);

    public static class Types {
        public static final char STRING = '$';
        public static final char INTEGER = ':';
        public static final char STRING_ARRAY = '@';
    }

    private final String name;
    private final char type;
    private volatile TraceFieldFilter filter = TraceFieldFilter.NO_FILTER;

    private static final Map<String, TraceField> nameFieldMap;

    static {
        nameFieldMap = new HashMap<>();
        for (TraceField field: TraceField.values()) {
            nameFieldMap.put(field.getName(), field);
        }
    }

    TraceField(String name, char type) {
        this.name = name;
        this.type = type;
    }

    /**
     * @param name  name of Trace Field
     * @return  TraceField object by name
     */
    public static TraceField get(String name) {
        return nameFieldMap.get(name);
    }

    public String getDefinition() {
        return this.type + this.name;
    }

    /**
     * @return  trace field name.
     */
    public String getName() {
        return this.name;
    }

    /**
     * @return  data type of trace field.
     */
    public char getType() {
        return this.type;
    }

    /**
     * @return  trace filter defined for the field.
     */
    public TraceFieldFilter getFilter() {
        return this.filter;
    }

    /**
     * @param filter  set trace filter for this field.
     */
    public void setFilter(TraceFieldFilter filter) {
        if (filter != null) {
            this.filter = filter;
        }
    }

    /**
     * Construct schema string containing all trace fields in the order defined.
     *
     * For example, the schema string for first five fields would be:
     *
     *   ":time,:zxid,:cxid,:session_id,$client_id"
     *
     * @return  trace fields definition as a string.
     */
    public static String getSchemaAsString() {
        // comma separated definitions in the same order as defined in TraceField.
        StringBuilder schema = new StringBuilder(512);
        for (TraceField tf: values()) {
            schema.append(tf.getDefinition()).append(",");
        }
        if (schema.length() > 0) {
            schema.deleteCharAt(schema.length() - 1);
        }
        return schema.toString();
    }
}
