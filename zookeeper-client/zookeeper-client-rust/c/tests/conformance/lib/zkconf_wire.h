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
 * In-process Jute frame inspection and flow-control proxy for digest
 * authentication conformance scenarios. Test-only support code. Runs on a
 * background thread within the test process, listening on an ephemeral
 * `127.0.0.1` port and relaying one client connection at a time to the
  * upstream server.
 *
 * Client-to-server traffic is forwarded immediately while recording a symbolic
 * label for each complete request frame:
 *   K       `ConnectRequest` (initial frame on a connection)
 *   A:<id>  `AuthPacket` (`xid == -4`, `op == 100`) carrying credential `<id>`
 *   W       `SetWatches` (`xid == -8`)
 *   E       `ExistsRequest` (`op == 3`)
 *   S       `SetSASLRequest` (`op == 102`)
 *   X       `CloseRequest` (`op == -11`)
 *   T<op>   any other request opcode
 * Ping requests (`xid == -2`) are forwarded without recording a label.
 *
 * Server-to-client forwarding is governed by a frame budget (`budget == -1`
 * forwards all frames; `budget == 0` holds non-ping frames; `budget == N`
 * forwards at most `N` non-ping frames). Ping responses (`xid == -2`) always
 * bypass the hold—even out of order behind held response frames—so prolonged
 * holds do not trigger a `2T/3` client receive timeout (`zookeeper.c:2745-2762`;
 * both cores accept out-of-order ping responses via `xid == -2` routing). Held
 * frames remain buffered until released or discarded by a proxy reset, and the
 * configured budget persists across reconnections.
 *
 * If the upstream server closes its connection while frames remain buffered
 * (for example, after rejecting an authentication packet), the buffered frames
 * remain subject to the budget and client side EOF is deferred until all
 * complete buffered frames have been delivered.
 */

#ifndef ZKCONF_WIRE_H_
#define ZKCONF_WIRE_H_

#include "zkconf.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define ZKW_UP_CAP 65536
#define ZKW_DOWN_CAP 262144
#define ZKW_LABEL_CAP 4096

struct zkw_proxy {
    pthread_mutex_t mu;
    pthread_t th;
    int running;
    int stop;
    int reset_req;
    int resets;
    int lfd;
    int c;
    int s;
    int wake[2];
    struct sockaddr_in target;
    char host[64];
    unsigned char up[ZKW_UP_CAP];
    size_t nup;
    int up_first;
    int up_broken;
    unsigned char down[ZKW_DOWN_CAP];
    size_t ndown;
    long budget;
    char labels[ZKW_LABEL_CAP];
    size_t nlabels;
};

static inline struct zkw_proxy *zkw(void)
{
    static struct zkw_proxy p;
    return &p;
}

static inline unsigned zkw_be32(const unsigned char *b)
{
    return ((unsigned)b[0] << 24) | ((unsigned)b[1] << 16) | ((unsigned)b[2] << 8) |
           (unsigned)b[3];
}

static inline int zkw_write_all(int fd, const unsigned char *b, size_t n)
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

/* Appends a space-delimited frame label to `p->labels` (caller must hold `p->mu`). */
static inline void zkw_add_label_locked(const char *text)
{
    struct zkw_proxy *p = zkw();
    size_t n = strlen(text);
    if (p->nlabels + n + 2 >= sizeof p->labels) {
        return;
    }
    if (p->nlabels > 0) {
        p->labels[p->nlabels++] = ' ';
    }
    memcpy(p->labels + p->nlabels, text, n);
    p->nlabels += n;
    p->labels[p->nlabels] = '\0';
}

/* Classifies and records a label for a complete client request frame `f` of `len` bytes. */
static inline void zkw_label_frame(const unsigned char *f, unsigned len)
{
    struct zkw_proxy *p = zkw();
    char text[48];
    int xid;
    int op;
    if (p->up_first) {
        p->up_first = 0;
        zkw_add_label_locked("K");
        return;
    }
    if (len < 8) {
        zkw_add_label_locked("?");
        return;
    }
    xid = (int)zkw_be32(f);
    op = (int)zkw_be32(f + 4);
    if (xid == -2) {
        return;
    }
    if (xid == -4 && op == 100) {
        /* Jute `AuthPacket` layout: `int32 type`, `string scheme`, `buffer auth`. */
        unsigned off = 12;
        unsigned slen = len >= off + 4 ? zkw_be32(f + off) : 0;
        unsigned alen;
        off += 4 + slen;
        alen = len >= off + 4 ? zkw_be32(f + off) : 0;
        off += 4;
        if (alen > 32 || off + alen > len) {
            alen = 0;
        }
        snprintf(text, sizeof text, "A:%.*s", (int)alen, (const char *)(f + off));
    } else if (xid == -8) {
        snprintf(text, sizeof text, "W");
    } else if (op == 3) {
        snprintf(text, sizeof text, "E");
    } else if (op == 102) {
        snprintf(text, sizeof text, "S");
    } else if (op == -11) {
        snprintf(text, sizeof text, "X");
    } else {
        snprintf(text, sizeof text, "T%d", op);
    }
    zkw_add_label_locked(text);
}

/* Extracts and labels all complete length-prefixed request frames in `p->up`. */
static inline void zkw_parse_up_locked(void)
{
    struct zkw_proxy *p = zkw();
    while (!p->up_broken && p->nup >= 4) {
        unsigned len = zkw_be32(p->up);
        if (len > ZKW_UP_CAP - 4) {
            p->up_broken = 1;
            zkw_add_label_locked("?");
            break;
        }
        if (p->nup < 4 + (size_t)len) {
            break;
        }
        zkw_label_frame(p->up + 4, len);
        memmove(p->up, p->up + 4 + len, p->nup - 4 - len);
        p->nup -= 4 + len;
    }
}

static inline void zkw_drop_locked(int hard);

/* Returns 1 if `p->down` begins with at least one complete length-prefixed frame. */
static inline int zkw_down_has_frame_locked(void)
{
    struct zkw_proxy *p = zkw();
    return p->ndown >= 4 && p->ndown >= 4 + (size_t)zkw_be32(p->down);
}

/*
 * Scans `p->down` for ping response frames (`xid == -2`) queued behind held
 * frames and forwards them out-of-band while preserving the relative order of
 * all non-ping frames.
 */
static inline void zkw_pass_pings_locked(void)
{
    struct zkw_proxy *p = zkw();
    size_t off = 0;
    while (p->c >= 0 && p->ndown - off >= 4) {
        size_t flen = 4 + (size_t)zkw_be32(p->down + off);
        if (p->ndown - off < flen) {
            break;
        }
        if (flen >= 8 && (int)zkw_be32(p->down + off + 4) == -2) {
            if (zkw_write_all(p->c, p->down + off, flen) != 0) {
                break;
            }
            memmove(p->down + off, p->down + off + flen, p->ndown - off - flen);
            p->ndown -= flen;
        } else {
            off += flen;
        }
    }
}

/*
 * Forwards buffered server response frames up to `p->budget`, closing the
 * client connection once the upstream socket has closed and no complete
 * frames remain buffered.
 */
static inline void zkw_flush_down_locked(void)
{
    struct zkw_proxy *p = zkw();
    while (p->c >= 0 && p->ndown >= 4) {
        unsigned len = zkw_be32(p->down);
        int ping;
        if (p->ndown < 4 + (size_t)len) {
            break;
        }
        ping = len >= 4 && (int)zkw_be32(p->down + 4) == -2;
        if (!ping && p->budget == 0) {
            break;
        }
        if (zkw_write_all(p->c, p->down, 4 + (size_t)len) != 0) {
            break;
        }
        memmove(p->down, p->down + 4 + len, p->ndown - 4 - len);
        p->ndown -= 4 + len;
        if (!ping && p->budget > 0) {
            p->budget--;
        }
    }
    if (p->budget == 0) {
        zkw_pass_pings_locked();
    }
    if (p->c >= 0 && p->s < 0 && !zkw_down_has_frame_locked()) {
        zkw_drop_locked(0);
    }
}

/* Closes the active client and upstream sockets (`hard=1` sends a TCP `RST` to the client). */
static inline void zkw_drop_locked(int hard)
{
    struct zkw_proxy *p = zkw();
    if (p->c >= 0) {
        if (hard) {
            struct linger lg = {1, 0};
            setsockopt(p->c, SOL_SOCKET, SO_LINGER, &lg, sizeof lg);
        }
        close(p->c);
    }
    if (p->s >= 0) {
        close(p->s);
    }
    p->c = -1;
    p->s = -1;
    p->nup = 0;
    p->ndown = 0;
}

static inline void zkw_wake(void)
{
    char b = 1;
    ssize_t w = write(zkw()->wake[1], &b, 1);
    (void)w;
}

static inline void zkw_accept_locked(void)
{
    struct zkw_proxy *p = zkw();
    int c = accept(p->lfd, NULL, NULL);
    int s;
    if (c < 0) {
        return;
    }
    s = socket(AF_INET, SOCK_STREAM, 0);
    if (s < 0 || connect(s, (struct sockaddr *)&p->target, sizeof p->target) != 0) {
        struct linger lg = {1, 0};
        if (s >= 0) {
            close(s);
        }
        setsockopt(c, SOL_SOCKET, SO_LINGER, &lg, sizeof lg);
        close(c);
        return;
    }
    zkw_drop_locked(0);
    p->c = c;
    p->s = s;
    p->up_first = 1;
    p->up_broken = 0;
}

static inline void *zkw_main(void *arg)
{
    struct zkw_proxy *p = zkw();
    unsigned char buf[16384];
    (void)arg;
    for (;;) {
        struct pollfd pf[4];
        int i;
        pthread_mutex_lock(&p->mu);
        if (p->stop) {
            zkw_drop_locked(0);
            pthread_mutex_unlock(&p->mu);
            break;
        }
        if (p->reset_req) {
            zkw_drop_locked(1);
            p->reset_req = 0;
            p->resets++;
        }
        zkw_flush_down_locked();
        pf[0].fd = p->wake[0];
        pf[1].fd = p->lfd;
        pf[2].fd = p->c;
        pf[3].fd = p->ndown < ZKW_DOWN_CAP ? p->s : -1;
        pthread_mutex_unlock(&p->mu);
        for (i = 0; i < 4; i++) {
            pf[i].events = POLLIN;
            pf[i].revents = 0;
        }
        if (poll(pf, 4, -1) < 0) {
            continue;
        }
        pthread_mutex_lock(&p->mu);
        if ((pf[0].revents & POLLIN) != 0) {
            ssize_t r = read(p->wake[0], buf, sizeof buf);
            (void)r;
        }
        if ((pf[1].revents & POLLIN) != 0) {
            zkw_accept_locked();
        } else {
            int c_ev = pf[2].revents & (POLLIN | POLLHUP | POLLERR);
            int s_ev = pf[3].revents & (POLLIN | POLLHUP | POLLERR);
            if (p->c >= 0 && pf[2].fd == p->c && c_ev) {
                ssize_t n = read(p->c, buf, sizeof buf);
                /* If the upstream server socket has already closed, discard client bytes. */
                if (n <= 0 || (p->s >= 0 && zkw_write_all(p->s, buf, (size_t)n) != 0)) {
                    zkw_drop_locked(0);
                } else {
                    size_t room = ZKW_UP_CAP - p->nup;
                    size_t take = (size_t)n < room ? (size_t)n : room;
                    memcpy(p->up + p->nup, buf, take);
                    p->nup += take;
                    zkw_parse_up_locked();
                }
            }
            if (p->s >= 0 && pf[3].fd == p->s && s_ev) {
                size_t room = ZKW_DOWN_CAP - p->ndown;
                ssize_t n = read(p->s, p->down + p->ndown, room < sizeof buf ? room : sizeof buf);
                if (n <= 0) {
                    /* Retain buffered downstream frames until released to the client. */
                    close(p->s);
                    p->s = -1;
                } else {
                    p->ndown += (size_t)n;
                }
            }
        }
        pthread_mutex_unlock(&p->mu);
    }
    return NULL;
}

/* Starts the wire proxy forwarding to `target` (`"ip:port"`), returning `"127.0.0.1:port"`. */
static inline const char *zkw_start(const char *target, long budget)
{
    struct zkw_proxy *p = zkw();
    struct sockaddr_in a;
    socklen_t alen = sizeof a;
    const char *colon = target != NULL ? strrchr(target, ':') : NULL;
    char ip[64];
    int one = 1;
    if (colon == NULL || (size_t)(colon - target) >= sizeof ip) {
        zkc_fail("bad wire proxy target");
        return NULL;
    }
    memcpy(ip, target, (size_t)(colon - target));
    ip[colon - target] = '\0';
    memset(&p->target, 0, sizeof p->target);
    p->target.sin_family = AF_INET;
    p->target.sin_port = htons((unsigned short)atoi(colon + 1));
    memset(&a, 0, sizeof a);
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    pthread_mutex_init(&p->mu, NULL);
    p->c = -1;
    p->s = -1;
    p->budget = budget;
    p->lfd = socket(AF_INET, SOCK_STREAM, 0);
    if (inet_pton(AF_INET, ip, &p->target.sin_addr) != 1 || p->lfd < 0 || pipe(p->wake) != 0) {
        zkc_fail("wire proxy socket: %s", strerror(errno));
        return NULL;
    }
    setsockopt(p->lfd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    if (bind(p->lfd, (struct sockaddr *)&a, sizeof a) != 0 || listen(p->lfd, 8) != 0 ||
        getsockname(p->lfd, (struct sockaddr *)&a, &alen) != 0) {
        zkc_fail("wire proxy listen: %s", strerror(errno));
        return NULL;
    }
    fcntl(p->wake[0], F_SETFL, O_NONBLOCK);
    fcntl(p->wake[1], F_SETFL, O_NONBLOCK);
    snprintf(p->host, sizeof p->host, "127.0.0.1:%d", (int)ntohs(a.sin_port));
    if (pthread_create(&p->th, NULL, zkw_main, NULL) != 0) {
        zkc_fail("wire proxy thread");
        return NULL;
    }
    p->running = 1;
    return p->host;
}

static inline void zkw_stop(void)
{
    struct zkw_proxy *p = zkw();
    if (!p->running) {
        return;
    }
    pthread_mutex_lock(&p->mu);
    p->stop = 1;
    pthread_mutex_unlock(&p->mu);
    zkw_wake();
    pthread_join(p->th, NULL);
    close(p->lfd);
    close(p->wake[0]);
    close(p->wake[1]);
    p->running = 0;
}

/* Updates the downstream frame budget (`0` = hold, `n` = forward `n` frames, `-1` = unlimited). */
static inline void zkw_budget(long budget)
{
    pthread_mutex_lock(&zkw()->mu);
    zkw()->budget = budget;
    pthread_mutex_unlock(&zkw()->mu);
    zkw_wake();
}

/* Terminates the active client connection with a TCP `RST` and discards buffered frames. */
static inline int zkw_reset(void)
{
    struct zkw_proxy *p = zkw();
    int want;
    int i;
    pthread_mutex_lock(&p->mu);
    want = p->resets + 1;
    p->reset_req = 1;
    pthread_mutex_unlock(&p->mu);
    zkw_wake();
    for (i = 0; i < 500; i++) {
        int done;
        pthread_mutex_lock(&p->mu);
        done = p->resets >= want;
        pthread_mutex_unlock(&p->mu);
        if (done) {
            return 0;
        }
        zkc_sleep_ms(10);
    }
    zkc_fail("wire proxy reset timed out");
    return -1;
}

/*
 * Copies recorded request frame labels since the last `zkw_clear` into `buf`,
 * truncating to `len - 1` bytes via `memcpy` to avoid `-Wformat-truncation`.
 */
static inline const char *zkw_labels(char *buf, size_t len)
{
    size_t n;

    if (len == 0) {
        return buf;
    }
    pthread_mutex_lock(&zkw()->mu);
    n = strlen(zkw()->labels);
    if (n >= len) {
        n = len - 1;
    }
    memcpy(buf, zkw()->labels, n);
    buf[n] = '\0';
    pthread_mutex_unlock(&zkw()->mu);
    return buf;
}

static inline void zkw_clear(void)
{
    pthread_mutex_lock(&zkw()->mu);
    zkw()->nlabels = 0;
    zkw()->labels[0] = '\0';
    pthread_mutex_unlock(&zkw()->mu);
}

/* Returns the count of recorded request labels beginning with `prefix`. */
static inline int zkw_count(const char *prefix)
{
    char buf[ZKW_LABEL_CAP];
    char *save = NULL;
    char *tok;
    int n = 0;
    zkw_labels(buf, sizeof buf);
    for (tok = strtok_r(buf, " ", &save); tok != NULL; tok = strtok_r(NULL, " ", &save)) {
        if (strncmp(tok, prefix, strlen(prefix)) == 0) {
            n++;
        }
    }
    return n;
}

/* Drives the poll registry for up to `ms` milliseconds until `n` labels match `prefix`. */
static inline int zkw_wait_count(const char *prefix, int n, int ms)
{
    int waited;
    for (waited = 0; waited < ms; waited += 10) {
        if (zkw_count(prefix) >= n) {
            return 0;
        }
        zkc_poll(10);
    }
    return zkw_count(prefix) >= n ? 0 : -1;
}

/* Drives the poll registry for up to `ms` milliseconds until `*flag >= n`. */
static inline int zkw_wait_int(const volatile int *flag, int n, int ms)
{
    int waited;
    for (waited = 0; waited < ms; waited += 10) {
        if (__atomic_load_n(flag, __ATOMIC_SEQ_CST) >= n) {
            return 0;
        }
        zkc_poll(10);
    }
    return __atomic_load_n(flag, __ATOMIC_SEQ_CST) >= n ? 0 : -1;
}

#endif /* ZKCONF_WIRE_H_ */
