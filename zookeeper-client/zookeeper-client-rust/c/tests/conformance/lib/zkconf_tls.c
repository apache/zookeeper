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
 * TLS conformance test support implementation and TLS record-layer proxy
 * (`zkconf_tls.h`). Test-only support code; never installed or shipped.
 */

#include "zkconf_tls.h"
#include "zkconf.h"
#include "zkconf_preload.h"

#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

typedef zhandle_t *(*zkt_init_fn)(const char *host, const char *cert,
                                  watcher_fn fn, int recv_timeout,
                                  const clientid_t *clientid, void *context,
                                  int flags);

#define ZKT_MAX_HOSTS 3
#define ZKT_LINE 2048

static zkt_init_fn g_init;
static char g_certs[1024];
static char g_fix[1024];
static char g_out[1024];
static char g_stream_path[1100];
static FILE *g_stream;
static char g_host[ZKT_MAX_HOSTS][64];
static int g_nhosts;
static char g_hosts_buf[256];
static char g_px_host[64];

/* ------------------------------------------------------------------ */
/* Setup                                                               */
/* ------------------------------------------------------------------ */

static void parse_tls_hosts(const char *spec)
{
    const char *p = spec;
    while (p != NULL && *p != '\0' && g_nhosts < ZKT_MAX_HOSTS) {
        const char *end = strchr(p, ',');
        size_t n = end == NULL ? strlen(p) : (size_t)(end - p);
        if (n < sizeof(g_host[0])) {
            memcpy(g_host[g_nhosts], p, n);
            g_host[g_nhosts][n] = '\0';
            g_nhosts++;
        }
        p = end == NULL ? NULL : end + 1;
    }
}

/*
 * `run.sh` supplies the `zk-testkit` TLS fixture directory path at compile time
 * via `-DZKCONF_TLS_FIX=...`.
 */
#ifndef ZKCONF_TLS_FIX
#error "give -DZKCONF_TLS_FIX=<zk-driver TLS fixture directory> (run.sh does)"
#endif

/* Populates `g_fix` and verifies that `ca.pem` is readable (returns 0 on success). */
static int find_fixtures(void)
{
    char ca[1100];
    snprintf(g_fix, sizeof(g_fix), "%s", ZKCONF_TLS_FIX);
    snprintf(ca, sizeof(ca), "%s/ca.pem", g_fix);
    return access(ca, R_OK);
}

static int parse_addr(const char *host, struct sockaddr_in *a)
{
    const char *colon = strrchr(host, ':');
    char ip[64];
    size_t n;
    if (colon == NULL || (size_t)(colon - host) >= sizeof(ip)) {
        return -1;
    }
    n = (size_t)(colon - host);
    memcpy(ip, host, n);
    ip[n] = '\0';
    memset(a, 0, sizeof(*a));
    a->sin_family = AF_INET;
    a->sin_port = htons((unsigned short)atoi(colon + 1));
    return inet_pton(AF_INET, ip, &a->sin_addr) == 1 ? 0 : -1;
}

/*
 * Because `test-server.sh` waits only for the plaintext client port before
 * returning, poll the Netty TLS listener on `host` until it accepts TCP
 * connections or `timeout_ms` elapses.
 */
static int wait_port(const char *host, int timeout_ms)
{
    struct sockaddr_in a;
    long end = zkt_now_ms() + timeout_ms;
    if (parse_addr(host, &a) != 0) {
        return -1;
    }
    while (zkt_now_ms() < end) {
        int s = socket(AF_INET, SOCK_STREAM, 0);
        int rc;
        if (s < 0) {
            return -1;
        }
        rc = connect(s, (struct sockaddr *)&a, sizeof(a));
        close(s);
        if (rc == 0) {
            return 0;
        }
        zkc_sleep_ms(100);
    }
    return -1;
}

int zkt_setup(int nservers)
{
    void *sym = dlsym(RTLD_DEFAULT, "zookeeper_init_ssl");
    const char *dir = getenv("ZKCONF_TLS_DIR");
    const char *out = getenv("ZKCONF_OUT");
    int i;
    if (sym == NULL) {
        zkc_ev("skip", "the library does not export zookeeper_init_ssl");
        return -1;
    }
    memcpy(&g_init, &sym, sizeof(g_init));
    if (dir == NULL || *dir == '\0') {
        zkc_ev("skip", "no TLS servers: run with run.sh --tls DIR");
        return -1;
    }
    snprintf(g_certs, sizeof(g_certs), "%s", dir);
    if (find_fixtures() != 0) {
        zkc_fail("no TLS fixtures under %s", g_fix);
        return -1;
    }
    snprintf(g_out, sizeof(g_out), "%s", out ? out : "/tmp/zkconf-tls");
    snprintf(g_stream_path, sizeof(g_stream_path), "%s.stream", g_out);
    parse_tls_hosts(getenv("ZKCONF_TLS_HOSTS"));
    for (i = 0; i < g_nhosts && i < nservers; i++) {
        if (wait_port(g_host[i], 20000) != 0) {
            zkc_fail("the secure port %s does not open", g_host[i]);
            return -1;
        }
    }
    g_stream = fopen(g_stream_path, "w+");
    if (g_stream == NULL) {
        zkc_fail("cannot open %s", g_stream_path);
        return -1;
    }
    zoo_set_debug_level(ZOO_LOG_LEVEL_ERROR);
    zoo_set_log_stream(g_stream);
    return 0;
}

const char *zkt_host(int index)
{
    if (index < 0 || index >= g_nhosts) {
        zkc_fail("no TLS server %d", index);
        return "127.0.0.1:1";
    }
    return g_host[index];
}

const char *zkt_hosts(int count)
{
    int i;
    g_hosts_buf[0] = '\0';
    for (i = 0; i < count && i < g_nhosts; i++) {
        size_t l = strlen(g_hosts_buf);
        snprintf(g_hosts_buf + l, sizeof(g_hosts_buf) - l, "%s%s",
                 i == 0 ? "" : ",", g_host[i]);
    }
    return g_hosts_buf;
}

const char *zkt_certs(void)
{
    return g_certs;
}

const char *zkt_fix(void)
{
    return g_fix;
}

const char *zkt_out(void)
{
    return g_out;
}

zhandle_t *zkt_init_raw(const char *hosts, const char *cert, watcher_fn fn,
                        int timeout_ms, const clientid_t *id, void *ctx,
                        int flags)
{
    if (g_init == NULL) {
        errno = EINVAL;
        return NULL;
    }
    return g_init(hosts, cert, fn, timeout_ms, id, ctx, flags);
}

zhandle_t *zkt_init(const char *hosts, const char *cert, int timeout_ms,
                    const clientid_t *id, const char *ctx, int flags)
{
    zhandle_t *zh;
    int e;
    errno = 0;
    zh = zkt_init_raw(hosts, cert, zkc_watcher, timeout_ms, id, (void *)ctx,
                      flags);
    e = errno;
    zkc_ev("call", "zookeeper_init_ssl ctx=%s -> %s errno=%s", ctx,
           zh == NULL ? "NULL" : "zh", zh == NULL ? zkt_errno(e) : "-");
    if (zh != NULL) {
        zkc_poll_add(zh);
    }
    return zh;
}

/* ------------------------------------------------------------------ */
/* Text                                                                */
/* ------------------------------------------------------------------ */

/* Copies `in` to `out`, substituting `tok` for every occurrence of `what`. */
static void replace_all(char *out, size_t outlen, const char *in,
                        const char *what, const char *tok)
{
    size_t wl = strlen(what);
    size_t o = 0;
    if (outlen == 0) {
        return;
    }
    while (*in != '\0' && o + 1 < outlen) {
        if (wl > 0 && strncmp(in, what, wl) == 0) {
            const char *t = tok;
            while (*t != '\0' && o + 1 < outlen) {
                out[o++] = *t++;
            }
            in += wl;
        } else {
            out[o++] = *in++;
        }
    }
    out[o] = '\0';
}

void zkt_norm_paths(char *out, size_t outlen, const char *in)
{
    char a[ZKT_LINE];
    char b[ZKT_LINE];
    char c[ZKT_LINE];
    char d[ZKT_LINE];
    replace_all(a, sizeof(a), in, g_certs, "CERTS");
    replace_all(b, sizeof(b), a, g_fix, "FIX");
    replace_all(c, sizeof(c), b, g_out, "OUT");
    if (g_px_host[0] != '\0') {
        replace_all(d, sizeof(d), c, g_px_host, "PX");
    } else {
        snprintf(d, sizeof(d), "%s", c);
    }
    zkc_norm_text(out, outlen, d);
}

/* Replaces `"errno=N(text): "` in log message `s` with `"errno=E: "`. */
static void hide_errno(char *s)
{
    char *p = strstr(s, "errno=");
    char *end;
    if (p == NULL) {
        return;
    }
    end = strstr(p, "): ");
    if (end == NULL) {
        return;
    }
    p += strlen("errno=");
    *p++ = 'E';
    memmove(p, end + 1, strlen(end + 1) + 1);
}

const char *zkt_errno(int e)
{
    switch (e) {
    case 0: return "0";
    case EINVAL: return "EINVAL";
    case ENOMEM: return "ENOMEM";
    case EAGAIN: return "EAGAIN";
    case EINPROGRESS: return "EINPROGRESS";
    case ECONNREFUSED: return "ECONNREFUSED";
    case ECONNRESET: return "ECONNRESET";
    case ETIMEDOUT: return "ETIMEDOUT";
    case ENOTCONN: return "ENOTCONN";
    case EIO: return "EIO";
    default: return "other";
    }
}

/* ------------------------------------------------------------------ */
/* The log stream                                                      */
/* ------------------------------------------------------------------ */

void zkt_log_reset(void)
{
    if (g_stream == NULL) {
        return;
    }
    fflush(g_stream);
    if (ftruncate(fileno(g_stream), 0) != 0) {
        zkc_fail("ftruncate failed");
    }
    rewind(g_stream);
}

/* Scans the log stream file, invoking `fn` (if non-NULL) on each line containing `needle`. */
static int scan(const char *needle, void (*fn)(const char *, void *),
                void *arg)
{
    char *line;
    FILE *in;
    int n = 0;
    if (g_stream == NULL) {
        return 0;
    }
    fflush(g_stream);
    in = fopen(g_stream_path, "r");
    line = malloc(16384);
    if (in == NULL || line == NULL) {
        zkc_fail("cannot read %s", g_stream_path);
        if (in != NULL) {
            fclose(in);
        }
        free(line);
        return 0;
    }
    while (fgets(line, 16384, in) != NULL) {
        size_t len = strlen(line);
        if (len > 0 && line[len - 1] == '\n') {
            line[len - 1] = '\0';
        }
        if (strstr(line, needle) != NULL) {
            n++;
            if (fn != NULL) {
                fn(line, arg);
            }
        }
    }
    fclose(in);
    free(line);
    return n;
}

int zkt_log_count(const char *needle)
{
    return scan(needle, NULL, NULL);
}

#define ZKT_MAX_LINES 16

struct zkt_lines {
    char text[ZKT_MAX_LINES][ZKT_LINE];
    int n;
    int hide;
};

static void keep_line(const char *line, void *arg)
{
    struct zkt_lines *l = arg;
    const char *p = strstr(line, "ZOO_");
    char b[ZKT_LINE];
    int i;
    if (p == NULL) {
        return;
    }
    zkt_norm_paths(b, sizeof(b), p);
    if (l->hide) {
        hide_errno(b);
    }
    for (i = 0; i < l->n; i++) {
        if (strcmp(l->text[i], b) == 0) {
            return;
        }
    }
    if (l->n < ZKT_MAX_LINES) {
        snprintf(l->text[l->n], ZKT_LINE, "%s", b);
        l->n++;
    }
}

void zkt_log_record(const char *label, const char *needle, int hide)
{
    struct zkt_lines *l = calloc(1, sizeof(*l));
    int i;
    if (l == NULL) {
        zkc_fail("out of memory");
        return;
    }
    l->hide = hide;
    scan(needle, keep_line, l);
    for (i = 0; i < l->n; i++) {
        zkc_ev("log", "%s %s", label, l->text[i]);
    }
    if (l->n == 0) {
        zkc_ev("log", "%s none", label);
    }
    free(l);
}

/* ------------------------------------------------------------------ */
/* Preload, time and the Ed25519 key                                   */
/* ------------------------------------------------------------------ */

int zkt_have_preload(void)
{
    return dlsym(RTLD_DEFAULT, "zkconf_connect_mode") != NULL;
}

void zkt_connect_mode(int mode)
{
    void *sym = dlsym(RTLD_DEFAULT, "zkconf_connect_mode");
    void (*fn)(int);
    if (sym == NULL) {
        return;
    }
    memcpy(&fn, &sym, sizeof(fn));
    fn(mode);
}

int zkt_connect_count(void)
{
    void *sym = dlsym(RTLD_DEFAULT, "zkconf_connect_count");
    int (*fn)(void);
    if (sym == NULL) {
        return -1;
    }
    memcpy(&fn, &sym, sizeof(fn));
    return fn();
}

long zkt_connect_time(int index)
{
    void *sym = dlsym(RTLD_DEFAULT, "zkconf_connect_time");
    long (*fn)(int);
    if (sym == NULL) {
        return -1;
    }
    memcpy(&fn, &sym, sizeof(fn));
    return fn(index);
}

long zkt_now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

long zkt_cpu_ms(void)
{
    struct rusage ru;
    if (getrusage(RUSAGE_SELF, &ru) != 0) {
        return -1;
    }
    return (long)(ru.ru_utime.tv_sec + ru.ru_stime.tv_sec) * 1000 +
           (long)(ru.ru_utime.tv_usec + ru.ru_stime.tv_usec) / 1000;
}

int zkt_write_ed25519(const char *path)
{
    static const char b64[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    static const unsigned char prefix[16] = {0x30, 0x2e, 0x02, 0x01, 0x00,
                                             0x30, 0x05, 0x06, 0x03, 0x2b,
                                             0x65, 0x70, 0x04, 0x22, 0x04,
                                             0x20};
    unsigned char der[48];
    char text[65];
    FILE *f;
    int i;
    memcpy(der, prefix, sizeof(prefix));
    for (i = 0; i < 32; i++) {
        der[16 + i] = (unsigned char)(0x41 + i);
    }
    /* 48 DER bytes divide evenly into 16 3-byte Base64 groups without padding. */
    for (i = 0; i < 16; i++) {
        unsigned v = ((unsigned)der[3 * i] << 16) |
                     ((unsigned)der[3 * i + 1] << 8) | der[3 * i + 2];
        text[4 * i] = b64[(v >> 18) & 63];
        text[4 * i + 1] = b64[(v >> 12) & 63];
        text[4 * i + 2] = b64[(v >> 6) & 63];
        text[4 * i + 3] = b64[v & 63];
    }
    text[64] = '\0';
    f = fopen(path, "w");
    if (f == NULL) {
        return -1;
    }
    fprintf(f, "-----BEGIN PRIVATE KEY-----\n%s\n-----END PRIVATE KEY-----\n",
            text);
    return fclose(f);
}

#ifndef THREADED
int zkt_st_pass(zhandle_t *zh, int max_ms, int *interest, int *err,
                const char **where, int *mid)
{
    int fd = -1;
    int rc;
    long ms;
    struct timeval tv;
    fd_set rd;
    fd_set wr;
    *interest = 0;
    *err = 0;
    *where = "-";
    tv.tv_sec = 0;
    tv.tv_usec = 0;
    errno = 0;
    rc = zkc_interest(zh, &fd, interest, &tv);
    *mid = zoo_state(zh);
    if (rc != ZOK) {
        *err = errno;
        *where = "interest";
        return rc;
    }
    ms = (long)tv.tv_sec * 1000 + tv.tv_usec / 1000;
    if (ms > max_ms || ms < 0) {
        ms = max_ms;
    }
    tv.tv_sec = ms / 1000;
    tv.tv_usec = (int)((ms % 1000) * 1000);
    FD_ZERO(&rd);
    FD_ZERO(&wr);
    if (fd >= 0 && (*interest & ZOOKEEPER_READ)) {
        FD_SET(fd, &rd);
    }
    if (fd >= 0 && (*interest & ZOOKEEPER_WRITE)) {
        FD_SET(fd, &wr);
    }
    if (select(fd >= 0 ? fd + 1 : 0, &rd, &wr, NULL, &tv) < 0) {
        zkc_fail("select failed");
        return ZSYSTEMERROR;
    }
    if (fd < 0) {
        return ZOK;
    }
    errno = 0;
    rc = zkc_process(zh, (FD_ISSET(fd, &rd) ? ZOOKEEPER_READ : 0) |
                             (FD_ISSET(fd, &wr) ? ZOOKEEPER_WRITE : 0));
    if (rc == ZNOTHING) {
        return ZOK;
    }
    if (rc != ZOK) {
        *err = errno;
        *where = "process";
    }
    return rc;
}
#endif

/* ------------------------------------------------------------------ */
/* TLS record proxy                                                   */
/* ------------------------------------------------------------------ */

/* Maximum TLS record size: 5-byte header + 2^14 payload + 2048 expansion allowance. */
#define PX_REC_MAX (5 + 16384 + 2048)
#define PX_BUF (2 * PX_REC_MAX)
#define PX_PAIRS 4
#define PX_EVENTS 4096
#define PX_CONNS 64

struct px_side {
    unsigned char buf[PX_BUF];
    size_t n;
};

struct px_pair {
    int c;
    int s;
    int conn;
    int c_eof;
    int s_eof;
    /* Coalesce state: 0 = disabled, 1 = awaiting ConnectRequest, 2 = buffering server records. */
    int coalesce;
    unsigned char *hold;
    size_t holdn;
    int holdrec;
    struct px_side up;
    struct px_side down;
};

struct px_event {
    long seq;
    int conn;
    int dir;
    int type;
    int len;
};

/*
 * `g_tp_mu` synchronizes shared proxy state and event logs; socket pairs are
 * accessed exclusively by the proxy thread.
 */
static pthread_mutex_t g_tp_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_tp_cv = PTHREAD_COND_INITIALIZER;
static struct {
    int running;
    int stop;
    int lfd;
    int wake[2];
    pthread_t th;
    struct sockaddr_in target;
    int action;
    int conns;
    int ended[PX_CONNS];
    long seq;
    struct px_event ev[PX_EVENTS];
    int nev;
    long coalesced;
    int coalesced_rec;
    struct px_pair *p[PX_PAIRS];
    int np;
} g_tp;

static void tp_wake(void)
{
    char b = 1;
    if (write(g_tp.wake[1], &b, 1) < 0) {
        /* A full pipe already wakes the proxy thread. */
    }
}

static int tp_write_all(int fd, const unsigned char *buf, size_t n)
{
    while (n > 0) {
        ssize_t w = send(fd, buf, n, 0);
        if (w < 0 && errno == EINTR) {
            continue;
        }
        if (w <= 0) {
            return -1;
        }
        buf += w;
        n -= (size_t)w;
    }
    return 0;
}

static void tp_event_locked(int conn, int dir, int type, int len)
{
    if (g_tp.nev < PX_EVENTS) {
        g_tp.ev[g_tp.nev].seq = g_tp.seq;
        g_tp.ev[g_tp.nev].conn = conn;
        g_tp.ev[g_tp.nev].dir = dir;
        g_tp.ev[g_tp.nev].type = type;
        g_tp.ev[g_tp.nev].len = len;
        g_tp.nev++;
    }
    g_tp.seq++;
}

/*
 * Identifies the initial client ApplicationData record carrying `ConnectRequest`.
 * The Rust core transmits the 49-byte length-prefixed frame in a single TLS
 * record, whereas the legacy C core transmits the 4-byte length prefix in a
 * separate record ahead of the body (with 17 bytes of AEAD overhead in TLS 1.3
 * or 24 bytes in TLS 1.2 GCM).
 */
static int is_connect_request(int len)
{
    return len == 49 + 17 || len == 4 + 17 || len == 49 + 24 || len == 4 + 24;
}

/* Records and forwards one complete TLS record, applying any armed fault-injection action. */
static void tp_record(struct px_pair *pp, int dir, unsigned char *rec,
                      size_t n)
{
    int type = rec[0];
    int len = (int)(n - 5);
    int action;
    int fd = dir == ZKT_C2S ? pp->s : pp->c;
    pthread_mutex_lock(&g_tp_mu);
    tp_event_locked(pp->conn, dir, type, len);
    action = g_tp.action;
    if (dir == ZKT_S2C && type == 23 &&
        (action == ZKT_PX_CORRUPT || action == ZKT_PX_REPLAY)) {
        g_tp.action = ZKT_PX_PASS;
    } else {
        action = ZKT_PX_PASS;
    }
    pthread_mutex_unlock(&g_tp_mu);
    if (dir == ZKT_C2S && pp->coalesce == 1 && type == 23 &&
        is_connect_request(len)) {
        pp->coalesce = 2;
    }
    if (dir == ZKT_S2C && pp->coalesce == 2) {
        unsigned char *h = realloc(pp->hold, pp->holdn + n);
        if (h != NULL) {
            memcpy(h + pp->holdn, rec, n);
            pp->hold = h;
            pp->holdn += n;
            pp->holdrec++;
        }
        return;
    }
    if (fd < 0) {
        return;
    }
    if (action == ZKT_PX_CORRUPT) {
        rec[n - 1] ^= 0x5a;
        tp_write_all(fd, rec, n);
    } else if (action == ZKT_PX_REPLAY) {
        unsigned char *two = malloc(2 * n);
        if (two != NULL) {
            memcpy(two, rec, n);
            memcpy(two + n, rec, n);
            tp_write_all(fd, two, 2 * n);
            free(two);
        }
    } else {
        tp_write_all(fd, rec, n);
    }
}

/* Parses buffered bytes into discrete TLS records, forwarding non-TLS bytes unmodified. */
static void tp_parse(struct px_pair *pp, int dir)
{
    struct px_side *sd = dir == ZKT_C2S ? &pp->up : &pp->down;
    int fd = dir == ZKT_C2S ? pp->s : pp->c;
    size_t off = 0;
    while (sd->n - off >= 5) {
        unsigned char *h = sd->buf + off;
        size_t len = ((size_t)h[3] << 8) | h[4];
        if (h[0] < 20 || h[0] > 24 || h[1] != 3 || 5 + len > PX_REC_MAX) {
            pthread_mutex_lock(&g_tp_mu);
            tp_event_locked(pp->conn, dir, 0, (int)(sd->n - off));
            pthread_mutex_unlock(&g_tp_mu);
            if (fd >= 0) {
                tp_write_all(fd, h, sd->n - off);
            }
            off = sd->n;
            break;
        }
        if (sd->n - off < 5 + len) {
            break;
        }
        tp_record(pp, dir, h, 5 + len);
        off += 5 + len;
    }
    if (off > 0) {
        memmove(sd->buf, sd->buf + off, sd->n - off);
        sd->n -= off;
    }
}

static void tp_free(int i)
{
    struct px_pair *pp = g_tp.p[i];
    if (pp->c >= 0) {
        close(pp->c);
    }
    if (pp->s >= 0) {
        close(pp->s);
    }
    free(pp->hold);
    free(pp);
    g_tp.p[i] = g_tp.p[--g_tp.np];
}

/* Suppresses `SIGPIPE` on socket `fd` where `SO_NOSIGPIPE` is supported. */
static void no_sigpipe(int fd)
{
#ifdef SO_NOSIGPIPE
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof(one));
#else
    (void)fd;
#endif
}

static void tp_accept(void)
{
    int c = accept(g_tp.lfd, NULL, NULL);
    int s;
    struct sockaddr_in target;
    struct px_pair *pp;
    int coalesce = 0;
    if (c < 0) {
        return;
    }
    pthread_mutex_lock(&g_tp_mu);
    target = g_tp.target;
    if (g_tp.action == ZKT_PX_COALESCE) {
        coalesce = 1;
        g_tp.action = ZKT_PX_PASS;
    }
    pthread_mutex_unlock(&g_tp_mu);
    s = socket(AF_INET, SOCK_STREAM, 0);
    pp = calloc(1, sizeof(*pp));
    if (s < 0 || pp == NULL || g_tp.np >= PX_PAIRS ||
        connect(s, (struct sockaddr *)&target, sizeof(target)) != 0) {
        close(c);
        if (s >= 0) {
            close(s);
        }
        free(pp);
        return;
    }
    pp->c = c;
    pp->s = s;
    pp->coalesce = coalesce;
    no_sigpipe(c);
    no_sigpipe(s);
    pthread_mutex_lock(&g_tp_mu);
    g_tp.conns++;
    pp->conn = g_tp.conns;
    pthread_cond_broadcast(&g_tp_cv);
    pthread_mutex_unlock(&g_tp_mu);
    g_tp.p[g_tp.np++] = pp;
}

/*
 * Handles upstream server EOF, flushing any coalesced records in a single write
 * before half-closing.
 */
static void tp_server_end(struct px_pair *pp)
{
    pp->s_eof = 1;
    if (pp->coalesce == 2) {
        if (pp->holdn > 0) {
            tp_write_all(pp->c, pp->hold, pp->holdn);
        }
        pthread_mutex_lock(&g_tp_mu);
        g_tp.coalesced = (long)pp->holdn;
        g_tp.coalesced_rec = pp->holdrec;
        pthread_mutex_unlock(&g_tp_mu);
        pp->coalesce = 0;
    }
    if (pp->down.n > 0) {
        tp_write_all(pp->c, pp->down.buf, pp->down.n);
        pp->down.n = 0;
    }
    shutdown(pp->c, SHUT_WR);
}

static void tp_client_end(struct px_pair *pp)
{
    pp->c_eof = 1;
    if (pp->s >= 0) {
        shutdown(pp->s, SHUT_WR);
    }
    pthread_mutex_lock(&g_tp_mu);
    if (pp->conn > 0 && pp->conn < PX_CONNS) {
        g_tp.ended[pp->conn] = 1;
    }
    pthread_cond_broadcast(&g_tp_cv);
    pthread_mutex_unlock(&g_tp_mu);
}

/* Executes `ZKT_PX_EOF`: half-closes active client streams and closes upstream sockets. */
static void tp_eof_now(void)
{
    int i;
    for (i = 0; i < g_tp.np; i++) {
        struct px_pair *pp = g_tp.p[i];
        if (!pp->c_eof) {
            shutdown(pp->c, SHUT_WR);
            if (pp->s >= 0) {
                close(pp->s);
                pp->s = -1;
            }
            pp->s_eof = 1;
        }
    }
}

static void *tp_main(void *arg)
{
    (void)arg;
    for (;;) {
        struct pollfd fds[2 + 2 * PX_PAIRS];
        struct px_pair *who[2 + 2 * PX_PAIRS];
        int dirs[2 + 2 * PX_PAIRS];
        int nf = 0;
        int i;
        int action;
        pthread_mutex_lock(&g_tp_mu);
        if (g_tp.stop) {
            pthread_mutex_unlock(&g_tp_mu);
            break;
        }
        action = g_tp.action;
        if (action == ZKT_PX_EOF) {
            g_tp.action = ZKT_PX_PASS;
        }
        pthread_mutex_unlock(&g_tp_mu);
        if (action == ZKT_PX_EOF) {
            tp_eof_now();
        }
        fds[nf].fd = g_tp.lfd;
        fds[nf].events = POLLIN;
        who[nf] = NULL;
        dirs[nf] = -1;
        nf++;
        fds[nf].fd = g_tp.wake[0];
        fds[nf].events = POLLIN;
        who[nf] = NULL;
        dirs[nf] = -2;
        nf++;
        for (i = 0; i < g_tp.np; i++) {
            struct px_pair *pp = g_tp.p[i];
            if (!pp->c_eof) {
                fds[nf].fd = pp->c;
                fds[nf].events = POLLIN;
                who[nf] = pp;
                dirs[nf] = ZKT_C2S;
                nf++;
            }
            if (!pp->s_eof && pp->s >= 0) {
                fds[nf].fd = pp->s;
                fds[nf].events = POLLIN;
                who[nf] = pp;
                dirs[nf] = ZKT_S2C;
                nf++;
            }
        }
        for (i = 0; i < nf; i++) {
            fds[i].revents = 0;
        }
        if (poll(fds, (nfds_t)nf, 200) < 0) {
            if (errno == EINTR) {
                continue;
            }
            break;
        }
        for (i = 0; i < nf; i++) {
            struct px_pair *pp = who[i];
            struct px_side *sd;
            ssize_t r;
            if (fds[i].revents == 0) {
                continue;
            }
            if (dirs[i] == -1) {
                tp_accept();
                continue;
            }
            if (dirs[i] == -2) {
                char b[64];
                while (read(g_tp.wake[0], b, sizeof(b)) > 0) {
                }
                continue;
            }
            sd = dirs[i] == ZKT_C2S ? &pp->up : &pp->down;
            r = recv(fds[i].fd, sd->buf + sd->n, PX_BUF - sd->n, 0);
            if (r > 0) {
                sd->n += (size_t)r;
                tp_parse(pp, dirs[i]);
            } else if (r == 0 || (errno != EINTR && errno != EAGAIN)) {
                if (dirs[i] == ZKT_C2S) {
                    tp_client_end(pp);
                } else {
                    tp_server_end(pp);
                }
            }
        }
        for (i = g_tp.np - 1; i >= 0; i--) {
            if (g_tp.p[i]->c_eof && g_tp.p[i]->s_eof) {
                tp_free(i);
            }
        }
    }
    while (g_tp.np > 0) {
        tp_free(0);
    }
    return NULL;
}

const char *zkt_proxy_start(const char *target)
{
    struct sockaddr_in a;
    socklen_t alen = sizeof(a);
    int one = 1;
    memset(&g_tp.target, 0, sizeof(g_tp.target));
    if (parse_addr(target, &g_tp.target) != 0) {
        zkc_fail("bad proxy target %s", target);
        return NULL;
    }
    memset(&a, 0, sizeof(a));
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    a.sin_port = 0;
    g_tp.lfd = socket(AF_INET, SOCK_STREAM, 0);
    if (g_tp.lfd < 0 || pipe(g_tp.wake) != 0) {
        zkc_fail("proxy socket: %s", strerror(errno));
        return NULL;
    }
    setsockopt(g_tp.lfd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    if (bind(g_tp.lfd, (struct sockaddr *)&a, sizeof(a)) != 0 ||
        listen(g_tp.lfd, 8) != 0 ||
        getsockname(g_tp.lfd, (struct sockaddr *)&a, &alen) != 0) {
        zkc_fail("proxy listen: %s", strerror(errno));
        return NULL;
    }
    fcntl(g_tp.wake[0], F_SETFL, O_NONBLOCK);
    fcntl(g_tp.wake[1], F_SETFL, O_NONBLOCK);
    snprintf(g_px_host, sizeof(g_px_host), "127.0.0.1:%d",
             (int)ntohs(a.sin_port));
    /* Ignore `SIGPIPE` in case a proxy `send` races against a concurrent peer close. */
    signal(SIGPIPE, SIG_IGN);
    g_tp.stop = 0;
    g_tp.action = ZKT_PX_PASS;
    if (pthread_create(&g_tp.th, NULL, tp_main, NULL) != 0) {
        zkc_fail("proxy thread: %s", strerror(errno));
        return NULL;
    }
    g_tp.running = 1;
    return g_px_host;
}

void zkt_proxy_target(const char *target)
{
    struct sockaddr_in a;
    if (parse_addr(target, &a) != 0) {
        zkc_fail("bad proxy target %s", target);
        return;
    }
    pthread_mutex_lock(&g_tp_mu);
    g_tp.target = a;
    pthread_mutex_unlock(&g_tp_mu);
}

void zkt_proxy_arm(int action)
{
    pthread_mutex_lock(&g_tp_mu);
    g_tp.action = action;
    pthread_mutex_unlock(&g_tp_mu);
    tp_wake();
}

long zkt_proxy_mark(void)
{
    long s;
    pthread_mutex_lock(&g_tp_mu);
    s = g_tp.seq;
    pthread_mutex_unlock(&g_tp_mu);
    return s;
}

int zkt_proxy_conns(void)
{
    int n;
    pthread_mutex_lock(&g_tp_mu);
    n = g_tp.conns;
    pthread_mutex_unlock(&g_tp_mu);
    return n;
}

int zkt_proxy_count(int dir, long since, int conn, int type)
{
    int i;
    int n = 0;
    pthread_mutex_lock(&g_tp_mu);
    for (i = 0; i < g_tp.nev; i++) {
        const struct px_event *e = &g_tp.ev[i];
        if (e->seq >= since && e->dir == dir &&
            (conn == 0 || e->conn == conn) && (type == 0 || e->type == type)) {
            n++;
        }
    }
    pthread_mutex_unlock(&g_tp_mu);
    return n;
}

long zkt_proxy_last(int dir, int conn)
{
    int i;
    long s = -1;
    pthread_mutex_lock(&g_tp_mu);
    for (i = 0; i < g_tp.nev; i++) {
        if (g_tp.ev[i].dir == dir && (conn == 0 || g_tp.ev[i].conn == conn)) {
            s = g_tp.ev[i].seq;
        }
    }
    pthread_mutex_unlock(&g_tp_mu);
    return s;
}

long zkt_proxy_first(int dir, int conn, long since)
{
    int i;
    long s = -1;
    pthread_mutex_lock(&g_tp_mu);
    for (i = 0; i < g_tp.nev; i++) {
        const struct px_event *e = &g_tp.ev[i];
        if (e->seq >= since && e->dir == dir &&
            (conn == 0 || e->conn == conn)) {
            s = e->seq;
            break;
        }
    }
    pthread_mutex_unlock(&g_tp_mu);
    return s;
}

int zkt_proxy_wait_end(int conn, int timeout_ms)
{
    struct timespec ts;
    int rc = 0;
    clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec += timeout_ms / 1000;
    ts.tv_nsec += (long)(timeout_ms % 1000) * 1000000L;
    if (ts.tv_nsec >= 1000000000L) {
        ts.tv_sec++;
        ts.tv_nsec -= 1000000000L;
    }
    pthread_mutex_lock(&g_tp_mu);
    while (conn <= 0 || conn >= PX_CONNS || !g_tp.ended[conn]) {
        if (conn <= 0 || conn >= PX_CONNS ||
            pthread_cond_timedwait(&g_tp_cv, &g_tp_mu, &ts) == ETIMEDOUT) {
            rc = -1;
            break;
        }
    }
    pthread_mutex_unlock(&g_tp_mu);
    return rc;
}

long zkt_proxy_coalesced(int *records)
{
    long n;
    pthread_mutex_lock(&g_tp_mu);
    n = g_tp.coalesced;
    if (records != NULL) {
        *records = g_tp.coalesced_rec;
    }
    pthread_mutex_unlock(&g_tp_mu);
    return n;
}

void zkt_proxy_events(char *buf, size_t len, int conn, long since)
{
    size_t o = 0;
    int i;
    if (len == 0) {
        return;
    }
    buf[0] = '\0';
    pthread_mutex_lock(&g_tp_mu);
    for (i = 0; i < g_tp.nev && o + 1 < len; i++) {
        const struct px_event *e = &g_tp.ev[i];
        int w;
        if (e->seq < since || (conn != 0 && e->conn != conn)) {
            continue;
        }
        w = snprintf(buf + o, len - o, "%s%c%d/%d", o == 0 ? "" : " ",
                     e->dir == ZKT_C2S ? 'C' : 'S', e->type, e->len);
        if (w < 0) {
            break;
        }
        o += (size_t)w;
    }
    pthread_mutex_unlock(&g_tp_mu);
    if (o >= len) {
        buf[len - 1] = '\0';
    }
}

void zkt_proxy_stop(void)
{
    if (!g_tp.running) {
        return;
    }
    pthread_mutex_lock(&g_tp_mu);
    g_tp.stop = 1;
    pthread_mutex_unlock(&g_tp_mu);
    tp_wake();
    pthread_join(g_tp.th, NULL);
    close(g_tp.lfd);
    close(g_tp.wake[0]);
    close(g_tp.wake[1]);
    g_tp.running = 0;
}
