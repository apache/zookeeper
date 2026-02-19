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

package org.apache.zookeeper.server.quorum;

public class ProtocolVersion {
    private ProtocolVersion() {}

    /**
     * Pre ZAB 1.0.
     */
    public static final int ANCIENT = 1;

    /**
     * ZAB 1.0.
     */
    public static final int V3_4_0 = 0x10000;

    /**
     * Protocol changes:
     * * Learner will piggyback whatever data leader attached in {@link Leader#PING} after session data.
     *   This way, leader is free to enhance {@link Leader#PING} in future without agreement from learner.
     */
    public static final int V3_10_0 = 0x20000;

    /**
     * Point to the newest coding version.
     */
    public static final int CURRENT = V3_10_0;
}
