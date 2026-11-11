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
 * single threaded handle shutdown prior to session establishment. Mode: EXACT.
 *
 * Invariants:
 * - zookeeper_close(NULL) returns ZBADARGUMENTS.
 * - Closing a handle before the first zookeeper_interest call, during a
 *   pending TCP connect, or while awaiting ConnectResponse returns ZOK without
 *   transmitting a CLOSE_OP frame, drains queued requests synchronously inside
 *   zookeeper_close with ZCLOSING, and emits no session events.
 * - File descriptor and thread counts return to baseline after each close.
 *
 * zkconf-flavors: st
 */

#include "zkconf.h"

#include <string.h>
#include <sys/select.h>
#include <sys/time.h>

static int g_fd0;
static int g_th0;

static void call(const char *what, int rc)
{
    zkc_ev("call", "%s -> %s", what, zkc_rc(rc));
}

static long now_ms(void)
{
    struct timeval now;
    gettimeofday(&now, NULL);
    return (long)now.tv_sec * 1000L + (long)(now.tv_usec / 1000);
}

static zhandle_t *init(const char *hosts, const char *what)
{
    zhandle_t *zh = zookeeper_init2(hosts, zkc_watcher, 10000, NULL,
                                    "default", 0, zkc_log_cb);
    zkc_ev("call", "zookeeper_init2 %s -> %s", what, zh ? "zh" : "NULL");
    return zh;
}

/*
 * Enqueues a single zoo_aexists request and closes the handle, optionally
 * verifying file descriptor and thread count deltas against baseline.
 */
static void submit_and_close(zhandle_t *zh, const char *label, int counts)
{
    call("zoo_aexists", zoo_aexists(zh, "/", 0, zkc_stat_cb, label));
    zkc_ev("check", "state before close=%s", zkc_state(zoo_state(zh)));
    zkc_in_call(1);
    call("zookeeper_close", zookeeper_close(zh));
    zkc_in_call(0);
    if (counts) {
        zkc_ev("check", "fds after close: %+d", zkc_fds() - g_fd0);
        zkc_ev("check", "threads after close: %+d", zkc_threads() - g_th0);
    }
}

/* Drives zh via interest/select/process until zoo_state reaches want or timeout_ms expires. */
static void drive_to(zhandle_t *zh, int want, int timeout_ms)
{
    long deadline = now_ms() + timeout_ms;
    while (zoo_state(zh) != want && now_ms() < deadline) {
        int fd = -1;
        int interest = 0;
        struct timeval tv = {0, 0};
        fd_set rd;
        fd_set wr;
        int events = 0;
        if (zkc_interest(zh, &fd, &interest, &tv) != ZOK || fd < 0) {
            zkc_sleep_ms(10);
            continue;
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
        if (select(fd + 1, &rd, &wr, NULL, &tv) > 0) {
            if (FD_ISSET(fd, &rd)) {
                events |= ZOOKEEPER_READ;
            }
            if (FD_ISSET(fd, &wr)) {
                events |= ZOOKEEPER_WRITE;
            }
            zkc_process(zh, events);
        }
    }
    zkc_ev("check", "state reached=%s", zkc_state(zoo_state(zh)));
}

int main(int argc, char **argv)
{
    zhandle_t *zh;
    const char *px;
    int fd = -1;
    int interest = 0;
    struct timeval tv = {0, 0};

    zkc_init(argc, argv, "st_close", 1, "EXACT", "ordered");
    call("zookeeper_close(NULL)", zookeeper_close(NULL));
    g_fd0 = zkc_fds();
    g_th0 = zkc_threads();

    /* Close before any zookeeper_interest call (no socket opened). */
    zh = init(zkc_host(0), "server");
    if (zh != NULL) {
        submit_and_close(zh, "never-driven", 1);
    }

    /* Close while nonblocking TCP connect to dead port is in progress. */
    zh = init(zkc_dead(), "dead");
    if (zh != NULL) {
        int rc = zkc_interest(zh, &fd, &interest, &tv);
        zkc_ev("interest", "rc=%s fd=%s state=%s", zkc_rc(rc),
               fd >= 0 ? "sock" : "-1", zkc_state(zoo_state(zh)));
        submit_and_close(zh, "connecting", 1);
    }

    /* Close in ZOO_ASSOCIATING_STATE while ConnectResponse is held at the proxy. */
    px = zkc_proxy_start(zkc_host(0));
    if (px == NULL) {
        return zkc_finish();
    }
    zkc_proxy_hold(1);
    zh = init(px, "proxy");
    if (zh != NULL) {
        drive_to(zh, ZOO_ASSOCIATING_STATE, 5000);
        submit_and_close(zh, "associating", 0);
    }
    zkc_proxy_hold(0);
    zkc_proxy_stop();
    zkc_ev("check", "fds at the end: %+d", zkc_fds() - g_fd0);
    zkc_ev("check", "threads at the end: %+d", zkc_threads() - g_th0);
    return zkc_finish();
}
