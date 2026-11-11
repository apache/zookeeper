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
 * Handle lifecycle management, runtime state machine control, and public accessor APIs.
 *
 * Implements client handle allocation (zks_init_common, zookeeper_init), session termination
 * (zookeeper_close), and handle destruction (zks_release). Configures the FFI dispatch vtable
 * linking C subsystem callbacks to the Rust driver core, manages cancellation safety masks,
 * and maintains handle registration within the debug tracking registry.
 *
 * Invariant: zh->core is cached in a local variable prior to FFI calls. Neither zh nor zh->core
 * is accessed following operations that can trigger handle release, except via controlled outbox
 * drain transitions.
 */

#include "zks_internal.h"
#ifdef ZKS_WITH_SASL
#include "zks_sasl.h"
#endif

#include <limits.h>
#include <time.h>
#include <unistd.h>
#if defined(__linux__)
#include <sys/auxv.h>
#endif

ZKS_HIDDEN ZKS_THREAD_LOCAL int zks_in_vtable;
ZKS_HIDDEN ZKS_THREAD_LOCAL const zhandle_t *zks_tls_zh;
ZKS_HIDDEN ZKS_THREAD_LOCAL uint32_t zks_tls_role;
ZKS_HIDDEN ZKS_THREAD_LOCAL zkrs_outbox *zks_tls_dispatch_ob;
/*
 * Per-thread cancellation mask state (OFF, SCOPED, or PINNED), governed by the
 * cancellation discipline specified in zks_internal.h.
 */
ZKS_HIDDEN ZKS_THREAD_LOCAL int zks_cancel_masked;

/* Static dispatch vtable binding C subsystem functions to the Rust FFI core interface. */
ZKS_HIDDEN const zkrs_host_vtable zks_vtable = {
    zks_vt_discard_token,
    zks_vt_snapshot_default_watcher,
    zks_vt_store_client_id,
    zks_vt_log_enabled,
    zks_vt_conn_permute_disabled,
    zks_vt_rng_random,
    zks_vt_rng_drand48,
    zks_vt_rng_reseed,
    zks_vt_resolve,
};

ZKS_HIDDEN void zks_null_watcher(zhandle_t *zh, int type, int state, const char *path, void *ctx)
{
    ZKS_UNUSED(zh);
    ZKS_UNUSED(type);
    ZKS_UNUSED(state);
    ZKS_UNUSED(path);
    ZKS_UNUSED(ctx);
}

/* Securely clears memory containing cryptographic secrets or passphrases using volatile stores. */
ZKS_HIDDEN void zks_secure_zero(void *p, size_t n)
{
    volatile unsigned char *b = (volatile unsigned char *)p;
    while (n > 0) {
        *b = 0;
        b++;
        n--;
    }
}

/* ---- Vtable Host Accessors ---- */

ZKS_HIDDEN void zks_vt_snapshot_default_watcher(uintptr_t host, zkrs_watcher *out)
{
    zhandle_t *zh = (zhandle_t *)host;
    struct zks_pair p;
    int prev = zks_in_vtable;

    zks_in_vtable = 1;
    out->fn = 0;
    out->ctx = 0;
    if (zh != NULL) {
        p = zks_watch_cell_read(&zh->dw);
        out->fn = (uintptr_t)p.fn;
        out->ctx = (uintptr_t)p.ctx;
    }
    zks_in_vtable = prev;
}

/*
 * Persists negotiated session credentials after each completed handshake. To
 * avoid mutating memory backing zoo_client_id across reconnections to the same
 * session, the handle storage is updated only when the session identifier or
 * password changes, using a constant-time comparison over the secret bytes.
 */
ZKS_HIDDEN void zks_vt_store_client_id(uintptr_t host, int64_t id, const uint8_t *passwd)
{
    zhandle_t *zh = (zhandle_t *)host;
    int prev = zks_in_vtable;
    unsigned char d = 0;
    size_t i;

    zks_in_vtable = 1;
    if (zh != NULL) {
        for (i = 0; i < sizeof zh->client_id.passwd; i++) {
            d |= (unsigned char)((unsigned char)zh->client_id.passwd[i] ^ passwd[i]);
        }
        if (zh->client_id.client_id != id || d != 0) {
            zh->client_id.client_id = id;
            memcpy(zh->client_id.passwd, passwd, sizeof zh->client_id.passwd);
        }
        zks_secure_zero(&d, sizeof d);
    }
    zks_in_vtable = prev;
}

/* ---- Handle Initialization, Teardown, and Resource Reclamation ---- */

/* Determines whether the process environment permits reading TLS configuration variables. */
static uint32_t zks_tls_env_allowed(void)
{
#if defined(__APPLE__) || defined(__FreeBSD__) || defined(__NetBSD__) || defined(__OpenBSD__) \
    || defined(__DragonFly__)
    return issetugid() ? 0 : 1;
#elif defined(__linux__)
    return getauxval(AT_SECURE) == 0 ? 1 : 0;
#else
    return 1;
#endif
}

/* Packages initialization arguments for transmission across the FFI boundary. */
static void zks_fill_init_args(zkrs_init_args *a, const char *host, int recv_timeout,
                               const clientid_t *cid, int flags, const struct zks_tls_args *tls,
                               int sasl)
{
    memset(a, 0, sizeof *a);
#ifdef THREADED
    a->flavor = ZKRS_FLAVOR_MT;
#else
    a->flavor = ZKRS_FLAVOR_ST;
#endif
    a->hosts = host;
    a->recv_timeout = recv_timeout;
    if (cid != NULL) {
        a->have_client_id = 1;
        a->client_id = cid->client_id;
        memcpy(a->client_passwd, cid->passwd, sizeof a->client_passwd);
    }
    a->flags = flags;
    if (tls != NULL) {
        a->tls = tls->tls ? 1 : 0;
        a->tls_ca = tls->ca;
        a->tls_cert = tls->cert;
        a->tls_key = tls->key;
        a->tls_passwd = tls->passwd;
    }
    a->tls_env_allowed = zks_tls_env_allowed();
    a->sasl = sasl ? 1 : 0;
    a->xid_seed = (int32_t)time(0);
#ifdef SOCK_CLOEXEC_ENABLED
    a->sock_cloexec = 1;
#endif
    a->c_char_signed = CHAR_MIN < 0 ? 1 : 0;
}

/*
 * Core handle initialization pipeline mirroring legacy zookeeper_init_internal.
 * Initializes logging banners, parses server connection strings, creates background threads,
 * and synchronizes startup barriers.
 */
ZKS_HIDDEN zhandle_t *zks_init_common(const char *host, watcher_fn fn, int recv_timeout,
                                      const clientid_t *cid, void *ctx, int flags,
                                      log_callback_fn logcb, const struct zks_tls_args *tls,
                                      const struct zoo_sasl_params *sasl)
{
    /* Mask thread cancellation across initialization to prevent partial handle construction. */
    ZKS_CANCEL_SCOPE;
    zhandle_t *zh = (zhandle_t *)calloc(1, sizeof *zh);
    zkrs_init_args a;
    zkrs_handle *core = NULL;
    zkrs_outbox ob = ZKRS_OUTBOX_INIT;
    int err = 0;
    zkrs_step s;
#ifdef THREADED
    int auth_lock_ready = 0;
#endif

    if (zh == NULL) {
        errno = ENOMEM;
        return NULL;
    }
    ZKS_STORE(&zh->log_callback, logcb);
    zh->flags = flags;
    if (!(flags & ZOO_NO_LOG_CLIENTENV)) {
        zks_log_env(zh);
    }
    ZKS_LOG(zh, ZKS_SITE_INIT_INITIATING, host, recv_timeout, (void *)fn,
            (long long)(cid != NULL ? cid->client_id : 0),
            (cid == NULL || !cid->passwd[0]) ? "<null>" : "<hidden>", ctx, flags);
    zks_watch_cell_init(&zh->dw, fn != NULL ? fn : zks_null_watcher, ctx);
    if (host == NULL || *host == 0) {
        err = EINVAL;
        goto fail;
    }
    if (cid != NULL) {
        memcpy(&zh->client_id, cid, sizeof *cid);
    }
    zks_fill_init_args(&a, host, recv_timeout, cid, flags, tls, sasl != NULL);
    s = ZKS_CALL(zkrs_handle_new(&a, &zks_vtable, (uintptr_t)zh, &core, &ob, &err));
    zks_secure_zero(a.client_passwd, sizeof a.client_passwd);
    /* Bind the allocated driver core handle to the C zhandle wrapper. */
    zh->core = core;
    while (s == ZKRS_MORE) {
        zks_drain(zh, &ob);
#ifdef ZKS_WITH_SASL
        /* Initializes Cyrus SASL session context prior to spawning background thread workers. */
        if (err == ZKRS_INIT_SASL_POINT) {
            err = 0;
            zh->sasl = zks_sasl_sess_new(zh, sasl);
            if (zh->sasl == NULL) {
                err = ENOMEM;
                goto fail_core;
            }
        }
#endif
        s = ZKS_CALL(zkrs_resume(core, &ob, &err));
    }
    zks_drain(zh, &ob);
    if (err == 0 && core == NULL) {
        err = EINVAL;
    }
    if (err != 0) {
        if (core == NULL) {
            goto fail;
        }
        goto fail_core;
    }
    /* Register the active handle in debug tracking structures prior to worker thread startup. */
    ZKS_REGISTRY_ADD(zh);
#ifdef THREADED
    err = pthread_mutex_init(&zh->auth_lock, 0);
    if (err != 0) {
        goto fail_core;
    }
    auth_lock_ready = 1;
    err = zks_start_threads(zh);
    if (err != 0) {
        goto fail_core;
    }
#endif
    return zh;
fail_core:
#ifdef THREADED
    if (auth_lock_ready) {
        pthread_mutex_destroy(&zh->auth_lock);
    }
#endif
    ZKS_REGISTRY_REMOVE(zh);
    ZKS_CALL_VOID(zkrs_handle_abandon(core));
fail:
#ifdef ZKS_WITH_SASL
    zks_sasl_sess_free(zh->sasl);
#endif
    zks_secure_zero(zh->client_id.passwd, sizeof zh->client_id.passwd);
    free(zh);
    errno = err;
    return NULL;
}

zhandle_t *zookeeper_init(const char *host, watcher_fn fn, int recv_timeout,
                          const clientid_t *clientid, void *context, int flags)
{
    return zks_init_common(host, fn, recv_timeout, clientid, context, flags, NULL, NULL, NULL);
}

zhandle_t *zookeeper_init2(const char *host, watcher_fn fn, int recv_timeout,
                           const clientid_t *clientid, void *context, int flags,
                           log_callback_fn log_callback)
{
    return zks_init_common(host, fn, recv_timeout, clientid, context, flags, log_callback, NULL,
                           NULL);
}

/*
 * Reclaims memory allocated for zhandle_t following final session release from
 * the Rust driver. Internal host pointers are cleared prior to ZKRS_RELEASE to
 * prevent dangling vtable invocations.
 */
ZKS_HIDDEN void zks_release(zhandle_t *zh, const zkrs_handle *core)
{
    zks_secure_zero(zh->client_id.passwd, sizeof zh->client_id.passwd);
#ifdef THREADED
    pthread_mutex_destroy(&zh->auth_lock);
    zks_latch_destroy(&zh->start_latch);
#endif
    ZKS_REGISTRY_REMOVE(zh);
#ifdef ZKS_WITH_SASL
    zks_sasl_sess_free(zh->sasl);
#endif
    free(zh);
    ZKS_CALL_VOID(zkrs_handle_release(core));
}

int zookeeper_close(zhandle_t *zh)
{
    ZKS_HOLD_SCOPE;
    const zkrs_handle *core;
    zkrs_outbox ob = ZKRS_OUTBOX_INIT;
    int rc = ZOK;
    zkrs_step s;

    ZKS_RETURN_IF_NO_HANDLE(zh, ZBADARGUMENTS);
    core = zh->core;
    /* Dispatch graceful session teardown according to the caller thread role. */
    s = ZKS_CALL(zkrs_close(core, zks_current_role(zh), &ob, &rc));
    return zks_drive(zh, core, s, &ob, &rc);
}

/* ---- Polling Interest and Event Processing ---- */

/*
 * Unified interest calculation driving network readiness polling across both MT and ST flavors.
 * Translates driver I/O requirements into socket descriptor polling flags and timeouts.
 */
int zookeeper_interest(zhandle_t *zh, int *fd, int *interest, struct timeval *tv)
{
    const zkrs_handle *core;
    zkrs_interest_out o;
    zkrs_outbox ob = ZKRS_OUTBOX_INIT;
    int written = 0;
    int rc = ZOK;
    int err = 0;
    zkrs_step s;

    if (zh == NULL || fd == NULL || interest == NULL || tv == NULL) {
        return ZBADARGUMENTS;
    }
    ZKS_REGISTRY_CHECK(zh, __func__);
    core = zh->core;
    memset(&o, 0, sizeof o);
    s = ZKS_CALL(zkrs_interest(core, &o, &written, &ob, &rc));
    for (;;) {
        if (s != ZKRS_MORE) {
            err = ZKS_CALL(zkrs_last_errno(core));
        }
        zks_drain(zh, &ob);
        if (s != ZKRS_MORE) {
            break;
        }
        s = ZKS_CALL(zkrs_interest_resume(core, &o, &written, &ob, &rc));
    }
    if (s == ZKRS_RELEASE) {
        zks_release(zh, core);
    }
    if (written) {
        *fd = (int)o.fd;
        *interest = o.interest;
        tv->tv_sec = (time_t)(o.timeout_ms / 1000);
        tv->tv_usec = (suseconds_t)((o.timeout_ms % 1000) * 1000);
    }
    if (err != 0) {
        errno = err;
    }
    return rc;
}

int zookeeper_process(zhandle_t *zh, int events)
{
    const zkrs_handle *core;
    zkrs_outbox ob = ZKRS_OUTBOX_INIT;
    int rc = ZOK;
    int err = 0;
    zkrs_step s;

    ZKS_RETURN_IF_NO_HANDLE(zh, ZBADARGUMENTS);
    core = zh->core;
    s = ZKS_CALL(zkrs_process(core, events, &ob, &rc));
    rc = zks_drive_errno(zh, core, s, &ob, &rc, &err);
    if (err != 0) {
        errno = err;
    }
    return rc;
}

/* ---- Public Handle Accessors ---- */

int zoo_state(zhandle_t *zh)
{
    const zkrs_handle *core;
    ZKS_RETURN_IF_NO_HANDLE(zh, 0);
    core = zh->core;
    return ZKS_CALL(zkrs_state(core));
}

int zoo_recv_timeout(zhandle_t *zh)
{
    const zkrs_handle *core;
    ZKS_RETURN_IF_NO_HANDLE(zh, 0);
    core = zh->core;
    return ZKS_CALL(zkrs_recv_timeout(core));
}

/*
 * Returns a pointer to the handle's stored session credentials. Prior to the
 * initial handshake, this storage contains the clientid_t supplied to
 * zookeeper_init or zeroed bytes. Subsequent handshakes update the storage only
 * when the negotiated session identifier or password changes. Consistent with
 * legacy C client semantics, the returned credentials become valid upon
 * delivery of the ZOO_CONNECTED_STATE session event and are not synchronized
 * against concurrent I/O thread handshakes.
 */
const clientid_t *zoo_client_id(zhandle_t *zh)
{
    ZKS_RETURN_IF_NO_HANDLE(zh, NULL);
    return &zh->client_id;
}

const void *zoo_get_context(zhandle_t *zh)
{
    ZKS_RETURN_IF_NO_HANDLE(zh, NULL);
    return zks_watch_cell_ctx(&zh->dw);
}

void zoo_set_context(zhandle_t *zh, void *context)
{
    ZKS_RETURN_VOID_IF_NO_HANDLE(zh);
    zks_watch_cell_set_ctx(&zh->dw, context);
}

/* Substitutes sentinel callbacks for NULL watcher pointers so stored pointers remain non-null. */
watcher_fn zoo_set_watcher(zhandle_t *zh, watcher_fn newFn)
{
    ZKS_RETURN_IF_NO_HANDLE(zh, NULL);
    return zks_watch_cell_set_fn(&zh->dw, newFn);
}

log_callback_fn zoo_get_log_callback(const zhandle_t *zh)
{
    ZKS_RETURN_IF_NO_HANDLE(zh, NULL);
    return ZKS_LOAD(&zh->log_callback);
}

void zoo_set_log_callback(zhandle_t *zh, log_callback_fn callback)
{
    ZKS_RETURN_VOID_IF_NO_HANDLE(zh);
    ZKS_STORE(&zh->log_callback, callback);
}

int zoo_set_servers(zhandle_t *zh, const char *hosts)
{
    const zkrs_handle *core;
    zkrs_outbox ob = ZKRS_OUTBOX_INIT;
    int rc = ZOK;
    zkrs_step s;

    /* Reject empty server specification strings before mutating ensemble endpoints. */
    if (hosts == NULL || hosts[0] == '\0') {
        ZKS_LOG(zh, ZKS_SITE_SERVERS_EMPTY);
        return ZBADARGUMENTS;
    }
    ZKS_RETURN_IF_NO_HANDLE(zh, ZBADARGUMENTS);
    core = zh->core;
    s = ZKS_CALL(zkrs_set_servers(core, hosts, &ob, &rc));
    return zks_drive(zh, core, s, &ob, &rc);
}

int zoo_set_servers_resolution_delay(zhandle_t *zh, int delay_ms)
{
    const zkrs_handle *core;

    if (delay_ms < -1) {
        ZKS_LOG(zh, ZKS_SITE_RESOLUTION_DELAY, delay_ms);
        return ZBADARGUMENTS;
    }
    ZKS_RETURN_IF_NO_HANDLE(zh, ZBADARGUMENTS);
    core = zh->core;
    return ZKS_CALL(zkrs_set_resolution_delay(core, delay_ms));
}

void zoo_cycle_next_server(zhandle_t *zh)
{
    const zkrs_handle *core;
    zkrs_outbox ob = ZKRS_OUTBOX_INIT;

    ZKS_RETURN_VOID_IF_NO_HANDLE(zh);
    core = zh->core;
    ZKS_CALL_VOID(zkrs_cycle_next_server(core, &ob));
    zks_drain(zh, &ob);
}

const char *zoo_get_current_server(zhandle_t *zh)
{
    const zkrs_handle *core;
    zkrs_sockaddr_view v;

    if (zh == NULL) {
        return "null";
    }
    ZKS_REGISTRY_CHECK(zh, __func__);
    core = zh->core;
    memset(&v, 0, sizeof v);
    ZKS_CALL_VOID(zkrs_current_server(core, &v));
    return zks_format_endpoint(&v);
}
