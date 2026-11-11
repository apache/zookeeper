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
 * single threaded handle operation and shutdown across fork() (zookeeper.c
 * zookeeper_close). Mode: EXACT.
 *
 * Unlike multithreaded handles, single threaded handles have no
 * background worker threads or PID guard in zookeeper_close, allowing a daemon
 * process to establish a session, fork(), and continue driving and closing the
 * handle in the child process.
 *
 * Invariants:
 * - The child process creates an ephemeral znode and invokes zookeeper_close
 *   with five pending requests, each of which drains with ZCLOSING.
 * - The child's zookeeper_close transmits CLOSE_OP and terminates the session,
 *   removing the ephemeral znode on the server.
 *
 * zkconf-flavors: st
 */

#include "zkconf.h"

#include <poll.h>
#include <stdio.h>

#define FORK_PATH "/st_fork"
#define PENDING 5

static zhandle_t *g_zh;
static int g_create_rc = -1;
static int g_closing;

static void create_cb(int rc, const char *v, const void *d)
{
    (void)v;
    (void)d;
    g_create_rc = rc;
}

static void exists_cb(int rc, const struct Stat *s, const void *d)
{
    (void)s;
    (void)d;
    if (rc == ZCLOSING) {
        g_closing++;
    }
}

/* Executes one untraced st event-loop iteration inside the child process. */
static void step(zhandle_t *zh)
{
    int fd = -1;
    int interest = 0;
    int events = 0;
    int ms;
    struct timeval tv = {0, 0};
    struct pollfd p;
    zookeeper_interest(zh, &fd, &interest, &tv);
    ms = (int)(tv.tv_sec * 1000 + tv.tv_usec / 1000);
    if (ms < 0 || ms > 100) {
        ms = 100;
    }
    if (fd >= 0) {
        p.fd = fd;
        p.events = (short)(((interest & ZOOKEEPER_READ) ? POLLIN : 0) |
                           ((interest & ZOOKEEPER_WRITE) ? POLLOUT : 0));
        p.revents = 0;
        poll(&p, 1, ms);
        if (p.revents & (POLLIN | POLLHUP | POLLERR)) {
            events |= ZOOKEEPER_READ;
        }
        if (p.revents & (POLLOUT | POLLHUP | POLLERR)) {
            events |= ZOOKEEPER_WRITE;
        }
    } else {
        poll(NULL, 0, ms);
    }
    zookeeper_process(zh, events);
}

static int child_main(void)
{
    int i;
    zoo_acreate(g_zh, FORK_PATH, "x", 1, &ZOO_OPEN_ACL_UNSAFE, ZOO_EPHEMERAL,
                create_cb, NULL);
    for (i = 0; i < 100 && g_create_rc == -1; i++) {
        step(g_zh);
    }
    for (i = 0; i < PENDING; i++) {
        zoo_aexists(g_zh, "/", 0, exists_cb, NULL);
    }
    zookeeper_close(g_zh);
    return g_closing + (g_create_rc == ZOK ? 16 : 0);
}

int main(int argc, char **argv)
{
    zhandle_t *check;
    struct Stat st;
    const char *res;

    zkc_init(argc, argv, "st_fork", 1, "EXACT", "ignore");
    g_zh = zkc_connect(zkc_host(0), 30000, 0);
    if (g_zh == NULL) {
        return zkc_finish();
    }
    /* Detach the handle from the parent poll loop before handing off to the child. */
    zkc_poll_remove(g_zh);
    res = zkc_child("close in a forked st child", child_main, 15000);
    zkc_ev("step", "child %s", res);

    check = zkc_connect(zkc_host(0), 10000, 0);
    if (check != NULL) {
        zkc_poll_remove(check);
        zkc_ev("call", "zoo_aexists " FORK_PATH " -> %s",
               zkc_rc(zoo_aexists(check, FORK_PATH, 0, zkc_stat_cb,
                                  "after-child")));
        zkc_poll_add(check);
        zkc_await("cb", 1, 10000);
        zkc_close(check);
    }
    (void)st;
    /* Release the parent's handle copy after the child has closed the session. */
    zookeeper_close(g_zh);
    return zkc_finish();
}
