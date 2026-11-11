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
 * Cyrus SASL Protocol Adapter.
 *
 * Implements the runtime bridge between ZooKeeper client network negotiation
 * and the Cyrus SASL C library. Outbox event processing invokes this unit on the
 * dedicated I/O event thread when authentication events are scheduled, without
 * holding locks across Rust execution frames. Because the protocol driver strictly
 * serializes authentication handshakes (permitting at most one in-flight START or STEP
 * operation per session), SASL session state does not require internal locking.
 *
 * The adapter provides Cyrus with a session scoped copy of client callbacks.
 * Password retrieval callbacks (SASL_CB_PASS) are wrapped by an internal trampoline
 * to allocate and manage credential buffers within the session's memory context.
 * Monotonic generation counters guard every operation, guaranteeing that expired or
 * superseded handshakes are rejected before mutating engine state.
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE /* POSIX names under -std=c99 on glibc */
#endif
#define ZKS_SASL_GLUE 1
#include "zks_internal.h"
#include "zks_sasl.h"
#include "zks_sasl_cb.h"

#include <netdb.h>
#include <netinet/in.h>
#include <sys/socket.h>

/* Defensive upper limits on callback entries and secrets per connection session. */
#define ZKS_SASL_MAX_CB 4096u     /* entries before SASL_CB_LIST_END */
#define ZKS_SASL_MAX_SECRETS 16u  /* PASS requests for each connection */

/*
 * C99 compliant alignment structure ensuring buffer payloads satisfy
 * the maximum fundamental alignment required for sasl_secret_t storage.
 */
union zks_max_align {
    long double ld;
    long long ll;
    double d;
    void *p;
    void (*fp)(void);
};

/* Session managed secret allocation backing a Cyrus sasl_secret_t descriptor. */
struct zks_secret {
    struct zks_secret *next;
    size_t size;                /* Total allocation size of trailing buffer. */
    union zks_max_align buf[];  /* Aligned storage for sasl_secret_t and payload bytes. */
};

/* Context descriptor passed to the SASL_CB_PASS interception trampoline. */
struct zks_pass_ctx {
    zks_sasl_proc orig_proc;    /* Original application password callback. */
    void *orig_ctx;             /* Associated user context pointer. */
    int is_library;             /* Non-zero if callback is the default library provider. */
    struct zks_secret *secrets; /* Linked list of active credential allocations. */
    unsigned nsecrets;
};

/*
 * Runtime session state maintaining connection context and parameters.
 * Connection handles, callback copies, and secret allocations remain active
 * strictly between connection initiation (START) and session disposal.
 */
struct zks_sasl_sess {
    struct zks_sasl_params params; /* Owned configuration string copies and borrowed callbacks. */
    zhandle_t *log_zh;             /* Associated ZooKeeper handle for logging diagnostics. */
    sasl_conn_t *conn;             /* Active Cyrus connection handle, or NULL when disconnected. */
    uint32_t gen;                  /* Monotonic generation counter tracking active connection. */
    sasl_callback_t *cb;           /* Session-allocated copy of callback descriptors. */
    struct zks_pass_ctx pass;
};

/* Resets output negotiation structure to a default failure state. */
static void zks_out_clear(zks_sasl_out *o)
{
    const zks_sasl_out fail = ZKS_SASL_OUT_FAIL;

    *o = fail;
}

/* Validates byte slice integrity and consistency with nullity indicators. */
static int zks_bytes_ok(zks_bytes b)
{
    if (b.len > ZKS_SASL_LEN_CAP) {
        return 0;
    }
    if (b.is_null) {
        return b.len == 0 && b.p == NULL;
    }
    return b.len == 0 || b.p != NULL;
}

/*
 * Populates output negotiation token descriptor from Cyrus output buffers.
 */
static void zks_token_out(const char *cout, unsigned coutlen, zkrs_sasl_out *out)
{
    out->token.p = (const uint8_t *)cout;
    out->token.len = (uint32_t)coutlen;
    out->token.is_null = (uint8_t)(cout == NULL ? 1 : 0);
}

/*
 * Releases Cyrus connection state and wipes associated secret allocations.
 * Invoked during explicit disposal, connection teardown, or prior to re-authenticating.
 */
static void zks_conn_dispose(struct zks_sasl_sess *s)
{
    struct zks_secret *x;
    struct zks_secret *nx;

    if (s->conn != NULL) {
        sasl_dispose(&s->conn);
    }
    s->conn = NULL;
    s->gen = 0;
    for (x = s->pass.secrets; x != NULL; x = nx) {
        nx = x->next;
        zks_secure_zero(x->buf, x->size);
        free(x);
    }
    s->pass.secrets = NULL;
    s->pass.nsecrets = 0;
    free(s->cb);
    s->cb = NULL;
}

/* Maximum buffer length required to hold formatted "ip;port" endpoint strings. */
#define ZKS_IPPORT_CAP ((size_t)NI_MAXHOST + (size_t)NI_MAXSERV)

/*
 * Formats a binary sockaddr into a canonical "ip;port" representation as required
 * by Cyrus SASL for host identity validation and auditing.
 */
static int zks_ipport(const struct zks_sasl_sess *s, zks_bytes a, char out[ZKS_IPPORT_CAP],
                      char host[NI_MAXHOST])
{
    struct sockaddr_storage ss;
    char hb[NI_MAXHOST];
    char pb[NI_MAXSERV];
    int fl = NI_NUMERICHOST | NI_NUMERICSERV;
    int e;
    int n;
    sa_family_t fam;
    const size_t fam_off = offsetof(struct sockaddr, sa_family);

    if (!zks_bytes_ok(a) || a.is_null || a.len < fam_off + sizeof fam) {
        return ZSYSTEMERROR;
    }
    memcpy(&fam, a.p + fam_off, sizeof fam);
    if (!((fam == AF_INET && a.len == sizeof(struct sockaddr_in))
          || (fam == AF_INET6 && a.len == sizeof(struct sockaddr_in6)))) {
        return ZSYSTEMERROR;
    }
    memset(&ss, 0, sizeof ss);
    memcpy(&ss, a.p, a.len);
#ifdef NI_WITHSCOPEID
    if (fam == AF_INET6) {
        fl |= NI_WITHSCOPEID;
    }
#endif
    e = getnameinfo((const struct sockaddr *)(const void *)&ss, (socklen_t)a.len, hb, sizeof hb,
                    pb, sizeof pb, fl);
    if (e != 0) {
        zks_log_site(s->log_zh, ZKS_SITE_SASL_GETNAMEINFO, gai_strerror(e));
        return ZSYSTEMERROR;
    }
    n = snprintf(out, ZKS_IPPORT_CAP, "%s;%s", hb, pb);
    if (n < 0 || (size_t)n >= ZKS_IPPORT_CAP) {
        return ZSYSTEMERROR;
    }
    if (host != NULL) {
        memcpy(host, hb, sizeof hb);
    }
    return ZOK;
}

/* Callback function signature for password retrieval operations. */
typedef int (*zks_pass_fn)(sasl_conn_t *, void *, int, sasl_secret_t **);

static int zks_sasl_pass_trampoline(sasl_conn_t *conn, void *context, int id,
                                    sasl_secret_t **psecret);

/*
 * Constructs a per-session copy of the caller provided callback array.
 * Because Cyrus inspects only the first entry matching a given callback identifier,
 * the first SASL_CB_PASS handler is intercepted and routed through an internal
 * trampoline that manages credential lifetime within the session context.
 */
static int zks_cb_build(struct zks_sasl_sess *s, const struct zks_sasl_params *p)
{
    const sasl_callback_t *app = (const sasl_callback_t *)p->callbacks;
    size_t n = 0;
    size_t bytes;
    size_t i;

    s->cb = NULL;
    s->pass.orig_proc = NULL;
    s->pass.orig_ctx = NULL;
    s->pass.is_library = 0;
    if (app == NULL) {
        return 0;
    }
    while (app[n].id != SASL_CB_LIST_END) {
        n++;
        if (n >= ZKS_SASL_MAX_CB) {
            return -1;
        }
    }
    if (ZKS_ADD_OVF(n, (size_t)1, &bytes) || ZKS_MUL_OVF(bytes, sizeof *app, &bytes)) {
        return -1;
    }
    s->cb = (sasl_callback_t *)malloc(bytes);
    if (s->cb == NULL) {
        // Abort cleanly on internal callback table allocation failure.
        abort();
    }
    memcpy(s->cb, app, bytes); /* also the SASL_CB_LIST_END entry */
    for (i = 0; i < n; i++) {
        if (s->cb[i].id == SASL_CB_PASS) {
            s->pass.orig_proc = s->cb[i].proc;
            s->pass.orig_ctx = s->cb[i].context;
            s->pass.is_library = zks_is_library_pass_cb(s->cb[i].proc);
            s->cb[i].proc = ZKS_SASL_PROC(&zks_sasl_pass_trampoline);
            s->cb[i].context = &s->pass;
            break;
        }
    }
    return 0;
}

/*
 * Session credential storage sink. Marshals retrieved password bytes into
 * a zero-initialized buffer owned by the session, returning the resulting
 * sasl_secret_t pointer directly to the Cyrus engine.
 */
static int zks_pass_store(void *arg, const unsigned char *data, size_t len, sasl_secret_t **out)
{
    struct zks_pass_ctx *pc = (struct zks_pass_ctx *)arg;
    const size_t hdr = offsetof(sasl_secret_t, data);
    size_t need;
    size_t total;
    struct zks_secret *x;
    unsigned char *dst;
    sasl_secret_t *sec;

    if (len > ZKS_SASL_LEN_CAP) {
        return SASL_BUFOVER;
    }
    if (pc->nsecrets >= ZKS_SASL_MAX_SECRETS) {
        return SASL_NOMEM;
    }
    if (ZKS_ADD_OVF(hdr, len, &need) || ZKS_ADD_OVF(need, (size_t)1, &need)) {
        return SASL_BUFOVER;
    }
    if (need < sizeof(sasl_secret_t)) {
        need = sizeof(sasl_secret_t);
    }
    if (ZKS_ADD_OVF(offsetof(struct zks_secret, buf), need, &total)) {
        return SASL_BUFOVER;
    }
    x = (struct zks_secret *)calloc(1, total);
    if (x == NULL) {
        return SASL_NOMEM;
    }
    x->size = need;
    sec = (sasl_secret_t *)(void *)x->buf;
    sec->len = (unsigned long)len;
    /*
     * Copy using absolute allocation base rather than flexible array member offset,
     * maintaining compliance with FORTIFY_SOURCE object-size validation.
     */
    dst = (unsigned char *)x->buf + hdr;
    if (len > 0) {
        memcpy(dst, data, len);
    }
    dst[len] = '\0';
    x->next = pc->secrets;
    pc->secrets = x;
    pc->nsecrets++;
    *out = sec;
    return SASL_OK;
}

/*
 * Interception trampoline for password retrieval callbacks. Dispatches to either
 * the built-in library secret resolver or an application defined callback,
 * capturing the credential payload into session storage without holding global locks.
 */
static int zks_sasl_pass_trampoline(sasl_conn_t *conn, void *context, int id,
                                    sasl_secret_t **psecret)
{
    struct zks_pass_ctx *pc = (struct zks_pass_ctx *)context;
    sasl_secret_t *got = NULL;
    zks_pass_fn fn;
    int rc;

    if (pc == NULL || psecret == NULL || id != SASL_CB_PASS) {
        return SASL_BADPARAM;
    }
    *psecret = NULL;
    if (pc->is_library) {
        return zks_getsecret_unlocked(conn, pc->orig_ctx, zks_pass_store, pc, psecret);
    }
    if (pc->orig_proc == NULL) {
        return SASL_FAIL;
    }
    fn = (zks_pass_fn)(void (*)(void))pc->orig_proc;
    rc = fn(conn, pc->orig_ctx, id, &got);
    if (rc != SASL_OK) {
        return rc;
    }
    if (got == NULL) {
        return SASL_FAIL;
    }
    if (got->len > ZKS_SASL_LEN_CAP) {
        return SASL_BUFOVER;
    }
    return zks_pass_store(pc, (const unsigned char *)got + offsetof(sasl_secret_t, data),
                          (size_t)got->len, psecret);
}

/* Creates a dynamically allocated duplicate of an external input string. */
static int zks_copy_str(const char *src, char **dst)
{
    size_t n;
    size_t sz;

    *dst = NULL;
    if (src == NULL) {
        return 0;
    }
    n = strnlen(src, ZKS_SASL_LEN_CAP);
    if (n >= ZKS_SASL_LEN_CAP || ZKS_ADD_OVF(n, (size_t)1, &sz)) {
        return -1;
    }
    *dst = (char *)malloc(sz);
    if (*dst == NULL) {
        return -1;
    }
    memcpy(*dst, src, n);
    (*dst)[n] = '\0';
    return 0;
}

/* ------------------------------------------------------------------------
 * Session Lifecycle Management.
 * ---------------------------------------------------------------------- */

/*
 * Allocates and initializes a new SASL session descriptor during handle initialization.
 * Application callback structures remain borrowed and must outlive the handle lifecycle.
 */
ZKS_HIDDEN struct zks_sasl_sess *zks_sasl_sess_new(zhandle_t *zh, const struct zoo_sasl_params *p)
{
    struct zks_sasl_sess *s;

    if (p == NULL) {
        return NULL;
    }
    s = (struct zks_sasl_sess *)calloc(1, sizeof *s);
    if (s == NULL) {
        return NULL;
    }
    s->log_zh = zh;
    if (zks_copy_str(p->service, &s->params.service) != 0
        || zks_copy_str(p->host, &s->params.host) != 0
        || zks_copy_str(p->mechlist, &s->params.mechlist) != 0) {
        zks_sasl_sess_free(s);
        return NULL;
    }
    s->params.callbacks = p->callbacks;
    return s;
}

ZKS_HIDDEN void zks_sasl_sess_clear(struct zks_sasl_sess *s)
{
    if (s == NULL) {
        return;
    }
    zks_conn_dispose(s);
    free(s->params.service);
    free(s->params.host);
    free(s->params.mechlist);
    s->params.service = NULL;
    s->params.host = NULL;
    s->params.mechlist = NULL;
}

/* Releases session memory prior to freeing the enclosing ZooKeeper handle. */
ZKS_HIDDEN void zks_sasl_sess_free(struct zks_sasl_sess *s)
{
    if (s == NULL) {
        return;
    }
    zks_sasl_sess_clear(s);
    free(s);
}

/* Records active connection generation following a successfully initiated handshake. */
ZKS_HIDDEN void zks_sasl_set_gen(struct zks_sasl_sess *s, uint32_t gen)
{
    if (s->conn != NULL) {
        s->gen = gen;
    }
}

/* ------------------------------------------------------------------------
 * SASL Protocol Exchange Operations (Start, Step, Dispose).
 * ---------------------------------------------------------------------- */

/*
 * Initiates client side SASL authentication. Tearing down any existing connection
 * context, initializes the Cyrus engine with local/peer endpoints, and evaluates
 * the initial authentication token.
 */
ZKS_HIDDEN void zks_sasl_start(zks_sasl_sess *s, uint32_t gen, zks_bytes local, zks_bytes peer,
                               zkrs_sasl_out *out)
{
    const zks_sasl_params *p = &s->params;
    char lip[ZKS_IPPORT_CAP];
    char rip[ZKS_IPPORT_CAP];
    char host[NI_MAXHOST];
    const char *chosen = NULL;
    const char *cout = NULL;
    unsigned coutlen = 0;
    size_t mlen;
    int sr;

    zks_out_clear(out);
    if (gen == 0 || gen == s->gen) {
        out->status = ZKRS_SASL_STALE;
        return;
    }
    zks_conn_dispose(s);
    if (zks_ipport(s, local, lip, NULL) != ZOK || zks_ipport(s, peer, rip, host) != ZOK) {
        return;
    }
    zks_log_site(s->log_zh, ZKS_SITE_SASL_HOST, lip, rip);
    /* Treat excessive callback list lengths as allocation failure. */
    if (zks_cb_build(s, p) != 0) {
        zks_log_site(s->log_zh, ZKS_SITE_SASL_NEW, sasl_errstring(SASL_NOMEM, NULL, NULL));
        return;
    }
    sr = sasl_client_new(p->service, p->host != NULL ? p->host : host, lip, rip, s->cb, 0,
                         &s->conn);
    if (sr != SASL_OK) {
        zks_log_site(s->log_zh, ZKS_SITE_SASL_NEW, sasl_errstring(sr, NULL, NULL));
        /* Dispose partial state to avoid retaining dangling callback references. */
        zks_conn_dispose(s);
        return;
    }
    sr = sasl_client_start(s->conn, p->mechlist, NULL, &cout, &coutlen, &chosen);
    if (sr != SASL_OK && sr != SASL_CONTINUE) {
        zks_log_site(s->log_zh, ZKS_SITE_SASL_START_ERR, sasl_errstring(sr, NULL, NULL),
                     sasl_errdetail(s->conn));
        return;
    }
    if (chosen == NULL) {
        return;
    }
    mlen = strnlen(chosen, (size_t)SASL_MECHNAMEMAX + 1u);
    if (mlen == 0 || mlen > SASL_MECHNAMEMAX || coutlen > ZKS_SASL_LEN_CAP
        || (coutlen > 0 && cout == NULL)) {
        return;
    }
    zks_log_site(s->log_zh, ZKS_SITE_SASL_START, sr, chosen, (int)coutlen);
    out->status = sr == SASL_CONTINUE ? ZKRS_SASL_CONTINUE : ZKRS_SASL_DONE;
    out->mech.p = (const uint8_t *)chosen;
    out->mech.len = (uint32_t)mlen;
    out->mech.is_null = 0;
    zks_token_out(cout, coutlen, out);
}

/* Processes an authentication challenge received from the server. */
ZKS_HIDDEN void zks_sasl_step(zks_sasl_sess *s, uint32_t gen, zks_bytes challenge,
                              zkrs_sasl_out *out)
{
    const char *cout = NULL;
    unsigned coutlen = 0;
    int sr;

    zks_out_clear(out);
    if (gen == 0 || gen != s->gen) {
        out->status = ZKRS_SASL_STALE;
        return;
    }
    if (s->conn == NULL || !zks_bytes_ok(challenge)) {
        return;
    }
    sr = sasl_client_step(s->conn, challenge.is_null ? NULL : (const char *)challenge.p,
                          challenge.is_null ? 0u : (unsigned)challenge.len, NULL, &cout, &coutlen);
    zks_log_site(s->log_zh, ZKS_SITE_SASL_STEP_OUT, sr, (int)coutlen);
    if (sr != SASL_OK && sr != SASL_CONTINUE) {
        zks_log_site(s->log_zh, ZKS_SITE_SASL_STEP_ERR, sasl_errstring(sr, NULL, NULL),
                     sasl_errdetail(s->conn));
        return;
    }
    if (coutlen > ZKS_SASL_LEN_CAP || (coutlen > 0 && cout == NULL)) {
        return;
    }
    out->status = sr == SASL_CONTINUE ? ZKRS_SASL_CONTINUE : ZKRS_SASL_DONE;
    zks_token_out(cout, coutlen, out);
}

/* Disposes the Cyrus connection if the generation matches the active session. */
ZKS_HIDDEN void zks_sasl_dispose(zks_sasl_sess *s, uint32_t gen)
{
    if (gen != 0 && gen == s->gen) {
        zks_conn_dispose(s);
    }
}

/* ------------------------------------------------------------------------
 * Public Initialization Entry Point.
 * ---------------------------------------------------------------------- */

/* Initializes a ZooKeeper client session configured with SASL authentication parameters. */
zhandle_t *zookeeper_init_sasl(const char *host, watcher_fn fn, int recv_timeout,
                               const clientid_t *clientid, void *context, int flags,
                               log_callback_fn log_callback, zoo_sasl_params_t *sasl_params)
{
    return zks_init_common(host, fn, recv_timeout, clientid, context, flags, log_callback, NULL,
                           sasl_params);
}

/* ------------------------------------------------------------------------
 * Historical / Leaked Internal SASL Symbol Stubs.
 *
 * Preserves external linker compatibility for legacy symbols that may be
 * historically referenced by external applications.
 * ---------------------------------------------------------------------- */

struct zks_sasl_sess *zoo_sasl_client_create(zoo_sasl_params_t *sasl_params);
void zoo_sasl_client_destroy(struct zks_sasl_sess *sasl_client);
void zoo_sasl_mark_failed(zhandle_t *zh);
int zoo_sasl_connect(zhandle_t *zh);
int zoo_sasl_client_start(zhandle_t *zh);
int zoo_sasl_client_step(zhandle_t *zh, const char *server_data, int server_data_len);

struct zks_sasl_sess *zoo_sasl_client_create(zoo_sasl_params_t *sasl_params)
{
    return zks_sasl_sess_new(NULL, sasl_params);
}

/* Cleans up an unattached SASL client structure allocated via zoo_sasl_client_create. */
void zoo_sasl_client_destroy(struct zks_sasl_sess *sasl_client)
{
    zks_sasl_sess_clear(sasl_client);
}

/* Notifies the driver core of an authentication failure without triggering teardown. */
void zoo_sasl_mark_failed(zhandle_t *zh)
{
    if (zh == NULL) {
        return;
    }
    ZKS_CALL_VOID(zkrs_sasl_mark_failed(zh->core));
}

/*
 * Legacy manual handshake drivers are stubbed. Handshake progression is driven
 * asynchronously by the background I/O event loop.
 */
int zoo_sasl_connect(zhandle_t *zh)
{
    ZKS_UNUSED(zh);
    return ZSYSTEMERROR;
}

int zoo_sasl_client_start(zhandle_t *zh)
{
    ZKS_UNUSED(zh);
    return ZSYSTEMERROR;
}

int zoo_sasl_client_step(zhandle_t *zh, const char *server_data, int server_data_len)
{
    ZKS_UNUSED(zh);
    ZKS_UNUSED(server_data);
    ZKS_UNUSED(server_data_len);
    return ZSYSTEMERROR;
}
