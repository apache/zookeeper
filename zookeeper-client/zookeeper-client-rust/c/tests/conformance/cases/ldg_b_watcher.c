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
 * Default watcher and context mutation semantics and thread safety. Mode: DEV.
 *
 * Invariants and deviations:
 * - Calling zoo_set_watcher(zh, NULL) returns the previously
 *   installed watcher and installs the internal no-op nullWatcher stub; a
 *   subsequent zoo_set_watcher call returns that stub pointer (which is safe to
 *   invoke directly). zoo_set_context and zoo_get_context round-trip the
 *   application context pointer identically on both libraries.
 * - Two threads continuously toggle zoo_set_watcher and
 *   zoo_set_context while the handle delivers watch events to the default
 *   watcher. Legacy C mutates zh->watcher and zh->context without
 *   synchronization, whereas the Rust drop-in stores each pointer as an
 *   independent lock-free atomic so every callback delivery and context query
 *   observes a valid pointer.
 * - A signal handler invoking zoo_get_context and zoo_set_context while the
 *   interrupted thread is executing zoo_set_watcher, zoo_set_context, or
 *   zoo_get_context completes without blocking and observes only valid context
 *   pointers. This check executes in a single threaded forked child process
 *   against non-registry release builds (probed beforehand via an unregistered
 *   stand-in handle).
 *
 * zkconf-skip-st: the race needs the library threads of mt
 */

#include "zkconf.h"

#include <pthread.h>
#include <signal.h>
#include <string.h>
#include <sys/time.h>

#define ROUNDS 40
#define FLIPS 200000

static char CTX_A[] = "ctxA";
static char CTX_B[] = "ctxB";

static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static int g_events;
static int g_bad;
static volatile int g_stop;

static int known_ctx(const void *ctx)
{
    return ctx == CTX_A || ctx == CTX_B;
}

static void count_event(int type, void *ctx)
{
    pthread_mutex_lock(&g_mu);
    if (type == ZOO_CREATED_EVENT) {
        g_events++;
        if (!known_ctx(ctx)) {
            g_bad++;
        }
    }
    pthread_mutex_unlock(&g_mu);
}

static void watcher_a(zhandle_t *zh, int type, int state, const char *path,
                      void *ctx)
{
    (void)zh;
    (void)state;
    (void)path;
    count_event(type, ctx);
}

static void watcher_b(zhandle_t *zh, int type, int state, const char *path,
                      void *ctx)
{
    (void)zh;
    (void)state;
    (void)path;
    count_event(type, ctx);
}

static void *flip_watcher(void *arg)
{
    zhandle_t *zh = arg;
    int i;
    for (i = 0; i < FLIPS && !g_stop; i++) {
        zoo_set_watcher(zh, (i & 1) != 0 ? watcher_a : watcher_b);
    }
    return NULL;
}

static void *flip_context(void *arg)
{
    zhandle_t *zh = arg;
    int i;
    for (i = 0; i < FLIPS && !g_stop; i++) {
        zoo_set_context(zh, (i & 1) != 0 ? CTX_A : CTX_B);
    }
    return NULL;
}

static int events_now(void)
{
    int n;
    pthread_mutex_lock(&g_mu);
    n = g_events;
    pthread_mutex_unlock(&g_mu);
    return n;
}

/* Triggers a single ZOO_CREATED_EVENT on the default watcher. */
static void fire_one(zhandle_t *zh, int want)
{
    int i;
    zoo_exists(zh, "/ldgb_w", 1, NULL);
    zoo_create(zh, "/ldgb_w", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, NULL, 0);
    for (i = 0; i < 200 && events_now() < want; i++) {
        zkc_sleep_ms(10);
    }
    zoo_delete(zh, "/ldgb_w", -1);
}

static void check_values(zhandle_t *zh)
{
    watcher_fn old;
    watcher_fn nullw;
    old = zoo_set_watcher(zh, NULL);
    zkc_dev("WAT-15", old == zkc_watcher,
            "zoo_set_watcher(NULL) returns the previous watcher: %s",
            old == zkc_watcher ? "zkc_watcher" : "OTHER");
    nullw = zoo_set_watcher(zh, watcher_a);
    zkc_dev("WAT-15", nullw != NULL && nullw != zkc_watcher,
            "the next zoo_set_watcher returns the null watcher: %s",
            nullw == NULL ? "NULL" : "LIBFN");
    if (nullw != NULL && nullw != zkc_watcher) {
        nullw(zh, ZOO_SESSION_EVENT, ZOO_CONNECTED_STATE, "", NULL);
    }
    zoo_set_context(zh, CTX_A);
    zkc_dev("WAT-15", zoo_get_context(zh) == CTX_A,
            "zoo_get_context gives the value of zoo_set_context");
}

static void check_race(zhandle_t *zh)
{
    pthread_t tw;
    pthread_t tc;
    int i;
    int reads_bad = 0;
    int events;
    int bad;
    g_stop = 0;
    if (pthread_create(&tw, NULL, flip_watcher, zh) != 0) {
        zkc_fail("pthread_create failed");
        return;
    }
    if (pthread_create(&tc, NULL, flip_context, zh) != 0) {
        g_stop = 1;
        pthread_join(tw, NULL);
        zkc_fail("pthread_create failed");
        return;
    }
    for (i = 0; i < ROUNDS; i++) {
        fire_one(zh, i + 1);
        if (!known_ctx(zoo_get_context(zh))) {
            reads_bad++;
        }
    }
    g_stop = 1;
    pthread_join(tw, NULL);
    pthread_join(tc, NULL);
    pthread_mutex_lock(&g_mu);
    events = g_events;
    bad = g_bad;
    pthread_mutex_unlock(&g_mu);
    zkc_dev("WAT-15",
            zkc_is_rust() ? (events == ROUNDS && bad == 0 && reads_bad == 0) : 1,
            "race: events=%d/%d unknown ctx=%d bad reads=%d (legacy: an "
            "unlocked race, recorded; rust: independent atomic pointers)",
            events, ROUNDS, bad, reads_bad);
}

static zhandle_t *g_zh;
static pthread_t g_main;
static volatile sig_atomic_t g_sig_bad;
static volatile sig_atomic_t g_sig_count;
static volatile int g_sig_stop;

/* Signal handler exercising lock-free context accessors upon SIGUSR1 delivery. */
static void on_usr1(int sig)
{
    (void)sig;
    if (!known_ctx(zoo_get_context(g_zh))) {
        g_sig_bad = 1;
    }
    zoo_set_context(g_zh, (g_sig_count & 1) != 0 ? CTX_A : CTX_B);
    g_sig_count++;
}

static void *signal_pump(void *arg)
{
    (void)arg;
    while (!g_sig_stop) {
        pthread_kill(g_main, SIGUSR1);
    }
    return NULL;
}

static long elapsed_ms(const struct timeval *t0)
{
    struct timeval t1;
    gettimeofday(&t1, NULL);
    return (t1.tv_sec - t0->tv_sec) * 1000L + (t1.tv_usec - t0->tv_usec) / 1000L;
}

static int signal_child(void)
{
    struct sigaction sa;
    struct timeval t0;
    pthread_t pump;
    long i;
    int bad = 0;

    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_usr1;
    sa.sa_flags = SA_RESTART;
    sigemptyset(&sa.sa_mask);
    if (sigaction(SIGUSR1, &sa, NULL) != 0) {
        return 9;
    }
    zoo_set_context(g_zh, CTX_A);
    g_main = pthread_self();
    if (pthread_create(&pump, NULL, signal_pump, NULL) != 0) {
        return 9;
    }
    gettimeofday(&t0, NULL);
    for (i = 0; (i & 1023) != 0 || elapsed_ms(&t0) < 1000; i++) {
        zoo_set_watcher(g_zh, (i & 1) != 0 ? watcher_a : watcher_b);
        zoo_set_context(g_zh, (i & 2) != 0 ? CTX_A : CTX_B);
        if (!known_ctx(zoo_get_context(g_zh))) {
            bad = 1;
        }
    }
    g_sig_stop = 1;
    pthread_join(pump, NULL);
    return bad || g_sig_bad ? 1 : 0;
}

/*
 * Unregistered zero-initialized stand-in handle buffer sized and aligned to
 * encompass the context field offset of any zhandle_t layout.
 */
static union {
    void *align_ptr;
    long double align_float;
    char bytes[65536];
} g_unregistered;

/*
 * Probes whether the linked library enforces debug handle registry validation:
 * non-registry builds return NULL from the zeroed stand-in buffer, whereas
 * debug registry builds abort on the unregistered handle address.
 */
static int registry_probe_child(void)
{
    (void)zoo_get_context((zhandle_t *)(void *)&g_unregistered);
    return 0;
}

static void check_signal(zhandle_t *zh)
{
    const char *res;
    char abort_res[32];
    res = zkc_child("WAT-15 registry probe", registry_probe_child, 10000);
    snprintf(abort_res, sizeof(abort_res), "signal:%d", SIGABRT);
    if (strcmp(res, abort_res) == 0) {
        zkc_ev("skip", "WAT-15 signal accessors need a library without "
                       "the handle registry");
        return;
    }
    if (strcmp(res, "exit:0") != 0) {
        zkc_dev("WAT-15", 0, "the handle registry probe failed: %s", res);
        return;
    }
    g_zh = zh;
    res = zkc_child("WAT-15 signal accessors", signal_child, 10000);
    zkc_dev("WAT-15", strcmp(res, "exit:0") == 0,
            "a signal handler uses the context accessors: %s", res);
}

int main(int argc, char **argv)
{
    zhandle_t *zh;

    zkc_init(argc, argv, "ldg_b_watcher", 1, "DEV", "ignore");
    zh = zkc_connect(zkc_host(0), 10000, 0);
    if (zh == NULL) {
        return zkc_finish();
    }
    check_values(zh);
    check_race(zh);
    check_signal(zh);
    zoo_set_watcher(zh, zkc_watcher);
    zoo_set_context(zh, "default");
    zkc_close(zh);
    return zkc_finish();
}
