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
 * nonblocking TLS handshake deadline enforcement and CA configuration failure
 * backoff. Mode: DEV.
 *
 * Invariants and deviations:
 * - A TLS handshake that stalls after TCP connection establishment times out with
 *   ZOPERATIONTIMEOUT at 2T/3 from the TCP connect on the Rust drop-in, and
 *   zookeeper_close returns promptly. Legacy C blocks indefinitely inside
 *   its synchronous SSL_connect / select() loop, blocking zookeeper_close as well.
 * - When CA file loading fails on the EINPROGRESS connect path,
 *   the Rust drop-in clears WRITE interest, avoids spinning, and fails over to
 *   the next server at the 2T/3 connect deadline. Legacy C retains WRITE
 *   interest in state 7 (SSL_CONNECTING_STATE_DEF), retries init_ssl_for_socket
 *   in a tight CPU spin on every poll iteration, and never fails over because
 *   state 7 bypasses the 2T/3 receive timeout check.
 *
 * The stalled peer is a local listen() socket that never calls accept(), so the
 * kernel completes the TCP handshake while sending no ServerHello. Each stall
 * scenario runs in a forked child under a timeout (exit 0 = timed out at 2T/3
 * and closed promptly).
 *
 * zkconf-flavors: mt st
 * zkconf-preload: connect
 */

#include "zkconf.h"
#include "zkconf_preload.h"
#include "zkconf_tls.h"

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define T_MS 3000
#define MISSING "/nonexistent-zkconf"

static char g_stall[64];
static int g_mode;
static char g_cert[4096];

static void quiet_watcher(zhandle_t *zh, int type, int state,
                          const char *path, void *ctx)
{
    (void)zh;
    (void)type;
    (void)state;
    (void)path;
    (void)ctx;
}

/* Passive TCP listener that never accepts, stalling the TLS handshake after TCP connect. */
static int stall_listener(void)
{
    struct sockaddr_in a;
    socklen_t alen = sizeof(a);
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) {
        return -1;
    }
    memset(&a, 0, sizeof(a));
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(fd, (struct sockaddr *)&a, sizeof(a)) != 0 ||
        listen(fd, 8) != 0 ||
        getsockname(fd, (struct sockaddr *)&a, &alen) != 0) {
        close(fd);
        return -1;
    }
    snprintf(g_stall, sizeof(g_stall), "127.0.0.1:%d",
             (int)ntohs(a.sin_port));
    return fd;
}

/* Child body for a stalled TLS handshake attempt under the configured connect preload mode. */
static int stall_child(void)
{
    zhandle_t *zh;
    long t0;
    long t1 = -1;
    long c0;
    long dt;
    long close_ms;
    /* Snapshot connect count prior to initializing the child handle. */
    int n0 = zkt_connect_count();
    FILE *null = fopen("/dev/null", "w");
    zoo_set_log_stream(null);
    zh = zkt_init_raw(g_stall, g_cert, quiet_watcher, T_MS, NULL, NULL, 0);
    if (zh == NULL) {
        return 4;
    }
    t0 = zkt_now_ms();
#ifdef THREADED
    /* In mt builds, failover after the 2T/3 handshake deadline triggers a second connect(). */
    while (zkt_now_ms() - t0 < 2 * T_MS && zkt_connect_count() - n0 < 2) {
        zkc_sleep_ms(10);
    }
    if (zkt_connect_count() - n0 >= 2) {
        t1 = zkt_connect_time(n0 + 1);
    }
#else
    while (zkt_now_ms() - t0 < 2 * T_MS && t1 < 0) {
        int interest;
        int err;
        int mid;
        const char *where;
        int want_st = g_mode == ZKCONF_CONNECT_EINPROGRESS
                          ? 7
                          : ZOO_NOTCONNECTED_STATE;
        int rc = zkt_st_pass(zh, 50, &interest, &err, &where, &mid);
        if (rc == ZOPERATIONTIMEOUT && err == ETIMEDOUT) {
            t1 = zkt_now_ms();
        } else if (rc != ZOK) {
            return 5;
        } else if (zoo_state(zh) != want_st) {
            /* Handle state must remain constant throughout the handshake stall. */
            return 6;
        }
    }
#endif
    c0 = zkt_connect_time(n0);
    close_ms = zkt_now_ms();
    zookeeper_close(zh);
    close_ms = zkt_now_ms() - close_ms;
    if (t1 < 0) {
        return 1;
    }
    dt = t1 - c0;
    if (dt < 2 * T_MS / 3 - 300 || dt > 2 * T_MS / 3 + 700) {
        return 2;
    }
    return close_ms < 1000 ? 0 : 3;
}

static void stall_case(int mode, const char *label)
{
    const char *res;
    g_mode = mode;
    zkt_connect_mode(mode);
    res = zkc_child(label, stall_child, 3 * T_MS + 2000);
    if (zkc_is_rust()) {
        zkc_dev("SEC-1", strcmp(res, "exit:0") == 0,
                "%s: stalled handshake: %s (exit:0 is ZOPERATIONTIMEOUT "
                "at 2/3 T and a prompt close)", label, res);
    } else {
        zkc_dev("SEC-1", strcmp(res, "hang") == 0,
                "%s: stalled handshake: %s (legacy blocks)", label, res);
    }
}

/* CA verification failure on the EINPROGRESS path across a two-server list. */
static void failover_case(void)
{
    char hosts[256];
    zhandle_t *zh;
    long t0;
    long cpu0;
    long cpu;
    int n0;
    int n;
    long dt = -1;
    snprintf(hosts, sizeof(hosts), "%s", zkt_hosts(2));
    zkt_connect_mode(ZKCONF_CONNECT_EINPROGRESS);
    zkt_log_reset();
    n0 = zkt_connect_count();
    cpu0 = zkt_cpu_ms();
    zh = zkt_init_raw(hosts, MISSING "/ca.pem,,,", quiet_watcher, T_MS, NULL,
                      NULL, 0);
    if (zh == NULL) {
        zkc_fail("init failed");
        return;
    }
    t0 = zkt_now_ms();
#ifdef THREADED
    while (zkt_now_ms() - t0 < 4000 && zkt_connect_count() - n0 < 2) {
        zkc_sleep_ms(10);
    }
#else
    while (zkt_now_ms() - t0 < 4000 && zkt_connect_count() - n0 < 2) {
        int interest;
        int err;
        int mid;
        const char *where;
        int rc = zkt_st_pass(zh, 50, &interest, &err, &where, &mid);
        if (rc != ZOK && rc != ZSSLCONNECTIONERROR &&
            rc != ZOPERATIONTIMEOUT) {
            zkc_fail("st rc %s", zkc_rc(rc));
            break;
        }
    }
#endif
    n = zkt_connect_count() - n0;
    cpu = zkt_cpu_ms() - cpu0;
    if (n >= 2) {
        dt = zkt_connect_time(n0 + 1) - zkt_connect_time(n0);
    }
    zookeeper_close(zh);
    if (zkc_is_rust()) {
        zkc_dev("SEC-3", n >= 2 && dt >= 2 * T_MS / 3 - 300 &&
                             dt <= 2 * T_MS / 3 + 700,
                "CA failure: failover after %ld ms (2/3 T is %d ms)", dt,
                2 * T_MS / 3);
        zkc_dev("SEC-3", cpu < 1000,
                "CA failure: %ld ms of CPU over the wait (no spin)", cpu);
        zkc_dev("SEC-3", zkt_log_count("Failed to load CA file") <= 2,
                "CA failure: %d CA lines for %d attempts",
                zkt_log_count("Failed to load CA file"), n);
    } else {
        zkc_dev("SEC-3", n < 2,
                "CA failure: %d connects in 4 s, %ld ms of CPU, %d CA lines "
                "(legacy spins in state 7 and does not fail over)", n, cpu,
                zkt_log_count("Failed to load CA file"));
    }
}

int main(int argc, char **argv)
{
    int lfd;
    const char *c;
    zkc_init(argc, argv, "tls_hs", 2, "DEV", "ignore");
    if (zkt_setup(2) != 0) {
        return zkc_finish();
    }
    if (!zkt_have_preload()) {
        zkc_fail("the connect preload is not loaded");
        return zkc_finish();
    }
    c = zkt_certs();
    snprintf(g_cert, sizeof(g_cert),
             "%s/server.crt,%s/client.crt,%s/clientkey.pem,password", c, c, c);
    lfd = stall_listener();
    if (lfd < 0) {
        zkc_fail("stall listener failed");
        return zkc_finish();
    }
    /* The children run before the first handle of this process. */
    stall_case(ZKCONF_CONNECT_EINPROGRESS, "einprogress");
    stall_case(ZKCONF_CONNECT_IMMEDIATE, "immediate");
    close(lfd);
    failover_case();
    zkt_connect_mode(ZKCONF_CONNECT_PASS);
    return zkc_finish();
}
