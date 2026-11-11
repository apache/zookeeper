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

/*
 * Public data symbols exported for ABI backwards compatibility.
 *
 * Defines exported global constants, bitmasks, states, events, and ACL structures
 * required by existing applications linking against libzookeeper. Emitted directly
 * from C to match original symbol types, sections, sizes, and copy-relocation behavior.
 */

#include "zks_internal.h"

const int ZOOKEEPER_WRITE = 1 << 0;
const int ZOOKEEPER_READ = 1 << 1;

const int ZOO_PERSISTENT = 0;
const int ZOO_EPHEMERAL = 1;
const int ZOO_PERSISTENT_SEQUENTIAL = 2;
const int ZOO_EPHEMERAL_SEQUENTIAL = 3;
const int ZOO_CONTAINER = 4;
const int ZOO_PERSISTENT_WITH_TTL = 5;
const int ZOO_PERSISTENT_SEQUENTIAL_WITH_TTL = 6;

/* Legacy flag mask retained for backward compatibility with older create API variants. */
const int ZOO_SEQUENCE = 1 << 1;

const int ZOO_EXPIRED_SESSION_STATE = -112;
const int ZOO_AUTH_FAILED_STATE = -113;
const int ZOO_CONNECTING_STATE = 1;
const int ZOO_ASSOCIATING_STATE = 2;
const int ZOO_CONNECTED_STATE = 3;
const int ZOO_READONLY_STATE = 5;
const int ZOO_SSL_CONNECTING_STATE = 7;
const int ZOO_NOTCONNECTED_STATE = 999;

const int ZOO_CREATED_EVENT = 1;
const int ZOO_DELETED_EVENT = 2;
const int ZOO_CHANGED_EVENT = 3;
const int ZOO_CHILD_EVENT = 4;
const int ZOO_SESSION_EVENT = -1;
const int ZOO_NOTWATCHING_EVENT = -2;

const int ZOO_PERM_READ = 1 << 0;
const int ZOO_PERM_WRITE = 1 << 1;
const int ZOO_PERM_CREATE = 1 << 2;
const int ZOO_PERM_DELETE = 1 << 3;
const int ZOO_PERM_ADMIN = 1 << 4;
const int ZOO_PERM_ALL = 0x1f;

struct Id ZOO_ANYONE_ID_UNSAFE = {"world", "anyone"};
struct Id ZOO_AUTH_IDS = {"auth", ""};
static struct ACL _OPEN_ACL_UNSAFE_ACL[] = {{0x1f, {"world", "anyone"}}};
static struct ACL _READ_ACL_UNSAFE_ACL[] = {{0x01, {"world", "anyone"}}};
static struct ACL _CREATOR_ALL_ACL_ACL[] = {{0x1f, {"auth", ""}}};
struct ACL_vector ZOO_OPEN_ACL_UNSAFE = {1, _OPEN_ACL_UNSAFE_ACL};
struct ACL_vector ZOO_READ_ACL_UNSAFE = {1, _READ_ACL_UNSAFE_ACL};
struct ACL_vector ZOO_CREATOR_ALL_ACL = {1, _CREATOR_ALL_ACL_ACL};

ZooLogLevel logLevel = ZOO_LOG_LEVEL_INFO;
