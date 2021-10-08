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

/**
 * ElectionAlgorithmType, since 3.6.0, only {@link FastLeaderElection} is available.
 */
public enum ElectionAlgorithmTypeEnum {
    FastLeaderElection(3, "FastLeaderElection"),
    ;
    private final int code;
    private final String name;

    ElectionAlgorithmTypeEnum(int code, String name) {
        this.code = code;
        this.name = name;
    }

    public int getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public static ElectionAlgorithmTypeEnum representOf(int code) {
        for (ElectionAlgorithmTypeEnum electionAlgorithmTypeEnum : ElectionAlgorithmTypeEnum.values()) {
            if (electionAlgorithmTypeEnum.getCode() == code) {
                return electionAlgorithmTypeEnum;
            }
        }
        throw new IllegalArgumentException(String.format("Election Algorithm %d is not supported", code));
    }
}
