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
 * Refused nonblocking TCP connect handling in single threaded poll loops that
 * map POLLHUP to ZOOKEEPER_READ. Mode: EXACT.
 *
 * Event loops frequently map POLLIN | POLLHUP | POLLERR to ZOOKEEPER_READ and
 * only POLLOUT to ZOOKEEPER_WRITE. On macOS, a refused nonblocking connect
 * wakes poll() with POLLIN | POLLHUP without setting POLLOUT. This test polls
 * with POLLIN only during the initial connection attempt so both libraries
 * receive ZOOKEEPER_READ alone while the TCP connect is pending.
 *
 * Invariants:
 * - Receiving ZOOKEEPER_READ without ZOOKEEPER_WRITE during an
 *   in-progress TCP connect attempts a frame-length read, detects the refused
 *   connection immediately, logs "failed while receiving a server response",
 *   and returns ZCONNECTIONLOSS from zookeeper_process in a single pass
 *   without spinning until the 2T/3 connect timeout (or fails synchronously in
 *   zookeeper_interest if connect() itself returns ECONNREFUSED).
 * - The initial failed attempt on a new handle has not exhausted
 *   the server list, both libraries advance immediately to the next endpoint
 *   (server 0) without a backoff delay.
 *
 * zkconf-flavors: st
 */

#include "zkconf.h"

#include <poll.h>
#include <stdio.h>
#include <sys/time.h>

#define T_MS 6000
/* A spinning client stalls until 2T/3 (4000 ms); a conforming client fails immediately. */
#define REFUSED_MS 1000
#define MAX_CALLS 20

static zhandle_t *g_zh;
static int g_connected;

static long now_ms(void)
{
    struct timeval now;
    gettimeofday(&now, NULL);
    return (long)now.tv_sec * 1000L + (long)(now.tv_usec / 1000);
}

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
 * ZOOKEEPER_READ. Records any return code other than ZOK or ZNOTHING.
 */
static int pass(int read_only, int *calls)
{
    int fd = -1;
    int interest = 0;
    struct timeval tv = {0, 0};
    struct pollfd p;
    int events = 0;
    long wait;
    int rc = zkc_interest(g_zh, &fd, &interest, &tv);
    (*calls)++;
    if (rc != ZOK) {
        zkc_ev("interest", "rc=%s state=%s", zkc_rc(rc),
               zkc_state(zoo_state(g_zh)));
        return rc;
    }
    wait = (long)tv.tv_sec * 1000L + (long)(tv.tv_usec / 1000);
    if (wait > 100) {
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
    rc = zkc_process(g_zh, events);
    if (rc != ZOK && rc != ZNOTHING) {
        zkc_ev("process", "events=%s rc=%s state=%s", bits(events),
               zkc_rc(rc), zkc_state(zoo_state(g_zh)));
    }
    return rc;
}

int main(int argc, char **argv)
{
    char hosts[256];
    int calls = 0;
    int ended = 0;
    long start;
    long elapsed;
    long deadline;

    zkc_init(argc, argv, "st_refused_read", 1, "EXACT", "ordered");
    zkc_set_watch_hook(watch_hook);
    zoo_deterministic_conn_order(1);
    snprintf(hosts, sizeof(hosts), "%s,%s", zkc_dead(), zkc_host(0));
    g_zh = zookeeper_init2(hosts, zkc_watcher, T_MS, NULL, "refused", 0,
                           zkc_log_cb);
    zkc_ev("call", "zookeeper_init2 -> %s", g_zh ? "zh" : "NULL");
    if (g_zh == NULL) {
        return zkc_finish();
    }

    /* Deliver ZOOKEEPER_READ alone while connecting to the dead endpoint. */
    start = now_ms();
    while (now_ms() - start < 3 * REFUSED_MS / 2) {
        int rc = pass(1, &calls);
        if (rc != ZOK && rc != ZNOTHING) {
            ended = 1;
            break;
        }
    }
    elapsed = now_ms() - start;
    zkc_ev("check", "refused attempt ended: %s", ended ? "yes" : "no");
    zkc_ev("check", "ended within %d ms: %s", REFUSED_MS,
           ended && elapsed <= REFUSED_MS ? "yes" : "no");
    zkc_ev("check", "interest calls <= %d: %s", MAX_CALLS,
           calls <= MAX_CALLS ? "yes" : "no");

    /* Failover to the live server proceeds immediately without backoff. */
    deadline = now_ms() + 5000;
    while (!g_connected && now_ms() < deadline) {
        pass(0, &calls);
    }
    zkc_ev("check", "connected to the next server: %s",
           g_connected ? "yes" : "no");

    zkc_in_call(1);
    zkc_ev("call", "zookeeper_close -> %s", zkc_rc(zookeeper_close(g_zh)));
    zkc_in_call(0);
    g_zh = NULL;
    return zkc_finish();
}
