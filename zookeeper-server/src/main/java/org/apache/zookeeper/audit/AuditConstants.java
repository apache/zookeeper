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

public final class AuditConstants {
    private AuditConstants() {
        //Utility classes should not have public constructors
    }

    static final String OP_START = "serverStart";
    static final String OP_STOP = "serverStop";
    public static final String OP_CREATE = "create";
    public static final String OP_DELETE = "delete";
    public static final String OP_SETDATA = "setData";
    public static final String OP_SETACL = "setAcl";
    public static final String OP_MULTI_OP = "multiOperation";
    public static final String OP_RECONFIG = "reconfig";
    public static final String OP_DEL_EZNODE_EXP = "ephemeralZNodeDeletionOnSessionCloseOrExpire";
}
