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
 * Constants reflecting different stages of request processing inside zookeeper server.
 */
public enum RequestStage {
    APPEND_LOG,
    CONNECT,
    FINAL,
    FOLLOWER,
    OBSERVER,
    PREP,
    PROPOSE,
    READONLY,
    RECEIVE_COMMIT,
    SEND_COMMIT,
    SEND_COMMIT_ACTIVATE,
    TREE_COMMIT,

    TRIGGER_DATA_WATCH,
    TRIGGER_CHILD_WATCH,
    SEND_WATCH,
    NO_WATCH,

    ADD_SESSION,
    EXISTING_SESSION,
    LOAD_SESSION,
    VALIDATE_SESSION,
    VALID_SESSION,
    INVALID_SESSION,
    KILL_SESSION,
    REMOVE_SESSION,
    UPGRADE_SESSION,
    TOUCH_CLOSING_SESSION,
    TOUCH_INVALID_SESSION,
    TOUCH_SESSION,
    CREATE_SESSION_LOG,
    CLOSE_SESSION_LOG,
}
