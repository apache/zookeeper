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
 * Asynchronous public entry points and unified request submission pipeline.
 *
 * Implements the zoo_a* client API family. Each entry constructs an operation descriptor,
 * allocates an associated tracking context, submits the request across the FFI boundary
 * to the Rust core, and processes intermediate outbox effects (such as inline log messages
 * or immediate local completions) while ensuring proper memory reclamation on error paths.
 */

#include "zks_internal.h"

/* Sentinel marker address used to identify synchronous operation completion contexts. */
ZKS_HIDDEN void zks_sync_marker(void)
{
}

/* Maps Rust FFI submission status codes to legacy ZooKeeper return codes. */
static int zks_funnel(zkrs_status s)
{
    switch (s) {
    case ZKRS_OK:
    case ZKRS_OK_LOCAL:
        return ZOK;
    case ZKRS_EARLY_BADARGS:
        return ZBADARGUMENTS;
    case ZKRS_EARLY_STATE:
        return ZINVALIDSTATE;
    case ZKRS_UNIMPLEMENTED:
        return ZUNIMPLEMENTED;
    case ZKRS_NOWATCHER:
        return ZNOWATCHER;
    case ZKRS_SYSTEM:
        return ZSYSTEMERROR;
    default:
        return ZMARSHALLINGERROR; /* LATE */
    }
}

/* Handles node creation with NULL handle, validating TTL constraints prior to handle checks. */
static int zks_create_null_handle(int mode, int64_t ttl)
{
    zkrs_status s = ZKS_CALL(zkrs_validate_ttl(mode, ttl));
    return s != ZKRS_OK ? zks_funnel(s) : ZBADARGUMENTS;
}

/*
 * Compile time assertion verifying void_completion_t pointer size compatibility
 * for legacy function pointer passing conventions.
 */
typedef char zks_fn_size_ok[sizeof(void_completion_t) == sizeof(void_completion_t *) ? 1 : -1];

ZKS_HIDDEN struct zks_req *zks_req_new(unsigned kind, unsigned mode, zks_fn fn, const void *data)
{
    struct zks_req *r = malloc(sizeof *r);
    if (r == NULL) {
        return NULL;
    }
    r->kind = (uint8_t)kind;
    r->mode = (uint8_t)mode;
    r->fn = fn;
    r->data = data;
    /* Synchronous requests carry tracking records in user data payloads. */
    r->sync = mode == ZKS_REQ_SYNC ? (struct zks_sync *)(uintptr_t)data : NULL;
    r->results = NULL;
    r->count = 0;
    r->op_kind = NULL;
    return r;
}

ZKS_HIDDEN void zks_req_free(struct zks_req *r)
{
    if (r == NULL) {
        return;
    }
    free(r->op_kind);
    free(r);
}

/*
 * Dispatches a caller allocated request structure to the Rust core. Handles memory ownership
 * transitions: on success (ZKRS_OK), ownership of `r` transfers to the driver pending completion;
 * on failure, `r` is reclaimed immediately in C frames before draining diagnostic logs.
 */
static zkrs_status zks_submit_req(zhandle_t *zh, const zkrs_request *rq, struct zks_req *r)
{
    /*
     * Coordinates cancellation masking and outbox draining during submission. Multithreaded
     * mode covers submission and inline log drains under one mask. Single threaded mode masks
     * only the core transition.
     */
    ZKS_HOLD_SCOPE;
    const zkrs_handle *core = zh->core;
    zkrs_outbox ob = ZKRS_OUTBOX_INIT;
    uint32_t delivery =
        (r != NULL && r->mode != ZKS_REQ_ASYNC) ? ZKRS_DELIVER_DIRECT : ZKRS_DELIVER_QUEUED;
    zkrs_status s = ZKS_CALL(zkrs_submit(core, rq, (uintptr_t)r, delivery, &ob));
    if (s != ZKRS_OK) {
        zks_req_free(r);
    }
    zks_drain(zh, &ob);
    return s;
}

/*
 * Allocates tracking context and submits an asynchronous request, preserving raw status to
 * distinguish local watch completions from wire submissions.
 */
static zkrs_status zks_submit_raw(zhandle_t *zh, const zkrs_request *rq, unsigned kind, zks_fn fn,
                                  const void *data)
{
    return zks_submit_req(zh, rq, zks_req_new(kind, zks_req_mode_of(fn), fn, data));
}

static int zks_submit(zhandle_t *zh, const zkrs_request *rq, unsigned kind, zks_fn fn,
                      const void *data)
{
    return zks_funnel(zks_submit_raw(zh, rq, kind, fn, data));
}

ZKS_HIDDEN int zks_submit_prepared(zhandle_t *zh, zkrs_request *rq, struct zks_req *r)
{
    return zks_funnel(zks_submit_req(zh, rq, r));
}

/* ---- Asynchronous Read Operations ---- */

/* Submits a read operation with optional one shot watch registration. */
static int zks_submit_read(zhandle_t *zh, uint32_t op, uint32_t flags, const char *path,
                           watcher_fn watcher, void *watcherCtx, unsigned kind, zks_fn fn,
                           const void *data)
{
    zkrs_request rq = ZKRS_REQUEST_INIT(op);
    rq.flags = flags;
    rq.path = path;
    rq.watch_fn = (uintptr_t)watcher;
    rq.watch_ctx = (uintptr_t)watcherCtx;
    return zks_submit(zh, &rq, kind, fn, data);
}

int zoo_awget(zhandle_t *zh, const char *path, watcher_fn watcher, void *watcherCtx,
              data_completion_t completion, const void *data)
{
    ZKS_RETURN_IF_NO_HANDLE(zh, ZBADARGUMENTS);
    return zks_submit_read(zh, ZKRS_OP_GETDATA, 0, path, watcher, watcherCtx, ZKRS_K_DATA,
                           ZKS_FN(completion), data);
}

int zoo_aget(zhandle_t *zh, const char *path, int watch, data_completion_t completion,
             const void *data)
{
    struct zks_pair p;
    if (!zks_watch_arg(zh, watch, __func__, &p)) {
        return ZBADARGUMENTS;
    }
    return zoo_awget(zh, path, p.fn, p.ctx, completion, data);
}

int zoo_awgetconfig(zhandle_t *zh, watcher_fn watcher, void *watcherCtx,
                    data_completion_t completion, const void *data)
{
    ZKS_RETURN_IF_NO_HANDLE(zh, ZBADARGUMENTS);
    /*
     * Special cased GETDATA targeting `/zookeeper/config` without chroot prefixing,
     * maintaining wire compatibility with dynamic reconfiguration queries.
     */
    return zks_submit_read(zh, ZKRS_OP_GETCONFIG, ZKRS_F_RAW_PATH, ZOO_CONFIG_NODE, watcher,
                           watcherCtx, ZKRS_K_DATA, ZKS_FN(completion), data);
}

int zoo_agetconfig(zhandle_t *zh, int watch, data_completion_t completion, const void *data)
{
    struct zks_pair p;
    if (!zks_watch_arg(zh, watch, __func__, &p)) {
        return ZBADARGUMENTS;
    }
    return zoo_awgetconfig(zh, p.fn, p.ctx, completion, data);
}

int zoo_awexists(zhandle_t *zh, const char *path, watcher_fn watcher, void *watcherCtx,
                 stat_completion_t completion, const void *data)
{
    ZKS_RETURN_IF_NO_HANDLE(zh, ZBADARGUMENTS);
    return zks_submit_read(zh, ZKRS_OP_EXISTS, 0, path, watcher, watcherCtx, ZKRS_K_STAT,
                           ZKS_FN(completion), data);
}

int zoo_aexists(zhandle_t *zh, const char *path, int watch, stat_completion_t completion,
                const void *data)
{
    struct zks_pair p;
    if (!zks_watch_arg(zh, watch, __func__, &p)) {
        return ZBADARGUMENTS;
    }
    return zoo_awexists(zh, path, p.fn, p.ctx, completion, data);
}

/* Helper routing children queries with optional stat output buffers. */
static int zks_awget_children_(zhandle_t *zh, const char *path, watcher_fn watcher,
                               void *watcherCtx, strings_completion_t completion,
                               const void *data)
{
    return zks_submit_read(zh, ZKRS_OP_GETCHILDREN, 0, path, watcher, watcherCtx,
                           ZKRS_K_STRINGLIST, ZKS_FN(completion), data);
}

static int zks_awget_children2_(zhandle_t *zh, const char *path, watcher_fn watcher,
                                void *watcherCtx, strings_stat_completion_t completion,
                                const void *data)
{
    return zks_submit_read(zh, ZKRS_OP_GETCHILDREN2, 0, path, watcher, watcherCtx,
                           ZKRS_K_STRINGLIST_STAT, ZKS_FN(completion), data);
}

int zoo_awget_children(zhandle_t *zh, const char *path, watcher_fn watcher, void *watcherCtx,
                       strings_completion_t completion, const void *data)
{
    ZKS_RETURN_IF_NO_HANDLE(zh, ZBADARGUMENTS);
    return zks_awget_children_(zh, path, watcher, watcherCtx, completion, data);
}

int zoo_aget_children(zhandle_t *zh, const char *path, int watch,
                      strings_completion_t completion, const void *data)
{
    struct zks_pair p;
    if (!zks_watch_arg(zh, watch, __func__, &p)) {
        return ZBADARGUMENTS;
    }
    return zks_awget_children_(zh, path, p.fn, p.ctx, completion, data);
}

int zoo_awget_children2(zhandle_t *zh, const char *path, watcher_fn watcher, void *watcherCtx,
                        strings_stat_completion_t completion, const void *data)
{
    ZKS_RETURN_IF_NO_HANDLE(zh, ZBADARGUMENTS);
    return zks_awget_children2_(zh, path, watcher, watcherCtx, completion, data);
}

int zoo_aget_children2(zhandle_t *zh, const char *path, int watch,
                       strings_stat_completion_t completion, const void *data)
{
    struct zks_pair p;
    if (!zks_watch_arg(zh, watch, __func__, &p)) {
        return ZBADARGUMENTS;
    }
    return zks_awget_children2_(zh, path, p.fn, p.ctx, completion, data);
}

int zoo_aget_acl(zhandle_t *zh, const char *path, acl_completion_t completion, const void *data)
{
    zkrs_request rq = ZKRS_REQUEST_INIT(ZKRS_OP_GETACL);
    ZKS_RETURN_IF_NO_HANDLE(zh, ZBADARGUMENTS);
    rq.path = path;
    return zks_submit(zh, &rq, ZKRS_K_ACLLIST, ZKS_FN(completion), data);
}

/* ---- Asynchronous Write Operations ---- */

/*
 * Submits node creation requests supporting TTL expiration semantics, selecting appropriate
 * wire opcodes based on creation flags.
 */
static int zks_acreate(zhandle_t *zh, const char *path, const char *value, int valuelen,
                       const struct ACL_vector *acl, int mode, int64_t ttl, uint32_t flags,
                       unsigned kind, zks_fn fn, const void *data)
{
    zkrs_request rq = ZKRS_REQUEST_INIT(ZKRS_OP_CREATE);
    rq.flags = flags;
    rq.path = path;
    rq.data = value;
    rq.datalen = valuelen;
    rq.mode = mode;
    rq.ttl = ttl;
    rq.acl = acl;
    return zks_submit(zh, &rq, kind, fn, data);
}

int zoo_acreate_ttl(zhandle_t *zh, const char *path, const char *value, int valuelen,
                    const struct ACL_vector *acl, int mode, int64_t ttl,
                    string_completion_t completion, const void *data)
{
    if (zh == NULL) {
        return zks_create_null_handle(mode, ttl);
    }
    ZKS_REGISTRY_CHECK(zh, __func__);
    return zks_acreate(zh, path, value, valuelen, acl, mode, ttl, 0, ZKRS_K_STRING,
                       ZKS_FN(completion), data);
}

int zoo_acreate(zhandle_t *zh, const char *path, const char *value, int valuelen,
                const struct ACL_vector *acl, int mode, string_completion_t completion,
                const void *data)
{
    return zoo_acreate_ttl(zh, path, value, valuelen, acl, mode, -1, completion, data);
}

int zoo_acreate2_ttl(zhandle_t *zh, const char *path, const char *value, int valuelen,
                     const struct ACL_vector *acl, int mode, int64_t ttl,
                     string_stat_completion_t completion, const void *data)
{
    if (zh == NULL) {
        return zks_create_null_handle(mode, ttl);
    }
    ZKS_REGISTRY_CHECK(zh, __func__);
    return zks_acreate(zh, path, value, valuelen, acl, mode, ttl, ZKRS_F_CREATE2,
                       ZKRS_K_STRING_STAT, ZKS_FN(completion), data);
}

int zoo_acreate2(zhandle_t *zh, const char *path, const char *value, int valuelen,
                 const struct ACL_vector *acl, int mode, string_stat_completion_t completion,
                 const void *data)
{
    return zoo_acreate2_ttl(zh, path, value, valuelen, acl, mode, -1, completion, data);
}

int zoo_adelete(zhandle_t *zh, const char *path, int version, void_completion_t completion,
                const void *data)
{
    zkrs_request rq = ZKRS_REQUEST_INIT(ZKRS_OP_DELETE);
    ZKS_RETURN_IF_NO_HANDLE(zh, ZBADARGUMENTS);
    rq.path = path;
    rq.version = version;
    return zks_submit(zh, &rq, ZKRS_K_VOID, ZKS_FN(completion), data);
}

int zoo_aset(zhandle_t *zh, const char *path, const char *buffer, int buflen, int version,
             stat_completion_t completion, const void *data)
{
    zkrs_request rq = ZKRS_REQUEST_INIT(ZKRS_OP_SETDATA);
    ZKS_RETURN_IF_NO_HANDLE(zh, ZBADARGUMENTS);
    rq.path = path;
    rq.data = buffer;
    rq.datalen = buflen;
    rq.version = version;
    return zks_submit(zh, &rq, ZKRS_K_STAT, ZKS_FN(completion), data);
}

int zoo_async(zhandle_t *zh, const char *path, string_completion_t completion, const void *data)
{
    zkrs_request rq = ZKRS_REQUEST_INIT(ZKRS_OP_SYNC);
    ZKS_RETURN_IF_NO_HANDLE(zh, ZBADARGUMENTS);
    rq.path = path;
    return zks_submit(zh, &rq, ZKRS_K_STRING, ZKS_FN(completion), data);
}

int zoo_aset_acl(zhandle_t *zh, const char *path, int version, struct ACL_vector *acl,
                 void_completion_t completion, const void *data)
{
    zkrs_request rq = ZKRS_REQUEST_INIT(ZKRS_OP_SETACL);
    ZKS_RETURN_IF_NO_HANDLE(zh, ZBADARGUMENTS);
    rq.path = path;
    rq.version = version;
    rq.acl = acl;
    return zks_submit(zh, &rq, ZKRS_K_VOID, ZKS_FN(completion), data);
}

int zoo_areconfig(zhandle_t *zh, const char *joining, const char *leaving, const char *members,
                  int64_t version, data_completion_t dc, const void *data)
{
    zkrs_request rq = ZKRS_REQUEST_INIT(ZKRS_OP_RECONFIG);
    ZKS_RETURN_IF_NO_HANDLE(zh, ZBADARGUMENTS);
    rq.joining = joining;
    rq.leaving = leaving;
    rq.members = members;
    rq.reconfig_version = version;
    return zks_submit(zh, &rq, ZKRS_K_DATA, ZKS_FN(dc), data);
}

/* ---- Authentication and Watcher Management ---- */

int zoo_add_auth(zhandle_t *zh, const char *scheme, const char *cert, int certLen,
                 void_completion_t completion, const void *data)
{
    const zkrs_handle *core;
    zkrs_outbox ob = ZKRS_OUTBOX_INIT;
    struct zks_req *r = NULL;
    uint32_t f = 0;
    zkrs_status s;
    if (scheme == NULL) {
        return ZBADARGUMENTS;
    }
    ZKS_RETURN_IF_NO_HANDLE(zh, ZBADARGUMENTS);
    core = zh->core;
    if (completion != NULL) {
        r = zks_req_new(ZKRS_K_VOID, ZKS_REQ_AUTH, ZKS_FN(completion), data);
        if (r == NULL) {
            f = ZKRS_AUTH_F_ALLOC_FAILED;
        }
    }
    /* Validation stages execute prior to acquiring the authentication lock. */
    s = ZKS_CALL(zkrs_add_auth_check(core, cert, certLen));
    if (s != ZKRS_OK) {
        zks_req_free(r);
        return zks_funnel(s);
    }
    /*
     * Serializes authentication mutations under the handle auth_lock, preserving the locking
     * hierarchy (auth_lock -> core lock) and external interposition hooks.
     */
    zoo_lock_auth(zh);
    s = ZKS_CALL(zkrs_add_auth(core, scheme, cert, certLen, (uintptr_t)r, f, &ob));
    zoo_unlock_auth(zh);
    if (s != ZKRS_OK) {
        zks_req_free(r);
    }
    zks_drain(zh, &ob);
    return zks_funnel(s);
}

ZKS_HIDDEN int zks_aremove_watches(zhandle_t *zh, const char *path, ZooWatcherType wtype,
                                   watcher_fn w, void *wctx, int local, void_completion_t fn,
                                   const void *data, int all, int sync)
{
    zkrs_request rq = ZKRS_REQUEST_INIT(ZKRS_OP_REMOVE_WATCHES);
    zkrs_status s;
    ZKS_RETURN_IF_NO_HANDLE(zh, ZBADARGUMENTS);
    rq.path = path;
    rq.wtype = (int32_t)wtype;
    rq.watch_fn = (uintptr_t)w;
    rq.watch_ctx = (uintptr_t)wctx;
    rq.flags = (local ? ZKRS_F_LOCAL : 0) | (all ? ZKRS_F_REMOVE_ALL : 0);
    s = zks_submit_raw(zh, &rq, ZKRS_K_VOID, sync ? ZKS_FN(ZKS_SYNC_MARKER) : ZKS_FN(fn), data);
    if (s == ZKRS_OK_LOCAL && sync) {
        /* Immediate local watch removals unblock synchronous callers without server round trips. */
#ifdef THREADED
        zks_sync_complete_local((struct zks_sync *)(uintptr_t)data);
#endif
    }
    return zks_funnel(s);
}

int zoo_aremove_watches(zhandle_t *zh, const char *path, ZooWatcherType wtype,
                        watcher_fn watcher, void *watcherCtx, int local,
                        void_completion_t *completion, const void *data)
{
    void_completion_t fn;
    /* Unpack completion callback pointer passed by value. */
    memcpy(&fn, &completion, sizeof fn);
    return zks_aremove_watches(zh, path, wtype, watcher, watcherCtx, local, fn, data, 0, 0);
}

int zoo_aremove_all_watches(zhandle_t *zh, const char *path, ZooWatcherType wtype, int local,
                            void_completion_t *completion, const void *data)
{
    void_completion_t fn;
    memcpy(&fn, &completion, sizeof fn);
    return zks_aremove_watches(zh, path, wtype, NULL, NULL, local, fn, data, 1, 0);
}
