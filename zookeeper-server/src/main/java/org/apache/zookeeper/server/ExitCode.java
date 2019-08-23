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

/**
 * Exit code used to exit server
 */
public enum ExitCode {

    /** Execution finished normally */
    EXECUTION_FINISHED(0),

    /** Unexpected errors like IO Exceptions */
    UNEXPECTED_ERROR(1),

    /** Invalid arguments during invocations */
    INVALID_INVOCATION(2),

    /** Cannot access datadir when trying to replicate server */
    UNABLE_TO_ACCESS_DATADIR(3),

    /** Unable to start admin server at ZooKeeper startup */
    ERROR_STARTING_ADMIN_SERVER(4),

    /** Severe error during snapshot IO */
    TXNLOG_ERROR_TAKING_SNAPSHOT(10),

    /** zxid from COMMIT does not match the one from pendingTxns queue */
    UNMATCHED_TXN_COMMIT(12),

    /** Unexpected packet from leader, or unable to truncate log on Leader.TRUNC */
    QUORUM_PACKET_ERROR(13),

    /** Unable to bind to the quorum (election) port after multiple retry */
    UNABLE_TO_BIND_QUORUM_PORT(14);

    private final int value;

    ExitCode(final int newValue) {
        value = newValue;
    }

    public int getValue() {
        return value;
    }

}
