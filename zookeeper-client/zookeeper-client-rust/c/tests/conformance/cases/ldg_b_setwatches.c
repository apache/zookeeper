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
 * SetWatches frame batching across session reconnection. Mode: DEV.
 *
 * Invariants and deviations:
 * - The client registers 200 exists watches on ~1000-byte paths so
 *   the total watch payload exceeds 128 KiB, then reconnects following a proxy
 *   reset. The legacy library serializes the entire watch set into a single
 *   oversized SetWatches frame, whereas the Rust drop-in partitions the watch
 *   set into multiple SetWatches frames capped at 128 KiB of path data so
 *   servers with a tight jute.maxbuffer limit accept them.
 * - On both libraries, all restored watches remain active on the
 *   server and fire when a watched znode is created.
 *
 * An inline stream-inspecting TCP proxy relays all traffic while parsing
 * upstream frame headers, counting SetWatches frames (xid == -8) and recording
 * the maximum observed frame length.
 *
 * zkconf-flavors: mt st
 */

#include "zkconf.h"
#include "zkconf_sync.h"

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <poll.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define NWATCH 200
#define NAME_LEN 1000
#define BATCH 131072

/* Stream-inspecting TCP proxy state (single active client connection). */
static struct {
    pthread_mutex_t mu;
    pthread_t th;
    struct sockaddr_in target;
    char host[64];
    int lfd;
    int c;
    int s;
    int wake[2];
    int stop;
    int reset_req;
    int resets;
    /* Upstream frame header parser state. */
    int first;
    unsigned char head[12];
    size_t nhead;
    unsigned long skip;
    /* SetWatches frame metrics. */
    int sw_frames;
    unsigned long sw_max;
} g_px;

static unsigned long be32(const unsigned char *b)
{
    return ((unsigned long)b[0] << 24) | ((unsigned long)b[1] << 16) |
           ((unsigned long)b[2] << 8) | (unsigned long)b[3];
}

/* Parses upstream frame headers across n client bytes (caller holds g_px.mu). */
static void parse_up(const unsigned char *b, size_t n)
{
    while (n > 0) {
        if (g_px.skip > 0) {
            size_t k = n < g_px.skip ? n : (size_t)g_px.skip;
            g_px.skip -= k;
            b += k;
            n -= k;
            continue;
        }
        g_px.head[g_px.nhead++] = *b++;
        n--;
        if (g_px.nhead == 4 && g_px.first) {
            /* The initial ConnectRequest frame has no XID field. */
            g_px.first = 0;
            g_px.skip = be32(g_px.head);
            g_px.nhead = 0;
        } else if (g_px.nhead == 8) {
            unsigned long len = be32(g_px.head);
            if ((int)be32(g_px.head + 4) == -8) {
                g_px.sw_frames++;
                if (len > g_px.sw_max) {
                    g_px.sw_max = len;
                }
            }
            g_px.skip = len >= 4 ? len - 4 : 0;
            g_px.nhead = 0;
        }
    }
}

static int write_all(int fd, const unsigned char *b, size_t n)
{
    while (n > 0) {
        ssize_t w = send(fd, b, n, ZKC_NOSIGNAL);
        if (w < 0 && errno == EINTR) {
            continue;
        }
        if (w <= 0) {
            return -1;
        }
        b += w;
        n -= (size_t)w;
    }
    return 0;
}

/* Aborts the active client socket with a TCP RST (caller holds g_px.mu). */
static void drop_locked(void)
{
    if (g_px.c >= 0) {
        struct linger lg = {1, 0};
        setsockopt(g_px.c, SOL_SOCKET, SO_LINGER, &lg, sizeof(lg));
        close(g_px.c);
    }
    if (g_px.s >= 0) {
        close(g_px.s);
    }
    g_px.c = -1;
    g_px.s = -1;
}

static void accept_locked(void)
{
    int c = accept(g_px.lfd, NULL, NULL);
    int s;
    if (c < 0) {
        return;
    }
    s = socket(AF_INET, SOCK_STREAM, 0);
    if (s < 0 ||
        connect(s, (struct sockaddr *)&g_px.target, sizeof(g_px.target)) != 0) {
        if (s >= 0) {
            close(s);
        }
        close(c);
        return;
    }
    drop_locked();
    g_px.c = c;
    g_px.s = s;
    g_px.first = 1;
    g_px.nhead = 0;
    g_px.skip = 0;
}

static void *px_main(void *arg)
{
    unsigned char buf[65536];
    (void)arg;
    for (;;) {
        struct pollfd pf[4];
        int i;
        pthread_mutex_lock(&g_px.mu);
        if (g_px.stop) {
            drop_locked();
            pthread_mutex_unlock(&g_px.mu);
            break;
        }
        if (g_px.reset_req) {
            drop_locked();
            g_px.reset_req = 0;
            g_px.resets++;
        }
        pf[0].fd = g_px.wake[0];
        pf[1].fd = g_px.lfd;
        pf[2].fd = g_px.c;
        pf[3].fd = g_px.s;
        pthread_mutex_unlock(&g_px.mu);
        for (i = 0; i < 4; i++) {
            pf[i].events = POLLIN;
            pf[i].revents = 0;
        }
        if (poll(pf, 4, -1) < 0) {
            continue;
        }
        pthread_mutex_lock(&g_px.mu);
        if ((pf[0].revents & POLLIN) != 0) {
            ssize_t r = read(g_px.wake[0], buf, sizeof(buf));
            (void)r;
        }
        if ((pf[1].revents & POLLIN) != 0) {
            accept_locked();
        } else {
            if (g_px.c >= 0 && pf[2].fd == g_px.c && pf[2].revents != 0) {
                ssize_t n = read(g_px.c, buf, sizeof(buf));
                if (n <= 0 || write_all(g_px.s, buf, (size_t)n) != 0) {
                    drop_locked();
                } else {
                    parse_up(buf, (size_t)n);
                }
            }
            if (g_px.s >= 0 && pf[3].fd == g_px.s && pf[3].revents != 0) {
                ssize_t n = read(g_px.s, buf, sizeof(buf));
                if (n <= 0 || write_all(g_px.c, buf, (size_t)n) != 0) {
                    drop_locked();
                }
            }
        }
        pthread_mutex_unlock(&g_px.mu);
    }
    return NULL;
}

static void px_wake(void)
{
    char b = 1;
    ssize_t w = write(g_px.wake[1], &b, 1);
    (void)w;
}

/* Starts the counting proxy forwarding to target ("ip:port"); returns "127.0.0.1:port". */
static const char *px_start(const char *target)
{
    struct sockaddr_in a;
    socklen_t alen = sizeof(a);
    const char *colon = strrchr(target, ':');
    char ip[64];
    int one = 1;
    if (colon == NULL || (size_t)(colon - target) >= sizeof(ip)) {
        zkc_fail("bad proxy target");
        return NULL;
    }
    memcpy(ip, target, (size_t)(colon - target));
    ip[colon - target] = '\0';
    memset(&g_px.target, 0, sizeof(g_px.target));
    g_px.target.sin_family = AF_INET;
    g_px.target.sin_port = htons((unsigned short)atoi(colon + 1));
    pthread_mutex_init(&g_px.mu, NULL);
    g_px.c = -1;
    g_px.s = -1;
    g_px.lfd = socket(AF_INET, SOCK_STREAM, 0);
    if (inet_pton(AF_INET, ip, &g_px.target.sin_addr) != 1 || g_px.lfd < 0 ||
        pipe(g_px.wake) != 0) {
        zkc_fail("proxy socket: %s", strerror(errno));
        return NULL;
    }
    memset(&a, 0, sizeof(a));
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    setsockopt(g_px.lfd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    if (bind(g_px.lfd, (struct sockaddr *)&a, sizeof(a)) != 0 ||
        listen(g_px.lfd, 8) != 0 ||
        getsockname(g_px.lfd, (struct sockaddr *)&a, &alen) != 0) {
        zkc_fail("proxy listen: %s", strerror(errno));
        return NULL;
    }
    snprintf(g_px.host, sizeof(g_px.host), "127.0.0.1:%d", ntohs(a.sin_port));
    if (pthread_create(&g_px.th, NULL, px_main, NULL) != 0) {
        zkc_fail("proxy thread");
        return NULL;
    }
    return g_px.host;
}

/* Resets the active client connection and zeroes SetWatches frame counters. */
static void px_reset(void)
{
    int want;
    int i;
    pthread_mutex_lock(&g_px.mu);
    want = g_px.resets + 1;
    g_px.reset_req = 1;
    g_px.sw_frames = 0;
    g_px.sw_max = 0;
    pthread_mutex_unlock(&g_px.mu);
    px_wake();
    for (i = 0; i < 500; i++) {
        int done;
        pthread_mutex_lock(&g_px.mu);
        done = g_px.resets >= want;
        pthread_mutex_unlock(&g_px.mu);
        if (done) {
            return;
        }
        zkc_sleep_ms(10);
    }
    zkc_fail("proxy reset timed out");
}

static void px_stop(void)
{
    pthread_mutex_lock(&g_px.mu);
    g_px.stop = 1;
    pthread_mutex_unlock(&g_px.mu);
    px_wake();
    pthread_join(g_px.th, NULL);
    close(g_px.lfd);
}

static void watch_path(char *out, size_t len, int i)
{
    int n = snprintf(out, len, "/ldgb_sw_%03d_", i);
    memset(out + n, 'x', NAME_LEN);
    out[n + NAME_LEN] = '\0';
}

int main(int argc, char **argv)
{
    static char path[NAME_LEN + 64];
    const char *px;
    zhandle_t *zh;
    int i;
    int frames;
    unsigned long max;
    int rust;
    int fired;

    zkc_init(argc, argv, "ldg_b_setwatches", 1, "DEV", "ignore");
    rust = zkc_is_rust();
    px = px_start(zkc_host(0));
    if (px == NULL) {
        return zkc_finish();
    }
    zh = zkc_connect(px, 20000, 0);
    if (zh == NULL) {
        px_stop();
        return zkc_finish();
    }
    for (i = 0; i < NWATCH; i++) {
        watch_path(path, sizeof(path), i);
        if (zkcs_exists(zh, path, 1, NULL) != ZNONODE) {
            zkc_fail("exists %d did not give ZNONODE", i);
        }
    }

    /* Trigger a proxy reset so the client reconnects and replays SetWatches. */
    px_reset();
    zkc_await("session:CONNECTED", 1, 15000);
    zkc_poll(1000);
    pthread_mutex_lock(&g_px.mu);
    frames = g_px.sw_frames;
    max = g_px.sw_max;
    pthread_mutex_unlock(&g_px.mu);
    zkc_dev("DV-FIX-4",
            rust ? (frames >= 2 && max <= BATCH + 4 * NWATCH + 64)
                 : (frames == 1 && max > BATCH + 4 * NWATCH + 64),
            "SetWatches of %d paths of %d bytes: %d frames, largest %lu "
            "bytes (legacy: one frame; rust: frames of at most 128 KiB)",
            NWATCH, NAME_LEN, frames, max);

    /* Verify that the restored watches fire when a watched znode is created. */
    watch_path(path, sizeof(path), NWATCH - 1);
    zkcs_create(zh, path, "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, NULL, 0);
    fired = zkc_await("watch", 1, 10000);
    zkc_dev("DV-FIX-4", fired == 0,
            "a create of a restored path fires its watch: %s (legacy and "
            "rust: fired)", fired == 0 ? "fired" : "none");

    zkc_close(zh);
    px_stop();
    return zkc_finish();
}
