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
 * Deferred handle destruction across nested driving calls and reentrant
 * zookeeper_close. Mode: EXACT.
 *
 * A completion callback invoked by an outer zookeeper_process call executes a
 * nested zookeeper_process call and invokes zookeeper_close in both relative
 * orderings. Each active driving frame holds a handle reference count via
 * api_prolog / api_epilog.
 *
 * Invariants:
 * - Invoking zookeeper_close inside the callback returns ZOK immediately and
 *   defers destruction while keeping the handle readable (zoo_state remains
 *   ZOO_CONNECTED_STATE inside the callback).
 * - Returning from the nested zookeeper_process call decrements only the inner
 *   reference count; final ZCLOSING completion drain and handle deallocation
 *   occur only when the outermost driving call reaches api_epilog.
 *
 * zkconf-flavors: st
 */

#include "zkconf.h"

#include <string.h>
#include <sys/select.h>

static zhandle_t *g_zh;
static int g_order;
static int g_closed;

static void call(const char *what, int rc)
{
    zkc_ev("call", "%s -> %s", what, zkc_rc(rc));
}

static void cb_hook(const char *kind, int rc, const void *d)
{
    (void)kind;
    (void)rc;
    if (g_closed || d == NULL || strcmp((const char *)d, "outer") != 0) {
        return;
    }
    call("zoo_aexists / inner",
         zoo_aexists(g_zh, "/", 0, zkc_stat_cb, "inner"));
    if (g_order == 1) {
        call("nested zookeeper_process", zkc_process(g_zh, 0));
        zkc_close(g_zh);
    } else {
        zkc_close(g_zh);
        call("nested zookeeper_process", zkc_process(g_zh, 0));
    }
    g_closed = 1;
    zkc_ev("step", "zoo_state in the callback -> %s",
           zkc_state(zoo_state(g_zh)));
}

/* Drives g_zh until the outer driving call that triggered zookeeper_close returns. */
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
            call("outer zookeeper_interest", rc);
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
        if (select(fd >= 0 ? fd + 1 : 0, &rd, &wr, NULL, &stv) > 0 &&
            fd >= 0) {
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
            call("outer zookeeper_process", rc);
            return;
        }
    }
    zkc_fail("no callback closed the handle");
}

int main(int argc, char **argv)
{
    zkc_init(argc, argv, "st_nest_close", 1, "EXACT", "ordered");
    zkc_set_hook(cb_hook);

    for (g_order = 1; g_order <= 2; g_order++) {
        zkc_ev("step", "order %s", g_order == 1 ? "nested call, then close"
                                                : "close, then nested call");
        g_closed = 0;
        g_zh = zkc_connect(zkc_host(0), 10000, 0);
        if (g_zh == NULL) {
            return zkc_finish();
        }
        zkc_poll_remove(g_zh);
        call("zoo_aexists / outer",
             zoo_aexists(g_zh, "/", 0, zkc_stat_cb, "outer"));
        drive_until_closed(10000);
    }
    return zkc_finish();
}
