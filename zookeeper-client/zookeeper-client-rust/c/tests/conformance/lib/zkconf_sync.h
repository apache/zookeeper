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
 * Blocking operation wrappers for conformance scenarios targeting both `mt`
 * and `st` library flavors. Test-only support code; never installed or shipped.
 *
 * Because `libzookeeper_st` omits the synchronous C API (spec "single threaded
 * driver design"), scenarios requiring synchronous setup or intermediate steps
 * invoke these `zkcs_*` wrappers, which mirror the signatures and return
 * semantics of their `zoo_*` counterparts:
 *
 * - `mt` (`THREADED`): delegates directly to the corresponding synchronous
 *   `zoo_*` entry point without altering the recorded trace.
 * - `st` (`!THREADED`): submits the corresponding `zoo_a*` operation and drives
 *   the handle poll registry until completion. The internal completion callback
 *   suppresses trace recording and copies output payloads into caller buffers
 *   following the exact truncation and allocation semantics of legacy
 *   `process_sync_completion`. The target `zhandle_t` must be registered in the
 *   poll loop.
 *
 * Each operation enforces a 20-second deadline (recording a harness failure and
 * returning `ZOPERATIONTIMEOUT` on expiration) and must not be invoked from
 * inside a callback.
 */

#ifndef ZKCONF_SYNC_H_
#define ZKCONF_SYNC_H_

#include <zookeeper.h>

#ifdef __cplusplus
extern "C" {
#endif

int zkcs_create(zhandle_t *zh, const char *path, const char *value,
                int valuelen, const struct ACL_vector *acl, int mode,
                char *path_buffer, int path_buffer_len);
int zkcs_delete(zhandle_t *zh, const char *path, int version);
int zkcs_exists(zhandle_t *zh, const char *path, int watch,
                struct Stat *stat);
int zkcs_wexists(zhandle_t *zh, const char *path, watcher_fn watcher,
                 void *ctx, struct Stat *stat);
int zkcs_get(zhandle_t *zh, const char *path, int watch, char *buffer,
             int *buffer_len, struct Stat *stat);
int zkcs_wget(zhandle_t *zh, const char *path, watcher_fn watcher,
              void *ctx, char *buffer, int *buffer_len, struct Stat *stat);
int zkcs_set(zhandle_t *zh, const char *path, const char *buffer, int buflen,
             int version);
int zkcs_get_children(zhandle_t *zh, const char *path, int watch,
                      struct String_vector *strings);
int zkcs_wget_children(zhandle_t *zh, const char *path, watcher_fn watcher,
                       void *ctx, struct String_vector *strings);
int zkcs_get_acl(zhandle_t *zh, const char *path, struct ACL_vector *acl,
                 struct Stat *stat);
int zkcs_multi(zhandle_t *zh, int count, const zoo_op_t *ops,
               zoo_op_result_t *results);
int zkcs_remove_watches(zhandle_t *zh, const char *path,
                        ZooWatcherType wtype, watcher_fn watcher,
                        void *ctx, int local);
int zkcs_remove_all_watches(zhandle_t *zh, const char *path,
                            ZooWatcherType wtype, int local);

#ifdef __cplusplus
}
#endif

#endif /* ZKCONF_SYNC_H_ */
