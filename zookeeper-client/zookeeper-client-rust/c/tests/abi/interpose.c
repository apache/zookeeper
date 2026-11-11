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
 * Dynamic symbol interposition probe covering exported `zoo_a*` and synchronous API
 * entry points. Each wrapper atomically increments an invocation counter before
 * delegating to the underlying library implementation.
 *
 * On ELF targets, wrappers act as `LD_PRELOAD` overrides. Internal library
 * calls to exported entry points traverse the PLT and are intercepted. On
 * Mach-O targets, wrappers register dyld interpose tuples, which rebind imports
 * without intercepting intra-image calls. Because the legacy C library exhibits
 * the same Mach-O binding behavior, the test runner performs differential
 * comparison between both implementations rather than asserting fixed ELF
 * intra-library call counts.
 *
 * Process termination flushes the accumulated counters to the path specified by
 * `ZK_INTERPOSE_OUT`.
 *
 * Because `libzookeeper_st` omits synchronous operations, single threaded
 * builds interpose only asynchronous entry points, leaving synchronous counters
 * at zero.
 */

#include <stdio.h>
#include <stdlib.h>

#ifndef __APPLE__
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <dlfcn.h>
#endif

#include <zookeeper.h>

enum {
    W_zoo_awget,
    W_zoo_wget,
    W_zoo_awexists,
    W_zoo_wexists,
    W_zoo_acreate_ttl,
    W_zoo_create_ttl,
    W_zoo_acreate2_ttl,
    W_zoo_create2_ttl,
    W_zoo_adelete,
    W_zoo_aset,
    W_zoo_set2,
    W_zoo_awget_children,
    W_zoo_awget_children2,
    W_zoo_aget_acl,
    W_zoo_aset_acl,
    W_zoo_amulti,
    W_zoo_awgetconfig,
    W_zoo_areconfig,
    W_COUNT
};

static const char *const zk_names[W_COUNT] = {
    "zoo_awget",          "zoo_wget",          "zoo_awexists",  "zoo_wexists",
    "zoo_acreate_ttl",    "zoo_create_ttl",    "zoo_acreate2_ttl", "zoo_create2_ttl",
    "zoo_adelete",        "zoo_aset",          "zoo_set2",      "zoo_awget_children",
    "zoo_awget_children2", "zoo_aget_acl",     "zoo_aset_acl",  "zoo_amulti",
    "zoo_awgetconfig",    "zoo_areconfig",
};

static int zk_hits[W_COUNT];

static void zk_hit(int i)
{
    __atomic_fetch_add(&zk_hits[i], 1, __ATOMIC_RELAXED);
}

__attribute__((destructor)) static void zk_report(void)
{
    const char *out = getenv("ZK_INTERPOSE_OUT");
    FILE *f;
    int i;

    if (out == NULL) {
        return;
    }
    f = fopen(out, "w");
    if (f == NULL) {
        return;
    }
    for (i = 0; i < W_COUNT; i++) {
        fprintf(f, "%s %d\n", zk_names[i], __atomic_load_n(&zk_hits[i], __ATOMIC_RELAXED));
    }
    fclose(f);
}

#ifdef __APPLE__
/*
 * Registers a dyld interpose tuple redirecting external references to `orig`
 * toward `repl`, while calls to `orig` within this translation unit resolve
 * directly to the underlying library export.
 */
#define ZK_WRAP(name, params, args)                                         \
    static int zkw_##name params                                            \
    {                                                                       \
        zk_hit(W_##name);                                                   \
        return name args;                                                   \
    }                                                                       \
    __attribute__((used, section("__DATA,__interpose"))) static const struct { \
        const void *repl;                                                   \
        const void *orig;                                                   \
    } zki_##name = {(const void *)(unsigned long)&zkw_##name,               \
                    (const void *)(unsigned long)&name};
#else
/* Defines an `LD_PRELOAD` interposer that forwards to `RTLD_NEXT`. */
#define ZK_WRAP(name, params, args)                                         \
    int name params                                                         \
    {                                                                       \
        static int(*real) params;                                           \
        zk_hit(W_##name);                                                   \
        if (real == NULL) {                                                 \
            *(void **)&real = dlsym(RTLD_NEXT, #name);                      \
        }                                                                   \
        return real args;                                                   \
    }
#endif

ZK_WRAP(zoo_awget,
        (zhandle_t * zh, const char *path, watcher_fn w, void *wctx, data_completion_t c,
         const void *d),
        (zh, path, w, wctx, c, d))
#ifdef THREADED
ZK_WRAP(zoo_wget,
        (zhandle_t * zh, const char *path, watcher_fn w, void *wctx, char *buf, int *len,
         struct Stat *st),
        (zh, path, w, wctx, buf, len, st))
#endif
ZK_WRAP(zoo_awexists,
        (zhandle_t * zh, const char *path, watcher_fn w, void *wctx, stat_completion_t c,
         const void *d),
        (zh, path, w, wctx, c, d))
#ifdef THREADED
ZK_WRAP(zoo_wexists,
        (zhandle_t * zh, const char *path, watcher_fn w, void *wctx, struct Stat *st),
        (zh, path, w, wctx, st))
#endif
ZK_WRAP(zoo_acreate_ttl,
        (zhandle_t * zh, const char *path, const char *v, int vl, const struct ACL_vector *acl,
         int mode, int64_t ttl, string_completion_t c, const void *d),
        (zh, path, v, vl, acl, mode, ttl, c, d))
#ifdef THREADED
ZK_WRAP(zoo_create_ttl,
        (zhandle_t * zh, const char *path, const char *v, int vl, const struct ACL_vector *acl,
         int mode, int64_t ttl, char *pb, int pbl),
        (zh, path, v, vl, acl, mode, ttl, pb, pbl))
#endif
ZK_WRAP(zoo_acreate2_ttl,
        (zhandle_t * zh, const char *path, const char *v, int vl, const struct ACL_vector *acl,
         int mode, int64_t ttl, string_stat_completion_t c, const void *d),
        (zh, path, v, vl, acl, mode, ttl, c, d))
#ifdef THREADED
ZK_WRAP(zoo_create2_ttl,
        (zhandle_t * zh, const char *path, const char *v, int vl, const struct ACL_vector *acl,
         int mode, int64_t ttl, char *pb, int pbl, struct Stat *st),
        (zh, path, v, vl, acl, mode, ttl, pb, pbl, st))
#endif
ZK_WRAP(zoo_adelete,
        (zhandle_t * zh, const char *path, int version, void_completion_t c, const void *d),
        (zh, path, version, c, d))
ZK_WRAP(zoo_aset,
        (zhandle_t * zh, const char *path, const char *buf, int bl, int version,
         stat_completion_t c, const void *d),
        (zh, path, buf, bl, version, c, d))
#ifdef THREADED
ZK_WRAP(zoo_set2,
        (zhandle_t * zh, const char *path, const char *buf, int bl, int version,
         struct Stat *st),
        (zh, path, buf, bl, version, st))
#endif
ZK_WRAP(zoo_awget_children,
        (zhandle_t * zh, const char *path, watcher_fn w, void *wctx,
         strings_completion_t c, const void *d),
        (zh, path, w, wctx, c, d))
ZK_WRAP(zoo_awget_children2,
        (zhandle_t * zh, const char *path, watcher_fn w, void *wctx,
         strings_stat_completion_t c, const void *d),
        (zh, path, w, wctx, c, d))
ZK_WRAP(zoo_aget_acl,
        (zhandle_t * zh, const char *path, acl_completion_t c, const void *d),
        (zh, path, c, d))
ZK_WRAP(zoo_aset_acl,
        (zhandle_t * zh, const char *path, int version, struct ACL_vector *acl,
         void_completion_t c, const void *d),
        (zh, path, version, acl, c, d))
ZK_WRAP(zoo_amulti,
        (zhandle_t * zh, int count, const zoo_op_t *ops, zoo_op_result_t *results,
         void_completion_t c, const void *d),
        (zh, count, ops, results, c, d))
ZK_WRAP(zoo_awgetconfig,
        (zhandle_t * zh, watcher_fn w, void *wctx, data_completion_t c, const void *d),
        (zh, w, wctx, c, d))
ZK_WRAP(zoo_areconfig,
        (zhandle_t * zh, const char *joining, const char *leaving, const char *members,
         int64_t version, data_completion_t c, const void *d),
        (zh, joining, leaving, members, version, c, d))
