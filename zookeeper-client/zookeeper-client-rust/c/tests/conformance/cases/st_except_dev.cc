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
 * Teardown preservation across C++ exceptions thrown from single threaded
 * callbacks. Mode: DEV.
 *
 * While the proxy holds server responses so two requests ("A", "B") and one
 * auth request remain pending, the proxy resets the connection. When the
 * default watcher receives the CONNECTING session event inside
 * zookeeper_process, it throws a C++ exception that is caught by the
 * application before driving the handle further.
 *
 * Invariants and deviations:
 * - On both libraries, the exception unwinds cleanly to the application catch
 *   block surrounding zookeeper_process.
 * - Rust commits the connection teardown and preserves remaining
 *   queued callbacks across the unwind; the next driving call drains the
 *   pending auth completion and both request completions with ZCONNECTIONLOSS
 *   in submission order prior to reconnecting.
 * - Legacy C aborts handle_error mid-teardown when the watcher throws,
 *   leaving stale entries in auth_h and sent_requests (causing the auth
 *   callback to complete with ZOK after reconnect and the orphaned requests to
 *   hang until ZOPERATIONTIMEOUT).
 *
 * zkconf-flavors: st
 */

#include "zkconf.h"

#include <cstring>
#include <stdexcept>
#include <sys/select.h>
#include <sys/time.h>

static int g_arm;
static int g_reconnected;
static int g_nreq;
static int g_req_rc[2] = {1, 1};
static int g_req_before_conn = 1;
static int g_nauth;
static int g_auth_rc = 1;
static int g_auth_before_conn;
static int g_order_ok = 1;

static long now_ms()
{
    struct timeval now;
    gettimeofday(&now, NULL);
    return (long)now.tv_sec * 1000L + (long)(now.tv_usec / 1000);
}

static bool lbl_is(const void *d, const char *s)
{
    return d != NULL && std::strcmp(static_cast<const char *>(d), s) == 0;
}

/* Default watcher throws std::runtime_error once on CONNECTING when armed. */
static void watch_hook(zhandle_t *zh, int type, int state, const char *path,
                       void *ctx)
{
    (void)zh;
    (void)path;
    if (type != ZOO_SESSION_EVENT || !lbl_is(ctx, "default")) {
        return;
    }
    if (state == ZOO_CONNECTING_STATE && g_arm) {
        g_arm = 0;
        throw std::runtime_error("watch");
    }
    if (state == ZOO_CONNECTED_STATE) {
        g_reconnected++;
    }
}

/* Tracks completion ordering and return codes for requests A, B, and auth. */
static void count_hook(const char *kind, int rc, const void *d)
{
    (void)kind;
    if (lbl_is(d, "A") || lbl_is(d, "B")) {
        int i = lbl_is(d, "A") ? 0 : 1;
        if (i != g_nreq || g_nauth != 1) {
            g_order_ok = 0;
        }
        if (g_nreq < 2) {
            g_req_rc[g_nreq] = rc;
        }
        g_nreq++;
        if (g_reconnected > 0) {
            g_req_before_conn = 0;
        }
    } else if (lbl_is(d, "auth")) {
        g_nauth++;
        g_auth_rc = rc;
        g_auth_before_conn = g_reconnected == 0;
    }
}

/* Drives zh with a try/catch block surrounding zookeeper_process. */
static int drive(zhandle_t *zh, int timeout_ms, int until_throw)
{
    long deadline = now_ms() + timeout_ms;
    while (now_ms() < deadline) {
        int fd = -1;
        int interest = 0;
        struct timeval tv = {0, 0};
        fd_set rd;
        fd_set wr;
        int events = 0;
        if (!until_throw && g_reconnected > 0 && g_nreq >= 2 && g_nauth >= 1) {
            return 0;
        }
        if (zkc_interest(zh, &fd, &interest, &tv) != ZOK || fd < 0) {
            zkc_sleep_ms(10);
            continue;
        }
        FD_ZERO(&rd);
        FD_ZERO(&wr);
        if (interest & ZOOKEEPER_READ) {
            FD_SET(fd, &rd);
        }
        if (interest & ZOOKEEPER_WRITE) {
            FD_SET(fd, &wr);
        }
        tv.tv_sec = 0;
        tv.tv_usec = 50000;
        if (select(fd + 1, &rd, &wr, NULL, &tv) <= 0) {
            continue;
        }
        if (FD_ISSET(fd, &rd)) {
            events |= ZOOKEEPER_READ;
        }
        if (FD_ISSET(fd, &wr)) {
            events |= ZOOKEEPER_WRITE;
        }
        try {
            zkc_process(zh, events);
        } catch (const std::exception &e) {
            /* Clear the in-call marker bypassed by stack unwinding. */
            zkc_in_call(0);
            zkc_ev("check", "caught %s around zookeeper_process", e.what());
            return 1;
        }
    }
    return 0;
}

int main(int argc, char **argv)
{
    zhandle_t *zh;
    const char *px;
    int caught;

    zkc_init(argc, argv, "st_except_dev", 1, "DEV", "ignore");
    zkc_set_watch_hook(watch_hook);
    px = zkc_proxy_start(zkc_host(0));
    if (px == NULL) {
        return zkc_finish();
    }
    zh = zkc_connect(px, 10000, 0);
    if (zh == NULL) {
        zkc_proxy_stop();
        return zkc_finish();
    }
    zkc_poll_remove(zh);
    zkc_set_hook(count_hook);
    zkc_proxy_hold(1);
    zkc_ev("call", "zoo_aexists A -> %s",
           zkc_rc(zoo_aexists(zh, "/", 0, zkc_stat_cb, "A")));
    zkc_ev("call", "zoo_aexists B -> %s",
           zkc_rc(zoo_aexists(zh, "/", 0, zkc_stat_cb, "B")));
    zkc_ev("call", "zoo_add_auth -> %s",
           zkc_rc(zoo_add_auth(zh, "digest", "u:p", 3, zkc_void_cb, "auth")));
    drive(zh, 300, 0);
    g_arm = 1;
    g_reconnected = 0;
    zkc_proxy_reset();
    zkc_proxy_hold(0);
    caught = drive(zh, 10000, 1);
    zkc_dev("DSP-14", caught == 1,
            "CC-32: the exception reaches the catch around zookeeper_process");
    drive(zh, 15000, 0);
    zkc_ev("check", "after the throw: requests=%d (%s, %s) before the "
           "reconnect=%d auth=%d (%s) before the reconnect=%d reconnected=%d",
           g_nreq, zkc_rc(g_req_rc[0]), zkc_rc(g_req_rc[1]),
           g_req_before_conn, g_nauth, zkc_rc(g_auth_rc), g_auth_before_conn,
           g_reconnected);
    if (zkc_is_rust()) {
        zkc_dev("DSP-14",
                g_nreq == 2 && g_req_rc[0] == ZCONNECTIONLOSS &&
                    g_req_rc[1] == ZCONNECTIONLOSS && g_order_ok &&
                    g_req_before_conn,
                "CC-32: Rust: after the auth, each request gets "
                "ZCONNECTIONLOSS in order before the reconnect");
        zkc_dev("DSP-14",
                g_nauth == 1 && g_auth_rc == ZCONNECTIONLOSS &&
                    g_auth_before_conn,
                "CC-32: Rust: the auth gets ZCONNECTIONLOSS before the "
                "reconnect");
        zkc_dev("DSP-14", g_reconnected >= 1,
                "CC-32: Rust: the handle connects again");
    }
    zkc_set_hook(NULL);
    zkc_proxy_stop();
    return zkc_finish();
}
