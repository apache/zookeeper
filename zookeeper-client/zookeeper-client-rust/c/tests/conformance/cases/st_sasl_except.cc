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
 * C++ exception propagation from a single threaded SASL password callback.
 * Mode: EXACT.
 *
 * Because the legacy C library is not exception-safe across stack unwinding,
 * the shared trace verifies only that the exception unwinds to the enclosing
 * catch block and that a second independent handle in the same process can
 * authenticate afterwards; subsequent recovery of the first handle is verified
 * via a DEV assertion.
 *
 * Invariants and deviations:
 * - Callback-frame invariant: the application SASL_CB_PASS callback
 *   executes on the application thread inside zookeeper_process with no Rust
 *   frames on the call stack between the callback and the C API entry point, so
 *   a C++ exception unwinds cleanly to the catch block surrounding
 *   zookeeper_process.
 * - Lock release invariant: no internal library locks remain held after stack
 *   unwinding, allowing a second handle in the same process to complete SASL
 *   authentication.
 * - Recovery: on the Rust drop-in, the interrupted SASL exchange times
 *   out after the receive timeout, disposes the stale Cyrus connection on
 *   reconnect, and establishes a session.
 *
 * zkconf-flavors: st
 */

#include "zkconf_sasl.h"

#include <cstring>
#include <stdexcept>
#include <sys/select.h>
#include <sys/time.h>

#ifdef HAVE_CYRUS_SASL_H

static int g_arm;
static int g_thrown;
static int g_h1_connected;

static long now_ms()
{
    struct timeval now;
    gettimeofday(&now, NULL);
    return (long)now.tv_sec * 1000L + (long)(now.tv_usec / 1000);
}

/* Application SASL_CB_PASS callback that throws std::runtime_error once when armed. */
static int throwing_pass(sasl_conn_t *conn, void *context, int id,
                         sasl_secret_t **psecret)
{
    static unsigned char
        storage[sizeof(sasl_secret_t) + sizeof ZKCS_SASL_PASSWORD];
    sasl_secret_t *secret = reinterpret_cast<sasl_secret_t *>(storage);
    (void)conn;
    (void)context;
    (void)id;
    if (g_arm) {
        g_arm = 0;
        g_thrown++;
        zkc_frame_check("sasl-pass");
        throw std::runtime_error("sasl-pass");
    }
    if (psecret == NULL) {
        return SASL_BADPARAM;
    }
    secret->len = sizeof ZKCS_SASL_PASSWORD - 1;
    std::memcpy(secret->data, ZKCS_SASL_PASSWORD, sizeof ZKCS_SASL_PASSWORD);
    *psecret = secret;
    return SASL_OK;
}

/* Counts CONNECTED session events on the primary handle without emitting trace events. */
static void quiet_watcher(zhandle_t *zh, int type, int state, const char *path,
                          void *ctx)
{
    (void)zh;
    (void)path;
    (void)ctx;
    if (type == ZOO_SESSION_EVENT && state == ZOO_CONNECTED_STATE) {
        g_h1_connected++;
    }
}

/*
 * Executes one st event-loop iteration on zh with a try/catch block around
 * zookeeper_process; returns 1 if an exception was caught.
 */
static int drive_once(zhandle_t *zh)
{
    int fd = -1;
    int interest = 0;
    struct timeval tv = {0, 0};
    fd_set rd;
    fd_set wr;
    int events = 0;
    if (zkc_interest(zh, &fd, &interest, &tv) != ZOK || fd < 0) {
        zkc_sleep_ms(10);
        return 0;
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
    if (select(fd + 1, &rd, &wr, NULL, &tv) < 0) {
        return 0;
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
    return 0;
}

static void run()
{
    sasl_callback_t cb1[4];
    sasl_callback_t cb2[4];
    zhandle_t *h1;
    zhandle_t *h2;
    long deadline;
    int caught = 0;

    zkcs_app_array(cb1, throwing_pass, NULL);
    zkcs_app_array(cb2, throwing_pass, NULL);
    /* Use a 4 s session timeout (the server minimum) so receive timeout recovery is prompt. */
    h1 = zkcs_sasl_init(zkc_host(0), "h1", cb1, quiet_watcher, NULL, 4000);
    zkc_ev("call", "zookeeper_init_sasl h1 -> %s", h1 != NULL ? "zh" : "NULL");
    if (h1 == NULL) {
        return;
    }
    g_arm = 1;
    deadline = now_ms() + 15000;
    while (!caught && now_ms() < deadline) {
        caught = drive_once(h1);
    }
    if (!caught) {
        zkc_fail("no exception around zookeeper_process");
        return;
    }
    zkc_ev("ret", "thrown=%d", g_thrown);

    /* Verify that a second handle can authenticate after h1 unwinds. */
    h2 = zkcs_sasl_init(zkc_host(0), "h2", cb2, zkc_watcher, NULL, 10000);
    zkc_ev("call", "zookeeper_init_sasl h2 -> %s", h2 != NULL ? "zh" : "NULL");
    if (h2 == NULL) {
        return;
    }
    zkc_poll_add(h2);
    zkc_await("session:CONNECTED@h2", 1, 15000);
    zkc_close(h2);

    /* Drive h1 for up to 20 s to verify post-exception session recovery on Rust. */
    deadline = now_ms() + 20000;
    while (g_h1_connected == 0 && now_ms() < deadline) {
        if (drive_once(h1)) {
            zkc_fail("a second exception from h1");
            return;
        }
    }
    if (zkc_is_rust()) {
        zkc_dev("DSP-14", g_h1_connected > 0,
                "SASL-THROW: rust h1 connected=%d", g_h1_connected);
    } else {
        zkc_dev("DSP-14", 1,
                "SASL-THROW: legacy h1 connected=%d (recorded only)",
                g_h1_connected);
    }
    if (g_h1_connected > 0) {
        zkc_in_call(1);
        (void)zookeeper_close(h1);
        zkc_in_call(0);
    }
}
#endif

int main(int argc, char **argv)
{
    zkc_init(argc, argv, "st_sasl_except", 1, "EXACT", "ignore");
#ifdef HAVE_CYRUS_SASL_H
    if (zkcs_sasl_resolve() == 0) {
        run();
        return zkc_finish();
    }
#endif
    zkc_ev("check", "skip: no SASL library or no SASL server");
    return zkc_finish();
}
