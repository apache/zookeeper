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

/**
 * Audit log performance reading
 */
public final class AuditLogPerfReading {
    // time taken by create operations
    private long create;
    // time taken by setData operations
    private long setData;
    // time taken by delete operations
    private long delete;

    public long getCreate() {
        return create;
    }

    public void setCreate(long create) {
        this.create = create;
    }

    public long getSetData() {
        return setData;
    }

    public void setSetData(long setData) {
        this.setData = setData;
    }

    public long getDelete() {
        return delete;
    }

    public void setDelete(long delete) {
        this.delete = delete;
    }

    public String report() {
        StringBuilder builder = new StringBuilder();
        builder.append("create=");
        builder.append(create);
        builder.append(" ms\n");
        builder.append("setData=");
        builder.append(setData);
        builder.append(" ms\n");
        builder.append("delete=");
        builder.append(delete);
        builder.append(" ms\n");
        return builder.toString();
    }

    @Override
    public String toString() {
        return "create=" + create + ", setData=" + setData + ", delete="
                + delete;
    }
}