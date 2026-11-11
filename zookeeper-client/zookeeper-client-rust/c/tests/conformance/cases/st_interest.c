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
 * single threaded zookeeper_interest / zookeeper_process contract and session
 * state trajectory. Mode: EXACT.
 *
 * Drives a handle through an explicit event loop and records state transitions
 * across zookeeper_interest output parameters (normalized as "sock" or "-1",
 * interest flags "R"/"W"/"RW"/"0", and timeout "0" or ">0") and
 * zookeeper_process return codes.
 *
 * Invariants:
 * - Passing a NULL handle or NULL output pointer returns ZBADARGUMENTS.
 * - Calling zookeeper_process prior to the first zookeeper_interest call fails
 *   because no socket has been opened yet.
 * - State progresses from NOTCONNECTED through CONNECTING and
 *   ASSOCIATING to CONNECTED.
 * - A single zookeeper_process(ZOOKEEPER_READ) call consumes the
 *   entire ConnectResponse without returning intermediate ZNOTHING results.
 * - A connected handle requests ZOOKEEPER_READ with a positive timeout
 *   bounded by zoo_recv_timeout(zh), and calling zookeeper_process(zh, 0) is a
 *   valid no-op.
 * - On an unrecoverable (expired) handle, zookeeper_interest returns
 *   ZINVALIDSTATE and leaves all output parameters unmodified.
 *
 * zkconf-flavors: st
 */

#include "zkconf.h"

#include <string.h>
#include <sys/select.h>

static int g_connected;
/* Count of ZNOTHING returns from zookeeper_process(READ) prior to CONNECTED. */
static int g_nothing;

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

static const char *bits(int interest)
{
    if ((interest & ZOOKEEPER_READ) && (interest & ZOOKEEPER_WRITE)) {
        return "RW";
    }
    if (interest & ZOOKEEPER_READ) {
        return "R";
    }
    if (interest & ZOOKEEPER_WRITE) {
        return "W";
    }
    return interest == 0 ? "0" : "?";
}

/* Emits a trace event only when line differs from the previous emission of that kind. */
static void changed(char *last, size_t len, const char *kind, const char *line)
{
    if (strcmp(last, line) != 0) {
        snprintf(last, len, "%s", line);
        zkc_ev(kind, "%s", line);
    }
}

/* Drives zh until the CONNECTED session event arrives or timeout_ms expires. */
static void drive_to_connected(zhandle_t *zh, int timeout_ms)
{
    char last_i[256] = "";
    char last_p[256] = "";
    char line[256];
    int waited = 0;
    while (!g_connected && waited < timeout_ms) {
        int fd = -1;
        int interest = 0;
        struct timeval tv = {0, 0};
        struct timeval stv = {0, 100000};
        fd_set rd;
        fd_set wr;
        int events = 0;
        int rc = zkc_interest(zh, &fd, &interest, &tv);
        snprintf(line, sizeof(line), "rc=%s fd=%s interest=%s tv=%s state=%s",
                 zkc_rc(rc), fd >= 0 ? "sock" : "-1", bits(interest),
                 tv.tv_sec == 0 && tv.tv_usec == 0 ? "0" : ">0",
                 zkc_state(zoo_state(zh)));
        changed(last_i, sizeof(last_i), "interest", line);
        if (g_connected) {
            break;
        }
        FD_ZERO(&rd);
        FD_ZERO(&wr);
        if (fd >= 0) {
            if (interest & ZOOKEEPER_READ) {
                FD_SET(fd, &rd);
            }
            if (interest & ZOOKEEPER_WRITE) {
                FD_SET(fd, &wr);
            }
        }
        if (tv.tv_sec == 0 && tv.tv_usec < stv.tv_usec) {
            stv = tv;
        }
        if (select(fd >= 0 ? fd + 1 : 0, &rd, &wr, NULL, &stv) > 0) {
            if (FD_ISSET(fd, &rd)) {
                events |= ZOOKEEPER_READ;
            }
            if (FD_ISSET(fd, &wr)) {
                events |= ZOOKEEPER_WRITE;
            }
        }
        waited += 100;
        if (events == 0) {
            continue;
        }
        rc = zkc_process(zh, events);
        if (rc == ZNOTHING) {
            g_nothing++;
            continue;
        }
        snprintf(line, sizeof(line), "events=%s rc=%s state=%s", bits(events),
                 zkc_rc(rc), zkc_state(zoo_state(zh)));
        changed(last_p, sizeof(last_p), "process", line);
    }
    if (!g_connected) {
        zkc_fail("no CONNECTED event");
    }
    /*
     * The ConnectResponse arrives as one segment. Both cores handle it in
     * one zookeeper_process call.
     */
    if (g_nothing != 0) {
        zkc_fail("ST-PROC-1: ZNOTHING results before CONNECTED: %d",
                 g_nothing);
    }
}

int main(int argc, char **argv)
{
    zhandle_t *zh;
    int fd = -1;
    int interest = 0;
    struct timeval tv = {0, 0};
    long ms;
    int rc;

    zkc_init(argc, argv, "st_interest", 1, "EXACT", "ordered");
    zkc_set_watch_hook(watch_hook);

    zkc_ev("call", "zookeeper_interest(NULL) -> %s",
           zkc_rc(zookeeper_interest(NULL, &fd, &interest, &tv)));
    zkc_ev("call", "zookeeper_process(NULL, 0) -> %s",
           zkc_rc(zookeeper_process(NULL, 0)));

    zh = zookeeper_init2(zkc_host(0), zkc_watcher, 10000, NULL, "default", 0,
                         zkc_log_cb);
    zkc_ev("call", "zookeeper_init2 -> %s state=%s", zh ? "zh" : "NULL",
           zh ? zkc_state(zoo_state(zh)) : "-");
    if (zh == NULL) {
        return zkc_finish();
    }
    zkc_ev("call", "zookeeper_interest(fd NULL) -> %s",
           zkc_rc(zookeeper_interest(zh, NULL, &interest, &tv)));
    zkc_ev("call", "zookeeper_interest(interest NULL) -> %s",
           zkc_rc(zookeeper_interest(zh, &fd, NULL, &tv)));
    zkc_ev("call", "zookeeper_interest(tv NULL) -> %s",
           zkc_rc(zookeeper_interest(zh, &fd, &interest, NULL)));
    zkc_ev("call", "zookeeper_process before interest, events=0 -> %s",
           zkc_rc(zkc_process(zh, 0)));
    zkc_ev("call", "zookeeper_process before interest, events=R -> %s",
           zkc_rc(zkc_process(zh, ZOOKEEPER_READ)));
    zkc_ev("check", "state before interest: %s", zkc_state(zoo_state(zh)));

    /* Advance the handle to ZOO_CONNECTED_STATE. */
    drive_to_connected(zh, 15000);

    /* Verify interest descriptor, flags, and timeout bounds on a connected handle. */
    rc = zkc_interest(zh, &fd, &interest, &tv);
    ms = (long)tv.tv_sec * 1000L + (long)(tv.tv_usec / 1000);
    zkc_ev("check", "connected interest rc=%s fd=%s interest=%s tv>0=%d "
           "tv<=recv_timeout=%d", zkc_rc(rc), fd >= 0 ? "sock" : "-1",
           bits(interest), ms > 0 || tv.tv_usec > 0,
           ms <= zoo_recv_timeout(zh));
    zkc_ev("call", "zookeeper_process connected, events=0 -> %s",
           zkc_rc(zkc_process(zh, 0)));

    /* Verify interest output parameters on an expired handle. */
    zkc_poll_add(zh);
    if (zkc_expire(zh, zkc_host(0)) == 0) {
        fd = -77;
        interest = -77;
        tv.tv_sec = 77;
        tv.tv_usec = 77;
        zkc_poll_remove(zh);
        rc = zkc_interest(zh, &fd, &interest, &tv);
        zkc_ev("check", "expired interest rc=%s outputs untouched=%d state=%s",
               zkc_rc(rc), fd == -77 && interest == -77 && tv.tv_sec == 77 &&
                   tv.tv_usec == 77, zkc_state(zoo_state(zh)));
        zkc_ev("call", "zookeeper_process expired, events=R -> %s",
               zkc_rc(zkc_process(zh, ZOOKEEPER_READ)));
        zkc_ev("call", "zoo_aexists expired -> %s",
               zkc_rc(zoo_aexists(zh, "/", 0, zkc_stat_cb, "expired")));
    }
    zkc_close(zh);
    return zkc_finish();
}
