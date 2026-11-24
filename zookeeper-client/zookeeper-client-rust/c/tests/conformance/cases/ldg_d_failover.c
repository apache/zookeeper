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
 * Two-server failover cursor progression on a newly initialized handle.
 * Mode: DEV.
 *
 * In-process nonblocking listeners bind to the two configured server ports,
 * accept incoming connections, and close them immediately so each attempt
 * fails during handshake, recording the index of the contacted endpoint.
 * zoo_deterministic_conn_order(1) disables address list permutation so attempt
 * 0 targets server 0 on both cores.
 *
 * Cursor advancement diverges after the initial pass through the address list:
 * - Rust advances the address cursor once per failed attempt, alternating
 *   deterministically across the two endpoints (0, 1, 0, 1, ...).
 * - Legacy C advances the cursor twice per failure once the initial pass has
 *   completed, causing a two-server list to pin retries to a single server
 *   (0, 1, 0, 0, ...).
 *
 * zkconf-flavors: mt st
 */

#include "zkconf.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define NSERVERS 2
#define NATTEMPTS 8

static void quiet_watcher(zhandle_t *zh, int type, int state,
                          const char *path, void *ctx)
{
    (void)zh;
    (void)type;
    (void)state;
    (void)path;
    (void)ctx;
}

/* Opens a nonblocking loopback listener on the port specified in host. */
static int listen_on(const char *host)
{
    const char *colon = strrchr(host, ':');
    struct sockaddr_in sa;
    int one = 1;
    int fd;
    if (colon == NULL) {
        return -1;
    }
    fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) {
        return -1;
    }
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET;
    sa.sin_port = htons((unsigned short)atoi(colon + 1));
    sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(fd, (struct sockaddr *)&sa, sizeof(sa)) != 0 ||
        listen(fd, 8) != 0) {
        close(fd);
        return -1;
    }
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK);
    return fd;
}

/* Accepts and immediately closes pending connections, recording their listener index. */
static void accept_all(const int *lfd, int *seq, int *n)
{
    int i;
    for (i = 0; i < NSERVERS; i++) {
        int c;
        while (*n < NATTEMPTS && (c = accept(lfd[i], NULL, NULL)) >= 0) {
            close(c);
            seq[(*n)++] = i;
        }
    }
}

int main(int argc, char **argv)
{
    int lfd[NSERVERS];
    int seq[NATTEMPTS];
    char text[3 * NATTEMPTS + 1];
    char hosts[128];
    zhandle_t *zh;
    int n = 0;
    int waited;
    int alternates = 1;
    int repeats = 0;
    int i;

    zkc_init(argc, argv, "ldg_d_failover", 0, "DEV", "ignore");
    for (i = 0; i < NSERVERS; i++) {
        lfd[i] = listen_on(zkc_host(i));
        if (lfd[i] < 0) {
            zkc_fail("no listener on %s", zkc_host(i));
            return zkc_finish();
        }
    }
    snprintf(hosts, sizeof(hosts), "%s", zkc_hosts(NSERVERS));
    zoo_set_debug_level(ZOO_LOG_LEVEL_ERROR);
    zoo_deterministic_conn_order(1);
    zh = zookeeper_init(hosts, quiet_watcher, 3000, NULL, NULL, 0);
    if (zh == NULL) {
        zkc_fail("zookeeper_init failed");
        return zkc_finish();
    }
    zkc_poll_add(zh);
    for (waited = 0; n < NATTEMPTS && waited < 20000; waited += 10) {
        zkc_poll_once(10);
        if (!zkc_is_st()) {
            zkc_sleep_ms(10);
        }
        accept_all(lfd, seq, &n);
    }
    zkc_poll_remove(zh);
    zookeeper_close(zh);
    for (i = 0; i < NSERVERS; i++) {
        close(lfd[i]);
    }

    text[0] = '\0';
    for (i = 0; i < n; i++) {
        size_t used = strlen(text);
        snprintf(text + used, sizeof(text) - used, "%s%d", i > 0 ? ", " : "",
                 seq[i]);
    }
    for (i = 1; i < n; i++) {
        if (seq[i] == seq[i - 1]) {
            alternates = 0;
            repeats++;
        }
    }
    zkc_ev("check", "attempts: %s", text);
    zkc_dev("DV-FIX-1", n == NATTEMPTS && seq[0] == 0 && seq[1] == 1,
            "the first pass tries 0, then 1: %s", text);
    if (zkc_is_rust()) {
        zkc_dev("DV-FIX-1", n == NATTEMPTS && alternates,
                "the attempts alternate: %s", text);
    } else {
        zkc_dev("DV-FIX-1", n == NATTEMPTS && repeats > 0,
                "legacy: the attempts repeat a server %d times: %s", repeats,
                text);
    }
    return zkc_finish();
}
