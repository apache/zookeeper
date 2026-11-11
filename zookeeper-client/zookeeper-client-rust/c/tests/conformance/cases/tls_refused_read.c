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
 * Refused nonblocking TLS connection handling in single threaded poll loops
 * that map POLLHUP to ZOOKEEPER_READ. Mode: EXACT.
 *
 * This is the TLS counterpart of st_refused_read. A nonblocking TCP connect to
 * the dead endpoint enters state 7 (SSL_CONNECTING_STATE_DEF); polling with
 * POLLIN alone delivers ZOOKEEPER_READ without ZOOKEEPER_WRITE when the
 * connection is refused.
 *
 * Invariants:
 * - In state 7 prior to SSL_connect, receiving ZOOKEEPER_READ without
 *   ZOOKEEPER_WRITE attempts a frame-length read on the underlying TCP socket,
 *   detects the refused connect immediately, logs "failed while receiving a
 *   server response", and returns ZCONNECTIONLOSS from zookeeper_process in a
 *   single pass without stalling until the 2T/3 receive timeout (or fails
 *   synchronously in zookeeper_interest if connect() returns ECONNREFUSED).
 * - On the initial pass through the server list, both libraries advance
 *   immediately to the next endpoint (the live TLS server 0) without a backoff
 *   delay.
 *
 * zkconf-flavors: st
 */

#include "zkconf.h"
#include "zkconf_tls.h"

#include <poll.h>
#include <stdio.h>
#include <sys/time.h>

#define T_MS 6000
/* A spinning client stalls until 2T/3 (4000 ms); a conforming client fails immediately. */
#define REFUSED_MS 1000
#define MAX_CALLS 20

static zhandle_t *g_zh;
static int g_connected;

static void watch_hook(zhandle_t *zh, int type, int state, const char *path,
                       void *ctx)
{
    (void)zh;
    (void)path;
    (void)ctx;
    if (type == ZOO_SESSION_EVENT && state == ZOO_CONNECTED_STATE) {
        g_connected = 1;
    }
}

static const char *bits(int events)
{
    if ((events & ZOOKEEPER_READ) && (events & ZOOKEEPER_WRITE)) {
        return "RW";
    }
    if (events & ZOOKEEPER_READ) {
        return "R";
    }
    if (events & ZOOKEEPER_WRITE) {
        return "W";
    }
    return "0";
}

/*
 * Executes one interest/poll/process iteration. When read_only is set, only
 * POLLIN is requested from poll() and POLLIN | POLLHUP | POLLERR map to
 * ZOOKEEPER_READ. Records any return code other than ZOK or ZNOTHING along with
 * the pre-call handle state.
 */
static int pass(int read_only, int *calls)
{
    int fd = -1;
    int interest = 0;
    struct timeval tv = {0, 0};
    struct pollfd p;
    int events = 0;
    int before;
    long wait;
    int rc = zkc_interest(g_zh, &fd, &interest, &tv);
    (*calls)++;
    if (rc != ZOK) {
        zkc_ev("interest", "rc=%s state=%s", zkc_rc(rc),
               zkc_state(zoo_state(g_zh)));
        return rc;
    }
    wait = (long)tv.tv_sec * 1000L + (long)(tv.tv_usec / 1000);
    if (wait > 100 || wait < 0) {
        wait = 100;
    }
    if (fd < 0) {
        zkc_sleep_ms((int)wait);
        return ZOK;
    }
    p.fd = fd;
    p.events = 0;
    p.revents = 0;
    if (interest & ZOOKEEPER_READ) {
        p.events |= POLLIN;
    }
    if (!read_only && (interest & ZOOKEEPER_WRITE)) {
        p.events |= POLLOUT;
    }
    if (poll(&p, 1, (int)wait) > 0) {
        if (p.revents & (POLLIN | POLLHUP | POLLERR)) {
            events |= ZOOKEEPER_READ;
        }
        if (p.revents & POLLOUT) {
            events |= ZOOKEEPER_WRITE;
        }
    }
    if (events == 0) {
        return ZOK;
    }
    before = zoo_state(g_zh);
    rc = zkc_process(g_zh, events);
    if (rc != ZOK && rc != ZNOTHING) {
        zkc_ev("process", "events=%s rc=%s state=%s -> %s", bits(events),
               zkc_rc(rc), zkc_state(before), zkc_state(zoo_state(g_zh)));
    }
    return rc;
}

int main(int argc, char **argv)
{
    char cert[4096];
    char hosts[512];
    const char *c;
    int calls = 0;
    int ended = 0;
    long start;
    long elapsed;
    long deadline;

    zkc_init(argc, argv, "tls_refused_read", 1, "EXACT", "ignore");
    if (zkt_setup(1) != 0) {
        return zkc_finish();
    }
    zkc_set_watch_hook(watch_hook);
    zoo_deterministic_conn_order(1);
    c = zkt_certs();
    snprintf(cert, sizeof(cert),
             "%s/server.crt,%s/client.crt,%s/clientkey.pem,password", c, c, c);
    snprintf(hosts, sizeof(hosts), "%s,%s", zkc_dead(), zkt_host(0));
    zkt_log_reset();
    g_zh = zkt_init(hosts, cert, T_MS, NULL, "refused", 0);
    if (g_zh == NULL) {
        return zkc_finish();
    }
    /* Detach from the shared poll registry so the local pass() loop drives g_zh. */
    zkc_poll_remove(g_zh);

    /* Deliver ZOOKEEPER_READ alone while connecting to the dead endpoint. */
    start = zkt_now_ms();
    while (zkt_now_ms() - start < 3 * REFUSED_MS / 2) {
        int rc = pass(1, &calls);
        if (rc != ZOK && rc != ZNOTHING) {
            ended = 1;
            break;
        }
    }
    elapsed = zkt_now_ms() - start;
    zkc_ev("check", "refused attempt ended: %s", ended ? "yes" : "no");
    zkc_ev("check", "ended within %d ms: %s", REFUSED_MS,
           ended && elapsed <= REFUSED_MS ? "yes" : "no");
    zkc_ev("check", "interest calls <= %d: %s", MAX_CALLS,
           calls <= MAX_CALLS ? "yes" : "no");

    /* Failover to the live TLS server proceeds immediately without backoff. */
    deadline = zkt_now_ms() + 5000;
    while (!g_connected && zkt_now_ms() < deadline) {
        pass(0, &calls);
    }
    zkc_ev("check", "connected to the next server: %s",
           g_connected ? "yes" : "no");

    zkc_close(g_zh);
    g_zh = NULL;
    zkt_log_record("refused", "failed while receiving", 0);
    zkt_log_record("refused", "timed out", 1);
    return zkc_finish();
}
