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
 * Single threaded errno values populated on failed driving calls.
 * Mode: EXACT.
 *
 * Drives each handle through an explicit event loop, recording the return code
 * and symbolic errno value of every failing zookeeper_interest or
 * zookeeper_process call.
 *
 * Invariants:
 * - Connection refusal sets rc = ZCONNECTIONLOSS and errno = ECONNREFUSED.
 * - Receive timeout while server traffic is held at the proxy sets
 *   rc = ZOPERATIONTIMEOUT and errno = ETIMEDOUT.
 * - Server-initiated socket closure sets rc = ZCONNECTIONLOSS and
 *   errno = EHOSTDOWN.
 * - Reconnecting with an expired session ID sets rc = ZSESSIONEXPIRED and
 *   errno = ESTALE.
 *
 * zkconf-flavors: st
 */

#include "zkconf.h"

#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/select.h>

static const char *errno_name(int e)
{
    static char buf[32];
    switch (e) {
    case 0:
        return "0";
    case ECONNREFUSED:
        return "ECONNREFUSED";
    case ETIMEDOUT:
        return "ETIMEDOUT";
    case ESTALE:
        return "ESTALE";
    case EHOSTDOWN:
        return "EHOSTDOWN";
    case ECONNRESET:
        return "ECONNRESET";
    case EPIPE:
        return "EPIPE";
    case EINVAL:
        return "EINVAL";
    default:
        snprintf(buf, sizeof(buf), "errno-%d", e);
        return buf;
    }
}

/* Returns 1 if rc represents a terminal step failure. */
static int is_failure(int rc)
{
    return rc != ZOK && rc != ZNOTHING && rc != ZINVALIDSTATE;
}

static void record(const char *what, int rc, int err)
{
    zkc_ev("fail", "%s -> %s errno=%s", what, zkc_rc(rc), errno_name(err));
}

/*
 * Drives zh for up to timeout_ms, recording each failing call and returning 1
 * as soon as a call fails with stop_rc.
 */
static int drive_until(zhandle_t *zh, int stop_rc, int timeout_ms)
{
    int waited = 0;
    while (waited < timeout_ms) {
        int fd = -1;
        int interest = 0;
        struct timeval tv = {0, 0};
        struct timeval stv = {0, 50000};
        fd_set rd;
        fd_set wr;
        int events = 0;
        int rc;
        errno = 0;
        rc = zkc_interest(zh, &fd, &interest, &tv);
        if (is_failure(rc)) {
            record("zookeeper_interest", rc, errno);
            if (rc == stop_rc) {
                return 1;
            }
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
        if (select(fd >= 0 ? fd + 1 : 0, &rd, &wr, NULL, &stv) > 0 &&
            fd >= 0) {
            if (FD_ISSET(fd, &rd)) {
                events |= ZOOKEEPER_READ;
            }
            if (FD_ISSET(fd, &wr)) {
                events |= ZOOKEEPER_WRITE;
            }
        }
        waited += 50;
        errno = 0;
        rc = zkc_process(zh, events);
        if (is_failure(rc)) {
            record("zookeeper_process", rc, errno);
            if (rc == stop_rc) {
                return 1;
            }
        }
    }
    zkc_fail("no call gave %s", zkc_rc(stop_rc));
    return 0;
}

static zhandle_t *open_handle(const char *hosts, int timeout_ms,
                              const clientid_t *id, const char *label)
{
    zhandle_t *zh = zookeeper_init2(hosts, zkc_watcher, timeout_ms, id,
                                    (void *)label, 0, zkc_log_cb);
    zkc_ev("call", "zookeeper_init2 %s -> %s", label, zh ? "zh" : "NULL");
    return zh;
}

static void connected(zhandle_t *zh, const char *label)
{
    char key[96];
    snprintf(key, sizeof(key), "session:CONNECTED@%s", label);
    zkc_poll_add(zh);
    zkc_await(key, 1, 15000);
    zkc_poll_remove(zh);
}

int main(int argc, char **argv)
{
    zhandle_t *zh;
    zhandle_t *clone;
    const char *px;
    clientid_t copy;

    zkc_init(argc, argv, "st_errno", 1, "EXACT", "ignore");

    /* ECONNREFUSED: connection attempt against an unbound port. */
    zh = open_handle(zkc_dead(), 6000, NULL, "refused");
    if (zh != NULL) {
        drive_until(zh, ZCONNECTIONLOSS, 10000);
        zkc_close(zh);
    }

    /* ETIMEDOUT: proxy holds server responses past the receive timeout. */
    px = zkc_proxy_start(zkc_host(0));
    if (px == NULL) {
        return zkc_finish();
    }
    zh = open_handle(px, 3000, NULL, "timeout");
    if (zh != NULL) {
        connected(zh, "timeout");
        zkc_proxy_hold(1);
        drive_until(zh, ZOPERATIONTIMEOUT, 10000);
        zkc_proxy_hold(0);
        zkc_close(zh);
    }
    zkc_proxy_stop();

    /*
     * EHOSTDOWN followed by ESTALE: a secondary handle attaches to the
     * session and closes it, causing the server to drop the primary connection
     * and reject the subsequent reconnect attempt as expired.
     */
    zh = open_handle(zkc_host(0), 4000, NULL, "expire");
    if (zh == NULL) {
        return zkc_finish();
    }
    connected(zh, "expire");
    memcpy(&copy, zoo_client_id(zh), sizeof(copy));
    clone = open_handle(zkc_host(0), 4000, &copy, "clone");
    if (clone != NULL) {
        connected(clone, "clone");
        zkc_close(clone);
    }
    drive_until(zh, ZSESSIONEXPIRED, 15000);
    zkc_close(zh);
    return zkc_finish();
}
