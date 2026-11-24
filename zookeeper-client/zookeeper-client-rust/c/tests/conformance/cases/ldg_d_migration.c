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
 * Forced server-list migration with an in-flight request. Mode: DEV.
 *
 * The client connects via the IPv4 proxy on 127.0.0.1 while the proxy holds
 * server responses so one request ("inflight") remains pending in sent_requests.
 * Calling zoo_set_servers with the IPv6 loopback address (::1) of the live
 * server forces a connection migration on both libraries (because the IP
 * address changes), after which a second request ("after") is submitted.
 *
 * Common invariants: zoo_set_servers returns ZOK, transitions zoo_state to
 * ZOO_NOTCONNECTED_STATE (999), and reconnects to ::1.
 *
 * Deviations on in-flight work during forced migration:
 * - Rust performs a complete connection teardown: it emits a CONNECTING
 *   session event, drains the in-flight request with ZCONNECTIONLOSS without
 *   retransmitting it, and completes the post-migration request with ZOK on
 *   the new connection.
 * - Legacy C closes the socket in update_addrs without draining sent_requests;
 *   when the response for the post-migration request arrives on the new
 *   connection, its XID mismatches the stale head of sent_requests, triggering
 *   a fatal ZRUNTIMEINCONSISTENCY teardown for both requests.
 *
 * zkconf-flavors: mt st
 */

#include "zkconf.h"

#include <stdio.h>
#include <string.h>

static volatile int g_inflight_rc = 1;
static volatile int g_after_rc = 1;
static volatile int g_connecting;
static volatile int g_connected;
static volatile int g_connecting_first;

static void cb_hook(const char *kind, int rc, const void *d)
{
    (void)kind;
    if (d == NULL) {
        return;
    }
    if (strcmp((const char *)d, "inflight") == 0) {
        g_inflight_rc = rc;
        g_connecting_first = g_connecting > 0;
    } else if (strcmp((const char *)d, "after") == 0) {
        g_after_rc = rc;
    }
}

static void watch_hook(zhandle_t *zh, int type, int state, const char *path,
                       void *ctx)
{
    (void)zh;
    (void)path;
    (void)ctx;
    if (type != ZOO_SESSION_EVENT) {
        return;
    }
    if (state == ZOO_CONNECTING_STATE) {
        g_connecting++;
    } else if (state == ZOO_CONNECTED_STATE) {
        g_connected++;
    }
}

/* Polls the handle until the post-migration request completes or timeout expires. */
static int wait_after(int ms)
{
    int waited;
    for (waited = 0; waited < ms; waited += 50) {
        if (g_after_rc != 1) {
            return 1;
        }
        zkc_poll(50);
    }
    return 0;
}

int main(int argc, char **argv)
{
    const char *px;
    const char *port;
    char v6[64];
    zhandle_t *zh;
    int rc;
    int arc;
    int state;
    int rust;

    zkc_init(argc, argv, "ldg_d_migration", 1, "DEV", "ignore");
    rust = zkc_is_rust();
    zkc_set_hook(cb_hook);
    zkc_set_watch_hook(watch_hook);
    port = strrchr(zkc_host(0), ':');
    if (port == NULL) {
        zkc_fail("no port in %s", zkc_host(0));
        return zkc_finish();
    }
    snprintf(v6, sizeof(v6), "::1%s", port);
    px = zkc_proxy_start(zkc_host(0));
    if (px == NULL) {
        return zkc_finish();
    }
    zh = zkc_connect(px, 10000, 0);
    if (zh == NULL) {
        zkc_proxy_stop();
        return zkc_finish();
    }
    g_connected = 0;

    /* Hold server responses at the proxy so "inflight" remains pending. */
    zkc_proxy_hold(1);
    arc = zoo_aexists(zh, "/", 0, zkc_stat_cb, "inflight");
    zkc_poll(300);

    rc = zoo_set_servers(zh, v6);
    state = zoo_state(zh);
    zkc_dev("SRV-09", arc == ZOK && rc == ZOK,
            "the submit returns %s and zoo_set_servers returns %s",
            zkc_rc(arc), zkc_rc(rc));
    zkc_dev("SRV-09", state == ZOO_NOTCONNECTED_STATE,
            "the state after the move is %s", zkc_state(state));

    /* Submit a second request after the forced migration to ::1. */
    arc = zoo_aexists(zh, "/", 0, zkc_stat_cb, "after");
    zkc_dev("SRV-09",
            arc == ZOK && wait_after(20000) &&
                g_after_rc == (rust ? ZOK : ZRUNTIMEINCONSISTENCY),
            "the request after the move completes with %s; CONNECTED=%d",
            g_after_rc == 1 ? "nothing" : zkc_rc(g_after_rc), g_connected);
    zkc_poll(300);

    if (rust) {
        zkc_dev("SRV-09",
                g_connecting == 1 && g_inflight_rc == ZCONNECTIONLOSS &&
                    g_connecting_first,
                "CONNECTING=%d; the request in flight completes with %s",
                g_connecting,
                g_inflight_rc == 1 ? "nothing" : zkc_rc(g_inflight_rc));
    } else {
        zkc_dev("SRV-09",
                g_inflight_rc == ZRUNTIMEINCONSISTENCY,
                "legacy: CONNECTING=%d; the request in flight completes "
                "with %s",
                g_connecting,
                g_inflight_rc == 1 ? "nothing" : zkc_rc(g_inflight_rc));
    }
    zkc_proxy_hold(0);
    zkc_close(zh);
    zkc_proxy_stop();
    return zkc_finish();
}
