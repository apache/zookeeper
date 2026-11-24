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
 * Synchronous API calls and handle close invoked from internal library threads.
 * Mode: DEV.
 *
 * Invariants and deviations:
 * - Invoking a synchronous API entry point from an mt I/O-thread callback (such as
 *   an auth completion or an I/O-thread log callback) fails immediately with
 *   ZSYSTEMERROR and errno = EDEADLK on the Rust drop-in, whereas the legacy
 *   library deadlocks the I/O thread waiting for its own response.
 * - Invoking zookeeper_close reentrantly from a ZCLOSING auth
 *   completion callback running on the closing thread itself returns ZOK as a
 *   no-op on the Rust drop-in, whereas the legacy library deadlocks.
 * - Invoking a synchronous API entry point from a completion or watcher
 *   callback on the completion thread succeeds on both libraries because the
 *   I/O thread remains free to service the request.
 *
 * Each I/O-thread deadlock scenario executes in an isolated forked child
 * process under a timeout prior to parent handle initialization.
 *
 * zkconf-skip-st: it needs library threads
 */

#include "zkconf.h"

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static zhandle_t *volatile g_zh;
static volatile int g_done;
static volatile int g_rc;
static volatile int g_errno;
static pthread_t g_main;

static void quiet_watcher(zhandle_t *zh, int type, int state,
                          const char *path, void *ctx)
{
    (void)zh;
    (void)type;
    (void)state;
    (void)path;
    (void)ctx;
}

static void quiet_log(const char *msg)
{
    (void)msg;
}

/* Polls *flag in a child process; returns 0 when set, -1 on timeout. */
static int wait_flag(volatile int *flag, int ms)
{
    int waited;
    for (waited = 0; waited < ms; waited += 10) {
        if (*flag) {
            return 0;
        }
        zkc_sleep_ms(10);
    }
    return -1;
}

static zhandle_t *child_connect(const char *host, log_callback_fn log)
{
    zhandle_t *zh = zookeeper_init2(host, quiet_watcher, 10000, NULL, NULL, 0,
                                    log);
    int waited;
    g_zh = zh;
    for (waited = 0; zh != NULL && waited < 10000; waited += 10) {
        if (zoo_state(zh) == ZOO_CONNECTED_STATE) {
            return zh;
        }
        zkc_sleep_ms(10);
    }
    return zh;
}

/* Child exit codes: 0 for ZSYSTEMERROR/EDEADLK, 1 for other rc, 3 on deadlock. */
static int exit_code(void)
{
    if (!g_done) {
        return 3;
    }
    return g_rc == ZSYSTEMERROR && g_errno == EDEADLK ? 0 : 1;
}

static void auth_sync_cb(int rc, const void *data)
{
    struct Stat st;
    (void)rc;
    (void)data;
    errno = 0;
    g_rc = zoo_exists(g_zh, "/", 0, &st);
    g_errno = errno;
    g_done = 1;
}

static int child_auth_sync(void)
{
    zhandle_t *zh = child_connect(zkc_host(0), quiet_log);
    if (zh == NULL || zoo_state(zh) != ZOO_CONNECTED_STATE) {
        return 4;
    }
    if (zoo_add_auth(zh, "digest", "u:p", 3, auth_sync_cb, NULL) != ZOK) {
        return 5;
    }
    wait_flag(&g_done, 8000);
    return exit_code();
}

static void log_sync_cb(const char *msg)
{
    struct Stat st;
    int waited;
    (void)msg;
    if (g_done || pthread_equal(pthread_self(), g_main)) {
        return;
    }
    for (waited = 0; g_zh == NULL && waited < 2000; waited += 10) {
        zkc_sleep_ms(10);
    }
    errno = 0;
    g_rc = zoo_exists(g_zh, "/", 0, &st);
    g_errno = errno;
    g_done = 1;
}

static int child_log_sync(void)
{
    g_main = pthread_self();
    child_connect(zkc_host(0), log_sync_cb);
    wait_flag(&g_done, 8000);
    return exit_code();
}

static volatile int g_nested_rc = -999;
static volatile int g_auth_rc = -999;

static void nested_close_cb(int rc, const void *data)
{
    (void)data;
    g_auth_rc = rc;
    if (rc == ZCLOSING) {
        g_nested_rc = zookeeper_close(g_zh);
    }
    g_done = 1;
}

/*
 * Passive TCP listener on the dead port that accepts connections without
 * responding, keeping the client in its session handshake so queued auth
 * callbacks remain pending until zookeeper_close.
 */
static int silent_listener(void)
{
    struct sockaddr_in sa;
    const char *colon = strrchr(zkc_dead(), ':');
    int one = 1;
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0 || colon == NULL) {
        return -1;
    }
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET;
    sa.sin_port = htons((unsigned short)atoi(colon + 1));
    sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(fd, (struct sockaddr *)&sa, sizeof(sa)) != 0 ||
        listen(fd, 4) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

/* Child exit codes: 0 for nested ZOK, 1 if no ZCLOSING callback, 2 for other rc. */
static int child_nested_close(void)
{
    int lfd = silent_listener();
    zhandle_t *zh;
    int rc;
    int waited;
    if (lfd < 0) {
        return 6;
    }
    zh = zookeeper_init2(zkc_dead(), quiet_watcher, 10000, NULL, NULL, 0,
                         quiet_log);
    g_zh = zh;
    if (zh == NULL) {
        return 4;
    }
    for (waited = 0; zoo_state(zh) <= 0 && waited < 5000; waited += 10) {
        zkc_sleep_ms(10);
    }
    rc = zoo_add_auth(zh, "digest", "u:p", 3, nested_close_cb, NULL);
    if (rc != ZOK) {
        return 5;
    }
    zookeeper_close(zh);
    if (!g_done || g_auth_rc != ZCLOSING) {
        return 1;
    }
    return g_nested_rc == ZOK ? 0 : 2;
}

/* Synchronous calls invoked from callbacks on the completion thread. */
static zhandle_t *g_th;
static int g_th_cb_rc = -999;
static int g_th_w_rc = -999;

static void th_hook(const char *kind, int rc, const void *d)
{
    struct Stat st;
    (void)kind;
    (void)rc;
    if (d != NULL && strcmp((const char *)d, "th04") == 0) {
        g_th_cb_rc = zoo_exists(g_th, "/", 0, &st);
    }
}

static void th_whook(zhandle_t *zh, int type, int state, const char *path,
                     void *ctx)
{
    char buf[16];
    int len = sizeof(buf);
    struct Stat st;
    (void)zh;
    (void)state;
    (void)ctx;
    if (type == ZOO_CHANGED_EVENT && strcmp(path, "/th04") == 0) {
        g_th_w_rc = zoo_get(g_th, "/th04", 0, buf, &len, &st);
    }
}

int main(int argc, char **argv)
{
    const char *res;
    int rust;
    struct Stat st;

    zkc_init(argc, argv, "edeadlk", 1, "DEV", "ignore");
    rust = zkc_is_rust();

    res = zkc_child("DSP-04-auth", child_auth_sync, 15000);
    zkc_dev("DSP-04", rust ? strcmp(res, "exit:0") == 0
                           : strcmp(res, "exit:0") != 0,
            "sync zoo_exists from an IO-thread auth completion: %s", res);
    res = zkc_child("DSP-04-log", child_log_sync, 15000);
    zkc_dev("DSP-04", rust ? strcmp(res, "exit:0") == 0
                           : strcmp(res, "exit:0") != 0,
            "sync zoo_exists from the IO-thread log callback: %s", res);
    res = zkc_child("CLS-05", child_nested_close, 15000);
    zkc_dev("CLS-05", rust ? strcmp(res, "exit:0") == 0
                           : strcmp(res, "exit:0") != 0,
            "zookeeper_close from the ZCLOSING auth completion: %s", res);

    g_th = zkc_connect(zkc_host(0), 10000, 0);
    if (g_th != NULL) {
        zkc_set_hook(th_hook);
        zkc_set_watch_hook(th_whook);
        zoo_create(g_th, "/th04", "a", 1, &ZOO_OPEN_ACL_UNSAFE, 0, NULL, 0);
        zoo_aexists(g_th, "/", 0, zkc_stat_cb, "th04");
        zkc_await("cb", 1, 10000);
        zoo_wexists(g_th, "/th04", zkc_watcher, "w", &st);
        zoo_set(g_th, "/th04", "b", 1, -1);
        zkc_await("watch", 1, 10000);
        zkc_dev("DSP-04", g_th_cb_rc == ZOK && g_th_w_rc == ZOK,
                "TH-04: sync call in a completion -> %s, in a watcher -> %s",
                zkc_rc(g_th_cb_rc), zkc_rc(g_th_w_rc));
        zkc_set_hook(NULL);
        zkc_set_watch_hook(NULL);
        zkc_close(g_th);
    }
    return zkc_finish();
}
