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
 * Drainage of in-flight requests when migrating from a read-only server to a
 * read/write server. Mode: DEV.
 *
 * A mock two-port server initially accepts sessions in read-only mode, responds
 * to pings, answers "isro" four-letter probes with "ro", and holds all other
 * requests. Once the client connects with ZOO_READONLY, receives the READONLY
 * session event, and has six requests held in flight (async delete, async
 * create, digest auth, and three synchronous calls on dedicated threads:
 * exists, wget, create2), the mock server switches to answering "isro" with
 * "rw" and accepting sessions in read/write mode.
 *
 * Invariants and deviations:
 * - Both cores emit a CONNECTING session event and migrate to
 *   the read/write endpoint.
 * - Legacy C: cleanup(zh, ZOK) drains all six pending requests with
 *   ZOK. Because no server response payload was deserialized, synchronous
 *   wrappers copy out of their zero-initialized sync_completion structs:
 *   zoo_exists zeroes Stat, zoo_wget leaves buffer_len at capacity and zeroes
 *   Stat, and zoo_create2 copies the union-aliased {path_buffer,
 *   path_buffer_len} fields into the output Stat struct.
 * - Rust: all six in-flight requests fail with
 *   ZCONNECTIONLOSS, leaving caller output buffers unmodified.
 *
 * zkconf-skip-st: a legacy st client aborts on a sync call in teardown
 */

#include "zkconf.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <poll.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define NCONN 16
#define CONN_BUF 8192
#define NPENDING 6
#define FILL 0xa5
#define WBUF_CAP 32
#define PBUF_CAP 64

struct fconn {
    int fd;
    int session;
    int handshake;
    int ro;
    size_t len;
    unsigned char buf[CONN_BUF];
};

static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_cv = PTHREAD_COND_INITIALIZER;
static int g_lfd[2] = {-1, -1};
static int g_wake[2] = {-1, -1};
static int g_stop;
static int g_rw;
static int g_held;
static int g_ro_sessions;
static int g_rw_sessions;
static int g_probes;
static struct fconn g_conns[NCONN];

/* Session events in order of arrival. */
static int g_states[32];
static int g_nstates;

/* Results of the six pending requests. */
static int g_del_rc = 1;
static int g_create_rc = 1;
static int g_auth_rc = 1;
static int g_ncb;

static unsigned be32(const unsigned char *b)
{
    return ((unsigned)b[0] << 24) | ((unsigned)b[1] << 16) |
           ((unsigned)b[2] << 8) | (unsigned)b[3];
}

static void put32(unsigned char *b, unsigned v)
{
    b[0] = (unsigned char)(v >> 24);
    b[1] = (unsigned char)(v >> 16);
    b[2] = (unsigned char)(v >> 8);
    b[3] = (unsigned char)v;
}

static void put64(unsigned char *b, unsigned long long v)
{
    put32(b, (unsigned)(v >> 32));
    put32(b + 4, (unsigned)v);
}

static void send_all(int fd, const unsigned char *b, size_t n)
{
    while (n > 0) {
        ssize_t w = send(fd, b, n, ZKC_NOSIGNAL);
        if (w <= 0) {
            return;
        }
        b += w;
        n -= (size_t)w;
    }
}

static void send_reply(int fd, unsigned xid, int err)
{
    unsigned char f[20];
    put32(f, 16);
    put32(f + 4, xid);
    put64(f + 8, 1);
    put32(f + 16, (unsigned)err);
    send_all(fd, f, sizeof(f));
}

static void drop_conn(struct fconn *c)
{
    close(c->fd);
    c->fd = -1;
}

/* Processes the initial ConnectRequest frame of a session. */
static void on_connect(struct fconn *c, const unsigned char *body,
                       unsigned blen)
{
    unsigned char f[41];
    unsigned long long sid = 0;
    unsigned timeout = 10000;

    if (blen >= 24) {
        timeout = be32(body + 12);
        sid = ((unsigned long long)be32(body + 16) << 32) | be32(body + 20);
    }
    if (sid == 0) {
        sid = 0x5a5a0001ULL;
    }
    c->handshake = 1;
    c->ro = !g_rw;
    if (c->ro) {
        g_ro_sessions++;
    } else {
        g_rw_sessions++;
    }
    put32(f, 37);
    put32(f + 4, 0);
    put32(f + 8, timeout);
    put64(f + 12, sid);
    put32(f + 20, 16);
    memset(f + 24, 0x11, 16);
    f[40] = (unsigned char)(c->ro ? 1 : 0);
    send_all(c->fd, f, sizeof(f));
}

/* Processes a length-delimited frame; returns 0 if the socket should close. */
static int on_frame(struct fconn *c, const unsigned char *body, unsigned blen)
{
    unsigned xid;
    int type;

    if (!c->handshake) {
        on_connect(c, body, blen);
        return 1;
    }
    if (blen < 8) {
        return 0;
    }
    xid = be32(body);
    type = (int)be32(body + 4);
    if ((int)xid == -2) {
        send_reply(c->fd, xid, 0);
    } else if (c->ro) {
        g_held++;
        pthread_cond_broadcast(&g_cv);
    } else if ((int)xid == -4 || (int)xid == -8 || type == -11) {
        send_reply(c->fd, xid, 0);
        return type != -11;
    }
    return 1;
}

static void on_readable(struct fconn *c)
{
    ssize_t n = recv(c->fd, c->buf + c->len, CONN_BUF - c->len, 0);
    if (n <= 0) {
        drop_conn(c);
        return;
    }
    c->len += (size_t)n;
    if (!c->session && c->len >= 4) {
        if (memcmp(c->buf, "isro", 4) == 0) {
            g_probes++;
            send_all(c->fd, (const unsigned char *)(g_rw ? "rw" : "ro"), 2);
            drop_conn(c);
            return;
        }
        c->session = 1;
    }
    while (c->session && c->len >= 4) {
        unsigned flen = be32(c->buf);
        if (flen > CONN_BUF - 4) {
            drop_conn(c);
            return;
        }
        if (c->len < 4 + (size_t)flen) {
            return;
        }
        if (!on_frame(c, c->buf + 4, flen)) {
            drop_conn(c);
            return;
        }
        memmove(c->buf, c->buf + 4 + flen, c->len - 4 - flen);
        c->len -= 4 + (size_t)flen;
    }
}

static void on_accept(int lfd)
{
    int i;
    int fd = accept(lfd, NULL, NULL);
    if (fd < 0) {
        return;
    }
    for (i = 0; i < NCONN; i++) {
        if (g_conns[i].fd < 0) {
            memset(&g_conns[i], 0, sizeof(g_conns[i]));
            g_conns[i].fd = fd;
            return;
        }
    }
    close(fd);
}

static void *fake_main(void *arg)
{
    (void)arg;
    for (;;) {
        struct pollfd p[NCONN + 3];
        int map[NCONN + 3];
        int n = 0;
        int i;

        pthread_mutex_lock(&g_mu);
        if (g_stop) {
            pthread_mutex_unlock(&g_mu);
            break;
        }
        p[n].fd = g_wake[0];
        p[n].events = POLLIN;
        map[n++] = -3;
        for (i = 0; i < 2; i++) {
            p[n].fd = g_lfd[i];
            p[n].events = POLLIN;
            map[n++] = -1 - i;
        }
        for (i = 0; i < NCONN; i++) {
            if (g_conns[i].fd >= 0) {
                p[n].fd = g_conns[i].fd;
                p[n].events = POLLIN;
                map[n++] = i;
            }
        }
        pthread_mutex_unlock(&g_mu);
        if (poll(p, (nfds_t)n, 200) <= 0) {
            continue;
        }
        pthread_mutex_lock(&g_mu);
        for (i = 0; i < n; i++) {
            if ((p[i].revents & (POLLIN | POLLHUP | POLLERR)) == 0) {
                continue;
            }
            if (map[i] == -3) {
                char b[16];
                /* Best-effort pipe drain (assign return value to satisfy warn_unused_result). */
                ssize_t got = read(g_wake[0], b, sizeof(b));
                (void)got;
            } else if (map[i] < 0) {
                on_accept(g_lfd[-1 - map[i]]);
            } else if (g_conns[map[i]].fd == p[i].fd) {
                on_readable(&g_conns[map[i]]);
            }
        }
        pthread_mutex_unlock(&g_mu);
    }
    return NULL;
}

static int listen_on(int port)
{
    struct sockaddr_in sa;
    int one = 1;
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) {
        return -1;
    }
    (void)setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET;
    sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    sa.sin_port = htons((unsigned short)port);
    if (bind(fd, (struct sockaddr *)&sa, sizeof(sa)) != 0 ||
        listen(fd, 8) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static void rw_watcher(zhandle_t *zh, int type, int state, const char *path,
                       void *ctx)
{
    (void)zh;
    (void)path;
    (void)ctx;
    if (type != ZOO_SESSION_EVENT) {
        return;
    }
    pthread_mutex_lock(&g_mu);
    if (g_nstates < (int)(sizeof(g_states) / sizeof(g_states[0]))) {
        g_states[g_nstates++] = state;
    }
    pthread_cond_broadcast(&g_cv);
    pthread_mutex_unlock(&g_mu);
}

static void result(int *slot, int rc)
{
    pthread_mutex_lock(&g_mu);
    *slot = rc;
    g_ncb++;
    pthread_cond_broadcast(&g_cv);
    pthread_mutex_unlock(&g_mu);
}

static void del_cb(int rc, const void *d)
{
    (void)d;
    result(&g_del_rc, rc);
}

static void create_cb(int rc, const char *value, const void *d)
{
    (void)value;
    (void)d;
    result(&g_create_rc, rc);
}

static void auth_cb(int rc, const void *d)
{
    (void)d;
    result(&g_auth_rc, rc);
}

/* Index of the first state STATE at or after FROM, or -1. */
static int state_at(int from, int state)
{
    int i;
    for (i = from; i < g_nstates; i++) {
        if (g_states[i] == state) {
            return i;
        }
    }
    return -1;
}

/* Waits under g_mu until PRED holds or MS pass. */
#define WAIT_FOR(pred, ms)                                                 \
    do {                                                                   \
        struct timespec ts_;                                               \
        clock_gettime(CLOCK_REALTIME, &ts_);                               \
        ts_.tv_sec += (ms) / 1000;                                         \
        pthread_mutex_lock(&g_mu);                                         \
        while (!(pred)) {                                                  \
            if (pthread_cond_timedwait(&g_cv, &g_mu, &ts_) != 0) {         \
                break;                                                     \
            }                                                              \
        }                                                                  \
        pthread_mutex_unlock(&g_mu);                                       \
    } while (0)

static zhandle_t *g_zh;

static struct {
    int rc;
    struct Stat st;
} g_exists = {1, {0}};

static struct {
    int rc;
    char buf[WBUF_CAP];
    int blen;
    struct Stat st;
} g_wget = {1, {0}, WBUF_CAP, {0}};

static struct {
    int rc;
    char path[PBUF_CAP];
    struct Stat st;
} g_create2 = {1, {0}, {0}};

static void *exists_main(void *arg)
{
    (void)arg;
    g_exists.rc = zoo_exists(g_zh, "/ldgA9e", 0, &g_exists.st);
    return NULL;
}

static void *wget_main(void *arg)
{
    (void)arg;
    g_wget.rc = zoo_wget(g_zh, "/ldgA9w", NULL, NULL, g_wget.buf,
                         &g_wget.blen, &g_wget.st);
    return NULL;
}

static void *create2_main(void *arg)
{
    (void)arg;
    g_create2.rc = zoo_create2(g_zh, "/ldgA9k", "v", 1, &ZOO_OPEN_ACL_UNSAFE,
                               0, g_create2.path, PBUF_CAP, &g_create2.st);
    return NULL;
}

/* Returns 1 if all len bytes at p equal b. */
static int all_bytes(const void *p, size_t len, unsigned char b)
{
    const unsigned char *q = (const unsigned char *)p;
    size_t i;
    for (i = 0; i < len; i++) {
        if (q[i] != b) {
            return 0;
        }
    }
    return 1;
}

int main(int argc, char **argv)
{
    pthread_t fake;
    pthread_t th[3];
    void *(*mains[3])(void *) = {exists_main, wget_main, create2_main};
    const char *dead;
    const char *colon;
    char hosts[64];
    struct Stat legacy_c2;
    char *pbuf = g_create2.path;
    int pcap = PBUF_CAP;
    int port;
    int ro_at;
    int conn_at;
    int rw_at;
    int ok;
    int rust;
    int rc;
    int i;

    zkc_init(argc, argv, "ldg_a_rw_switch", 0, "DEV", "ignore");
    rust = zkc_is_rust();
    dead = zkc_dead();
    colon = strrchr(dead, ':');
    port = colon != NULL ? atoi(colon + 1) : 0;
    for (i = 0; i < NCONN; i++) {
        g_conns[i].fd = -1;
    }
    g_lfd[0] = listen_on(port);
    g_lfd[1] = listen_on(port + 4);
    if (port <= 0 || g_lfd[0] < 0 || g_lfd[1] < 0 || pipe(g_wake) != 0 ||
        pthread_create(&fake, NULL, fake_main, NULL) != 0) {
        zkc_fail("fake server setup failed on port %d", port);
        return zkc_finish();
    }
    snprintf(hosts, sizeof(hosts), "127.0.0.1:%d,127.0.0.1:%d", port,
             port + 4);

    memset(&g_exists.st, FILL, sizeof(g_exists.st));
    memset(g_wget.buf, FILL, sizeof(g_wget.buf));
    memset(&g_wget.st, FILL, sizeof(g_wget.st));
    memset(g_create2.path, FILL, sizeof(g_create2.path));
    memset(&g_create2.st, FILL, sizeof(g_create2.st));

    g_zh = zookeeper_init(hosts, rw_watcher, 10000, NULL, NULL, ZOO_READONLY);
    if (g_zh == NULL) {
        zkc_fail("zookeeper_init failed");
        return zkc_finish();
    }
    WAIT_FOR(state_at(0, ZOO_READONLY_STATE) >= 0, 10000);
    pthread_mutex_lock(&g_mu);
    ro_at = state_at(0, ZOO_READONLY_STATE);
    pthread_mutex_unlock(&g_mu);
    if (ro_at < 0) {
        zkc_fail("no READONLY session event");
        zookeeper_close(g_zh);
        return zkc_finish();
    }

    rc = zoo_adelete(g_zh, "/ldgA9d", -1, del_cb, NULL);
    ok = rc == ZOK;
    rc = zoo_acreate(g_zh, "/ldgA9c", "v", 1, &ZOO_OPEN_ACL_UNSAFE, 0,
                     create_cb, NULL);
    ok = ok && rc == ZOK;
    rc = zoo_add_auth(g_zh, "digest", "u:p", 3, auth_cb, NULL);
    ok = ok && rc == ZOK;
    for (i = 0; i < 3; i++) {
        ok = ok && pthread_create(&th[i], NULL, mains[i], NULL) == 0;
    }
    if (!ok) {
        zkc_fail("submit of the pending work failed");
        zookeeper_close(g_zh);
        return zkc_finish();
    }
    WAIT_FOR(g_held >= NPENDING, 10000);
    pthread_mutex_lock(&g_mu);
    zkc_ev("held", "requests=%d callbacks=%d", g_held, g_ncb);
    g_rw = 1;
    pthread_mutex_unlock(&g_mu);

    WAIT_FOR(state_at(ro_at, ZOO_CONNECTED_STATE) >= 0, 30000);
    for (i = 0; i < 3; i++) {
        pthread_join(th[i], NULL);
    }
    WAIT_FOR(g_ncb >= 3, 5000);

    pthread_mutex_lock(&g_mu);
    conn_at = state_at(ro_at, ZOO_CONNECTING_STATE);
    rw_at = conn_at >= 0 ? state_at(conn_at, ZOO_CONNECTED_STATE) : -1;
    zkc_dev("DV-FIX-9", conn_at > ro_at && rw_at > conn_at &&
                            g_rw_sessions == 1,
            "events: READONLY at %d, CONNECTING at %d, CONNECTED at %d; "
            "ro sessions %d, rw sessions %d, probes %d", ro_at, conn_at,
            rw_at, g_ro_sessions, g_rw_sessions, g_probes);
    zkc_dev("DV-FIX-9",
            rust ? (g_del_rc == ZCONNECTIONLOSS &&
                    g_create_rc == ZCONNECTIONLOSS &&
                    g_auth_rc == ZCONNECTIONLOSS)
                 : (g_del_rc == ZOK && g_create_rc == ZOK &&
                    g_auth_rc == ZOK),
            "async: delete %s, create %s, auth %s", zkc_rc(g_del_rc),
            zkc_rc(g_create_rc), zkc_rc(g_auth_rc));
    pthread_mutex_unlock(&g_mu);

    zkc_dev("DV-FIX-9",
            rust ? (g_exists.rc == ZCONNECTIONLOSS &&
                    all_bytes(&g_exists.st, sizeof(g_exists.st), FILL))
                 : (g_exists.rc == ZOK &&
                    all_bytes(&g_exists.st, sizeof(g_exists.st), 0)),
            "sync exists: %s, stat %s", zkc_rc(g_exists.rc),
            all_bytes(&g_exists.st, sizeof(g_exists.st), FILL) ? "untouched"
            : all_bytes(&g_exists.st, sizeof(g_exists.st), 0)  ? "zero"
                                                               : "other");
    zkc_dev("DV-FIX-9",
            all_bytes(g_wget.buf, sizeof(g_wget.buf), FILL) &&
                g_wget.blen == WBUF_CAP &&
                (rust ? (g_wget.rc == ZCONNECTIONLOSS &&
                         all_bytes(&g_wget.st, sizeof(g_wget.st), FILL))
                      : (g_wget.rc == ZOK &&
                         all_bytes(&g_wget.st, sizeof(g_wget.st), 0))),
            "sync wget: %s, buffer_len %d, buffer %s, stat %s",
            zkc_rc(g_wget.rc), g_wget.blen,
            all_bytes(g_wget.buf, sizeof(g_wget.buf), FILL) ? "untouched"
                                                           : "written",
            all_bytes(&g_wget.st, sizeof(g_wget.st), FILL) ? "untouched"
            : all_bytes(&g_wget.st, sizeof(g_wget.st), 0)  ? "zero"
                                                           : "other");

    /* Legacy union-aliased Stat image: u.str = {path_buffer, path_buffer_len}. */
    memset(&legacy_c2, 0, sizeof(legacy_c2));
    memcpy(&legacy_c2, &pbuf, sizeof(pbuf));
    memcpy((char *)&legacy_c2 + sizeof(pbuf), &pcap, sizeof(pcap));
    zkc_dev("DV-FIX-9",
            rust ? (g_create2.rc == ZCONNECTIONLOSS &&
                    all_bytes(&g_create2.st, sizeof(g_create2.st), FILL) &&
                    all_bytes(g_create2.path, sizeof(g_create2.path), FILL))
                 : (g_create2.rc == ZOK &&
                    memcmp(&g_create2.st, &legacy_c2, sizeof(legacy_c2)) ==
                        0),
            "sync create2: %s, stat %s, path buffer %s",
            zkc_rc(g_create2.rc),
            all_bytes(&g_create2.st, sizeof(g_create2.st), FILL)
                ? "untouched"
            : memcmp(&g_create2.st, &legacy_c2, sizeof(legacy_c2)) == 0
                ? "path buffer image"
                : "other",
            all_bytes(g_create2.path, sizeof(g_create2.path), FILL)
                ? "untouched"
                : "written");

    zookeeper_close(g_zh);
    pthread_mutex_lock(&g_mu);
    g_stop = 1;
    pthread_mutex_unlock(&g_mu);
    {
        /* Best-effort wakeup write (assign return value to satisfy warn_unused_result). */
        ssize_t put = write(g_wake[1], "x", 1);
        (void)put;
    }
    pthread_join(fake, NULL);
    for (i = 0; i < NCONN; i++) {
        if (g_conns[i].fd >= 0) {
            close(g_conns[i].fd);
        }
    }
    close(g_lfd[0]);
    close(g_lfd[1]);
    return zkc_finish();
}
