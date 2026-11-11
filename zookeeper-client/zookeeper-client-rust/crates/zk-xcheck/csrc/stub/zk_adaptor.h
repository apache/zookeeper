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

/*
 * Minimal stub header replacing src/zk_adaptor.h for cross validation builds.
 * Defines the subset of _zhandle fields referenced by extracted legacy C routines
 * so all harness translation units share an identical structure layout.
 */
#ifndef ZK_ADAPTOR_H_
#define ZK_ADAPTOR_H_

#include <sys/time.h>
#include "zookeeper.h"
#include "addrvec.h"
#include "zk_hashtable.h"

#define CREATED_EVENT_DEF 1
#define DELETED_EVENT_DEF 2
#define CHANGED_EVENT_DEF 3
#define CHILD_EVENT_DEF 4
#define SESSION_EVENT_DEF -1
#define NOTWATCHING_EVENT_DEF -2

typedef struct _zsock {
    int sock;
} zsock_t;

struct _zhandle {
    zsock_t *fd;
    char *hostname;
    struct sockaddr_storage addr_cur;
    addrvec_t addrs;
    addrvec_t addrs_old;
    addrvec_t addrs_new;
    struct timeval last_resolve;
    int resolve_delay_ms;
    int reconfig;
    double pOld;
    double pNew;
    int state;
    watcher_fn watcher;
    void *context;
    zk_hashtable *active_node_watchers;
    zk_hashtable *active_exist_watchers;
    zk_hashtable *active_child_watchers;
    char *chroot;
};

char *sub_string(zhandle_t *zh, const char *server_path);
void free_duplicate_path(const char *free_path, const char *path);

#endif
