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
 * Reentrant zookeeper_close invoked from a callback in the single threaded
 * library. Mode: EXACT.
 *
 * The test drives the handle via an explicit event loop so the return code of
 * the outer zookeeper_process or zookeeper_interest call that triggered the
 * closing callback is captured in the trace.
 *
 * Invariants:
 * - Calling zookeeper_close inside a completion callback returns ZOK
 *   immediately; remaining queued requests drain with ZCLOSING after the
 *   current callback returns, within the enclosing driving call, before the
 *   handle memory is released at api_epilog.
 * - Submitting an asynchronous request after close has been requested
 *   from a callback fails synchronously without invoking a completion.
 * - Closing the handle from a session watcher invoked inside
 *   zookeeper_interest (triggered by a receive timeout while the proxy holds
 *   server traffic) drains pending requests and releases the handle at the
 *   end of zookeeper_interest.
 * - When a watcher closes the handle inside
 *   zookeeper_interest, the Rust drop-in sets *fd = -1, whereas legacy C writes
 *   the descriptor number of the already-closed socket to *fd.
 *
 * zkconf-flavors: st
 */

#include "zkconf.h"

#include <string.h>
#include <sys/select.h>

static zhandle_t *g_zh;
static int g_closed;

static void call(const char *what, int rc)
{
    zkc_ev("call", "%s -> %s", what, zkc_rc(rc));
}

static void close_now(const char *why)
{
    zkc_ev("step", "close from %s", why);
    zkc_close(g_zh);
    call("zoo_aexists after the close request",
         zoo_aexists(g_zh, "/", 0, zkc_stat_cb, "after-close"));
    g_closed = 1;
}

static void cb_hook(const char *kind, int rc, const void *d)
{
    (void)kind;
    (void)rc;
    if (!g_closed && d != NULL && strcmp((const char *)d, "closer") == 0) {
        close_now("a completion");
    }
}

static void watch_hook(zhandle_t *zh, int type, int state, const char *path,
                       void *ctx)
{
    (void)zh;
    (void)path;
    if (!g_closed && type == ZOO_SESSION_EVENT &&
        state == ZOO_CONNECTING_STATE && ctx != NULL &&
        strcmp((const char *)ctx, "timeout") == 0) {
        close_now("a CONNECTING watcher");
    }
}

/*
 * Drives g_zh until a callback invokes zookeeper_close, recording the return
 * value of the enclosing zookeeper_interest or zookeeper_process call.
 */
static void drive_until_closed(int timeout_ms)
{
    int waited = 0;
    while (!g_closed && waited < timeout_ms) {
        int fd = -1;
        int interest = 0;
        struct timeval tv = {0, 0};
        struct timeval stv = {0, 50000};
        fd_set rd;
        fd_set wr;
        int events = 0;
        int rc = zkc_interest(g_zh, &fd, &interest, &tv);
        if (g_closed) {
            /* Legacy C populates *fd with the descriptor of the closed socket. */
            zkc_ev("call", "zookeeper_interest that closed -> %s interest=%d",
                   zkc_rc(rc), interest);
            if (zkc_is_rust()) {
                zkc_dev("SRV-13", fd == -1, "the closing interest call gives "
                        "fd %s", fd >= 0 ? "of the closed socket" : "-1");
            } else {
                zkc_dev("SRV-13", fd >= 0, "legacy: the closing interest call "
                        "gives fd %s", fd >= 0 ? "of the closed socket" : "-1");
            }
            return;
        }
        FD_ZERO(&rd);
        FD_ZERO(&wr);
        if (fd >= 0) {
            if (interest & ZOOKEEPER_READ) {
                FD_SET(fd, &rd);
            }
            if (interest & ZOOKEEPER_WRITE) {
                FD_SET(fd, &wr);
            }
        }
        if (tv.tv_sec == 0 && tv.tv_usec < stv.tv_usec) {
            stv = tv;
        }
        if (select(fd >= 0 ? fd + 1 : 0, &rd, &wr, NULL, &stv) > 0) {
            if (FD_ISSET(fd, &rd)) {
                events |= ZOOKEEPER_READ;
            }
            if (FD_ISSET(fd, &wr)) {
                events |= ZOOKEEPER_WRITE;
            }
        }
        waited += 50;
        if (events == 0) {
            continue;
        }
        rc = zkc_process(g_zh, events);
        if (g_closed) {
            zkc_ev("call", "zookeeper_process that closed -> %s", zkc_rc(rc));
            return;
        }
    }
    zkc_fail("no callback closed the handle");
}

int main(int argc, char **argv)
{
    const char *px;
    int i;

    zkc_init(argc, argv, "st_close_cb", 1, "EXACT", "ordered");
    zkc_set_hook(cb_hook);
    zkc_set_watch_hook(watch_hook);

    /* Close from a completion callback inside zookeeper_process. */
    g_zh = zkc_connect(zkc_host(0), 10000, 0);
    if (g_zh == NULL) {
        return zkc_finish();
    }
    zkc_poll_remove(g_zh);
    call("zoo_aexists / closer",
         zoo_aexists(g_zh, "/", 0, zkc_stat_cb, "closer"));
    for (i = 0; i < 3; i++) {
        static const char *const labels[] = {"p1", "p2", "p3"};
        call("zoo_aexists /", zoo_aexists(g_zh, "/", 0, zkc_stat_cb,
                                          labels[i]));
    }
    drive_until_closed(10000);

    /* Close from the CONNECTING watcher inside zookeeper_interest. */
    px = zkc_proxy_start(zkc_host(0));
    if (px == NULL) {
        return zkc_finish();
    }
    g_closed = 0;
    g_zh = zookeeper_init2(px, zkc_watcher, 3000, NULL, "timeout", 0,
                           zkc_log_cb);
    zkc_ev("call", "zookeeper_init2 proxy -> %s", g_zh ? "zh" : "NULL");
    if (g_zh != NULL) {
        zkc_poll_add(g_zh);
        zkc_await("session:CONNECTED@timeout", 1, 15000);
        zkc_poll_remove(g_zh);
        zkc_proxy_hold(1);
        call("zoo_aexists / held",
             zoo_aexists(g_zh, "/", 0, zkc_stat_cb, "held"));
        drive_until_closed(10000);
        zkc_proxy_hold(0);
    }
    zkc_proxy_stop();
    return zkc_finish();
}
