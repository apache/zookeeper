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
 * Thread affinity of Cyrus SASL callbacks during handshake and reconnection.
 * Mode: PER-ROLE.
 *
 * Invariants:
 * - All Cyrus SASL operations for a handle execute on the I/O owner
 *   thread. In mt builds, the I/O owner is the dedicated I/O thread (distinct
 *   from both the application thread and the completion/watcher thread). In st
 *   builds, the I/O owner is the application thread invoking zookeeper_process.
 * - Application SASL callbacks (SASL_CB_AUTHNAME, SASL_CB_USER, SASL_CB_PASS)
 *   run synchronously inside sasl_client_start and sasl_client_step and
 *   therefore execute on the I/O owner thread.
 * - Following a server restart, re-authentication executes on the same I/O
 *   owner thread role.
 *
 * Requires SASL-enabled library builds and a server configured with
 * DigestLoginModule (user_myuser="mypassword"); emits a skip record otherwise.
 *
 * zkconf-flavors: mt st
 */

#if defined(__has_include)
#if __has_include(<sasl/sasl.h>)
#define HAVE_CYRUS_SASL_H 1
#endif
#endif

#include "zkconf.h"
#include "zkconf_sasl.h"
#include "zkconf_sync.h"

#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef HAVE_CYRUS_SASL_H
#include <sasl/sasl.h>

typedef zhandle_t *(*init_sasl_fn)(const char *, watcher_fn, int,
                                   const clientid_t *, void *, int,
                                   log_callback_fn, zoo_sasl_params_t *);

static init_sasl_fn p_init_sasl;

/* Thread identity tracking and observed callback thread bitmasks. */
static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_t g_app;
static pthread_t g_watcher;
static int g_have_watcher;
/* Bitmask: bit 0 = application thread, bit 1 = watcher thread, bit 2 = other. */
static int g_simple_kinds;
static int g_pass_kinds;

static union {
    sasl_secret_t s;
    char space[sizeof(sasl_secret_t) + 32];
} g_secret;

static int kind_bit(void)
{
    pthread_t self = pthread_self();
    if (pthread_equal(self, g_app)) {
        return 1;
    }
    if (g_have_watcher && pthread_equal(self, g_watcher)) {
        return 2;
    }
    return 4;
}

static const char *kinds(int bits, char *buf, size_t len)
{
    snprintf(buf, len, "%s%s%s%s", bits == 0 ? "none" : "",
             (bits & 1) != 0 ? "+app" : "", (bits & 2) != 0 ? "+watcher" : "",
             (bits & 4) != 0 ? "+other" : "");
    return buf[0] == '+' ? buf + 1 : buf;
}

static int app_simple(void *context, int id, const char **result,
                      unsigned *len)
{
    (void)id;
    if (result == NULL) {
        return SASL_BADPARAM;
    }
    pthread_mutex_lock(&g_mu);
    g_simple_kinds |= kind_bit();
    pthread_mutex_unlock(&g_mu);
    *result = (const char *)context;
    if (len != NULL) {
        *len = (unsigned)strlen((const char *)context);
    }
    return SASL_OK;
}

static int app_pass(sasl_conn_t *conn, void *context, int id,
                    sasl_secret_t **psecret)
{
    const char *pw = (const char *)context;
    size_t n = strlen(pw);
    (void)conn;
    if (id != SASL_CB_PASS || psecret == NULL || n >= 32) {
        return SASL_BADPARAM;
    }
    pthread_mutex_lock(&g_mu);
    g_pass_kinds |= kind_bit();
    pthread_mutex_unlock(&g_mu);
    memset(&g_secret, 0, sizeof g_secret);
    g_secret.s.len = (unsigned long)n;
    memcpy((char *)&g_secret + offsetof(sasl_secret_t, data), pw, n);
    *psecret = &g_secret.s;
    return SASL_OK;
}

/* Captures the watcher thread identity before forwarding the event. */
static void watcher(zhandle_t *zh, int type, int state, const char *path,
                    void *ctx)
{
    pthread_mutex_lock(&g_mu);
    if (!g_have_watcher) {
        g_watcher = pthread_self();
        g_have_watcher = 1;
    }
    pthread_mutex_unlock(&g_mu);
    zkc_watcher(zh, type, state, path, ctx);
}

/* Emits the observed callback thread categories and resets the bitmasks. */
static void show_kinds(const char *label)
{
    char a[64];
    char b[64];
    pthread_mutex_lock(&g_mu);
    zkc_ev("ret", "%s: AUTHNAME and USER on %s, PASS on %s", label,
           kinds(g_simple_kinds, a, sizeof a),
           kinds(g_pass_kinds, b, sizeof b));
    g_simple_kinds = 0;
    g_pass_kinds = 0;
    pthread_mutex_unlock(&g_mu);
}

static void run_sasl(void)
{
    zoo_sasl_params_t sp;
    sasl_callback_t app[4];
    zhandle_t *zh;
    struct Stat st;

    g_app = pthread_self();
    memset(app, 0, sizeof app);
    app[0].id = SASL_CB_AUTHNAME;
    app[0].proc = (int (*)(void))(void (*)(void))app_simple;
    app[0].context = (void *)"myuser";
    app[1].id = SASL_CB_USER;
    app[1].proc = (int (*)(void))(void (*)(void))app_simple;
    app[1].context = (void *)"myuser";
    app[2].id = SASL_CB_PASS;
    app[2].proc = (int (*)(void))(void (*)(void))app_pass;
    app[2].context = (void *)"mypassword";
    app[3].id = SASL_CB_LIST_END;

    memset(&sp, 0, sizeof sp);
    sp.service = "zookeeper";
    sp.host = "zk-sasl-md5";
    sp.mechlist = "DIGEST-MD5";
    sp.callbacks = app;
    zh = p_init_sasl(zkc_host(0), watcher, 10000, NULL, (void *)"sasl", 0,
                     zkc_log_cb, &sp);
    zkc_ev("call", "zookeeper_init_sasl -> %s", zh != NULL ? "zh" : "NULL");
    if (zh == NULL) {
        return;
    }
    zkc_poll_add(zh);
    zkc_await("session:CONNECTED@sasl", 1, 15000);
    zkc_ev("call", "zoo_exists / -> %s",
           zkc_rc(zkcs_exists(zh, "/", 0, &st)));
    show_kinds("first connection");

    /* Verify that re-authentication after a server restart runs on the same I/O owner. */
    zkc_server("stop", 0);
    zkc_await("session:CONNECTING@sasl", 1, 15000);
    zkc_server("start", 0);
    zkc_await("session:CONNECTED@sasl", 1, 30000);
    zkc_ev("call", "zoo_exists / -> %s",
           zkc_rc(zkcs_exists(zh, "/", 0, &st)));
    show_kinds("after the restart");
    zkc_close(zh);
}

static int resolve(void)
{
    void *a = dlsym(RTLD_DEFAULT, "zookeeper_init_sasl");
    if (a == NULL) {
        return -1;
    }
    /* Copy the dlsym void* address into the typed function pointer without UB. */
    memcpy(&p_init_sasl, &a, sizeof p_init_sasl);
    return zkcs_sasl_client_init() == SASL_OK ? 0 : -1;
}

#endif /* HAVE_CYRUS_SASL_H */

int main(int argc, char **argv)
{
    zkc_init(argc, argv, "ldg_c_sasl_thread", 1, "PER-ROLE", "ignore");
#ifdef HAVE_CYRUS_SASL_H
    if (getenv("ZKCONF_SASL") != NULL && resolve() == 0) {
        run_sasl();
        return zkc_finish();
    }
#endif
    zkc_ev("check", "skip: no SASL library or no SASL server");
    return zkc_finish();
}
