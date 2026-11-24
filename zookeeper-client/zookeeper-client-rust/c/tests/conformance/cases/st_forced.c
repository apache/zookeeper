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
 * single threaded forced socket teardown after an inline send flush failure.
 * Mode: DEV.
 *
 * The proxy resets the active connection while the handle is idle, so the next
 * asynchronous request submission flushes directly into the reset socket and
 * encounters a write failure inside nonblocking_send.
 *
 * Invariants and deviations:
 * - On both libraries, the submission call returns ZOK, immediately sets
 *   zoo_state to ZOO_NOTCONNECTED_STATE (999), and invokes no callbacks inline
 *   during the submission call.
 * - Rust performs full connection teardown on the subsequent
 *   zookeeper_interest call, delivering a CONNECTING session event (because the
 *   session was connected when the failure occurred) and draining the failed
 *   request with ZCONNECTIONLOSS.
 * - Legacy C closes the socket in nonblocking_send without draining
 *   outgoing_queue or emitting a CONNECTING event; the request remains queued
 *   and is transmitted after reconnection.
 *
 * zkconf-flavors: st
 */

#include "zkconf.h"

#include <string.h>

static int g_ncb;
static int g_nconnecting;
static int g_forced_rc = 1;
static int g_connecting_first;

static void cb_hook(const char *kind, int rc, const void *d)
{
    (void)kind;
    g_ncb++;
    if (d != NULL && strcmp((const char *)d, "forced") == 0) {
        g_forced_rc = rc;
        g_connecting_first = g_nconnecting > 0;
    }
}

static void watch_hook(zhandle_t *zh, int type, int state, const char *path,
                       void *ctx)
{
    (void)zh;
    (void)path;
    (void)ctx;
    if (type == ZOO_SESSION_EVENT && state == ZOO_CONNECTING_STATE) {
        g_nconnecting++;
    }
}

int main(int argc, char **argv)
{
    const char *px;
    zhandle_t *zh;
    int fd = -1;
    int interest = 0;
    struct timeval tv = {0, 0};
    int before;
    int rc;
    int state;
    int irc;

    zkc_init(argc, argv, "st_forced", 1, "DEV", "ignore");
    zkc_set_hook(cb_hook);
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
    zkc_proxy_reset();
    zkc_sleep_ms(300);

    before = g_ncb + g_nconnecting;
    rc = zoo_aexists(zh, "/", 0, zkc_stat_cb, "forced");
    state = zoo_state(zh);
    zkc_ev("call", "zoo_aexists forced -> %s state=%s", zkc_rc(rc),
           zkc_state(state));
    zkc_dev("DV-FIX-8", rc == ZOK, "the submit returns %s", zkc_rc(rc));
    zkc_dev("DV-FIX-8", state == ZOO_NOTCONNECTED_STATE,
            "the state after the failed flush is %s", zkc_state(state));
    zkc_dev("DV-FIX-8", g_ncb + g_nconnecting == before,
            "callbacks inside the submit: %d", g_ncb + g_nconnecting - before);

    /* The next driving call. */
    before = g_ncb;
    irc = zkc_interest(zh, &fd, &interest, &tv);
    zkc_ev("call", "next zookeeper_interest -> %s; CONNECTING=%d callbacks=%d",
           zkc_rc(irc), g_nconnecting, g_ncb - before);
    if (zkc_is_rust()) {
        zkc_dev("DV-FIX-8", g_nconnecting == 1 && g_forced_rc == ZCONNECTIONLOSS,
                "the next interest call gives CONNECTING=%d and %s",
                g_nconnecting, g_forced_rc == 1 ? "no completion"
                                                : zkc_rc(g_forced_rc));
    } else {
        zkc_dev("DV-FIX-8", g_nconnecting == 0 && g_forced_rc == 1,
                "legacy: the next interest call gives CONNECTING=%d and %s",
                g_nconnecting, g_forced_rc == 1 ? "no completion"
                                                : zkc_rc(g_forced_rc));
    }

    /* The handle connects again through the proxy. */
    zkc_poll_add(zh);
    zkc_await("session:CONNECTED@default", 1, 15000);
    if (g_forced_rc == 1) {
        zkc_await("cb", 1, 10000);
    }
    zkc_ev("check", "forced completion %s, CONNECTING before it: %d",
           zkc_rc(g_forced_rc), g_connecting_first);
    if (!zkc_is_rust()) {
        zkc_dev("DV-FIX-8", g_forced_rc == ZOK && !g_connecting_first,
                "legacy: the resent request completes with %s",
                zkc_rc(g_forced_rc));
    }
    zkc_close(zh);
    zkc_proxy_stop();
    return zkc_finish();
}
