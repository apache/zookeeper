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
 * Event outbox draining and asynchronous result dispatching.
 *
 * Dispatches queued events, completions, watch notifications, log lines, and thread operations
 * from native C stack frames. Isolates application callback execution from Rust call frames
 * to prevent foreign stack unwinding across the FFI boundary, with RAII cleanup handlers
 * ensuring undelivered queue items are safely requeued or reclaimed on abnormal termination.
 */

#include "zks_internal.h"
#ifdef ZKS_WITH_SASL
#include "zks_sasl.h"
#endif

/* ---- result temporaries ---- */

/*
 * Temporary storage holding deserialized completion payloads (stats, strings, ACLs)
 * during user callback dispatch, reclaimed via GCC/Clang cleanup attributes.
 */
struct zks_tmp {
    struct zks_req *req;
    char *val;
    int32_t val_len;
    struct String_vector sv;
    struct ACL_vector av;
    char *str;
    int32_t str_len;
};

/* Cleanup handler releasing temporary buffers and the associated request descriptor. */
static void zks_tmp_free(struct zks_tmp *t)
{
    free(t->val);
    deallocate_String_vector(&t->sv);
    deallocate_ACL_vector(&t->av);
    free(t->str);
    zks_req_free(t->req);
}

static void zks_free_charp(char **p)
{
    free(*p);
}

static void zks_emit_logs(zhandle_t *zh, const zkrs_log_view *logs, uint32_t n)
{
    uint32_t i;
    for (i = 0; i < n; i++) {
        zks_log_emit(zh, &logs[i]);
    }
}

/*
 * Dispatches an asynchronous operation completion, deserializing result data into temporary
 * structures and invoking the user callback matching the request signature.
 */
static void zks_deliver_result(zhandle_t *zh, uintptr_t token, const zkrs_result_view *v)
{
    struct zks_req *r = (struct zks_req *)token;
    struct zks_tmp tmp __attribute__((cleanup(zks_tmp_free)));
    struct Stat st;
    zkrs_result_dst d = ZKRS_RESULT_DST_INIT;
    /* Failed responses pass NULL result pointers to legacy completion callbacks. */
    struct Stat *stp = NULL;
    struct String_vector *svp = NULL;
    struct ACL_vector *avp = NULL;

    memset(&tmp, 0, sizeof tmp);
    memset(&st, 0, sizeof st);
    tmp.req = r;
    /* Emits correlated wire and diagnostic logs prior to invoking the user completion. */
    zks_emit_logs(zh, v->delivery_logs, v->n_delivery_logs);
#ifdef THREADED
    if (r->mode == ZKS_REQ_SYNC) {
        /* Synchronous completions unblock waiting threads without user callbacks. */
        zks_sync_complete(r, v);
        return;
    }
#endif
    if (r->kind == ZKRS_K_MULTI) {
        zks_multi_complete(r, v);
        return;
    }
    if (r->fn.g == NULL) {
        /* No completion callback configured; cleanup handler reclaims request resources. */
        return;
    }
    if (!v->failed) {
        d.stat = &st;
        d.value = &tmp.val;
        d.value_len = &tmp.val_len;
        d.strings = &tmp.sv;
        d.acl = &tmp.av;
        d.str = &tmp.str;
        d.str_len = &tmp.str_len;
        ZKS_CALL_VOID(zkrs_write_result(v, &d));
        stp = &st;
        svp = &tmp.sv;
        avp = &tmp.av;
    }
    /* Dispatches results according to the requested completion signature. */
    switch (r->kind) {
    case ZKRS_K_VOID:
        ZKS_APP_CALL(r->fn.v(v->rc, r->data));
        break;
    case ZKRS_K_STAT:
        ZKS_APP_CALL(r->fn.s(v->rc, stp, r->data));
        break;
    case ZKRS_K_DATA:
        /* Null znode values are passed as (NULL, -1) per C client contract. */
        ZKS_APP_CALL(r->fn.d(v->rc, tmp.val, tmp.val_len, stp, r->data));
        break;
    case ZKRS_K_STRINGLIST:
        ZKS_APP_CALL(r->fn.sl(v->rc, svp, r->data));
        break;
    case ZKRS_K_STRINGLIST_STAT:
        ZKS_APP_CALL(r->fn.sls(v->rc, svp, stp, r->data));
        break;
    case ZKRS_K_ACLLIST:
        ZKS_APP_CALL(r->fn.acl(v->rc, avp, stp, r->data));
        break;
    case ZKRS_K_STRING:
        ZKS_APP_CALL(r->fn.str(v->rc, tmp.str, r->data));
        break;
    case ZKRS_K_STRING_STAT:
        ZKS_APP_CALL(r->fn.strs(v->rc, tmp.str, stp, r->data));
        break;
    default:
        break;
    }
}

/*
 * Dispatches watch notifications to registered watcher callbacks.
 * Skips internal sentinel markers and formats path strings cleanly.
 */
static void zks_deliver_watch(zhandle_t *zh, const zkrs_watch_event_view *ev)
{
    char *path __attribute__((cleanup(zks_free_charp))) = NULL;
    uint32_t i;

    zks_emit_logs(zh, ev->delivery_logs, ev->n_delivery_logs);
    if (ev->n == 0) {
        return;
    }
    path = ZKS_CALL(zkrs_write_path(&ev->path));
    for (i = 0; i < ev->n; i++) {
        watcher_fn w = (watcher_fn)ev->list[i].fn;
        if (w == NULL || w == zks_null_watcher) {
            continue;
        }
        ZKS_APP_CALL(w(zh, ev->type, ev->state, path, (void *)ev->list[i].ctx));
    }
}

/*
 * Synchronization checkpoint honoring legacy auth lock semantics. Ensures threads
 * modifying authentication credentials serialize before processing state transitions.
 */
static void zks_auth_lockpoint(zhandle_t *zh)
{
    zoo_lock_auth(zh);
    zoo_unlock_auth(zh);
}

#ifdef ZKS_WITH_SASL
/*
 * Bridges SASL authentication state machine steps between Rust protocol drivers
 * and the Cyrus SASL C library, rejecting stale generation tokens.
 */
static void zks_sasl_item(zhandle_t *zh, const zkrs_sasl_call *c)
{
    struct zks_sasl_sess *s = zh->sasl;
    zks_sasl_out out = ZKS_SASL_OUT_FAIL;
    int report = 0;
    int m;

    if (s == NULL) {
        if (c->kind != ZKRS_SASL_DISPOSE) {
            ZKS_CALL_VOID(zkrs_sasl_result(zh->core, c->gen, &out));
        }
        return;
    }
    /* Cyrus SASL invokes application callbacks synchronously during these steps. */
    m = zks_app_enter();
    switch (c->kind) {
    case ZKRS_SASL_START:
        zks_sasl_start(s, c->gen, c->local, c->peer, &out);
        if (out.status != ZKRS_SASL_STALE) {
            zks_sasl_set_gen(s, c->gen);
        }
        report = 1;
        break;
    case ZKRS_SASL_STEP:
        zks_sasl_step(s, c->gen, c->token, &out);
        report = 1;
        break;
    case ZKRS_SASL_DISPOSE:
        zks_sasl_dispose(s, c->gen);
        break;
    default:
        break;
    }
    zks_app_leave(m);
    if (!report || out.status == ZKRS_SASL_STALE) {
        return;
    }
    ZKS_CALL_VOID(zkrs_sasl_result(zh->core, c->gen, &out));
}
#endif

/*
 * Exception unwind cleanup handler returning undelivered outbox items to the
 * driver when a C++ exception escapes an application callback. Non-local jumps
 * via longjmp bypass scope cleanup handlers, leaving undelivered items and the
 * active continuation suspended in the abandoned outbox.
 */
static void zks_outbox_unwind(zkrs_outbox **g)
{
    if (*g != NULL && *g != zks_tls_dispatch_ob) {
        ZKS_CALL_VOID(zkrs_outbox_requeue(*g));
    }
}

/* ---- drain and drive ---- */

/*
 * Drains queued outbox items (logs, results, watches, thread actions, SASL steps)
 * completely in native C frames, unmasking cancellations safely outside Rust code.
 */
ZKS_HIDDEN void zks_drain(zhandle_t *zh, zkrs_outbox *ob)
{
    ZKS_HOLD_SCOPE;
    zkrs_outbox *guard __attribute__((cleanup(zks_outbox_unwind))) = ob;
    zkrs_item it;

    ZKS_ASSERT_NOT_IN_RUST();
    while (ZKS_CALL(zkrs_outbox_next(ob, &it))) {
        switch (it.type) {
        case ZKRS_ITEM_LOG:
            zks_log_emit(zh, it.log);
            break;
        case ZKRS_ITEM_RESULT:
            zks_deliver_result(zh, it.token, it.result);
            break;
        case ZKRS_ITEM_WATCH:
            zks_deliver_watch(zh, it.watch);
            break;
        case ZKRS_ITEM_AUTH_LOCKPOINT:
            zks_auth_lockpoint(zh);
            break;
#ifdef THREADED
        case ZKRS_ITEM_THREAD:
            zks_thread_item(zh, it.thread_op, it.thread_role);
            break;
#endif
#ifdef ZKS_WITH_SASL
        case ZKRS_ITEM_SASL:
            zks_sasl_item(zh, it.sasl);
            break;
#endif
        default:
            break;
        }
    }
    guard = NULL;
    ZKS_CALL_VOID(zkrs_outbox_finish(ob));
}

ZKS_HIDDEN int zks_drive(zhandle_t *zh, const zkrs_handle *core, zkrs_step s, zkrs_outbox *ob,
                         int *rc)
{
    return zks_drive_errno(zh, core, s, ob, rc, NULL);
}

/*
 * Advances the protocol driver state machine across yield barriers, draining the outbox
 * after each step and capturing system errno values for single threaded drivers.
 */
ZKS_HIDDEN int zks_drive_errno(zhandle_t *zh, const zkrs_handle *core, zkrs_step s,
                               zkrs_outbox *ob, int *rc, int *err)
{
    ZKS_HOLD_SCOPE;
    for (;;) {
        if (s != ZKRS_MORE && err != NULL) {
            *err = ZKS_CALL(zkrs_last_errno(core));
        }
        zks_drain(zh, ob);
        if (s == ZKRS_MORE) {
            s = ZKS_CALL(zkrs_resume(core, ob, rc));
            continue;
        }
        if (s == ZKRS_RELEASE) {
            /* Final state machine transition: release the handle and associated core resources. */
            zks_release(zh, core);
        }
        return *rc;
    }
}

/* ---- Vtable callback discarding cancelled or unreferenced request tokens ---- */

ZKS_HIDDEN void zks_vt_discard_token(uintptr_t token)
{
    struct zks_req *r = (struct zks_req *)token;
    int prev = zks_in_vtable;

    zks_in_vtable = 1;
#ifdef THREADED
    if (r != NULL && r->mode == ZKS_REQ_SYNC) {
        /* Unblock synchronous waiting threads with ZCLOSING upon handle teardown. */
        zks_sync_complete_closing(r);
    }
#endif
    zks_req_free(r);
    zks_in_vtable = prev;
}
