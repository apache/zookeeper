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

package org.apache.zookeeper.server;

public class TransactionChangeRecord {
    public static final String DATANODE = "DataNode";
    public static final String ACL = "ACL";
    public static final String SESSION = "Session";
    public static final String ZXIDDIGEST = "ZxidDigest";

    public static final String ADD = "add";
    public static final String UPDATE = "update";
    public static final String REMOVE = "remove";

    private String type;
    private String operation;
    private Object key;
    private Object value;

    public TransactionChangeRecord(String type, String operation, Object key, Object value) {
        this.type = type;
        this.operation = operation;
        this.key = key;
        this.value = value;
    }

    public String getType() {
        return type;
    }

    public String getOperation() {
        return operation;
    }

    public Object getKey() {
        return key;
    }

    public Object getValue() {
        return value;
    }
}
