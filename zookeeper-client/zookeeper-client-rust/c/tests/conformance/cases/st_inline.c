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
 * Inline callback execution on the caller thread in single threaded builds.
 * Mode: EXACT.
 *
 * Invariants:
 * - A single threaded handle spawns no background threads; the process
 *   thread count after connection establishment and after close equals the
 *   pre-initialization baseline.
 * - All completion and watcher callbacks execute inline on the calling
 *   thread inside zookeeper_interest, zookeeper_process, or zookeeper_close.
 * - Asynchronous request submission only enqueues and flushes outbound data; no
 *   completion callback executes until the next driving call even after the
 *   server response has arrived in the socket.
 *
 * zkconf-flavors: st
 */

#include "zkconf.h"

#include <string.h>

static int g_ncb;

static void count_hook(const char *kind, int rc, const void *d)
{
    (void)kind;
    (void)rc;
    (void)d;
    g_ncb++;
}

static void call(const char *what, int rc)
{
    zkc_ev("call", "%s -> %s", what, zkc_rc(rc));
}

int main(int argc, char **argv)
{
    zhandle_t *zh;
    int t0;

    zkc_init(argc, argv, "st_inline", 1, "EXACT", "ordered");
    t0 = zkc_threads();
    zh = zkc_connect(zkc_host(0), 10000, 0);
    if (zh == NULL) {
        return zkc_finish();
    }
    zkc_ev("check", "threads after connect: %+d", zkc_threads() - t0);
    zkc_set_hook(count_hook);

    call("zoo_acreate /si", zoo_acreate(zh, "/si", "v", 1, &ZOO_OPEN_ACL_UNSAFE,
                                        0, zkc_string_cb, "create"));
    /* Allow the server response to arrive while the handle is not being driven. */
    zkc_sleep_ms(300);
    zkc_ev("check", "callbacks without a driving call: %d", g_ncb);
    zkc_await("cb", 1, 10000);

    call("zoo_awexists /si W1",
         zoo_awexists(zh, "/si", zkc_watcher, "W1", zkc_stat_cb, "exists"));
    call("zoo_awget /si W2",
         zoo_awget(zh, "/si", zkc_watcher, "W2", zkc_data_cb, "get"));
    zkc_await("cb", 2, 10000);
    call("zoo_aset /si", zoo_aset(zh, "/si", "w", 1, -1, zkc_stat_cb, "set"));
    zkc_await("cb", 1, 10000);
    zkc_await("watch", 2, 10000);
    call("zoo_adelete /si", zoo_adelete(zh, "/si", -1, zkc_void_cb, "del"));
    zkc_await("cb", 1, 10000);
    zkc_ev("check", "threads before close: %+d", zkc_threads() - t0);
    zkc_set_hook(NULL);
    zkc_close(zh);
    zkc_ev("check", "threads after close: %+d", zkc_threads() - t0);
    return zkc_finish();
}
