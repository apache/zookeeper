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
 * Handle shutdown with pending asynchronous requests.
 * Mode: PER-ROLE.
 *
 * The TCP proxy holds server responses and waits for a
 * settle window (100 ms or 300 ms) so the I/O thread parks in
 * poll() before zookeeper_close drains pending requests with ZCLOSING. Without
 * holding server responses, the legacy core can race and drop completions
 * during close.
 *
 * Invariants:
 * - Zookeeper_close(NULL) returns ZBADARGUMENTS; closing a valid mt handle
 *   returns ZOK.
 * - When server responses are held, calling zookeeper_close from the
 *   application thread with five in-flight requests drains all five
 *   completions on the completion thread with ZCLOSING in submission
 *   order before zookeeper_close returns, without emitting a session event.
 * - Invoking zookeeper_close from inside a completion callback returns
 *   ZOK immediately without self-joining, and remaining queued completions
 *   drain after the current callback returns.
 * - Submitting an asynchronous request after close has been initiated
 *   from a completion callback returns ZMARSHALLINGERROR.
 * - Closing a handle whose session handshake has not yet completed
 *   returns ZOK and drains all queued requests with ZCLOSING prior to return.
 * - Library worker threads terminate and the process thread count
 *   returns to baseline after zookeeper_close completes.
 *
 * zkconf-skip-st: it checks mt library threads; st_close_cb covers st
 */

#include "zkconf.h"

#include <pthread.h>
#include <string.h>

static zhandle_t *g_zh;
static int g_done;
static int g_bad;
static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;

/* SET-mode completion callback verifying that rc belongs to the allowed set. */
static void set_cb(int rc, const char *value, const void *d)
{
    const char *allowed = (const char *)d;
    char name[64];
    (void)value;
    snprintf(name, sizeof(name), ",%s,", zkc_rc(rc));
    pthread_mutex_lock(&g_mu);
    g_done++;
    if (strstr(allowed, name) == NULL) {
        g_bad++;
        zkc_fail("rc %s not in %s", zkc_rc(rc), allowed);
    }
    pthread_mutex_unlock(&g_mu);
}

static void closer_hook(const char *kind, int rc, const void *d)
{
    (void)kind;
    (void)rc;
    if (d != NULL && strcmp((const char *)d, "closer") == 0) {
        int crc = zookeeper_close(g_zh);
        int arc;
        zkc_ev("call", "zookeeper_close from completion -> %s", zkc_rc(crc));
        arc = zoo_aexists(g_zh, "/", 0, zkc_stat_cb, "after-close");
        zkc_ev("call", "zoo_aexists after close request -> %s", zkc_rc(arc));
    }
}

/* Wire frame length in bytes of a zoo_async("/") request including length prefix. */
#define SYNC_FRAME_BYTES 17

static int g_held;

/*
 * Completion callback for proxy-held requests. When the final held request
 * ("p4") completes, the proxy hold is released so the close sequence does not
 * block waiting for the CLOSE response timeout.
 */
static void held_cb(int rc, const char *value, const void *d)
{
    zkc_string_cb(rc, value, d);
    pthread_mutex_lock(&g_mu);
    g_held++;
    pthread_mutex_unlock(&g_mu);
    if (d != NULL && strcmp((const char *)d, "p4") == 0) {
        zkc_proxy_hold(0);
    }
}

/*
 * Waits until the proxy has accepted the expected number of connections and
 * received the expected upstream byte count, then sleeps briefly so the I/O
 * thread parks in poll() before zookeeper_close is invoked.
 */
static void await_io_idle(int accepted, long up_bytes, const char *what)
{
    int i;
    for (i = 0; i < 500; i++) {
        if (zkc_proxy_accepted() >= accepted &&
            zkc_proxy_up_bytes() >= up_bytes) {
            zkc_sleep_ms(100);
            return;
        }
        zkc_sleep_ms(10);
    }
    zkc_fail("the proxy did not get %s", what);
}

static int held_count(void)
{
    int n;
    pthread_mutex_lock(&g_mu);
    n = g_held;
    pthread_mutex_unlock(&g_mu);
    return n;
}

static int done_count(void)
{
    int n;
    pthread_mutex_lock(&g_mu);
    n = g_done;
    g_done = 0;
    pthread_mutex_unlock(&g_mu);
    return n;
}

int main(int argc, char **argv)
{
    static const char *const ok_closing = ",ZOK,ZCLOSING,";
    static const char *const held[5] = {"p0", "p1", "p2", "p3", "p4"};
    static const char *const conn[3] = {"c0", "c1", "c2"};
    const char *px;
    zhandle_t *zh;
    long up;
    int base;
    int acc;
    int t0;
    int i;
    int rc;

    zkc_init(argc, argv, "close", 1, "PER-ROLE", "ordered");
    zkc_ev("call", "zookeeper_close(NULL) -> %s",
           zkc_rc(zookeeper_close(NULL)));
    px = zkc_proxy_start(zkc_host(0));
    if (px == NULL) {
        return zkc_finish();
    }
    t0 = zkc_threads();

    /* Close from the application thread with five requests pending. */
    zh = zkc_connect(px, 10000, 0);
    if (zh == NULL) {
        return zkc_finish();
    }
    zkc_proxy_hold(1);
    up = zkc_proxy_up_bytes();
    for (i = 0; i < 5; i++) {
        rc = zoo_async(zh, "/", held_cb, held[i]);
        if (rc != ZOK) {
            zkc_fail("zoo_async %d -> %s", i, zkc_rc(rc));
        }
    }
    await_io_idle(zkc_proxy_accepted(), up + 5 * SYNC_FRAME_BYTES,
                  "the five requests");
    rc = zookeeper_close(zh);
    zkc_ev("call",
           "zookeeper_close with 5 pending -> %s; done before return=%d",
           zkc_rc(rc), held_count());
    zkc_ev("check", "session events after close: %d",
           zkc_await_none("session:CONNECTING", 200));
    zkc_sleep_ms(200);
    zkc_ev("check", "threads after close: %d", zkc_threads() - t0);

    /* Re-entrant close invoked from a completion callback. */
    zh = zkc_connect(zkc_host(0), 10000, 0);
    if (zh == NULL) {
        return zkc_finish();
    }
    g_zh = zh;
    zkc_set_hook(closer_hook);
    rc = zoo_aexists(zh, "/", 0, zkc_stat_cb, "closer");
    zkc_ev("call", "zoo_aexists closer -> %s", zkc_rc(rc));
    for (i = 0; i < 3; i++) {
        rc = zoo_async(zh, "/", set_cb, ok_closing);
        if (rc != ZOK) {
            zkc_fail("zoo_async %d -> %s", i, zkc_rc(rc));
        }
    }
    zkc_await("cb", 1, 10000);
    for (i = 0; i < 100 && zkc_threads() - t0 > 0; i++) {
        zkc_sleep_ms(50);
    }
    zkc_ev("check", "set completions after close from completion: %d",
           done_count());
    zkc_ev("check", "threads after close from completion: %d",
           zkc_threads() - t0);
    zkc_set_hook(NULL);

    /* Close a handle that never completes its handshake. */
    zkc_proxy_hold(1);
    base = held_count();
    up = zkc_proxy_up_bytes();
    acc = zkc_proxy_accepted();
    zh = zookeeper_init2(px, zkc_watcher, 10000, NULL, "connecting", 0,
                         zkc_log_cb);
    zkc_ev("call", "zookeeper_init2 connecting -> %s", zh ? "zh" : "NULL");
    if (zh != NULL) {
        await_io_idle(acc + 1, up + 1, "the connect request");
        for (i = 0; i < 3; i++) {
            rc = zoo_async(zh, "/", held_cb, conn[i]);
            if (rc != ZOK) {
                zkc_fail("zoo_async connecting %d -> %s", i, zkc_rc(rc));
            }
        }
        /*
         * Each submission wakes the I/O thread; allow a fixed settle window
         * for it to park in poll() before invoking zookeeper_close.
         */
        zkc_sleep_ms(300);
        rc = zookeeper_close(zh);
        zkc_ev("call",
               "zookeeper_close connecting -> %s; done before return=%d",
               zkc_rc(rc), held_count() - base);
        zkc_ev("check", "session events: %d",
               zkc_await_none("watch", 200) +
                   zkc_await_none("session:CONNECTING", 1));
    }
    zkc_proxy_hold(0);
    zkc_sleep_ms(200);
    zkc_ev("check", "threads at end: %d", zkc_threads() - t0);
    zkc_ev("check", "set failures: %d", g_bad);
    zkc_proxy_stop();
    return zkc_finish();
}
