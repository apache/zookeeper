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
 * TLS configuration failure handling across immediate and EINPROGRESS TCP
 * connect paths. Mode: EXACT with DEV checks.
 *
 * Invariants and deviations:
 * - zookeeper_interest executes TLS setup. A configuration error fails with
 *   ZSSLCONNECTIONERROR, errno = EINVAL, and the corresponding legacy log line,
 *   setting *fd = -1, *interest = 0, and *tv = 0 while preserving handle state
 *   and the open socket (so the next zookeeper_interest call does not invoke
 *   connect()). Because legacy C leaves last_recv at 0 on the immediate path,
 *   its next zookeeper_interest call computes idle_recv from the raw monotonic
 *   clock truncated to int and returns ZOPERATIONTIMEOUT whenever
 *   (2T/3 - (int)clock_ms) <= 0; the Rust drop-in initializes session timers at
 *   handle creation and returns ZOK.
 * - Handle state is 7 (SSL_CONNECTING_STATE_DEF), and zookeeper_process runs TLS
 *   setup once the socket becomes writable, failing with the same
 *   ZSSLCONNECTIONERROR return code, errno = EINVAL, and log line without
 *   tearing down the socket.
 * - Following a configuration failure in state 7, legacy C
 *   retains ZOOKEEPER_WRITE interest and re-runs init_ssl_for_socket on every
 *   poll iteration, whereas the Rust core clears WRITE interest and logs the
 *   diagnostic line once.
 *
 * zkconf-flavors: mt st
 * zkconf-preload: connect
 */

#include "zkconf.h"
#include "zkconf_preload.h"
#include "zkconf_tls.h"

#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/select.h>
#include <sys/time.h>
#include <time.h>
#ifdef __MACH__
#include <mach/clock.h>
#include <mach/mach.h>
#endif

#define SITE "@init_ssl_for_socket@"
#define MISSING "/nonexistent-zkconf"

#ifndef THREADED
/*
 * Computes the legacy calculate_interval() idle duration (in ms, truncated to
 * int) when last_recv is 0, matching get_system_time() in zookeeper.c.
 */
static int legacy_idle_ms(void)
{
    struct timeval tv;
    int ret = 1;
#ifdef __MACH__
    clock_serv_t cclock;
    mach_timespec_t mts;
    ret = host_get_clock_service(mach_host_self(), SYSTEM_CLOCK, &cclock);
    if (!ret) {
        ret += clock_get_time(cclock, &mts);
        ret += mach_port_deallocate(mach_task_self(), cclock);
        if (!ret) {
            tv.tv_sec = mts.tv_sec;
            tv.tv_usec = mts.tv_nsec / 1000;
        }
    }
#elif defined CLOCK_MONOTONIC_RAW
    {
        struct timespec ts;
        ret = clock_gettime(CLOCK_MONOTONIC_RAW, &ts);
        tv.tv_sec = ts.tv_sec;
        tv.tv_usec = ts.tv_nsec / 1000;
    }
#else
    {
        struct timespec ts;
        ret = clock_gettime(CLOCK_MONOTONIC, &ts);
        tv.tv_sec = ts.tv_sec;
        tv.tv_usec = ts.tv_nsec / 1000;
    }
#endif
    if (ret) {
        gettimeofday(&tv, NULL);
    }
    return (int)(tv.tv_sec * 1000 + (tv.tv_usec / 1000));
}
#endif

static void quiet_watcher(zhandle_t *zh, int type, int state,
                          const char *path, void *ctx)
{
    (void)zh;
    (void)type;
    (void)state;
    (void)path;
    (void)ctx;
}

static const char *path_name(int mode)
{
    return mode == ZKCONF_CONNECT_IMMEDIATE ? "immediate" : "einprogress";
}

#ifndef THREADED
static long tv_ms(const struct timeval *tv)
{
    return (long)tv->tv_sec * 1000 + tv->tv_usec / 1000;
}

/*
 * Drives zh in single threaded mode until the first non-ZOK return code, then
 * performs one follow-up zookeeper_interest call to inspect socket retention
 * and interest flags.
 */
static void drive(zhandle_t *zh, const char *label, int c0)
{
    long end = zkt_now_ms() + 10000;
    int failed = 0;
    while (zkt_now_ms() < end && !failed) {
        int fd = -1;
        int interest = 0;
        int rc;
        int e;
        struct timeval tv;
        fd_set rd;
        fd_set wr;
        tv.tv_sec = 0;
        tv.tv_usec = 0;
        errno = 0;
        rc = zkc_interest(zh, &fd, &interest, &tv);
        e = errno;
        if (rc != ZOK) {
            zkc_ev("st", "%s fail in=interest rc=%s errno=%s state=%s fd=%s "
                   "interest=%d tv=%ld", label, zkc_rc(rc), zkt_errno(e),
                   zkc_state(zoo_state(zh)), fd < 0 ? "-1" : "sock", interest,
                   tv_ms(&tv));
            failed = 1;
            break;
        }
        FD_ZERO(&rd);
        FD_ZERO(&wr);
        if (fd >= 0 && (interest & ZOOKEEPER_READ)) {
            FD_SET(fd, &rd);
        }
        if (fd >= 0 && (interest & ZOOKEEPER_WRITE)) {
            FD_SET(fd, &wr);
        }
        if (tv_ms(&tv) > 50) {
            tv.tv_sec = 0;
            tv.tv_usec = 50000;
        }
        if (select(fd >= 0 ? fd + 1 : 0, &rd, &wr, NULL, &tv) < 0) {
            zkc_fail("select failed");
            return;
        }
        if (fd >= 0) {
            int ev = (FD_ISSET(fd, &rd) ? ZOOKEEPER_READ : 0) |
                     (FD_ISSET(fd, &wr) ? ZOOKEEPER_WRITE : 0);
            errno = 0;
            rc = zkc_process(zh, ev);
            e = errno;
            if (rc != ZOK) {
                zkc_ev("st", "%s fail in=process rc=%s errno=%s state=%s",
                       label, zkc_rc(rc), zkt_errno(e),
                       zkc_state(zoo_state(zh)));
                failed = 1;
            }
        }
    }
    if (!failed) {
        zkc_ev("st", "%s no failure in 10 s", label);
        return;
    }
    {
        int fd = -1;
        int interest = 0;
        struct timeval tv;
        int rc;
        tv.tv_sec = 0;
        tv.tv_usec = 0;
        rc = zkc_interest(zh, &fd, &interest, &tv);
        /* The connect count shows that the failure kept the socket. */
        zkc_ev("st", "%s next interest connects=%d", label,
               zkt_connect_count() - c0);
        if (zoo_state(zh) == 7) {
            zkc_ev("st", "%s next interest rc=%s fd=%s state=%s", label,
                   zkc_rc(rc), fd < 0 ? "-1" : "sock",
                   zkc_state(zoo_state(zh)));
        }
        if (strncmp(label, "immediate", 9) == 0) {
            /*
             * The immediate path does not reset last_recv, and the legacy
             * handle starts with last_recv 0.
             */
            int legacy_timeout = 30000 * 2 / 3 - legacy_idle_ms() <= 0;
            zkc_dev("ST-17",
                    zkc_is_rust() || !legacy_timeout ? rc == ZOK
                                                      : rc == ZOPERATIONTIMEOUT,
                    "TL-06: %s: next interest rc=%s, legacy timeout %s", label,
                    zkc_rc(rc), legacy_timeout ? "yes" : "no");
        }
        /* Legacy C retains WRITE interest in state 7 and spins. */
        if (zoo_state(zh) == 7) {
            int write = (interest & ZOOKEEPER_WRITE) != 0;
            zkc_dev("ST-17", zkc_is_rust() ? !write : write,
                    "%s: WRITE interest after the failure: %s", label,
                    write ? "yes" : "no");
        }
    }
}
#endif

static void attempt(int mode, const char *step, const char *cert)
{
    char label[64];
    char shown[2048];
    zhandle_t *zh;
    int c0;
    snprintf(label, sizeof(label), "%s/%s", path_name(mode), step);
    zkt_log_reset();
    zkt_connect_mode(mode);
    zkt_norm_paths(shown, sizeof(shown), cert);
    zkc_ev("case", "%s cert=\"%s\"", label, shown);
    c0 = zkt_connect_count();
    errno = 0;
    zh = zkt_init_raw(zkt_host(0), cert, quiet_watcher, 30000, NULL, NULL, 0);
    if (zh == NULL) {
        zkc_ev("init", "%s NULL errno=%s", label, zkt_errno(errno));
        return;
    }
#ifdef THREADED
    {
        long end = zkt_now_ms() + 10000;
        while (zkt_log_count(SITE) == 0 && zkt_now_ms() < end) {
            zkc_sleep_ms(20);
        }
        /* Allow time to observe any repeated log lines from a retry spin. */
        zkc_sleep_ms(300);
        (void)c0;
    }
#else
    drive(zh, label, c0);
#endif
    zookeeper_close(zh);
    zkt_log_record(label, SITE, 1);
    if (zkc_is_rust()) {
        int n = zkt_log_count(SITE);
        /* Exactly one diagnostic line per attempt (no retry spin). */
        zkc_dev("ST-17", n == 1, "%s: %d init_ssl_for_socket lines", label, n);
    }
}

int main(int argc, char **argv)
{
    static const int modes[2] = {ZKCONF_CONNECT_IMMEDIATE,
                                 ZKCONF_CONNECT_EINPROGRESS};
    char edkey[1200];
    char cs[4][4096];
    static const char *const steps[4] = {"ca", "cert", "key", "check"};
    const char *f;
    int m;
    int s;

    zkc_init(argc, argv, "tls_paths", 1, "EXACT", "ignore");
    if (zkt_setup(1) != 0) {
        return zkc_finish();
    }
    if (!zkt_have_preload()) {
        zkc_fail("the connect preload is not loaded");
        return zkc_finish();
    }
    f = zkt_fix();
    snprintf(edkey, sizeof(edkey), "%s.ed25519.key", zkt_out());
    if (zkt_write_ed25519(edkey) != 0) {
        zkc_fail("cannot write %s", edkey);
        return zkc_finish();
    }
    snprintf(cs[0], sizeof(cs[0]), MISSING "/ca.pem,,,");
    snprintf(cs[1], sizeof(cs[1]),
             "%s/ca.pem," MISSING "/c.pem,%s/client.key,pw", f, f);
    snprintf(cs[2], sizeof(cs[2]), "%s/ca.pem,%s/client.pem,%s/server.key,pw",
             f, f, f);
    snprintf(cs[3], sizeof(cs[3]), "%s/ca.pem,%s/client.pem,%s,pw", f, f,
             edkey);
    for (m = 0; m < 2; m++) {
        for (s = 0; s < 4; s++) {
            attempt(modes[m], steps[s], cs[s]);
        }
    }
    zkt_connect_mode(ZKCONF_CONNECT_PASS);
    return zkc_finish();
}
