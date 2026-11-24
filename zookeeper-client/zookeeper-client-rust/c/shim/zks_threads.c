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
 * Multithreaded runtime subsystem managing I/O and event completion worker threads.
 *
 * Implements thread lifecycle primitives (creation, synchronization barriers, join, and detach)
 * for POSIX threads. Thread coordination occurs in native C frames, ensuring Rust call frames
 * do not block indefinitely across thread joins or cancellations.
 */

#include "zks_internal.h"

#ifdef THREADED

/* ---- start latch ---- */

/*
 * Countdown synchronization latch coordinating thread startup across initialization,
 * I/O, and completion dispatch threads, with support for abort broadcasting on failure.
 */

ZKS_HIDDEN int zks_latch_init(struct zks_latch *l, int parties)
{
    int err = pthread_mutex_init(&l->m, NULL);
    if (err != 0) {
        return err;
    }
    err = pthread_cond_init(&l->c, NULL);
    if (err != 0) {
        pthread_mutex_destroy(&l->m);
        return err;
    }
    l->remaining = parties;
    return 0;
}

ZKS_HIDDEN void zks_latch_arrive_and_wait(struct zks_latch *l)
{
    pthread_mutex_lock(&l->m);
    if (l->remaining > 0) {
        l->remaining--;
        if (l->remaining == 0) {
            pthread_cond_broadcast(&l->c);
        }
    }
    while (l->remaining > 0) {
        pthread_cond_wait(&l->c, &l->m);
    }
    pthread_mutex_unlock(&l->m);
}

ZKS_HIDDEN void zks_latch_destroy(struct zks_latch *l)
{
    pthread_cond_destroy(&l->c);
    pthread_mutex_destroy(&l->m);
}

/* Broadcasts an abort signal to unblock waiting threads upon thread initialization failure. */
static void zks_latch_abort(struct zks_latch *l)
{
    pthread_mutex_lock(&l->m);
    l->remaining = -1;
    pthread_cond_broadcast(&l->c);
    pthread_mutex_unlock(&l->m);
}

static int zks_latch_aborted(struct zks_latch *l)
{
    int aborted;
    pthread_mutex_lock(&l->m);
    aborted = l->remaining < 0;
    pthread_mutex_unlock(&l->m);
    return aborted;
}

/* ---- thread bodies ---- */

/*
 * Executes event loop iterations to completion, repeatedly draining queued outbox actions
 * across yield points until achieving DONE or EXIT state.
 */
static zkrs_step zks_loop_finish(zhandle_t *zh, const zkrs_handle *core, zkrs_step s,
                                 zkrs_outbox *ob, int *rc)
{
    while (s == ZKRS_MORE) {
        zks_drain(zh, ob);
        s = ZKS_CALL(zkrs_resume(core, ob, rc));
    }
    zks_drain(zh, ob);
    return s;
}

static void *zks_io_main(void *arg)
{
    zhandle_t *zh = (zhandle_t *)arg;
    const zkrs_handle *core = zh->core;
    zkrs_outbox ob = ZKRS_OUTBOX_INIT;
    int rc = 0;
    int aborted;
    zkrs_step s;
    log_callback_fn cb;

    /* Dedicated worker threads do not accept external thread cancellations. */
    zks_cancel_pin();
    /* Associate thread local context and role before releasing the start barrier. */
    zks_tls_zh = zh;
    zks_tls_role = ZKRS_ROLE_IO;
    ZKS_CALL_VOID(zkrs_thread_enter(core, ZKRS_ROLE_IO));
    zks_latch_arrive_and_wait(&zh->start_latch);
    aborted = zks_latch_aborted(&zh->start_latch);
    if (!aborted) {
        ZKS_LOG(zh, ZKS_SITE_IO_THREAD_STARTED);
        do {
            s = ZKS_CALL(zkrs_io_step(core, &ob, &rc));
            s = zks_loop_finish(zh, core, s, &ob, &rc);
        } while (s != ZKRS_EXIT);
    }
    /* Cache log callback reference prior to potential handle destruction during teardown. */
    cb = ZKS_LOAD(&zh->log_callback);
    s = ZKS_CALL(zkrs_thread_exit(core, ZKRS_ROLE_IO, &ob, &rc));
    zks_drive(zh, core, s, &ob, &rc);
    if (!aborted) {
        ZKS_LOG_CB(cb, ZKS_SITE_IO_THREAD_TERMINATED);
    }
    ZKS_CALL_VOID(zkrs_handle_release(core));
    return NULL;
}

/* Stack frame context passed to pthread cleanup handlers during completion dispatch. */
struct zks_dispatch_frame {
    const zkrs_handle *core;
    zkrs_outbox *ob;
};

/*
 * Emergency cleanup handler invoked if an application callback terminates the dispatch thread
 * abnormally (e.g. via pthread_exit), releasing pending outbox items and cleaning references.
 */
static void zks_dispatch_ended(void *arg)
{
    struct zks_dispatch_frame *f = (struct zks_dispatch_frame *)arg;

    zks_tls_dispatch_ob = NULL;
    ZKS_CALL_VOID(zkrs_dispatch_ended(f->core, f->ob));
    ZKS_CALL_VOID(zkrs_handle_release(f->core));
}

static void *zks_dispatch_main(void *arg)
{
    zhandle_t *zh = (zhandle_t *)arg;
    const zkrs_handle *core = zh->core;
    zkrs_outbox ob = ZKRS_OUTBOX_INIT;
    struct zks_dispatch_frame frame;
    int rc = 0;
    zkrs_step s;
    log_callback_fn cb;

    /* Dedicated worker threads do not accept external thread cancellations. */
    zks_cancel_pin();
    zks_tls_zh = zh;
    zks_tls_role = ZKRS_ROLE_DISPATCH;
    ZKS_CALL_VOID(zkrs_thread_enter(core, ZKRS_ROLE_DISPATCH));
    frame.core = core;
    frame.ob = &ob;
    /*
     * Register cleanup handler covering user callback execution in the dispatch loop,
     * popped prior to normal thread exit.
     */
    pthread_cleanup_push(zks_dispatch_ended, &frame);
    zks_tls_dispatch_ob = &ob;
    zks_latch_arrive_and_wait(&zh->start_latch);
    ZKS_LOG(zh, ZKS_SITE_COMPLETION_THREAD_STARTED);
    do {
        s = ZKS_CALL(zkrs_wait_dispatch(core, &ob, &rc));
        s = zks_loop_finish(zh, core, s, &ob, &rc);
    } while (s != ZKRS_EXIT);
    zks_tls_dispatch_ob = NULL;
    pthread_cleanup_pop(0);
    cb = ZKS_LOAD(&zh->log_callback);
    s = ZKS_CALL(zkrs_thread_exit(core, ZKRS_ROLE_DISPATCH, &ob, &rc));
    zks_drive(zh, core, s, &ob, &rc);
    ZKS_LOG_CB(cb, ZKS_SITE_COMPLETION_THREAD_TERMINATED);
    ZKS_CALL_VOID(zkrs_handle_release(core));
    return NULL;
}

/*
 * Finalizes the initialization thread's registration, transitioning handle ownership
 * to the background worker threads.
 */
static void zks_init_epilog(zhandle_t *zh, const zkrs_handle *core)
{
    zkrs_outbox ob = ZKRS_OUTBOX_INIT;
    int rc = 0;
    zkrs_step s = ZKS_CALL(zkrs_thread_exit(core, ZKRS_ROLE_INIT, &ob, &rc));
    zks_drive(zh, core, s, &ob, &rc);
}

/*
 * Tears down the I/O thread during startup failure if the companion dispatch thread
 * fails to spawn successfully.
 */
static void zks_stop_io_thread(zhandle_t *zh)
{
    zks_latch_abort(&zh->start_latch);
    pthread_join(zh->io_thread, NULL);
}

ZKS_HIDDEN int zks_start_threads(zhandle_t *zh)
{
    const zkrs_handle *core = zh->core;
    int err = zks_latch_init(&zh->start_latch, 3); /* IO, dispatch and init thread */

    if (err != 0) {
        return err;
    }
    ZKS_CALL_VOID(zkrs_thread_enter(core, ZKRS_ROLE_INIT));
    /* Each background worker thread holds an explicit reference to the Rust core handle. */
    ZKS_CALL_VOID(zkrs_handle_retain(core));
    ZKS_LOG(zh, ZKS_SITE_STARTING_THREADS);
    err = pthread_create(&zh->io_thread, NULL, zks_io_main, zh);
    if (err != 0) {
        ZKS_CALL_VOID(zkrs_handle_release(core));
        zks_latch_destroy(&zh->start_latch);
        return err;
    }
    ZKS_CALL_VOID(zkrs_handle_retain(core));
    err = pthread_create(&zh->dispatch_thread, NULL, zks_dispatch_main, zh);
    if (err != 0) {
        ZKS_CALL_VOID(zkrs_handle_release(core));
        zks_stop_io_thread(zh);
        zks_latch_destroy(&zh->start_latch);
        return err;
    }
    /*
     * Coordinates the three party startup rendezvous (init, I/O, dispatch) ensuring thread IDs
     * are fully published before user watcher callbacks can execute.
     */
    zks_init_epilog(zh, core);
    zks_latch_arrive_and_wait(&zh->start_latch);
    return 0;
}

/* ---- THREAD items ---- */

ZKS_HIDDEN void zks_thread_item(zhandle_t *zh, uint32_t op, uint32_t role)
{
    if (op == ZKRS_THREAD_JOIN) {
        pthread_join(role == ZKRS_ROLE_IO ? zh->io_thread : zh->dispatch_thread, NULL);
    } else if (op == ZKRS_THREAD_DETACH) {
        /* DETACH always targets the calling thread's own execution context. */
        pthread_detach(pthread_self());
    }
}

#endif /* THREADED */
