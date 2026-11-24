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
 * Reentrant API invocations from single threaded callbacks inside
 * zookeeper_process. Mode: EXACT.
 *
 * Invariants:
 * - Completion callbacks running inside zookeeper_process may query zoo_state,
 *   submit new requests, and register watches; submissions return ZOK and
 *   complete on subsequent driving calls.
 * - Invoking zookeeper_process reentrantly from a completion callback
 *   processes the next buffered server response at call depth 2 before
 *   returning to the outer callback and completing the outer zookeeper_process
 *   call.
 * - Watcher callbacks running inside zookeeper_process may re-register a watch
 *   on the same path to observe subsequent mutations.
 * - When zookeeper_process detects connection loss, the CONNECTING
 *   session watcher executes while zoo_state still reports ZOO_CONNECTED_STATE,
 *   allowing a request submitted inside the watcher to enqueue and drain with
 *   ZCONNECTIONLOSS behind the already in-flight request before reconnection.
 *
 * zkconf-flavors: st
 */

#include "zkconf.h"
#include "zkconf_sync.h"

#include <string.h>

static zhandle_t *g_zh;
static int g_depth;
static int g_in_loss;
static int g_loss_submits;

static void call(const char *what, int rc)
{
    zkc_ev("call", "%s -> %s", what, zkc_rc(rc));
}

static int lbl_is(const void *d, const char *s)
{
    return d != NULL && strcmp((const char *)d, s) == 0;
}

/*
 * Completion "A" reentrantly queries state, submits requests, and invokes a
 * nested zookeeper_process(READ) call; all completions record their nesting
 * depth.
 */
static void reenter_hook(const char *kind, int rc, const void *d)
{
    (void)kind;
    (void)rc;
    zkc_ev("check", "%s at depth %d", zkc_lbl(d), g_depth);
    if (!lbl_is(d, "A")) {
        return;
    }
    zkc_ev("check", "zoo_state in A=%s", zkc_state(zoo_state(g_zh)));
    call("zoo_aget D in A",
         zoo_aget(g_zh, "/sr", 0, zkc_data_cb, "D"));
    call("zoo_awexists W1 in A",
         zoo_awexists(g_zh, "/sr", zkc_watcher, "W1", zkc_stat_cb, "E"));
    g_depth++;
    call("nested zookeeper_process(READ) in A",
         zkc_process(g_zh, ZOOKEEPER_READ));
    g_depth--;
}

/*
 * Watcher W1 re-arms watch W2 on the same path; the initial CONNECTING session
 * watcher during the connection-loss phase submits a request reentrantly.
 */
static void watch_hook(zhandle_t *zh, int type, int state, const char *path,
                       void *ctx)
{
    (void)path;
    if (type == ZOO_CHANGED_EVENT && lbl_is(ctx, "W1")) {
        call("zoo_awexists W2 in W1",
             zoo_awexists(zh, "/sr", zkc_watcher, "W2", zkc_stat_cb, "F"));
    }
    if (type == ZOO_SESSION_EVENT && state == ZOO_CONNECTING_STATE &&
        g_in_loss && g_loss_submits == 0) {
        g_loss_submits++;
        zkc_ev("check", "zoo_state in CONNECTING=%s",
               zkc_state(zoo_state(zh)));
        call("zoo_aget in CONNECTING",
             zoo_aget(zh, "/sr", 0, zkc_data_cb, "after-loss"));
    }
}

int main(int argc, char **argv)
{
    const char *px;
    zhandle_t *lz;

    zkc_init(argc, argv, "st_reenter", 1, "EXACT", "ordered");
    zkc_set_watch_hook(watch_hook);
    g_zh = zkc_connect(zkc_host(0), 10000, 0);
    if (g_zh == NULL) {
        return zkc_finish();
    }
    call("create /sr", zkcs_create(g_zh, "/sr", "v", 1, &ZOO_OPEN_ACL_UNSAFE,
                                   ZOO_EPHEMERAL, NULL, 0));

    /* Allow all three responses to arrive in the socket buffer before driving. */
    zkc_set_hook(reenter_hook);
    call("zoo_aget A", zoo_aget(g_zh, "/sr", 0, zkc_data_cb, "A"));
    call("zoo_aexists B", zoo_aexists(g_zh, "/sr", 0, zkc_stat_cb, "B"));
    call("zoo_aexists C", zoo_aexists(g_zh, "/sr", 0, zkc_stat_cb, "C"));
    zkc_sleep_ms(300);
    zkc_await("cb", 5, 10000);

    /* Re-arming a watch inside a watcher callback fires on the subsequent update. */
    call("zoo_aset 1", zoo_aset(g_zh, "/sr", "w", 1, -1, zkc_stat_cb, "S1"));
    zkc_await("watch", 1, 10000);
    zkc_await("cb", 2, 10000);
    call("zoo_aset 2", zoo_aset(g_zh, "/sr", "x", 1, -1, zkc_stat_cb, "S2"));
    zkc_await("watch", 1, 10000);
    zkc_await("cb", 1, 10000);
    zkc_set_hook(NULL);
    zkc_close(g_zh);
    g_zh = NULL;

    /* Connection loss inside zookeeper_process with an in-flight request. */
    px = zkc_proxy_start(zkc_host(0));
    if (px == NULL) {
        return zkc_finish();
    }
    lz = zkc_connect(px, 10000, 0);
    if (lz == NULL) {
        zkc_proxy_stop();
        return zkc_finish();
    }
    call("create /sr on the proxy",
         zkcs_create(lz, "/sr", "v", 1, &ZOO_OPEN_ACL_UNSAFE, ZOO_EPHEMERAL,
                     NULL, 0));
    zkc_proxy_hold(1);
    call("zoo_aget pending", zoo_aget(lz, "/sr", 0, zkc_data_cb, "pending"));
    g_in_loss = 1;
    zkc_proxy_reset();
    zkc_proxy_hold(0);
    zkc_await("session:CONNECTING", 1, 10000);
    zkc_await("cb", 1, 10000);
    zkc_await("session:CONNECTED", 1, 15000);
    zkc_await("cb", 1, 10000);
    g_in_loss = 0;
    zkc_close(lz);
    zkc_proxy_stop();
    return zkc_finish();
}
