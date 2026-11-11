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
 * Concurrent execution of independent single threaded handles across separate
 * threads. Mode: DEV.
 *
 * The legacy single threaded library stores its XID counter and log formatting
 * buffers in process-wide static storage without synchronization, causing data
 * races when two threads drive separate st handles concurrently. The Rust
 * drop-in maintains per-handle XID counters and thread-local log
 * formatting buffers.
 *
 * Each worker thread drives its own st handle with a dedicated select() loop at
 * ZOO_LOG_LEVEL_DEBUG, pipelining zoo_aexists requests for paths tagged with a
 * thread-specific prefix and capturing output via a per-handle log callback.
 *
 * Invariants:
 * - Rust: Neither thread's log callback ever observes a log message
 *   containing the other thread's path prefix, all requests complete with
 *   ZNONODE, and no connection loss occurs.
 * - Legacy C: metrics are recorded without assertion because the
 *   underlying data race is non-deterministic.
 *
 * zkconf-flavors: st
 */

#include "zkconf.h"

#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <sys/select.h>
#include <sys/time.h>

#define NREQ 1500
#define INFLIGHT 16

struct side {
    const char *mine;
    const char *other;
    zhandle_t *zh;
    int sent;
    int done;
    int nonode;
    int lines;
    int foreign;
    int lost;
    int ok;
};

static struct side g_a = {"/ldgA19", "/ldgB19", NULL, 0, 0, 0, 0, 0, 0, 0};
static struct side g_b = {"/ldgB19", "/ldgA19", NULL, 0, 0, 0, 0, 0, 0, 0};

static void count_line(struct side *s, const char *msg)
{
    s->lines++;
    if (strstr(msg, s->other) != NULL) {
        s->foreign++;
    }
}

static void log_a(const char *msg)
{
    count_line(&g_a, msg);
}

static void log_b(const char *msg)
{
    count_line(&g_b, msg);
}

static void side_watcher(zhandle_t *zh, int type, int state,
                         const char *path, void *ctx)
{
    struct side *s = (struct side *)ctx;
    (void)zh;
    (void)path;
    if (type == ZOO_SESSION_EVENT && state == ZOO_CONNECTING_STATE) {
        s->lost++;
    }
}

static void exists_cb(int rc, const struct Stat *st, const void *d)
{
    struct side *s = (struct side *)d;
    (void)st;
    s->done++;
    if (rc == ZNONODE) {
        s->nonode++;
    }
}

static long now_ms(void)
{
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (long)tv.tv_sec * 1000L + (long)(tv.tv_usec / 1000);
}

/* Executes a single iteration of the st event loop (interest -> select -> process). */
static void drive_once(zhandle_t *zh)
{
    int fd = -1;
    int interest = 0;
    int events = 0;
    struct timeval tv;
    fd_set rfds;
    fd_set wfds;

    tv.tv_sec = 0;
    tv.tv_usec = 50000;
    if (zookeeper_interest(zh, &fd, &interest, &tv) != ZOK || fd < 0) {
        zkc_sleep_ms(5);
        return;
    }
    if (tv.tv_sec > 0 || tv.tv_usec > 50000) {
        tv.tv_sec = 0;
        tv.tv_usec = 50000;
    }
    FD_ZERO(&rfds);
    FD_ZERO(&wfds);
    if (interest & ZOOKEEPER_READ) {
        FD_SET(fd, &rfds);
    }
    if (interest & ZOOKEEPER_WRITE) {
        FD_SET(fd, &wfds);
    }
    if (select(fd + 1, &rfds, &wfds, NULL, &tv) > 0) {
        if (FD_ISSET(fd, &rfds)) {
            events |= ZOOKEEPER_READ;
        }
        if (FD_ISSET(fd, &wfds)) {
            events |= ZOOKEEPER_WRITE;
        }
    }
    zookeeper_process(zh, events);
}

static void *side_main(void *arg)
{
    struct side *s = (struct side *)arg;
    long end = now_ms() + 20000;
    char path[64];

    while (zoo_state(s->zh) != ZOO_CONNECTED_STATE && now_ms() < end) {
        drive_once(s->zh);
    }
    s->ok = zoo_state(s->zh) == ZOO_CONNECTED_STATE;
    while (s->ok && s->done < NREQ && now_ms() < end) {
        while (s->sent < NREQ && s->sent - s->done < INFLIGHT) {
            snprintf(path, sizeof(path), "%s/n%d", s->mine, s->sent);
            if (zoo_aexists(s->zh, path, 0, exists_cb, s) != ZOK) {
                s->ok = 0;
                break;
            }
            s->sent++;
        }
        drive_once(s->zh);
    }
    return NULL;
}

int main(int argc, char **argv)
{
    pthread_t ta;
    pthread_t tb;
    int started;
    struct side *sides[2] = {&g_a, &g_b};
    int i;

    zkc_init(argc, argv, "ldg_a_st_threads", 1, "DEV", "ignore");
    g_a.zh = zookeeper_init2(zkc_host(0), side_watcher, 10000, NULL, &g_a, 0,
                             log_a);
    g_b.zh = zookeeper_init2(zkc_host(0), side_watcher, 10000, NULL, &g_b, 0,
                             log_b);
    if (g_a.zh == NULL || g_b.zh == NULL) {
        zkc_fail("zookeeper_init2 failed");
        return zkc_finish();
    }
    zoo_set_debug_level(ZOO_LOG_LEVEL_DEBUG);
    started = pthread_create(&ta, NULL, side_main, &g_a) == 0;
    started = started && pthread_create(&tb, NULL, side_main, &g_b) == 0;
    if (!started) {
        zkc_fail("pthread_create failed");
        return zkc_finish();
    }
    pthread_join(ta, NULL);
    pthread_join(tb, NULL);
    zoo_set_debug_level(ZOO_LOG_LEVEL_ERROR);
    for (i = 0; i < 2; i++) {
        struct side *s = sides[i];
        zkc_dev("DSP-19",
                !zkc_is_rust() || (s->ok && s->done == NREQ &&
                                   s->nonode == NREQ && s->lost == 0 &&
                                   s->foreign == 0 && s->lines > 0),
                "%s: connected=%d done=%d nonode=%d lost=%d lines=%d "
                "foreign=%d", s->mine, s->ok, s->done, s->nonode, s->lost,
                s->lines, s->foreign);
    }
    zookeeper_close(g_a.zh);
    zookeeper_close(g_b.zh);
    return zkc_finish();
}
