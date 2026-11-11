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
 * Reentrant API calls from callbacks invoked while internal locks are held in
 * the legacy library. Mode: DEV.
 *
 * The legacy C client invokes certain user callbacks while holding internal
 * mutexes, causing any reentrant API call that acquires the same mutex to
 * self-deadlock. The Rust drop-in releases all internal locks prior to
 * invoking user callbacks. Each scenario runs in an isolated forked child
 * process under a timeout.
 *
 * Scenarios:
 * - Teardown auth completion: a connection times out during handshake while an
 *   auth request is pending, invoking the auth completion on the I/O thread
 *   during teardown; the callback calls zoo_aexists. Legacy C holds zh_lock
 *   across cleanup_bufs and self-deadlocks; Rust completes zoo_aexists without
 *   blocking.
 * - Log callback under reconfig_lock: calling zoo_set_servers with a portless
 *   host logs "no port in" while legacy C holds reconfig_lock; the log
 *   callback reentrantly invokes zoo_set_servers_resolution_delay, which
 *   acquires reconfig_lock. Legacy C deadlocks the application thread; Rust
 *   returns ZOK.
 *
 * zkconf-skip-st: st runs no library thread and takes no lock
 */

#include "zkconf.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static zhandle_t *volatile g_zh;
static volatile int g_done;
static volatile int g_rc = -999;
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

static void quiet_stat(int rc, const struct Stat *s, const void *d)
{
    (void)rc;
    (void)s;
    (void)d;
}

/* Polls *flag; returns 0 when set, -1 on timeout. */
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

/* Passive TCP listener on the dead address that accepts without responding. */
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
        listen(fd, 8) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static void auth_reenter_cb(int rc, const void *data)
{
    (void)rc;
    (void)data;
    g_rc = zoo_aexists(g_zh, "/", 0, quiet_stat, NULL);
    g_done = 1;
}

/* Child exit codes: 0 if the callback returned, 3 if it deadlocked. */
static int child_teardown_auth(void)
{
    int lfd = silent_listener();
    zhandle_t *zh;
    if (lfd < 0) {
        return 6;
    }
    zh = zookeeper_init2(zkc_dead(), quiet_watcher, 3000, NULL, NULL, 0,
                         quiet_log);
    g_zh = zh;
    if (zh == NULL) {
        return 4;
    }
    if (zoo_add_auth(zh, "digest", "u:p", 3, auth_reenter_cb, NULL) != ZOK) {
        return 5;
    }
    return wait_flag(&g_done, 8000) == 0 ? 0 : 3;
}

static void log_reenter_cb(const char *msg)
{
    if (g_done || !pthread_equal(pthread_self(), g_main) ||
        strstr(msg, "no port in") == NULL) {
        return;
    }
    g_rc = zoo_set_servers_resolution_delay(g_zh, 0);
    g_done = 1;
}

/* Child exit codes: 0 if the callback returned ZOK, 1 for other rc, 2 if uncalled. */
static int child_log_reconfig(void)
{
    zhandle_t *zh;
    int rc;
    g_main = pthread_self();
    zh = zookeeper_init2(zkc_dead(), quiet_watcher, 10000, NULL, NULL, 0,
                         log_reenter_cb);
    g_zh = zh;
    if (zh == NULL) {
        return 4;
    }
    rc = zoo_set_servers(zh, "127.0.0.1");
    (void)rc;
    if (!g_done) {
        return 2;
    }
    return g_rc == ZOK ? 0 : 1;
}

int main(int argc, char **argv)
{
    const char *res;
    int rust;

    zkc_init(argc, argv, "ldg_a_lock_reenter", 0, "DEV", "ignore");
    rust = zkc_is_rust();
    res = zkc_child("DSP-13 teardown auth", child_teardown_auth, 15000);
    zkc_dev("DSP-13", rust ? strcmp(res, "exit:0") == 0
                           : strcmp(res, "exit:3") == 0,
            "zoo_aexists from a teardown auth completion: %s", res);
    res = zkc_child("DSP-13 log under reconfig_lock", child_log_reconfig,
                    5000);
    zkc_dev("DSP-13", rust ? strcmp(res, "exit:0") == 0
                           : strcmp(res, "hang") == 0,
            "zoo_set_servers_resolution_delay from a log callback: %s", res);
    return zkc_finish();
}
