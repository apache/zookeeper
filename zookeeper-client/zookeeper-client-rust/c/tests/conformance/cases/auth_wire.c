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
 * Wire-level digest authentication framing and completion correlation.
 * Mode: PER-ROLE.
 *
 * The handle connects through the wire-capture proxy (zkconf_wire.h), which
 * records outbound request frames and gates inbound server responses.
 *
 * Timing considerations: in the legacy mt library, calling zoo_add_auth on a connected handle
 * does not wake the I/O thread. Transmission of the AUTH frame is deferred until the next I/O
 * wakeup (at latest the T/3 ping timer, 5 s for T = 15 s), so waits for outbound AUTH frames
 * and completions allow up to 10 s. The Rust core wakes its I/O loop immediately. While non-
 * ping responses are held by the proxy, ping responses pass through and refresh the client's
 * receive timestamp so the 2T/3 receive timeout does not expire
 
 * Invariants:
 * - Upon reconnection, all registered credentials are re-sent ahead of
 *   the SetWatches frame and queued application requests.
 * - Ealling zoo_add_auth while in ZOO_ASSOCIATING_STATE without SASL enqueues an AUTH packet at
 *   the head of outgoing_queue in addition to registering the credential in auth_h. After the
 *   connect response arrives, send_auth_info prepends the auth list and SetWatches ahead of that
 *   queued packet, transmitting the new credential twice.
 * - The legacy core drains and completes all active auth callbacks upon receiving a single AUTH
 *   response, whereas the Rust core correlates and completes each auth callback one-for-one in
 *   wire order.
 * - When a valid digest auth is followed by an auth with an unrecognized scheme ("bogus") and
 *   both responses arrive prior to socket closure, the legacy core completes both callbacks
 *   with ZOK on the first response before transitioning to ZOO_AUTH_FAILED_STATE, whereas
 *   the Rust core completes the first with ZOK and the second with ZAUTHFAILED.
 * - When a valid digest auth and a rejected auth are registered while waiting for the connect
 *   response, reconnect replay sends them in LIFO order (rejected auth first). The legacy core
 *   completes both callbacks in list order upon the single ZAUTHFAILED response, whereas the
 *   Rust core completes the rejected auth first on its response and drains the
 *   remaining auth during AUTH_FAILED teardown.
 * - ZOOKEEPER-800: in the single threaded library, calling zoo_add_auth during
 *   the transient state-0 window following connection loss (before the next
 *   zookeeper_interest call transitions to ZOO_CONNECTING_STATE) returns
 *   ZINVALIDSTATE.
 *
 * Because the Rust mt core wakes its I/O loop immediately on each zoo_add_auth
 * call, two back-to-back calls may either be flushed individually in FIFO
 * order or coalesced in LIFO prepend order; the shared trace canonicalizes the
 * two frames in sorted order while DEV assertions verify per-core behavior.
 *
 * zkconf-flavors: mt st
 */

#include "zkconf.h"
#include "zkconf_wire.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static volatile int g_auth;
static volatile int g_exists;

static void auth_cb(int rc, const void *d)
{
    zkc_void_cb(rc, d);
    __atomic_fetch_add(&g_auth, 1, __ATOMIC_SEQ_CST);
}

static void exists_cb(int rc, const struct Stat *s, const void *d)
{
    zkc_stat_cb(rc, s, d);
    __atomic_fetch_add(&g_exists, 1, __ATOMIC_SEQ_CST);
}

static int count(const volatile int *v)
{
    return __atomic_load_n(v, __ATOMIC_SEQ_CST);
}

/*
 * State tracking for per-core auth completion checks. Completions are recorded
 * in memory rather than emitted to the shared trace because completion ordering
 * differs between legacy and Rust cores.
 */
struct dev_auth {
    const char *cred;
    int rc;
    int done;
    int seq;
};

#define DEV_AUTHS 6

static struct dev_auth g_dev[DEV_AUTHS] = {
    {"b1:p", 0, 0, 0},
    {"b2:p", 0, 0, 0},
    {"c1:p", 0, 0, 0},
    {"c2:p", 0, 0, 0},
    {"e1:p", 0, 0, 0},
    {"e2:p", 0, 0, 0},
};

/* Monotonic sequence counter for per-core auth completions. */
static volatile int g_dev_seq;

static void dev_auth_cb(int rc, const void *d)
{
    int i;
    for (i = 0; i < DEV_AUTHS; i++) {
        if (d == (const void *)&g_dev[i]) {
            int seq = __atomic_add_fetch(&g_dev_seq, 1, __ATOMIC_SEQ_CST);
            __atomic_store_n(&g_dev[i].seq, seq, __ATOMIC_SEQ_CST);
            __atomic_store_n(&g_dev[i].rc, rc, __ATOMIC_SEQ_CST);
            __atomic_fetch_add(&g_dev[i].done, 1, __ATOMIC_SEQ_CST);
        }
    }
    __atomic_fetch_add(&g_auth, 1, __ATOMIC_SEQ_CST);
}

static void dev_add_auth(zhandle_t *zh, const char *scheme, int i)
{
    const char *cred = g_dev[i].cred;
    int rc = zoo_add_auth(zh, scheme, cred, (int)strlen(cred), dev_auth_cb,
                          &g_dev[i]);
    zkc_ev("call", "zoo_add_auth %s %s -> %s", scheme, cred, zkc_rc(rc));
}

static int dev_done(int i)
{
    return __atomic_load_n(&g_dev[i].done, __ATOMIC_SEQ_CST);
}

static int dev_rc(int i)
{
    return __atomic_load_n(&g_dev[i].rc, __ATOMIC_SEQ_CST);
}

static int dev_seq(int i)
{
    return __atomic_load_n(&g_dev[i].seq, __ATOMIC_SEQ_CST);
}

static void show_wire(const char *when)
{
    char buf[ZKW_LABEL_CAP];
    zkc_ev("wire", "%s: %s", when, zkw_labels(buf, sizeof buf));
}

static int cmp_label(const void *a, const void *b)
{
    return strcmp(*(const char *const *)a, *(const char *const *)b);
}

/* Emits captured wire frame labels in lexicographical order. */
static void show_wire_sorted(const char *when)
{
    char buf[ZKW_LABEL_CAP];
    char out[ZKW_LABEL_CAP];
    char *words[64];
    char *save = NULL;
    char *w;
    size_t n = 0;
    size_t i;
    size_t used = 0;

    zkw_labels(buf, sizeof buf);
    for (w = strtok_r(buf, " ", &save); w != NULL && n < 64;
         w = strtok_r(NULL, " ", &save)) {
        words[n++] = w;
    }
    qsort(words, n, sizeof words[0], cmp_label);
    out[0] = '\0';
    for (i = 0; i < n; i++) {
        int k = snprintf(out + used, sizeof out - used, "%s%s",
                         i > 0 ? " " : "", words[i]);
        if (k < 0 || (size_t)k >= sizeof out - used) {
            break;
        }
        used += (size_t)k;
    }
    zkc_ev("wire", "%s (sorted): %s", when, out);
}

static void add_auth(zhandle_t *zh, const char *cred)
{
    int rc = zoo_add_auth(zh, "digest", cred, (int)strlen(cred), auth_cb, cred);
    zkc_ev("call", "zoo_add_auth %s -> %s", cred, zkc_rc(rc));
}

static void aexists(zhandle_t *zh, const char *label, int watch)
{
    int rc = zoo_aexists(zh, "/zkconf-auth-wire-none", watch, exists_cb, label);
    zkc_ev("call", "zoo_aexists %s -> %s", label, zkc_rc(rc));
}

#ifndef THREADED
/*
 * ZOOKEEPER-800: handle_error resets zh->state to 0 until the next
 * zookeeper_interest call transitions it to ZOO_CONNECTING_STATE
 * (zookeeper.c:2116-2118). In st builds, the session watcher is invoked
 * immediately inside handle_error while state is still 0.
 */
static void state_zero_gap(zhandle_t *zh)
{
    int state;
    int rc;
    int base;
    zkw_clear();
    zkw_reset();
    zkc_await("session:CONNECTING@wire", 1, 10000);
    state = zoo_state(zh);
    rc = zoo_add_auth(zh, "digest", "gap:p", 5, auth_cb, "gap");
    zkc_ev("call", "zoo_add_auth gap:p state=%d -> %s", state, zkc_rc(rc));
    zkc_dev("RC-08", state == 0 && rc == ZINVALIDSTATE,
            "ZOOKEEPER-800: state=%d rc=%d", state, rc);
    zkc_await("session:CONNECTED@wire", 1, 10000);
    /* Wait for replayed auth frames and SetWatches to flush post-reconnect. */
    if (zkw_wait_count("W", 1, 5000) != 0) {
        zkc_fail("no SetWatches after the reconnect");
    }
    /*
     * Fence all in-flight replayed AUTH responses with a round-trip exists
     * request so that late responses from this reconnect cannot interfere with
     * subsequent auth completion ordering checks.
     */
    base = count(&g_exists);
    aexists(zh, "gap", 0);
    if (zkw_wait_int(&g_exists, base + 1, 10000) != 0) {
        zkc_fail("no reply after the resent auths");
    }
}
#endif

/* Frame ordering across reconnect with zoo_add_auth during ASSOCIATING. */
static void reconnect_order(zhandle_t *zh)
{
    zkw_clear();
    zkw_budget(0);
    zkw_reset();
    zkc_await("session:CONNECTING@wire", 1, 10000);
    if (zkw_wait_count("K", 1, 10000) != 0) {
        zkc_fail("no reconnect through the proxy");
        return;
    }
    zkc_ev("ret", "held state=%s", zkc_state(zoo_state(zh)));
    aexists(zh, "q1", 0);
    aexists(zh, "q2", 0);
    add_auth(zh, "a3:p");
    /* Verify that no application or auth frames precede the connect reply. */
    (void)zkw_wait_count("A:a3", 1, 2000);
    show_wire("associating");

    zkw_budget(-1);
    zkc_await("session:CONNECTED@wire", 1, 10000);
    if (zkw_wait_int(&g_auth, 3, 10000) != 0 ||
        zkw_wait_int(&g_exists, 3, 10000) != 0 ||
        zkw_wait_count("E", 2, 10000) != 0) {
        zkc_fail("the reconnect did not complete the queued work");
    }
    show_wire("reconnected");
}

/*
 * Submits a valid digest auth followed by an unrecognized scheme ("bogus")
 * while holding responses at the proxy until both requests reach the server.
 */
static void rejected_second(const char *px)
{
    zhandle_t *zh;
    int ok;
    zkw_budget(-1);
    zh = zookeeper_init2(px, zkc_watcher, 15000, NULL, "rej", 0, zkc_log_cb);
    zkc_ev("call", "zookeeper_init2 rej -> %s", zh != NULL ? "zh" : "NULL");
    if (zh == NULL) {
        return;
    }
    zkc_poll_add(zh);
    if (zkc_await("session:CONNECTED@rej", 1, 15000) != 0) {
        zkc_close(zh);
        return;
    }
    zkw_clear();
    zkw_budget(0);
    dev_add_auth(zh, "digest", 2);
    /* Wait for the first AUTH frame to reach the proxy before queuing the second. */
    if (zkw_wait_count("A:c1", 1, 10000) != 0) {
        zkc_fail("the first auth did not reach the proxy");
    }
    dev_add_auth(zh, "bogus", 3);
    if (zkw_wait_count("A:c2", 1, 10000) != 0) {
        zkc_fail("the second auth did not reach the proxy");
    }
    zkw_budget(-1);
    zkc_await("session:AUTH_FAILED@rej", 1, 10000);
    if (zkw_wait_int(&g_dev[2].done, 1, 10000) != 0 ||
        zkw_wait_int(&g_dev[3].done, 1, 10000) != 0) {
        zkc_fail("an auth did not complete");
    }
    show_wire("rejected second");
    zkc_ev("ret", "zoo_state rej -> %s", zkc_state(zoo_state(zh)));
    if (zkc_is_rust()) {
        ok = dev_rc(2) == ZOK && dev_rc(3) == ZAUTHFAILED;
    } else {
        ok = dev_rc(2) == ZOK && dev_rc(3) == ZOK;
    }
    zkc_dev("DV-FIX-11", ok && dev_done(2) == 1 && dev_done(3) == 1,
            "c1 done=%d rc=%s c2 done=%d rc=%s", dev_done(2),
            zkc_rc(dev_rc(2)), dev_done(3), zkc_rc(dev_rc(3)));
    zkc_close(zh);
}

/*
 * Queues a valid digest auth followed by a rejected scheme while the connect
 * response is held. Because send_auth_info prepends each entry to
 * outgoing_queue, the rejected scheme is transmitted first on the wire.
 */
static void rejected_first(const char *px)
{
    zhandle_t *zh;
    int ok;
    char labels[ZKW_LABEL_CAP];
    zkw_clear();
    zkw_budget(0);
    zh = zookeeper_init2(px, zkc_watcher, 15000, NULL, "fst", 0, zkc_log_cb);
    zkc_ev("call", "zookeeper_init2 fst -> %s", zh != NULL ? "zh" : "NULL");
    if (zh == NULL) {
        zkw_budget(-1);
        return;
    }
    zkc_poll_add(zh);
    if (zkw_wait_count("K", 1, 10000) != 0) {
        zkc_fail("no connect through the proxy");
        zkw_budget(-1);
        zkc_close(zh);
        return;
    }
    dev_add_auth(zh, "digest", 4);
    dev_add_auth(zh, "bogus", 5);
    zkw_budget(-1);
    zkc_await("session:AUTH_FAILED@fst", 1, 10000);
    if (zkw_wait_int(&g_dev[4].done, 1, 10000) != 0 ||
        zkw_wait_int(&g_dev[5].done, 1, 10000) != 0) {
        zkc_fail("an auth did not complete");
    }
    zkc_ev("ret", "zoo_state fst -> %s", zkc_state(zoo_state(zh)));
    ok = dev_done(4) == 1 && dev_done(5) == 1 && dev_rc(4) == ZAUTHFAILED &&
         dev_rc(5) == ZAUTHFAILED;
    if (zkc_is_rust()) {
        ok = ok && dev_seq(5) < dev_seq(4);
    } else {
        ok = ok && dev_seq(4) < dev_seq(5);
    }
    zkc_dev("DV-FIX-11", ok, "e1 seq=%d rc=%s e2 seq=%d rc=%s wire=%s",
            dev_seq(4), zkc_rc(dev_rc(4)), dev_seq(5), zkc_rc(dev_rc(5)),
            zkw_labels(labels, sizeof labels));
    zkc_close(zh);
}

int main(int argc, char **argv)
{
    const char *px;
    zhandle_t *zh;
    int rc;
    int base;
    int n0;
    int n1;
    int first;
    char labels[ZKW_LABEL_CAP];

    zkc_init(argc, argv, "auth_wire", 1, "PER-ROLE", "ignore");
    px = zkw_start(zkc_host(0), -1);
    if (px == NULL) {
        return zkc_finish();
    }
    zh = zookeeper_init2(px, zkc_watcher, 15000, NULL, "wire", 0, zkc_log_cb);
    zkc_ev("call", "zookeeper_init2 proxy -> %s", zh != NULL ? "zh" : "NULL");
    if (zh == NULL) {
        zkw_stop();
        return zkc_finish();
    }
    zkc_poll_add(zh);
    if (zkc_await("session:CONNECTED@wire", 1, 15000) != 0) {
        zkc_close(zh);
        zkw_stop();
        return zkc_finish();
    }
    add_auth(zh, "a1:p");
    (void)zkw_wait_int(&g_auth, 1, 10000);
    add_auth(zh, "a2:p");
    (void)zkw_wait_int(&g_auth, 2, 10000);
    aexists(zh, "w", 1);
    (void)zkw_wait_int(&g_exists, 1, 10000);
    show_wire("connected");

    reconnect_order(zh);
#ifndef THREADED
    state_zero_gap(zh);
#endif

    /*
     * Hold responses, transmit two AUTH requests, and release a single
     * response. The first AUTH request on the wire correlates with the released
     * response.
     */
    base = count(&g_auth);
    zkw_clear();
    zkw_budget(0);
    dev_add_auth(zh, "digest", 0);
    dev_add_auth(zh, "digest", 1);
    /* Legacy mt may defer flushing the AUTH frames until the T/3 ping timer. */
    if (zkw_wait_count("A:b", 2, 10000) != 0) {
        zkc_fail("the auth frames did not reach the proxy");
    }
    n0 = count(&g_auth) - base;
    zkc_ev("ret", "auth completions held=%d", n0);
    first = strstr(zkw_labels(labels, sizeof labels), "A:b2") == labels ? 1 : 0;
    zkw_budget(1);
    /*
     * Releasing one response completes one callback in Rust and both callbacks
     * in a single pass in legacy C.
     */
    (void)zkw_wait_int(&g_auth, base + 1, 10000);
    (void)zkw_wait_int(&g_auth, base + 2, 2000);
    n1 = count(&g_auth) - base;
    if (zkc_is_rust()) {
        zkc_dev("DV-FIX-11",
                n0 == 0 && n1 == 1 && dev_done(first) == 1 &&
                    dev_rc(first) == ZOK,
                "held=%d after one reply=%d first=b%d done=%d", n0, n1,
                first + 1, dev_done(first));
    } else {
        zkc_dev("DV-FIX-11", n0 == 0 && n1 == 2,
                "held=%d after one reply=%d first=b%d", n0, n1, first + 1);
    }
    zkw_budget(-1);
    (void)zkw_wait_int(&g_auth, base + 2, 10000);
    show_wire_sorted("two auths");
    zkc_dev("DV-FIX-11",
            dev_done(0) == 1 && dev_done(1) == 1 && dev_rc(0) == ZOK &&
                dev_rc(1) == ZOK,
            "b1 done=%d rc=%s b2 done=%d rc=%s", dev_done(0),
            zkc_rc(dev_rc(0)), dev_done(1), zkc_rc(dev_rc(1)));

    zkc_close(zh);
    rc = count(&g_auth) - base;
    zkc_ev("ret", "auth completions total=%d", rc);

    rejected_second(px);
    rejected_first(px);
    zkw_stop();
    return zkc_finish();
}
