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
 * Explicit authentication list locking via zoo_lock_auth / zoo_unlock_auth.
 * Mode: PER-ROLE
 *
 * The application acquires zoo_lock_auth for 500 ms across each critical
 * section where the legacy I/O pipeline locks auth_h. The handle connects
 * through the wire-capture proxy (zkconf_wire.h), which holds server responses
 * until the lock is held by the application thread.
 *
 * Invariants:
 * - zoo_lock_auth and zoo_unlock_auth return 0.
 * - AUTH reply processing: auth completion delivery and any subsequent replies on the same
 *   connection block until zoo_unlock_auth, as does a concurrent zoo_add_auth call.
 * - Connection teardown drain: following a server disconnect, pending auth completion callbacks
 *   block until zoo_unlock_auth.
 * - Reconnect auth replay: following the connect response, neither AUTH frames nor the CONNECTED
 *   session event are emitted until zoo_unlock_auth.
 * - Handle teardown drain: a concurrent zookeeper_close call blocks until zoo_unlock_auth.
 *
 * Each 500 ms hold window verifies the absence of progress while the mutex is
 * held; all post-unlock waits are bounded.
 *
 * zkconf-flavors: mt
 */

#include "zkconf.h"
#include "zkconf_wire.h"

#include <pthread.h>
#include <string.h>

/* Exported by both mt libraries; declared only in private zk_adaptor.h. */
int zoo_lock_auth(zhandle_t *zh);
int zoo_unlock_auth(zhandle_t *zh);

static volatile int g_auth;
static volatile int g_exists;
static volatile int g_connected;
static volatile int g_done;
static zhandle_t *g_zh;

static void auth_cb(int rc, const void *d)
{
    zkc_void_cb(rc, d);
    __atomic_fetch_add(&g_auth, 1, __ATOMIC_SEQ_CST);
}

static void exists_cb(int rc, const struct Stat *s, const void *d)
{
    zkc_stat_cb(rc, s, d);
    __atomic_fetch_add(&g_exists, 1, __ATOMIC_SEQ_CST);
}

static void lock_watcher(zhandle_t *zh, int type, int state, const char *path,
                         void *ctx)
{
    zkc_watcher(zh, type, state, path, ctx);
    if (type == ZOO_SESSION_EVENT && state == ZOO_CONNECTED_STATE) {
        __atomic_fetch_add(&g_connected, 1, __ATOMIC_SEQ_CST);
    }
}

static int count(const volatile int *v)
{
    return __atomic_load_n(v, __ATOMIC_SEQ_CST);
}

static void show_wire(const char *when)
{
    char buf[ZKW_LABEL_CAP];
    zkc_ev("wire", "%s: %s", when, zkw_labels(buf, sizeof buf));
}

static void lock(zhandle_t *zh)
{
    int rc = zoo_lock_auth(zh);
    zkc_ev("call", "zoo_lock_auth -> %d", rc);
}

static void unlock(zhandle_t *zh)
{
    int rc = zoo_unlock_auth(zh);
    zkc_ev("call", "zoo_unlock_auth -> %d", rc);
}

static void add_auth(zhandle_t *zh, const char *cred)
{
    int rc = zoo_add_auth(zh, "digest", cred, (int)strlen(cred), auth_cb, cred);
    zkc_ev("call", "zoo_add_auth %s -> %s", cred, zkc_rc(rc));
}

/* Secondary application thread; emits no trace events directly. */
static void *add_auth_thread(void *arg)
{
    const char *cred = (const char *)arg;
    int rc = zoo_add_auth(g_zh, "digest", cred, (int)strlen(cred), auth_cb,
                          cred);
    __atomic_store_n(&g_done, rc == ZOK ? 1 : 2, __ATOMIC_SEQ_CST);
    return NULL;
}

static void *close_thread(void *arg)
{
    int rc = zookeeper_close(g_zh);
    (void)arg;
    __atomic_store_n(&g_done, rc == ZOK ? 1 : 2, __ATOMIC_SEQ_CST);
    return NULL;
}

/* Awaits completion of a helper thread via g_done and joins it. */
static int join_done(pthread_t th, int ms)
{
    if (zkw_wait_int(&g_done, 1, ms) != 0) {
        zkc_fail("a helper thread did not return after the unlock");
        return -1;
    }
    pthread_join(th, NULL);
    return 0;
}

/* AUTH reply critical section. */
static void auth_reply(zhandle_t *zh)
{
    pthread_t th;
    int a;
    int e;
    int d;
    zkw_budget(0);
    add_auth(zh, "la1:p");
    if (zoo_aexists(zh, "/zkconf-auth-lock-none", 0, exists_cb, "e1") != ZOK ||
        zkw_wait_count("A:la1", 1, 5000) != 0 ||
        zkw_wait_count("E", 1, 5000) != 0) {
        zkc_fail("the requests did not reach the proxy");
        return;
    }
    lock(zh);
    zkw_budget(-1);
    __atomic_store_n(&g_done, 0, __ATOMIC_SEQ_CST);
    if (pthread_create(&th, NULL, add_auth_thread, (void *)"la2:p") != 0) {
        unlock(zh);
        zkc_fail("pthread_create");
        return;
    }
    zkc_sleep_ms(500);
    a = count(&g_auth);
    e = count(&g_exists);
    d = count(&g_done);
    zkc_ev("ret", "reply held: auth=%d exists=%d add_auth returned=%d", a, e,
           d);
    if (!(a == 0 && e == 0 && d == 0)) {
        zkc_fail("AUT-09 LA-01-reply: auth=%d exists=%d add_auth=%d", a, e, d);
    }
    unlock(zh);
    if (join_done(th, 10000) == 0 && zkw_wait_int(&g_auth, 2, 10000) == 0 &&
        zkw_wait_int(&g_exists, 1, 10000) == 0) {
        zkc_ev("ret", "reply released: auth=%d exists=%d add_auth=%d",
               count(&g_auth), count(&g_exists), count(&g_done));
    } else {
        zkc_fail("the auth reply did not complete after the unlock");
    }
}

/* Connection teardown drain critical section. */
static void teardown(zhandle_t *zh)
{
    int base = count(&g_auth);
    int a;
    zkw_budget(0);
    add_auth(zh, "lt:p");
    /*
     * On a connected legacy mt handle, zoo_add_auth does not wake the
     * I/O thread, so frame transmission may wait until the T/3 ping interval
     * (5 s for T = 15 s); allow up to 10 s.
     */
    if (zkw_wait_count("A:lt", 1, 10000) != 0) {
        zkc_fail("the auth did not reach the proxy");
        return;
    }
    lock(zh);
    zkw_reset();
    zkw_budget(-1);
    zkc_sleep_ms(500);
    a = count(&g_auth) - base;
    zkc_ev("ret", "teardown held: auth=%d", a);
    if (a != 0) {
        zkc_fail("AUT-09 LA-01-teardown: auth=%d", a);
    }
    unlock(zh);
    if (zkw_wait_int(&g_auth, base + 1, 10000) != 0) {
        zkc_fail("the teardown did not complete the auth after the unlock");
    }
    zkc_await("session:CONNECTED@lock", 1, 15000);
}

/* Reconnect auth replay critical section. */
static void resend(zhandle_t *zh)
{
    int c;
    int n;
    char buf[ZKW_LABEL_CAP];
    zkw_budget(0);
    zkw_clear();
    zkw_reset();
    zkc_await("session:CONNECTING@lock", 1, 10000);
    if (zkw_wait_count("K", 1, 10000) != 0) {
        zkc_fail("no reconnect through the proxy");
        return;
    }
    c = count(&g_connected);
    lock(zh);
    zkw_budget(-1);
    zkc_sleep_ms(500);
    n = zkw_count("A:");
    zkc_ev("ret", "resend held: auth frames=%d new CONNECTED=%d", n,
           count(&g_connected) - c);
    if (!(n == 0 && count(&g_connected) == c)) {
        zkc_fail("AUT-09 LA-01-resend: frames=%d connected=%d", n,
                 count(&g_connected) - c);
    }
    unlock(zh);
    zkc_await("session:CONNECTED@lock", 1, 10000);
    if (zkw_wait_count("A:", 3, 10000) != 0) {
        zkc_fail("no auth resend after the unlock");
    }
    zkc_ev("wire", "resend: %s", zkw_labels(buf, sizeof buf));
}

/* Handle close drain critical section executed on a helper thread. */
static void close_held(zhandle_t *zh)
{
    pthread_t th;
    int d;
    lock(zh);
    __atomic_store_n(&g_done, 0, __ATOMIC_SEQ_CST);
    if (pthread_create(&th, NULL, close_thread, NULL) != 0) {
        unlock(zh);
        zkc_fail("pthread_create");
        return;
    }
    zkc_sleep_ms(500);
    d = count(&g_done);
    zkc_ev("ret", "close held: returned=%d", d);
    if (d != 0) {
        zkc_fail("AUT-09 LA-01-close: returned=%d", d);
    }
    unlock(zh);
    if (join_done(th, 15000) == 0) {
        zkc_ev("ret", "close released: rc=%s",
               count(&g_done) == 1 ? "ZOK" : "error");
    }
}

int main(int argc, char **argv)
{
    const char *px;

    zkc_init(argc, argv, "auth_lock", 1, "PER-ROLE", "ignore");
    px = zkw_start(zkc_host(0), -1);
    if (px == NULL) {
        return zkc_finish();
    }
    g_zh = zookeeper_init2(px, lock_watcher, 15000, NULL, "lock", 0,
                           zkc_log_cb);
    zkc_ev("call", "zookeeper_init2 proxy -> %s", g_zh != NULL ? "zh" : "NULL");
    if (g_zh == NULL || zkc_await("session:CONNECTED@lock", 1, 15000) != 0) {
        if (g_zh != NULL) {
            zookeeper_close(g_zh);
        }
        zkw_stop();
        return zkc_finish();
    }
    auth_reply(g_zh);
    teardown(g_zh);
    resend(g_zh);
    show_wire("end");
    close_held(g_zh);
    zkw_stop();
    return zkc_finish();
}
