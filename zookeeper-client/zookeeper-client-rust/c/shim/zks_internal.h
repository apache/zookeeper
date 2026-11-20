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
 * Internal C Drop-in Shim Interface.
 *
 * This header defines internal declarations and macros shared across all C shim
 * translation units. It acts as the sole inclusion point for zookeeper.h, sanitizing
 * feature macros (such as OpenSSL and Cyrus SASL defines) prior to header expansion
 * to guarantee that the shim core compiles cleanly without direct dependencies on
 * OpenSSL or Cyrus SASL headers unless explicitly required by SASL glue units.
 *
 * Identifiers prefixed with zks_ represent library-private implementation details
 * scoped with hidden ELF visibility to prevent symbol leakage into applications.
 */

#ifndef ZKS_INTERNAL_H_
#define ZKS_INTERNAL_H_

#undef HAVE_OPENSSL_H
#ifndef ZKS_SASL_GLUE
#undef HAVE_CYRUS_SASL_H
#endif

#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
/* POSIX threads required across both single threaded and multithreaded builds for cancellation masking. */
#include <pthread.h>

#include "zookeeper.h"
#include "zookeeper_log.h"
#include "zkrs.h"

/* ------------------------------------------------------------------------
 * Compiler and Toolchain Abstraction Attributes.
 * ---------------------------------------------------------------------- */

#define ZKS_HIDDEN __attribute__((visibility("hidden")))
#define ZKS_THREAD_LOCAL __thread
#define ZKS_NORETURN __attribute__((noreturn))
#define ZKS_UNUSED(x) ((void)(x))

/* Relaxed atomic memory ordering primitives for thread-safe scalar updates. */
#define ZKS_ATOMIC(T) T
#define ZKS_LOAD(p) __atomic_load_n((p), __ATOMIC_RELAXED)
#define ZKS_STORE(p, v) __atomic_store_n((p), (v), __ATOMIC_RELAXED)
/* Acquire and release forms for pointers that publish memory to other threads. */
#define ZKS_LOAD_ACQ(p) __atomic_load_n((p), __ATOMIC_ACQUIRE)
#define ZKS_STORE_REL(p, v) __atomic_store_n((p), (v), __ATOMIC_RELEASE)

/* ------------------------------------------------------------------------
 * Rust FFI Re-entrancy Protection.
 *
 * Prevents illegal recursive calls from host vtable callbacks back into Rust FFI
 * entry points. When a host vtable hook is active on the current thread, any attempt
 * to re-enter Rust triggers an assertion failure and abort, protecting driver
 * invariants and avoiding lock deadlocks.
 * ---------------------------------------------------------------------- */

extern ZKS_HIDDEN ZKS_THREAD_LOCAL int zks_in_vtable;

static inline void zks_check_call(const char *what)
{
    if (zks_in_vtable) {
        fprintf(stderr, "zookeeper: Rust call from a vtable entry: %s\n", what);
        abort();
    }
}

#define ZKS_ASSERT_NOT_IN_RUST() zks_check_call(__func__)

/* ------------------------------------------------------------------------
 * Thread Cancellation Safety and Stack Unwinding Masks.
 *
 * A cancel inside a Rust frame can end the thread while it holds a Rust lock
 * and can skip Drop. The shim therefore disables cancellation for each call
 * into Rust and while it holds Rust outbox items. Synchronous waits
 * (pthread_cond_wait) keep cancellation enabled, so applications keep their
 * usual cancellation points.
 *
 * A per-thread mask state lets nested calls skip the state change. OFF means
 * that the shim did not change the cancel state. SCOPED means that an outer
 * shim frame disabled cancellation and restores it on exit. PINNED means that
 * a library thread disabled cancellation for its full life.
 *
 * Invariant: the state is SCOPED only while no application code runs above
 * the frame that set it. ZKS_APP_CALL clears SCOPED before each application
 * callback and disables cancellation again after the callback returns. A
 * longjmp out of a callback or a cancel state change in a callback therefore
 * cannot make later calls skip the mask.
 *
 * A longjmp out of a callback skips the restore of the outer frame. The
 * thread then keeps cancellation disabled. This is the safe direction, and
 * the shim accepts it.
 * ---------------------------------------------------------------------- */

#define ZKS_CANCEL_NONE (-1)

#define ZKS_MASK_OFF 0
#define ZKS_MASK_SCOPED 1
#define ZKS_MASK_PINNED 2

extern ZKS_HIDDEN ZKS_THREAD_LOCAL int zks_cancel_masked;

/* Disables thread cancellation for the current thread, returning the prior state. */
static inline int zks_cancel_mask(void)
{
    int saved_errno;
    int old;
    if (zks_cancel_masked != ZKS_MASK_OFF) {
        return ZKS_CANCEL_NONE;
    }
    saved_errno = errno;
    if (pthread_setcancelstate(PTHREAD_CANCEL_DISABLE, &old) != 0) {
        old = ZKS_CANCEL_NONE;
    } else {
        zks_cancel_masked = ZKS_MASK_SCOPED;
    }
    errno = saved_errno;
    return old;
}

/* Restores the previous thread cancellation state saved by zks_cancel_mask. */
static inline void zks_cancel_unmask(int old)
{
    int saved_errno;
    if (old != ZKS_CANCEL_NONE) {
        saved_errno = errno;
        zks_cancel_masked = ZKS_MASK_OFF;
        pthread_setcancelstate(old, NULL);
        errno = saved_errno;
    }
}

/*
 * Disables cancellation for the rest of the life of a library thread. The
 * pinned state never changes, so callbacks on these threads cost nothing extra.
 */
static inline void zks_cancel_pin(void)
{
    int saved_errno = errno;
    if (pthread_setcancelstate(PTHREAD_CANCEL_DISABLE, NULL) == 0) {
        zks_cancel_masked = ZKS_MASK_PINNED;
    }
    errno = saved_errno;
}

/*
 * Clears a scoped mask state before an application callback runs. The real
 * cancel state does not change. Returns the prior state for zks_app_leave.
 */
static inline int zks_app_enter(void)
{
    int m = zks_cancel_masked;
    if (m == ZKS_MASK_SCOPED) {
        zks_cancel_masked = ZKS_MASK_OFF;
    }
    return m;
}

/*
 * Disables cancellation again after an application callback returns, because
 * the callback can enable it. If this fails, the state stays OFF, and nested
 * calls then mask for real.
 */
static inline void zks_app_leave(int m)
{
    int saved_errno;
    if (m == ZKS_MASK_SCOPED) {
        saved_errno = errno;
        if (pthread_setcancelstate(PTHREAD_CANCEL_DISABLE, NULL) == 0) {
            zks_cancel_masked = ZKS_MASK_SCOPED;
        }
        errno = saved_errno;
    }
}

/* Runs one application callback outside the cached mask state. */
#define ZKS_APP_CALL(x)                                                                  \
    do {                                                                                 \
        int zks_ac_ = zks_app_enter();                                                   \
        (x);                                                                             \
        zks_app_leave(zks_ac_);                                                          \
    } while (0)

static inline void zks_cancel_scope_end(int *old)
{
    zks_cancel_unmask(*old);
}

/* RAII-style scope guard that automatically restores cancellation state upon scope exit. */
#define ZKS_CANCEL_SCOPE                                                                 \
    int zks_cancel_scope_ __attribute__((cleanup(zks_cancel_scope_end))) = zks_cancel_mask()

/*
 * Scope guard for blocks executing application callbacks during outbox draining.
 * In multithreaded mode, cancellation is disabled for the duration of the drain scope.
 * In single threaded mode, cancellation masking applies strictly to nested Rust calls.
 */
#ifdef THREADED
#define ZKS_HOLD_SCOPE ZKS_CANCEL_SCOPE
#else
#define ZKS_HOLD_SCOPE int zks_hold_scope_ __attribute__((unused)) = 0
#endif

/* Validates re-entrancy invariants and masks thread cancellation prior to calling Rust. */
static inline int zks_rust_enter(const char *what)
{
    zks_check_call(what);
    return zks_cancel_mask();
}

/* Invokes a Rust FFI function returning a value, safely bracketed by cancellation guards. */
#define ZKS_CALL(x)                                                                      \
    __extension__({                                                                      \
        int zks_cm_ = zks_rust_enter(#x);                                                \
        __typeof__(x) zks_cr_ = (x);                                                     \
        zks_cancel_unmask(zks_cm_);                                                      \
        zks_cr_;                                                                         \
    })
/* Invokes a void Rust FFI function, safely bracketed by cancellation guards. */
#define ZKS_CALL_VOID(x)                                                                 \
    do {                                                                                 \
        int zks_cm_ = zks_rust_enter(#x);                                                \
        (x);                                                                             \
        zks_cancel_unmask(zks_cm_);                                                      \
    } while (0)

/*
 * Invokes a Rust leaf entry without the cancel mask. A leaf entry only reads
 * or copies memory and takes short Rust locks. It calls no cancellation point,
 * no application code, and it never drops the last handle reference. A
 * deferred cancel thus cannot act inside it. Use ZKS_CALL for all other entries.
 */
#define ZKS_CALL_LEAF(x)                                                                 \
    __extension__({                                                                      \
        zks_check_call(#x);                                                              \
        (x);                                                                             \
    })
/* Invokes a void Rust leaf entry without the cancel mask. */
#define ZKS_CALL_LEAF_VOID(x)                                                            \
    do {                                                                                 \
        zks_check_call(#x);                                                              \
        (x);                                                                             \
    } while (0)

/* ------------------------------------------------------------------------
 * Thread Role Tracking and Execution Context.
 *
 * Dedicated client threads (such as I/O pump and event dispatch) initialize
 * thread local identity markers upon startup before synchronizing on the start latch.
 * ---------------------------------------------------------------------- */

extern ZKS_HIDDEN ZKS_THREAD_LOCAL const zhandle_t *zks_tls_zh;
extern ZKS_HIDDEN ZKS_THREAD_LOCAL uint32_t zks_tls_role;

/*
 * Active outbox descriptor currently being processed by the dispatch loop.
 * Thread cleanup handlers rely on this pointer to release undelivered items
 * if thread execution terminates abruptly.
 */
extern ZKS_HIDDEN ZKS_THREAD_LOCAL zkrs_outbox *zks_tls_dispatch_ob;

/* Evaluates the operational role of the calling thread relative to a specific handle. */
static inline uint32_t zks_current_role(const zhandle_t *zh)
{
    return zks_tls_zh == zh ? zks_tls_role : ZKRS_ROLE_FOREIGN;
}

/* ------------------------------------------------------------------------
 * Atomic Watcher Registration Cell.
 *
 * The default watcher and its context are two independent lock-free atomic
 * pointers. A reader never sees a torn pointer. No lock exists, so a signal
 * handler or a real-time thread cannot block on the cell. As in the C client,
 * zoo_set_watcher and zoo_set_context change their fields independently.
 * Release stores and acquire loads publish the memory that each pointer
 * refers to.
 * ---------------------------------------------------------------------- */

struct zks_pair {
    watcher_fn fn;
    void *ctx;
};

struct zks_watch_cell {
    ZKS_ATOMIC(watcher_fn) fn;
    ZKS_ATOMIC(void *) ctx;
};

/* Default no-op watcher sentinel substituted when no watcher callback is configured. */
ZKS_HIDDEN void zks_null_watcher(zhandle_t *zh, int type, int state, const char *path,
                                 void *ctx);

/* Plain stores are sufficient, because no other thread can see the handle yet. */
static inline void zks_watch_cell_init(struct zks_watch_cell *c, watcher_fn fn, void *ctx)
{
    c->fn = fn;
    c->ctx = ctx;
}

static inline struct zks_pair zks_watch_cell_read(struct zks_watch_cell *c)
{
    struct zks_pair p;
    p.fn = ZKS_LOAD_ACQ(&c->fn);
    p.ctx = ZKS_LOAD_ACQ(&c->ctx);
    return p;
}

static inline void *zks_watch_cell_ctx(struct zks_watch_cell *c)
{
    return ZKS_LOAD_ACQ(&c->ctx);
}

/* Updates the active watcher function pointer and returns the previously installed handler. */
static inline watcher_fn zks_watch_cell_set_fn(struct zks_watch_cell *c, watcher_fn fn)
{
    return __atomic_exchange_n(&c->fn, fn != NULL ? fn : zks_null_watcher, __ATOMIC_ACQ_REL);
}

static inline void zks_watch_cell_set_ctx(struct zks_watch_cell *c, void *ctx)
{
    ZKS_STORE_REL(&c->ctx, ctx);
}

/* ------------------------------------------------------------------------
 * multithreaded Startup Synchronization Barrier.
 * ---------------------------------------------------------------------- */

#ifdef THREADED
struct zks_latch {
    pthread_mutex_t m;
    pthread_cond_t c;
    int remaining;
};

ZKS_HIDDEN int zks_latch_init(struct zks_latch *l, int parties);
ZKS_HIDDEN void zks_latch_arrive_and_wait(struct zks_latch *l);
ZKS_HIDDEN void zks_latch_destroy(struct zks_latch *l);
#endif

/* ------------------------------------------------------------------------
 * ZooKeeper Client Handle Internal Layout.
 *
 * Retains the canonical _zhandle structure tag required by historical C++ ABI symbols.
 * ---------------------------------------------------------------------- */

struct zks_sasl_sess; /* Active SASL authentication session context (when compiled with SASL). */

struct _zhandle {
    clientid_t client_id; /* Session credentials returned via zoo_client_id(). */
    struct zks_watch_cell dw;
    ZKS_ATOMIC(log_callback_fn) log_callback; /* Application logging sink, or NULL for logStream. */
    const zkrs_handle *core; /* Strong reference to underlying Rust driver handle. */
    int flags;
    struct zks_sasl_sess *sasl;
#ifdef THREADED
    pthread_t io_thread, dispatch_thread;
    pthread_mutex_t auth_lock; /* Guards zoo_lock_auth and zoo_unlock_auth operations. */
    struct zks_latch start_latch; /* Startup rendezvous for I/O, dispatch, and caller threads. */
#endif
};

/* ------------------------------------------------------------------------
 * Asynchronous Completion Function Dispatch Descriptor.
 *
 * Holds caller completion callbacks represented as a discriminated union over
 * standard ZooKeeper C API callback signatures.
 * ---------------------------------------------------------------------- */

typedef void (*zks_generic_fn)(void);

typedef union zks_fn {
    zks_generic_fn g;
    void_completion_t v;
    stat_completion_t s;
    data_completion_t d;
    strings_completion_t sl;
    strings_stat_completion_t sls;
    acl_completion_t acl;
    string_completion_t str;
    string_stat_completion_t strs;
} zks_fn;

static inline zks_fn zks_fn_make(zks_generic_fn f)
{
    zks_fn u;
    u.g = f;
    return u;
}

#define ZKS_FN(f) zks_fn_make((zks_generic_fn)(f))

/*
 * Sentinel completion function address used to designate synchronous requests
 * dispatched through internal asynchronous machinery.
 */
ZKS_HIDDEN void zks_sync_marker(void);
#define ZKS_SYNC_MARKER (&zks_sync_marker)

/* ------------------------------------------------------------------------
 * Asynchronous Request Records.
 *
 * Tracks individual outstanding operations submitted across the FFI boundary.
 * The pointer to each zks_req serves as an opaque completion token in Rust,
 * returned to C exactly once either upon completion or connection teardown.
 * ---------------------------------------------------------------------- */

enum zks_req_mode {
    ZKS_REQ_ASYNC = 0,
    ZKS_REQ_SYNC = 1,
    ZKS_REQ_AUTH = 2
};

/* Detects operational mode based on whether the completion matches the sync marker. */
static inline unsigned zks_req_mode_of(zks_fn fn)
{
#ifdef THREADED
    if (fn.g == ZKS_SYNC_MARKER) {
        return ZKS_REQ_SYNC;
    }
#else
    ZKS_UNUSED(fn);
#endif
    return ZKS_REQ_ASYNC;
}

struct zks_sync;

struct zks_req {
    uint8_t kind; /* Operation opcode (ZKRS_K_*). */
    uint8_t mode; /* Execution mode from enum zks_req_mode. */
    zks_fn fn;
    const void *data;
    struct zks_sync *sync; /* Synchronization context for synchronous requests. */
    /* Aggregated transaction records for multi-op batches. */
    zoo_op_result_t *results;
    int32_t count;
    uint8_t *op_kind;
};

/* Allocates a new request tracking record; returns NULL on memory exhaustion. */
ZKS_HIDDEN struct zks_req *zks_req_new(unsigned kind, unsigned mode, zks_fn fn, const void *data);
/* Deallocates a request tracking record and associated operation arrays. */
ZKS_HIDDEN void zks_req_free(struct zks_req *r);

/* ------------------------------------------------------------------------
 * Synchronous Call Waiting Records (multithreaded Mode Only).
 * ---------------------------------------------------------------------- */

#ifdef THREADED
struct zks_sync {
    pthread_mutex_t m;
    pthread_cond_t c;
    int refs, done, rc, failed, origin;
    uint32_t kind; /* Target operation opcode (ZKRS_K_*). */
    char *data;
    int32_t data_len; /* Length in bytes, or -1 for non-existent/null data. */
    char *path;
    int32_t path_len;
    struct Stat stat;
    struct String_vector strs;
    struct ACL_vector acl;
    zkrs_multi_copy *ops;
    int32_t nops;
    uint8_t *op_kind;
};

/* Unmarshals completed result payload and wakes blocked synchronous caller. */
ZKS_HIDDEN void zks_sync_complete(struct zks_req *r, const zkrs_result_view *v);
/* Signals synchronous completion for local watch deregistration. */
ZKS_HIDDEN void zks_sync_complete_local(struct zks_sync *s);
/* Completes synchronous request with ZCLOSING when handle is shut down before completion. */
ZKS_HIDDEN void zks_sync_complete_closing(struct zks_req *r);
#endif

/* ------------------------------------------------------------------------
 * Optional Initialization Configurations.
 * ---------------------------------------------------------------------- */

struct zks_tls_args {
    int tls;
    const char *ca, *cert, *key, *passwd;
};

struct zoo_sasl_params;

/* ------------------------------------------------------------------------
 * Internal Function Declarations.
 * ---------------------------------------------------------------------- */

/* zks_handle.c */
ZKS_HIDDEN zhandle_t *zks_init_common(const char *host, watcher_fn fn, int recv_timeout,
                                      const clientid_t *cid, void *ctx, int flags,
                                      log_callback_fn logcb, const struct zks_tls_args *tls,
                                      const struct zoo_sasl_params *sasl);
ZKS_HIDDEN void zks_release(zhandle_t *zh, const zkrs_handle *core);
ZKS_HIDDEN void zks_secure_zero(void *p, size_t n);
extern ZKS_HIDDEN const zkrs_host_vtable zks_vtable;

/* zks_drain.c */
ZKS_HIDDEN void zks_drain(zhandle_t *zh, zkrs_outbox *ob);
ZKS_HIDDEN int zks_drive(zhandle_t *zh, const zkrs_handle *core, zkrs_step s, zkrs_outbox *ob,
                         int *rc);
ZKS_HIDDEN int zks_drive_errno(zhandle_t *zh, const zkrs_handle *core, zkrs_step s,
                               zkrs_outbox *ob, int *rc, int *err);

/* zks_async.c */
ZKS_HIDDEN int zks_submit_prepared(zhandle_t *zh, zkrs_request *rq, struct zks_req *r);
ZKS_HIDDEN int zks_aremove_watches(zhandle_t *zh, const char *path, ZooWatcherType wtype,
                                   watcher_fn w, void *wctx, int local, void_completion_t fn,
                                   const void *data, int all, int sync);

/* zks_multi.c */
ZKS_HIDDEN void zks_multi_complete(struct zks_req *r, const zkrs_result_view *v);

/* zks_threads.c (mt only) */
#ifdef THREADED
ZKS_HIDDEN int zks_start_threads(zhandle_t *zh);
ZKS_HIDDEN void zks_thread_item(zhandle_t *zh, uint32_t op, uint32_t role);
#endif

/* zks_log.c */
ZKS_HIDDEN void zks_log_emit(zhandle_t *zh, const zkrs_log_view *v);
ZKS_HIDDEN void zks_log_site(zhandle_t *zh, uint32_t site, ...);
ZKS_HIDDEN void zks_log_site_cb(log_callback_fn cb, uint32_t site, ...);

/* zks_env.c */
ZKS_HIDDEN void zks_log_env(zhandle_t *zh);

/* zks_resolve.c: formats into a per-thread buffer of 134 bytes */
ZKS_HIDDEN const char *zks_format_endpoint(const zkrs_sockaddr_view *v);

/* Host vtable callbacks invoked by the Rust driver. */
ZKS_HIDDEN void zks_vt_discard_token(uintptr_t token);                           /* zks_drain.c */
ZKS_HIDDEN void zks_vt_snapshot_default_watcher(uintptr_t host, zkrs_watcher *out); /* zks_handle.c */
ZKS_HIDDEN void zks_vt_store_client_id(uintptr_t host, int64_t id, const uint8_t *passwd); /* zks_handle.c */
ZKS_HIDDEN int zks_vt_log_enabled(uint32_t level);                               /* zks_log.c */
ZKS_HIDDEN int zks_vt_conn_permute_disabled(void);                               /* zks_rng.c */
ZKS_HIDDEN long zks_vt_rng_random(void);                                         /* zks_rng.c */
ZKS_HIDDEN double zks_vt_rng_drand48(void);                                      /* zks_rng.c */
ZKS_HIDDEN void zks_vt_rng_reseed(void);                                         /* zks_rng.c */
ZKS_HIDDEN int zks_vt_resolve(const char *name, const char *port, int32_t *gai_rc,
                              void (*push)(void *sink, const zkrs_sockaddr_view *addr),
                              void (*skip)(void *sink, int32_t family), void *sink); /* zks_resolve.c */

/* ------------------------------------------------------------------------
 * C Shim Diagnostic Logging Call Sites.
 *
 * Instrumentation sites defined across C shim translation units, keyed against
 * the baseline ABI specification.
 * ---------------------------------------------------------------------- */

#include "zks_log_sites.h"

#define ZKS_C_SITE_KEY(name, level, func, line, line_end, fmt) name,

enum zks_c_site { ZKS_C_SITES(ZKS_C_SITE_KEY) ZKS_SITE_C_COUNT };

/* Dispatches formatted log output to the active log sink if severity is enabled. */
#define ZKS_LOG(...) zks_log_site(__VA_ARGS__)
#define ZKS_LOG_CB(...) zks_log_site_cb(__VA_ARGS__)

/* ------------------------------------------------------------------------
 * Debug Handle Registry Verification.
 *
 * In diagnostic registry builds, ensures client handles passed to public API
 * functions are actively registered before dereferencing internal state.
 * ---------------------------------------------------------------------- */

#ifdef ZKS_HANDLE_REGISTRY
#define ZKS_REGISTRY_ADD(zh) ZKS_CALL_VOID(zkrs_registry_add(zh))
#define ZKS_REGISTRY_REMOVE(zh) ZKS_CALL_VOID(zkrs_registry_remove(zh))
#define ZKS_REGISTRY_CHECK(zh, func) ZKS_CALL_VOID(zkrs_registry_check((zh), (func)))
#else
#define ZKS_REGISTRY_ADD(zh) ((void)0)
#define ZKS_REGISTRY_REMOVE(zh) ((void)0)
#define ZKS_REGISTRY_CHECK(zh, func) ((void)0)
#endif

/*
 * Validates that the supplied handle pointer is non-null and currently live,
 * returning early with the specified value on validation failure.
 */
#define ZKS_RETURN_IF_NO_HANDLE(zh, rv)                                                            \
    do {                                                                                           \
        if ((zh) == NULL) {                                                                        \
            return rv;                                                                             \
        }                                                                                          \
        ZKS_REGISTRY_CHECK((zh), __func__);                                                        \
    } while (0)
#define ZKS_RETURN_VOID_IF_NO_HANDLE(zh) ZKS_RETURN_IF_NO_HANDLE(zh, )

/*
 * Resolves the effective watcher callback pair for operations accepting an integer watch flag.
 * If watch is non-zero, captures the default watcher registered on the handle.
 */
static inline int zks_watch_arg(zhandle_t *zh, int watch, const char *func, struct zks_pair *p)
{
    ZKS_UNUSED(func); /* Referenced only when handle registry verification is enabled. */
    if (zh == NULL) {
        return 0;
    }
    ZKS_REGISTRY_CHECK(zh, func);
    *p = zks_watch_cell_read(&zh->dw);
    if (!watch) {
        p->fn = NULL;
    }
    return 1;
}

/* ------------------------------------------------------------------------
 * Legacy Internal Declarations.
 *
 * Retains forward declarations for internal routines defined by legacy C client
 * sources that lacked public header exports.
 * ---------------------------------------------------------------------- */

int zoo_lock_auth(zhandle_t *zh);
int zoo_unlock_auth(zhandle_t *zh);

/* Defined by zookeeper.c in legacy; no installed header declares them. */
void zoo_create2_op_init(zoo_op_t *op, const char *path, const char *value, int valuelen,
                         const struct ACL_vector *acl, int mode, char *path_buffer,
                         int path_buffer_len);
extern ZOOAPI const int ZOO_SSL_CONNECTING_STATE;

#endif /* ZKS_INTERNAL_H_ */
