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
 * Differential conformance test harness and trace recorder implementation.
 *
 * A single mutex (`g_mu`) serializes all recorder state mutations and trace
 * output across both application and library callback threads, flushing each
 * line immediately so partial transcripts survive process crashes or aborts.
 */

#include "zkconf.h"

#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <execinfo.h>
#include <fcntl.h>
#include <limits.h>
#include <netinet/in.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#ifdef __APPLE__
#include <mach-o/dyld.h>
#include <mach/mach.h>
#else
#include <dirent.h>
#include <link.h>
#endif

#define ZKC_MAX_SIDS 64
#define ZKC_MAX_ZXIDS 8192
#define ZKC_MAX_KEYS 64
#define ZKC_MAX_PORTS 8
#define ZKC_MAX_HOSTS 8
#define ZKC_LINE 8192
#define ZKC_MAX_POLLED 16
#define ZKC_MAX_CALLS 8

struct zkc_label {
    int64_t value;
    char text[24];
};

struct zkc_counter {
    char key[48];
    int count;
    int consumed;
};

static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_cv = PTHREAD_COND_INITIALIZER;

static FILE *g_trace;
static FILE *g_log;
static FILE *g_devf;
static const char *g_core = "c";
static const char *g_id = "?";
static int g_in_child;
static int g_dev_fail;
static int g_dev_pass;
static int g_harness_fail;
static int g_frames_checked;

static struct zkc_label g_sids[ZKC_MAX_SIDS];
static int g_nsids;
static struct zkc_label g_zxids[ZKC_MAX_ZXIDS];
static int g_nzxids;
static struct zkc_counter g_keys[ZKC_MAX_KEYS];
static int g_nkeys;

static char g_port_num[ZKC_MAX_PORTS][8];
static char g_port_name[ZKC_MAX_PORTS][8];
static int g_nports;
static char g_hosts[ZKC_MAX_HOSTS][64];
static int g_nhosts;
static char g_hostlist[512];

static zkc_hook_fn g_hook;
static zkc_whook_fn g_whook;

static __thread int t_role = -1;
static int g_l1_taken;
static int g_next_role = 2;

/* Per-thread stack of active `st` driving calls, innermost last. */
static __thread const char *t_calls[ZKC_MAX_CALLS];
static __thread int t_ncalls;
static int g_cb_total;
static int g_cb_outside;

/* ------------------------------------------------------------------ */
/* Small helpers                                                       */
/* ------------------------------------------------------------------ */

static char *tls_buf(void)
{
    static __thread char bufs[8][128];
    static __thread int next;
    next = (next + 1) % 8;
    return bufs[next];
}

static void assign_role_locked(int watcher)
{
    if (t_role >= 0) {
        return;
    }
    if (watcher && !g_l1_taken) {
        g_l1_taken = 1;
        t_role = 1;
    } else {
        t_role = g_next_role++;
    }
}

const char *zkc_role(void)
{
    char *b;
    if (t_role < 0) {
        pthread_mutex_lock(&g_mu);
        assign_role_locked(0);
        pthread_mutex_unlock(&g_mu);
    }
    if (t_role == 0) {
        return "app";
    }
    b = tls_buf();
    snprintf(b, 128, "L%d", t_role);
    return b;
}

void zkc_role_app(void)
{
    t_role = 0;
}

static FILE *open_out(const char *prefix, const char *suffix, FILE *dflt)
{
    char path[PATH_MAX];
    FILE *f;
    if (prefix == NULL || prefix[0] == '\0') {
        return dflt;
    }
    snprintf(path, sizeof(path), "%s%s", prefix, suffix);
    f = fopen(path, "w");
    if (f == NULL) {
        fprintf(stderr, "zkconf: cannot open %s: %s\n", path, strerror(errno));
        exit(3);
    }
    return f;
}

static void parse_ports(const char *spec)
{
    const char *p = spec;
    while (p != NULL && *p != '\0' && g_nports < ZKC_MAX_PORTS) {
        const char *eq = strchr(p, '=');
        const char *end = strchr(p, ',');
        size_t nl;
        size_t vl;
        if (eq == NULL || (end != NULL && end < eq)) {
            break;
        }
        if (end == NULL) {
            end = p + strlen(p);
        }
        nl = (size_t)(eq - p);
        vl = (size_t)(end - eq - 1);
        if (nl < sizeof(g_port_num[0]) && vl < sizeof(g_port_name[0])) {
            memcpy(g_port_num[g_nports], p, nl);
            g_port_num[g_nports][nl] = '\0';
            memcpy(g_port_name[g_nports], eq + 1, vl);
            g_port_name[g_nports][vl] = '\0';
            g_nports++;
        }
        p = (*end == ',') ? end + 1 : end;
    }
}

static void parse_hosts(const char *spec)
{
    const char *p = spec;
    while (p != NULL && *p != '\0' && g_nhosts < ZKC_MAX_HOSTS) {
        const char *end = strchr(p, ',');
        size_t n;
        if (end == NULL) {
            end = p + strlen(p);
        }
        n = (size_t)(end - p);
        if (n < sizeof(g_hosts[0])) {
            memcpy(g_hosts[g_nhosts], p, n);
            g_hosts[g_nhosts][n] = '\0';
            g_nhosts++;
        }
        p = (*end == ',') ? end + 1 : end;
    }
}

#ifndef __APPLE__
/* `dl_iterate_phdr` callback that records the first loaded `libzookeeper_*` object path. */
static int find_zk_object(struct dl_phdr_info *info, size_t size, void *data)
{
    const char **found = data;
    (void)size;
    if (info->dlpi_name != NULL && strstr(info->dlpi_name, "libzookeeper_") != NULL) {
        *found = info->dlpi_name;
        return 1;
    }
    return 0;
}
#endif

/* Verifies that the dynamically loaded `libzookeeper` image resides in `ZKCONF_LIBDIR`. */
static void check_loaded_library(void)
{
    const char *want = getenv("ZKCONF_LIBDIR");
    char wantreal[PATH_MAX];
    char gotreal[PATH_MAX];
    const char *found = NULL;
#ifdef __APPLE__
    uint32_t i;
    for (i = 0; i < _dyld_image_count(); i++) {
        const char *name = _dyld_get_image_name(i);
        if (name != NULL && strstr(name, "libzookeeper_") != NULL) {
            found = name;
            break;
        }
    }
#else
    (void)dl_iterate_phdr(find_zk_object, &found);
#endif
    if (found == NULL) {
        fprintf(stderr, "zkconf: no libzookeeper image is loaded\n");
        exit(3);
    }
    if (realpath(found, gotreal) == NULL) {
        snprintf(gotreal, sizeof(gotreal), "%s", found);
    }
    if (g_trace != NULL) {
        fprintf(g_trace, "# lib %s\n", gotreal);
    }
    if (want != NULL && want[0] != '\0') {
        char *slash;
        if (realpath(want, wantreal) == NULL) {
            fprintf(stderr, "zkconf: bad ZKCONF_LIBDIR %s\n", want);
            exit(3);
        }
        slash = strrchr(gotreal, '/');
        if (slash != NULL) {
            *slash = '\0';
        }
        if (strcmp(gotreal, wantreal) != 0) {
            fprintf(stderr, "zkconf: loaded library in %s, expected %s\n",
                    gotreal, wantreal);
            exit(3);
        }
    }
}

void zkc_init(int argc, char **argv, const char *test_id, int nservers,
              const char *mode, const char *logmode)
{
    const char *out;
    const char *core;
    if (argc > 1 && strcmp(argv[1], "--describe") == 0) {
        printf("servers=%d mode=%s log=%s\n", nservers, mode, logmode);
        exit(0);
    }
    g_id = test_id;
    core = getenv("ZKCONF_CORE");
    if (core != NULL && core[0] != '\0') {
        g_core = core;
    }
    out = getenv("ZKCONF_OUT");
    g_trace = open_out(out, ".trace", stdout);
    g_log = open_out(out, ".log", stderr);
    g_devf = open_out(out, ".dev", stderr);
    parse_ports(getenv("ZKCONF_PORTS"));
    parse_hosts(getenv("ZKCONF_HOSTS"));
    zkc_role_app();
    /*
     * Many scenarios inject TCP resets. On macOS, the legacy C client does not
     * set `SO_NOSIGPIPE`, so `SIGPIPE` is ignored for legacy runs,
     * single threaded (`st`) runs, and platforms without `MSG_NOSIGNAL`. Rust
     * multithreaded (`mt`) runs preserve `SIG_DFL` so every scenario implicitly
     * verifies `SIGPIPE` suppression (`ldg_d_sigpipe` tests `st` under
     * `SIG_DFL` in an isolated child process).
     */
    if (zkc_is_st() || !zkc_is_rust() || ZKC_NOSIGNAL == 0) {
        signal(SIGPIPE, SIG_IGN);
    }
    fprintf(g_trace, "# test %s core=%s flavor=%s pid=%ld\n", test_id, g_core,
            zkc_is_st() ? "st" : "mt", (long)getpid());
    fprintf(g_trace, "# mode %s log %s\n", mode, logmode);
    check_loaded_library();
    fflush(g_trace);
    zoo_set_debug_level(ZOO_LOG_LEVEL_INFO);
}

int zkc_finish(void)
{
    pthread_mutex_lock(&g_mu);
    if (g_frames_checked > 0 && zkc_is_rust()) {
        fprintf(g_devf, "DEV TH-15 %s %s: %d callbacks checked\n", g_core,
                "PASS", g_frames_checked);
    }
    if (zkc_is_st() && g_cb_total > 0) {
        if (g_cb_outside > 0) {
            g_dev_fail++;
        } else {
            g_dev_pass++;
        }
        fprintf(g_devf, "DEV TH-02 %s %s: %d of %d callbacks outside a call\n",
                g_core, g_cb_outside > 0 ? "FAIL" : "PASS", g_cb_outside,
                g_cb_total);
    }
    fprintf(g_trace, "# end harness_fail=%d dev_fail=%d dev_pass=%d\n",
            g_harness_fail, g_dev_fail, g_dev_pass);
    fflush(g_trace);
    fflush(g_log);
    fflush(g_devf);
    pthread_mutex_unlock(&g_mu);
    if (g_harness_fail > 0) {
        return 2;
    }
    return g_dev_fail > 0 ? 1 : 0;
}

int zkc_is_rust(void)
{
    return strcmp(g_core, "rust") == 0;
}

const char *zkc_core(void)
{
    return g_core;
}

const char *zkc_host(int index)
{
    if (index < 0 || index >= g_nhosts) {
        return "127.0.0.1:1";
    }
    return g_hosts[index];
}

const char *zkc_hosts(int count)
{
    int i;
    size_t used = 0;
    g_hostlist[0] = '\0';
    for (i = 0; i < count && i < g_nhosts; i++) {
        int n = snprintf(g_hostlist + used, sizeof(g_hostlist) - used, "%s%s",
                         i > 0 ? "," : "", g_hosts[i]);
        if (n < 0 || (size_t)n >= sizeof(g_hostlist) - used) {
            break;
        }
        used += (size_t)n;
    }
    return g_hostlist;
}

const char *zkc_dead(void)
{
    const char *d = getenv("ZKCONF_DEAD");
    return (d != NULL && d[0] != '\0') ? d : "127.0.0.1:1";
}

/* ------------------------------------------------------------------ */
/* Normalizers                                                         */
/* ------------------------------------------------------------------ */

static const char *label_locked(struct zkc_label *tab, int *n, int max,
                                char prefix, int64_t value)
{
    int i;
    if (value == 0) {
        return "0";
    }
    for (i = 0; i < *n; i++) {
        if (tab[i].value == value) {
            return tab[i].text;
        }
    }
    if (*n >= max) {
        return "OVERFLOW";
    }
    tab[*n].value = value;
    snprintf(tab[*n].text, sizeof(tab[*n].text), "%c%d", prefix, *n + 1);
    (*n)++;
    return tab[*n - 1].text;
}

const char *zkc_sid(int64_t session_id)
{
    const char *r;
    pthread_mutex_lock(&g_mu);
    r = label_locked(g_sids, &g_nsids, ZKC_MAX_SIDS, 'S', session_id);
    pthread_mutex_unlock(&g_mu);
    return r;
}

const char *zkc_zxid(int64_t zxid)
{
    const char *r;
    pthread_mutex_lock(&g_mu);
    r = label_locked(g_zxids, &g_nzxids, ZKC_MAX_ZXIDS, 'Z', zxid);
    pthread_mutex_unlock(&g_mu);
    return r;
}

const char *zkc_rc(int rc)
{
    char *b;
    switch (rc) {
    case ZOK: return "ZOK";
    case ZSYSTEMERROR: return "ZSYSTEMERROR";
    case ZRUNTIMEINCONSISTENCY: return "ZRUNTIMEINCONSISTENCY";
    case ZDATAINCONSISTENCY: return "ZDATAINCONSISTENCY";
    case ZCONNECTIONLOSS: return "ZCONNECTIONLOSS";
    case ZMARSHALLINGERROR: return "ZMARSHALLINGERROR";
    case ZUNIMPLEMENTED: return "ZUNIMPLEMENTED";
    case ZOPERATIONTIMEOUT: return "ZOPERATIONTIMEOUT";
    case ZBADARGUMENTS: return "ZBADARGUMENTS";
    case ZINVALIDSTATE: return "ZINVALIDSTATE";
    case ZNEWCONFIGNOQUORUM: return "ZNEWCONFIGNOQUORUM";
    case ZRECONFIGINPROGRESS: return "ZRECONFIGINPROGRESS";
    case ZSSLCONNECTIONERROR: return "ZSSLCONNECTIONERROR";
    case ZAPIERROR: return "ZAPIERROR";
    case ZNONODE: return "ZNONODE";
    case ZNOAUTH: return "ZNOAUTH";
    case ZBADVERSION: return "ZBADVERSION";
    case ZNOCHILDRENFOREPHEMERALS: return "ZNOCHILDRENFOREPHEMERALS";
    case ZNODEEXISTS: return "ZNODEEXISTS";
    case ZNOTEMPTY: return "ZNOTEMPTY";
    case ZSESSIONEXPIRED: return "ZSESSIONEXPIRED";
    case ZINVALIDCALLBACK: return "ZINVALIDCALLBACK";
    case ZINVALIDACL: return "ZINVALIDACL";
    case ZAUTHFAILED: return "ZAUTHFAILED";
    case ZCLOSING: return "ZCLOSING";
    case ZNOTHING: return "ZNOTHING";
    case ZSESSIONMOVED: return "ZSESSIONMOVED";
    case ZNOTREADONLY: return "ZNOTREADONLY";
    case ZEPHEMERALONLOCALSESSION: return "ZEPHEMERALONLOCALSESSION";
    case ZNOWATCHER: return "ZNOWATCHER";
    case ZRECONFIGDISABLED: return "ZRECONFIGDISABLED";
    case ZSESSIONCLOSEDREQUIRESASLAUTH: return "ZSESSIONCLOSEDREQUIRESASLAUTH";
    case ZTHROTTLEDOP: return "ZTHROTTLEDOP";
    default:
        b = tls_buf();
        snprintf(b, 128, "rc(%d)", rc);
        return b;
    }
}

const char *zkc_state(int state)
{
    char *b;
    if (state == ZOO_EXPIRED_SESSION_STATE) return "EXPIRED";
    if (state == ZOO_AUTH_FAILED_STATE) return "AUTH_FAILED";
    if (state == ZOO_CONNECTING_STATE) return "CONNECTING";
    if (state == ZOO_ASSOCIATING_STATE) return "ASSOCIATING";
    if (state == ZOO_CONNECTED_STATE) return "CONNECTED";
    if (state == ZOO_READONLY_STATE) return "READONLY";
    if (state == ZOO_NOTCONNECTED_STATE) return "NOTCONNECTED";
    if (state == 0) return "CLOSED";
    b = tls_buf();
    snprintf(b, 128, "state(%d)", state);
    return b;
}

const char *zkc_type(int type)
{
    char *b;
    if (type == ZOO_CREATED_EVENT) return "CREATED";
    if (type == ZOO_DELETED_EVENT) return "DELETED";
    if (type == ZOO_CHANGED_EVENT) return "CHANGED";
    if (type == ZOO_CHILD_EVENT) return "CHILD";
    if (type == ZOO_SESSION_EVENT) return "SESSION";
    if (type == ZOO_NOTWATCHING_EVENT) return "NOTWATCHING";
    b = tls_buf();
    snprintf(b, 128, "type(%d)", type);
    return b;
}

const char *zkc_lbl(const void *ctx)
{
    return ctx == NULL ? "NULL" : (const char *)ctx;
}

void zkc_stat_str(char *buf, size_t len, const struct Stat *s)
{
    static const struct Stat zero;
    if (s == NULL) {
        snprintf(buf, len, "stat=NULL");
        return;
    }
    if (memcmp(s, &zero, sizeof(zero)) == 0) {
        snprintf(buf, len, "stat=ZERO");
        return;
    }
    snprintf(buf, len,
             "stat={c=%s m=%s p=%s t=%s v=%d cv=%d av=%d eo=%s dl=%d nc=%d}",
             zkc_zxid(s->czxid), zkc_zxid(s->mzxid), zkc_zxid(s->pzxid),
             s->ctime == s->mtime ? "eq" : "ne", s->version, s->cversion,
             s->aversion, zkc_sid(s->ephemeralOwner), s->dataLength,
             s->numChildren);
}

static int is_hex(char c)
{
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') ||
           (c >= 'A' && c <= 'F');
}

static int is_digit(char c)
{
    return c >= '0' && c <= '9';
}

/* Returns 1 if `p` immediately follows a pointer-valued key in `start`. */
static int after_ptr_key(const char *start, const char *p)
{
    static const char *const keys[] = {"watcher=", "context=", "Ctx=",
                                       "zh=", "completion="};
    size_t i;
    for (i = 0; i < sizeof(keys) / sizeof(keys[0]); i++) {
        size_t kl = strlen(keys[i]);
        if ((size_t)(p - start) >= kl && strncmp(p - kl, keys[i], kl) == 0) {
            return 1;
        }
    }
    return 0;
}

/*
 * Normalizes raw pointer addresses to `PTR`, 12..16-digit hex session IDs to
 * `S<n>`, and configured TCP port numbers to symbolic port names.
 */
void zkc_norm_text(char *out, size_t outlen, const char *in)
{
    size_t o = 0;
    const char *p = in;
    if (outlen == 0) {
        return;
    }
    while (*p != '\0' && o + 1 < outlen) {
        if (p[0] == '0' && p[1] == 'x') {
            const char *q = p + 2;
            size_t n;
            while (is_hex(*q)) {
                q++;
            }
            n = (size_t)(q - p - 2);
            if (n > 0 && after_ptr_key(in, p)) {
                const char *lbl = "PTR";
                while (*lbl != '\0' && o + 1 < outlen) {
                    out[o++] = *lbl++;
                }
                p = q;
                continue;
            }
            if (n >= 12 && n <= 16) {
                char hex[20];
                const char *lbl;
                memcpy(hex, p + 2, n);
                hex[n] = '\0';
                lbl = zkc_sid((int64_t)strtoull(hex, NULL, 16));
                while (*lbl != '\0' && o + 1 < outlen) {
                    out[o++] = *lbl++;
                }
                p = q;
                continue;
            }
        }
        if (is_digit(*p) && (p == in || !is_digit(p[-1]))) {
            int i;
            int done = 0;
            for (i = 0; i < g_nports && !done; i++) {
                size_t pl = strlen(g_port_num[i]);
                if (strncmp(p, g_port_num[i], pl) == 0 && !is_digit(p[pl])) {
                    const char *nm = g_port_name[i];
                    while (*nm != '\0' && o + 1 < outlen) {
                        out[o++] = *nm++;
                    }
                    p += pl;
                    done = 1;
                }
            }
            if (done) {
                continue;
            }
        }
        out[o++] = *p++;
    }
    out[o] = '\0';
}

/* ------------------------------------------------------------------ */
/* Recording                                                           */
/* ------------------------------------------------------------------ */

static void bump_locked(const char *key)
{
    int i;
    for (i = 0; i < g_nkeys; i++) {
        if (strcmp(g_keys[i].key, key) == 0) {
            g_keys[i].count++;
            pthread_cond_broadcast(&g_cv);
            return;
        }
    }
    if (g_nkeys < ZKC_MAX_KEYS) {
        snprintf(g_keys[g_nkeys].key, sizeof(g_keys[0].key), "%s", key);
        g_keys[g_nkeys].count = 1;
        g_keys[g_nkeys].consumed = 0;
        g_nkeys++;
    }
    pthread_cond_broadcast(&g_cv);
}

static void bump(const char *key)
{
    pthread_mutex_lock(&g_mu);
    bump_locked(key);
    pthread_mutex_unlock(&g_mu);
}

static void vev(const char *kind, const char *fmt, va_list ap)
{
    char raw[ZKC_LINE];
    char norm[ZKC_LINE];
    const char *role;
    if (g_in_child || g_trace == NULL) {
        return;
    }
    vsnprintf(raw, sizeof(raw), fmt, ap);
    zkc_norm_text(norm, sizeof(norm), raw);
    role = zkc_role();
    pthread_mutex_lock(&g_mu);
    if (zkc_is_st() &&
        (strcmp(kind, "cb") == 0 || strcmp(kind, "watch") == 0)) {
        /* single threaded callbacks must execute strictly within a driving call. */
        g_cb_total++;
        if (t_ncalls == 0) {
            g_cb_outside++;
        }
        fprintf(g_trace, "%s %s %s in=%s\n", role, kind, norm,
                zkc_call_name());
    } else {
        fprintf(g_trace, "%s %s %s\n", role, kind, norm);
    }
    fflush(g_trace);
    pthread_mutex_unlock(&g_mu);
}

void zkc_ev(const char *kind, const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    vev(kind, fmt, ap);
    va_end(ap);
}

void zkc_fail(const char *fmt, ...)
{
    va_list ap;
    char raw[1024];
    va_start(ap, fmt);
    vsnprintf(raw, sizeof(raw), fmt, ap);
    va_end(ap);
    zkc_ev("FAIL", "%s", raw);
    fprintf(stderr, "zkconf %s %s: FAIL %s\n", g_id, g_core, raw);
    pthread_mutex_lock(&g_mu);
    g_harness_fail++;
    pthread_mutex_unlock(&g_mu);
}

void zkc_dev(const char *id, int ok, const char *fmt, ...)
{
    va_list ap;
    char raw[1024];
    va_start(ap, fmt);
    vsnprintf(raw, sizeof(raw), fmt, ap);
    va_end(ap);
    pthread_mutex_lock(&g_mu);
    if (ok) {
        g_dev_pass++;
    } else {
        g_dev_fail++;
    }
    fprintf(g_devf, "DEV %s %s %s: %s\n", id, g_core, ok ? "PASS" : "FAIL",
            raw);
    fflush(g_devf);
    pthread_mutex_unlock(&g_mu);
}

void zkc_known(const char *id, int ok, const char *fmt, ...)
{
    va_list ap;
    char raw[1024];
    va_start(ap, fmt);
    vsnprintf(raw, sizeof(raw), fmt, ap);
    va_end(ap);
    pthread_mutex_lock(&g_mu);
    fprintf(g_devf, "KNOWN %s %s %s: %s\n", id, g_core, ok ? "XPASS" : "XFAIL",
            raw);
    fflush(g_devf);
    pthread_mutex_unlock(&g_mu);
}

static int rust_frame_name(const char *n)
{
    if (n == NULL) {
        return 0;
    }
    while (*n == '_' && (n[1] == '_' || n[1] == 'Z' || n[1] == 'R')) {
        if (n[1] == 'Z' && n[2] == 'N') {
            return 1;
        }
        if (n[1] == 'R' && n[2] >= 'A' && n[2] <= 'Z') {
            return 1;
        }
        n++;
    }
    return strncmp(n, "zkrs_", 5) == 0;
}

/*
 * Stack-hygiene verification: inspects the call stack to ensure no internal
 * Rust frames (`zkrs_*`, `_ZN*`, or `_R*`) appear below any C callback.
 */
void zkc_frame_check(const char *site)
{
    void *frames[64];
    int n;
    int i;
    if (g_in_child) {
        return;
    }
    n = backtrace(frames, 64);
    for (i = 2; i < n; i++) {
        Dl_info info;
        if (dladdr(frames[i], &info) != 0 && rust_frame_name(info.dli_sname)) {
            zkc_fail("TH-15 %s: frame %d is %s", site, i, info.dli_sname);
            return;
        }
    }
    pthread_mutex_lock(&g_mu);
    g_frames_checked++;
    pthread_mutex_unlock(&g_mu);
}

void zkc_set_hook(zkc_hook_fn hook)
{
    g_hook = hook;
}

void zkc_set_watch_hook(zkc_whook_fn hook)
{
    g_whook = hook;
}

void zkc_watcher(zhandle_t *zh, int type, int state, const char *path,
                 void *ctx)
{
    char key[48];
    zkc_frame_check("watcher");
    if (t_role < 0) {
        pthread_mutex_lock(&g_mu);
        assign_role_locked(1);
        pthread_mutex_unlock(&g_mu);
    }
    zkc_ev("watch", "type=%s state=%s path=%s%s%s ctx=%s zh=%s",
           zkc_type(type), zkc_state(state), path == NULL ? "" : "\"",
           path == NULL ? "NULL" : path, path == NULL ? "" : "\"",
           zkc_lbl(ctx), zh == NULL ? "NULL" : "zh");
    if (g_whook != NULL) {
        g_whook(zh, type, state, path, ctx);
    }
    pthread_mutex_lock(&g_mu);
    if (type == ZOO_SESSION_EVENT) {
        snprintf(key, sizeof(key), "session:%s", zkc_state(state));
        bump_locked(key);
        snprintf(key, sizeof(key), "session:%s@%s", zkc_state(state),
                 zkc_lbl(ctx));
        bump_locked(key);
    } else {
        bump_locked("watch");
    }
    pthread_mutex_unlock(&g_mu);
}

static void finish_cb(const char *kind, int rc, const void *d)
{
    if (g_hook != NULL) {
        g_hook(kind, rc, d);
    }
    bump("cb");
}

static void payload_str(char *buf, size_t len, const char *v, int vlen)
{
    size_t o = 0;
    int i;
    int shown;
    uint64_t h = 1469598103934665603ULL;
    if (v == NULL) {
        snprintf(buf, len, "value=NULL len=%d", vlen);
        return;
    }
    o = (size_t)snprintf(buf, len, "len=%d value=\"", vlen);
    shown = vlen < 0 ? 0 : (vlen > 64 ? 64 : vlen);
    for (i = 0; i < shown && o + 6 < len; i++) {
        unsigned char c = (unsigned char)v[i];
        if (c >= 0x20 && c < 0x7f && c != '"' && c != '\\') {
            buf[o++] = (char)c;
        } else {
            o += (size_t)snprintf(buf + o, len - o, "\\x%02x", c);
        }
    }
    for (i = 0; i < vlen; i++) {
        h ^= (unsigned char)v[i];
        h *= 1099511628211ULL;
    }
    snprintf(buf + o, len - o, "\"%s%016llx", vlen > 64 ? " fnv=" : " h=",
             (unsigned long long)h);
}

static void strings_str(char *buf, size_t len, const struct String_vector *sv)
{
    size_t o;
    int i;
    if (sv == NULL) {
        snprintf(buf, len, "strings=NULL");
        return;
    }
    o = (size_t)snprintf(buf, len, "strings=%d[", sv->count);
    for (i = 0; sv->data != NULL && i < sv->count && o + 2 < len; i++) {
        o += (size_t)snprintf(buf + o, len - o, "%s%s", i ? "," : "",
                              sv->data[i] == NULL ? "NULL" : sv->data[i]);
    }
    if (o + 2 < len) {
        snprintf(buf + o, len - o, "]%s", sv->data == NULL ? "data=NULL" : "");
    }
}

static void acl_str(char *buf, size_t len, const struct ACL_vector *acl)
{
    size_t o;
    int i;
    if (acl == NULL) {
        snprintf(buf, len, "acl=NULL");
        return;
    }
    o = (size_t)snprintf(buf, len, "acl=%d[", acl->count);
    for (i = 0; acl->data != NULL && i < acl->count && o + 2 < len; i++) {
        o += (size_t)snprintf(buf + o, len - o, "%s%d:%s:%s", i ? "," : "",
                              acl->data[i].perms, acl->data[i].id.scheme,
                              acl->data[i].id.id);
    }
    if (o + 2 < len) {
        snprintf(buf + o, len - o, "]");
    }
}

void zkc_void_cb(int rc, const void *d)
{
    zkc_frame_check("void_cb");
    zkc_ev("cb", "void rc=%s d=%s", zkc_rc(rc), zkc_lbl(d));
    finish_cb("void", rc, d);
}

void zkc_stat_cb(int rc, const struct Stat *s, const void *d)
{
    char st[512];
    zkc_frame_check("stat_cb");
    zkc_stat_str(st, sizeof(st), s);
    zkc_ev("cb", "stat rc=%s %s d=%s", zkc_rc(rc), st, zkc_lbl(d));
    finish_cb("stat", rc, d);
}

void zkc_data_cb(int rc, const char *v, int len, const struct Stat *s,
                 const void *d)
{
    char st[512];
    char pl[512];
    zkc_frame_check("data_cb");
    zkc_stat_str(st, sizeof(st), s);
    payload_str(pl, sizeof(pl), v, len);
    zkc_ev("cb", "data rc=%s %s %s d=%s", zkc_rc(rc), pl, st, zkc_lbl(d));
    finish_cb("data", rc, d);
}

void zkc_strings_cb(int rc, const struct String_vector *sv, const void *d)
{
    char sb[2048];
    zkc_frame_check("strings_cb");
    strings_str(sb, sizeof(sb), sv);
    zkc_ev("cb", "strings rc=%s %s d=%s", zkc_rc(rc), sb, zkc_lbl(d));
    finish_cb("strings", rc, d);
}

void zkc_strings_stat_cb(int rc, const struct String_vector *sv,
                         const struct Stat *s, const void *d)
{
    char sb[2048];
    char st[512];
    zkc_frame_check("strings_stat_cb");
    strings_str(sb, sizeof(sb), sv);
    zkc_stat_str(st, sizeof(st), s);
    zkc_ev("cb", "strings_stat rc=%s %s %s d=%s", zkc_rc(rc), sb, st,
           zkc_lbl(d));
    finish_cb("strings_stat", rc, d);
}

void zkc_string_cb(int rc, const char *v, const void *d)
{
    zkc_frame_check("string_cb");
    zkc_ev("cb", "string rc=%s value=%s d=%s", zkc_rc(rc),
           v == NULL ? "NULL" : v, zkc_lbl(d));
    finish_cb("string", rc, d);
}

void zkc_string_stat_cb(int rc, const char *v, const struct Stat *s,
                        const void *d)
{
    char st[512];
    zkc_frame_check("string_stat_cb");
    zkc_stat_str(st, sizeof(st), s);
    zkc_ev("cb", "string_stat rc=%s value=%s %s d=%s", zkc_rc(rc),
           v == NULL ? "NULL" : v, st, zkc_lbl(d));
    finish_cb("string_stat", rc, d);
}

void zkc_acl_cb(int rc, struct ACL_vector *acl, struct Stat *s, const void *d)
{
    char ab[2048];
    char st[512];
    zkc_frame_check("acl_cb");
    acl_str(ab, sizeof(ab), acl);
    zkc_stat_str(st, sizeof(st), s);
    zkc_ev("cb", "acl rc=%s %s %s d=%s", zkc_rc(rc), ab, st, zkc_lbl(d));
    finish_cb("acl", rc, d);
}

/*
 * Log normalization: `zookeeper_init_internal` logs `sessionPasswd=<null>`
 * when `clientid == NULL` or `passwd[0] == '\0'`, and `<hidden>` otherwise.
 * When resuming a server-assigned session (`sessionId != 0`), the random
 * 16-byte server password occasionally begins with a zero byte, causing the
 * logged marker to vary across runs. Coalesce `<null>` and `<hidden>` to
 * `<null|hidden>` on `zookeeper_init_internal` log lines with a non-zero
 * `sessionId`, while preserving exact markers for deterministic `sessionId=0`
 * inputs.
 */
static void norm_init_passwd(char *s, size_t len)
{
    static const char site[] = "@zookeeper_init_internal@";
    static const char text[] = ": Initiating client connection, ";
    static const char sid_key[] = " sessionId=";
    static const char pw_key[] = " sessionPasswd=";
    static const char *const marks[] = {"<null>", "<hidden>"};
    char tail[ZKC_LINE];
    char *p = strstr(s, site);
    char *sid;
    char *pw;
    size_t i;
    if (p == NULL || strstr(p, text) == NULL) {
        return;
    }
    sid = strstr(p, sid_key);
    if (sid == NULL) {
        return;
    }
    sid += sizeof(sid_key) - 1;
    if (sid[0] == '0' && (sid[1] == ' ' || sid[1] == '\0')) {
        return;
    }
    pw = strstr(sid, pw_key);
    if (pw == NULL) {
        return;
    }
    pw += sizeof(pw_key) - 1;
    for (i = 0; i < sizeof(marks) / sizeof(marks[0]); i++) {
        size_t ml = strlen(marks[i]);
        if (strncmp(pw, marks[i], ml) == 0 &&
            (pw[ml] == ' ' || pw[ml] == '\0')) {
            snprintf(tail, sizeof(tail), "%s", pw + ml);
            snprintf(pw, len - (size_t)(pw - s), "<null|hidden>%s", tail);
            return;
        }
    }
}

/* Strip leading timestamp, PID, and TID prefix prior to `ZOO_`. */
void zkc_log_cb(const char *msg)
{
    char norm[ZKC_LINE];
    const char *p;
    const char *role;
    if (g_in_child || g_log == NULL) {
        return;
    }
    zkc_frame_check("log_cb");
    p = (msg == NULL) ? NULL : strstr(msg, ":ZOO_");
    p = (p == NULL) ? (msg == NULL ? "NULL" : msg) : p + 1;
    zkc_norm_text(norm, sizeof(norm), p);
    norm_init_passwd(norm, sizeof(norm));
    role = zkc_role();
    pthread_mutex_lock(&g_mu);
    fprintf(g_log, "%s %s\n", role, norm);
    fflush(g_log);
    pthread_mutex_unlock(&g_mu);
}

/* ------------------------------------------------------------------ */
/* Waits                                                               */
/* ------------------------------------------------------------------ */

static struct zkc_counter *find_key_locked(const char *key)
{
    int i;
    for (i = 0; i < g_nkeys; i++) {
        if (strcmp(g_keys[i].key, key) == 0) {
            return &g_keys[i];
        }
    }
    if (g_nkeys < ZKC_MAX_KEYS) {
        snprintf(g_keys[g_nkeys].key, sizeof(g_keys[0].key), "%s", key);
        g_keys[g_nkeys].count = 0;
        g_keys[g_nkeys].consumed = 0;
        return &g_keys[g_nkeys++];
    }
    return NULL;
}

static void deadline_after(struct timespec *ts, int ms)
{
    struct timeval now;
    long nsec;
    gettimeofday(&now, NULL);
    nsec = (long)now.tv_usec * 1000L + (long)(ms % 1000) * 1000000L;
    ts->tv_sec = now.tv_sec + ms / 1000 + nsec / 1000000000L;
    ts->tv_nsec = nsec % 1000000000L;
}

static int await_mt(const char *key, int n, int timeout_ms)
{
    struct timespec ts;
    struct zkc_counter *c;
    int rc = 0;
    deadline_after(&ts, timeout_ms);
    pthread_mutex_lock(&g_mu);
    c = find_key_locked(key);
    while (c != NULL && c->count - c->consumed < n) {
        if (pthread_cond_timedwait(&g_cv, &g_mu, &ts) == ETIMEDOUT) {
            rc = -1;
            break;
        }
    }
    if (c != NULL && rc == 0) {
        c->consumed += n;
    }
    pthread_mutex_unlock(&g_mu);
    if (c == NULL || rc != 0) {
        zkc_fail("timeout waiting for %d %s", n, key);
        return -1;
    }
    return 0;
}

static int await_none_mt(const char *key, int wait_ms)
{
    struct timespec ts;
    struct zkc_counter *c;
    int extra = 0;
    deadline_after(&ts, wait_ms);
    pthread_mutex_lock(&g_mu);
    c = find_key_locked(key);
    while (c != NULL && c->count == c->consumed) {
        if (pthread_cond_timedwait(&g_cv, &g_mu, &ts) == ETIMEDOUT) {
            break;
        }
    }
    if (c != NULL) {
        extra = c->count - c->consumed;
        c->consumed = c->count;
    }
    pthread_mutex_unlock(&g_mu);
    return extra;
}

static long now_ms(void)
{
    struct timeval now;
    gettimeofday(&now, NULL);
    return (long)now.tv_sec * 1000L + (long)(now.tv_usec / 1000);
}

/*
 * single threaded event-wait loop. Releases `g_mu` before invoking
 * `zkc_poll_once` so callbacks dispatched during polling can acquire `g_mu`.
 */
static int await_st(const char *key, int n, int timeout_ms)
{
    long deadline = now_ms() + timeout_ms;
    struct zkc_counter *c;
    for (;;) {
        long left;
        pthread_mutex_lock(&g_mu);
        c = find_key_locked(key);
        if (c != NULL && c->count - c->consumed >= n) {
            c->consumed += n;
            pthread_mutex_unlock(&g_mu);
            return 0;
        }
        pthread_mutex_unlock(&g_mu);
        left = deadline - now_ms();
        if (c == NULL || left <= 0) {
            zkc_fail("timeout waiting for %d %s", n, key);
            return -1;
        }
        zkc_poll_once((int)left);
    }
}

static int await_none_st(const char *key, int wait_ms)
{
    long deadline = now_ms() + wait_ms;
    struct zkc_counter *c;
    int extra = 0;
    for (;;) {
        long left;
        pthread_mutex_lock(&g_mu);
        c = find_key_locked(key);
        if (c == NULL || c->count != c->consumed) {
            pthread_mutex_unlock(&g_mu);
            break;
        }
        pthread_mutex_unlock(&g_mu);
        left = deadline - now_ms();
        if (left <= 0) {
            break;
        }
        zkc_poll_once((int)left);
    }
    pthread_mutex_lock(&g_mu);
    if (c != NULL) {
        extra = c->count - c->consumed;
        c->consumed = c->count;
    }
    pthread_mutex_unlock(&g_mu);
    return extra;
}

int zkc_await(const char *key, int n, int timeout_ms)
{
    return zkc_is_st() ? await_st(key, n, timeout_ms)
                       : await_mt(key, n, timeout_ms);
}

int zkc_await_none(const char *key, int wait_ms)
{
    return zkc_is_st() ? await_none_st(key, wait_ms)
                       : await_none_mt(key, wait_ms);
}

void zkc_sleep_ms(int ms)
{
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (long)(ms % 1000) * 1000000L;
    while (nanosleep(&ts, &ts) != 0 && errno == EINTR) {
    }
}

/* ------------------------------------------------------------------ */
/* Driving calls and the st poll loop                                  */
/* ------------------------------------------------------------------ */

int zkc_is_st(void)
{
#ifdef THREADED
    return 0;
#else
    return 1;
#endif
}

static void call_enter(const char *what)
{
    if (t_ncalls < ZKC_MAX_CALLS) {
        t_calls[t_ncalls] = what;
    }
    t_ncalls++;
}

static void call_leave(void)
{
    if (t_ncalls > 0) {
        t_ncalls--;
    }
}

void zkc_in_call(int entering)
{
    if (entering) {
        call_enter("call");
    } else {
        call_leave();
    }
}

const char *zkc_call_name(void)
{
    if (t_ncalls == 0) {
        return "none";
    }
    if (t_ncalls > ZKC_MAX_CALLS) {
        return t_calls[ZKC_MAX_CALLS - 1];
    }
    return t_calls[t_ncalls - 1];
}

/* Accessed exclusively by the main test thread; requires no internal mutex. */
static zhandle_t *g_polled[ZKC_MAX_POLLED];
static int g_npolled;

static int polled_index(const zhandle_t *zh)
{
    int i;
    for (i = 0; i < g_npolled; i++) {
        if (g_polled[i] == zh) {
            return i;
        }
    }
    return -1;
}

void zkc_poll_add(zhandle_t *zh)
{
    if (!zkc_is_st() || zh == NULL || polled_index(zh) >= 0) {
        return;
    }
    if (g_npolled >= ZKC_MAX_POLLED) {
        zkc_fail("the poll registry is full");
        return;
    }
    g_polled[g_npolled++] = zh;
}

int zkc_polled(const zhandle_t *zh)
{
    return polled_index(zh) >= 0;
}

void zkc_poll_remove(zhandle_t *zh)
{
    int i = polled_index(zh);
    if (i < 0) {
        return;
    }
    g_polled[i] = g_polled[--g_npolled];
}

#ifndef THREADED
int zkc_interest(zhandle_t *zh, int *fd, int *interest, struct timeval *tv)
{
    int rc;
    call_enter("interest");
    rc = zookeeper_interest(zh, fd, interest, tv);
    call_leave();
    return rc;
}

int zkc_process(zhandle_t *zh, int events)
{
    int rc;
    call_enter("process");
    rc = zookeeper_process(zh, events);
    call_leave();
    return rc;
}

/*
 * Drives a single iteration over a snapshot of the poll registry. Re-verifies
 * registry membership before each `zkc_interest` and `zkc_process` call in case
 * a callback closed and deregistered a handle. Handles returning `fd < 0` skip
 * `zookeeper_process` so connection-loss transitions re-initiate connection on
 * the next `zookeeper_interest` call.
 */
void zkc_poll_once(int ms)
{
    zhandle_t *hs[ZKC_MAX_POLLED];
    int fds[ZKC_MAX_POLLED];
    fd_set rd;
    fd_set wr;
    struct timeval tv;
    long wait_ms = ms < 0 ? 0 : ms;
    int n = g_npolled;
    int maxfd = -1;
    int live = 0;
    int i;
    memcpy(hs, g_polled, sizeof(hs[0]) * (size_t)n);
    FD_ZERO(&rd);
    FD_ZERO(&wr);
    for (i = 0; i < n; i++) {
        int fd = -1;
        int interest = 0;
        struct timeval htv = {0, 0};
        long hms;
        int rc;
        fds[i] = -1;
        if (polled_index(hs[i]) < 0) {
            hs[i] = NULL;
            continue;
        }
        rc = zkc_interest(hs[i], &fd, &interest, &htv);
        if (polled_index(hs[i]) < 0 || rc == ZINVALIDSTATE ||
            rc == ZBADARGUMENTS) {
            hs[i] = NULL;
            continue;
        }
        live++;
        hms = (long)htv.tv_sec * 1000L + (long)((htv.tv_usec + 999) / 1000);
        if (hms < wait_ms) {
            wait_ms = hms;
        }
        if (fd >= 0 && fd < FD_SETSIZE) {
            fds[i] = fd;
            if (interest & ZOOKEEPER_READ) {
                FD_SET(fd, &rd);
            }
            if (interest & ZOOKEEPER_WRITE) {
                FD_SET(fd, &wr);
            }
            if (fd > maxfd) {
                maxfd = fd;
            }
        }
    }
    if (live == 0) {
        zkc_sleep_ms(wait_ms < 10 ? (int)wait_ms : 10);
        return;
    }
    tv.tv_sec = wait_ms / 1000;
    tv.tv_usec = (wait_ms % 1000) * 1000;
    if (select(maxfd + 1, &rd, &wr, NULL, &tv) < 0) {
        FD_ZERO(&rd);
        FD_ZERO(&wr);
    }
    for (i = 0; i < n; i++) {
        int events = 0;
        if (hs[i] == NULL || fds[i] < 0 || polled_index(hs[i]) < 0) {
            continue;
        }
        if (FD_ISSET(fds[i], &rd)) {
            events |= ZOOKEEPER_READ;
        }
        if (FD_ISSET(fds[i], &wr)) {
            events |= ZOOKEEPER_WRITE;
        }
        if (events != 0) {
            zkc_process(hs[i], events);
        }
    }
}
#else
void zkc_poll_once(int ms)
{
    zkc_sleep_ms(ms);
}
#endif

int zkc_poll(int ms)
{
    long deadline = now_ms() + ms;
    int passes = 0;
    if (!zkc_is_st()) {
        zkc_sleep_ms(ms);
        return 0;
    }
    for (;;) {
        long left = deadline - now_ms();
        if (left <= 0) {
            break;
        }
        zkc_poll_once((int)left);
        passes++;
    }
    return passes;
}

static const char *errno_name(int e)
{
    char *b;
    switch (e) {
    case 0: return "0";
    case EINVAL: return "EINVAL";
    case ENOENT: return "ENOENT";
    case ENOMEM: return "ENOMEM";
    case EDEADLK: return "EDEADLK";
    case EAGAIN: return "EAGAIN";
    case EHOSTUNREACH: return "EHOSTUNREACH";
    default:
        b = tls_buf();
        snprintf(b, 128, "errno(%d)", e);
        return b;
    }
}

/* Marks all currently accumulated events for `key` as consumed. */
static void drain_key(const char *key)
{
    struct zkc_counter *c;
    pthread_mutex_lock(&g_mu);
    c = find_key_locked(key);
    if (c != NULL) {
        c->consumed = c->count;
    }
    pthread_mutex_unlock(&g_mu);
}

/*
 * Establishes a session via `zookeeper_init2`, draining prior `CONNECTED`
 * counters beforehand so the initial connection wait and subsequent reconnect
 * waits on either `"session:CONNECTED"` or `"session:CONNECTED@default"`
 * observe only events from this handle.
 */
zhandle_t *zkc_connect(const char *hosts, int timeout_ms, int flags)
{
    zhandle_t *zh;
    drain_key("session:CONNECTED");
    drain_key("session:CONNECTED@default");
    errno = 0;
    zh = zookeeper_init2(hosts, zkc_watcher, timeout_ms, NULL, "default",
                         flags, zkc_log_cb);
    zkc_ev("call", "zookeeper_init2 hosts=%s -> %s errno=%s",
           hosts == NULL ? "NULL" : hosts, zh == NULL ? "NULL" : "zh",
           zh == NULL ? errno_name(errno) : "-");
    if (zh != NULL) {
        zkc_poll_add(zh);
        zkc_await("session:CONNECTED", 1, 15000);
        drain_key("session:CONNECTED@default");
    }
    return zh;
}

/*
 * Synchronization gate used by `zkc_expire` to pause a handle's I/O thread
 * inside its log callback.
 */
static pthread_mutex_t g_gate_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_gate_cv = PTHREAD_COND_INITIALIZER;
static int g_gate_armed;
static int g_gate_held;

static void gate_log_cb(const char *msg)
{
    zkc_log_cb(msg);
    pthread_mutex_lock(&g_gate_mu);
    if (g_gate_armed && zkc_role()[0] == 'L') {
        g_gate_held = 1;
        pthread_cond_broadcast(&g_gate_cv);
        while (g_gate_armed) {
            pthread_cond_wait(&g_gate_cv, &g_gate_mu);
        }
        g_gate_held = 0;
    }
    pthread_mutex_unlock(&g_gate_mu);
}

/*
 * single threaded session expiration: removing `zh` from the poll registry
 * suspends its I/O while the clone handle attaches to and closes the session.
 */
static int expire_st(zhandle_t *zh, const char *hosts)
{
    clientid_t copy;
    zhandle_t *clone;
    char key[128];
    memcpy(&copy, zoo_client_id(zh), sizeof(copy));
    zkc_poll_remove(zh);
    clone = zookeeper_init2(hosts, zkc_watcher, zoo_recv_timeout(zh), &copy,
                            "clone", 0, zkc_log_cb);
    zkc_ev("call", "zookeeper_init2 clone -> %s", clone ? "zh" : "NULL");
    if (clone == NULL) {
        zkc_poll_add(zh);
        zkc_fail("clone init failed");
        return -1;
    }
    zkc_poll_add(clone);
    zkc_await("session:CONNECTED@clone", 1, 15000);
    zkc_poll_remove(clone);
    call_enter("close");
    zkc_ev("call", "zookeeper_close clone -> %s",
           zkc_rc(zookeeper_close(clone)));
    call_leave();
    zkc_poll_add(zh);
    snprintf(key, sizeof(key), "session:EXPIRED@%s",
             zkc_lbl(zoo_get_context(zh)));
    return zkc_await(key, 1, 20000);
}

int zkc_expire(zhandle_t *zh, const char *hosts)
{
    clientid_t copy;
    zhandle_t *clone;
    struct timespec ts;
    char key[128];
    int held = 1;
    if (zkc_is_st()) {
        return expire_st(zh, hosts);
    }
    memcpy(&copy, zoo_client_id(zh), sizeof(copy));
    pthread_mutex_lock(&g_gate_mu);
    g_gate_armed = 1;
    g_gate_held = 0;
    pthread_mutex_unlock(&g_gate_mu);
    zoo_set_log_callback(zh, gate_log_cb);
    clone = zookeeper_init2(hosts, zkc_watcher, zoo_recv_timeout(zh), &copy,
                            "clone", 0, zkc_log_cb);
    zkc_ev("call", "zookeeper_init2 clone -> %s", clone ? "zh" : "NULL");
    if (clone != NULL) {
        zkc_await("session:CONNECTED@clone", 1, 15000);
        /* Wait until the server drops `zh` and `zh`'s I/O thread blocks in `gate_log_cb`. */
        deadline_after(&ts, 10000);
        pthread_mutex_lock(&g_gate_mu);
        while (!g_gate_held) {
            if (pthread_cond_timedwait(&g_gate_cv, &g_gate_mu, &ts) ==
                ETIMEDOUT) {
                held = 0;
                break;
            }
        }
        pthread_mutex_unlock(&g_gate_mu);
        if (!held) {
            zkc_fail("the IO thread of the handle logged no connection loss");
        }
        zkc_ev("call", "zookeeper_close clone -> %s",
               zkc_rc(zookeeper_close(clone)));
    }
    pthread_mutex_lock(&g_gate_mu);
    g_gate_armed = 0;
    pthread_cond_broadcast(&g_gate_cv);
    pthread_mutex_unlock(&g_gate_mu);
    zoo_set_log_callback(zh, zkc_log_cb);
    if (clone == NULL) {
        zkc_fail("clone init failed");
        return -1;
    }
    snprintf(key, sizeof(key), "session:EXPIRED@%s",
             zkc_lbl(zoo_get_context(zh)));
    return zkc_await(key, 1, 20000);
}

void zkc_close(zhandle_t *zh)
{
    int rc;
    zkc_poll_remove(zh);
    call_enter("close");
    rc = zookeeper_close(zh);
    call_leave();
    zkc_ev("call", "zookeeper_close -> %s", zkc_rc(rc));
}

/* ------------------------------------------------------------------ */
/* Resources, servers and children                                     */
/* ------------------------------------------------------------------ */

int zkc_threads(void)
{
#ifdef __APPLE__
    thread_act_array_t list;
    mach_msg_type_number_t count = 0;
    if (task_threads(mach_task_self(), &list, &count) != KERN_SUCCESS) {
        return -1;
    }
    {
        mach_msg_type_number_t i;
        for (i = 0; i < count; i++) {
            mach_port_deallocate(mach_task_self(), list[i]);
        }
    }
    vm_deallocate(mach_task_self(), (vm_address_t)list,
                  sizeof(*list) * count);
    return (int)count;
#else
    /* Linux has one /proc/self/task entry for each thread. */
    DIR *d = opendir("/proc/self/task");
    struct dirent *e;
    int count = 0;
    if (d == NULL) {
        return -1;
    }
    while ((e = readdir(d)) != NULL) {
        if (e->d_name[0] != '.') {
            count++;
        }
    }
    closedir(d);
    return count;
#endif
}

int zkc_fds(void)
{
    int fd;
    int n = 0;
    for (fd = 0; fd < 1024; fd++) {
        if (fcntl(fd, F_GETFD) != -1) {
            n++;
        }
    }
    return n;
}

#ifndef __APPLE__
/*
 * Inspects the `PT_DYNAMIC` section of any loaded `libsasl2` object and sets
 * `*found = 1` if a `DT_NEEDED` entry references `libcrypto`.
 */
static int sasl_crypto_cb(struct dl_phdr_info *info, size_t size, void *data)
{
    int *found = data;
    const ElfW(Dyn) *dyn = NULL;
    const ElfW(Dyn) *d;
    const char *strtab = NULL;
    ElfW(Half) i;
    (void)size;
    if (info->dlpi_name == NULL || strstr(info->dlpi_name, "libsasl2") == NULL) {
        return 0;
    }
    for (i = 0; i < info->dlpi_phnum; i++) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) {
            dyn = (const ElfW(Dyn) *)(info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
        }
    }
    if (dyn == NULL) {
        return 1;
    }
    for (d = dyn; d->d_tag != DT_NULL; d++) {
        if (d->d_tag == DT_STRTAB) {
            /* glibc relocates `DT_STRTAB` in place at load time; musl leaves a relative offset. */
            ElfW(Addr) a = d->d_un.d_ptr;
            if (a < info->dlpi_addr) {
                a += info->dlpi_addr;
            }
            strtab = (const char *)a;
        }
    }
    if (strtab == NULL) {
        return 1;
    }
    for (d = dyn; d->d_tag != DT_NULL; d++) {
        if (d->d_tag == DT_NEEDED && strstr(strtab + d->d_un.d_val, "libcrypto") != NULL) {
            *found = 1;
        }
    }
    return 1;
}
#endif

int zkc_sasl_needs_crypto(void)
{
#ifdef __APPLE__
    return 0;
#else
    int found = 0;
    (void)dl_iterate_phdr(sasl_crypto_cb, &found);
    return found;
#endif
}

int zkc_server(const char *cmd, int index)
{
    const char *ctl = getenv("ZKCONF_CTL");
    char line[PATH_MAX + 64];
    int rc;
    if (ctl == NULL || ctl[0] == '\0') {
        zkc_fail("ZKCONF_CTL is not set");
        return -1;
    }
    snprintf(line, sizeof(line), "'%s' %s %d >/dev/null 2>&1", ctl, cmd,
             index);
    rc = system(line);
    zkc_ev("server", "%s %d -> %s", cmd, index, rc == 0 ? "ok" : "failed");
    if (rc != 0) {
        zkc_fail("server %s %d failed", cmd, index);
    }
    return rc;
}

const char *zkc_child(const char *label, int (*fn)(void), int timeout_ms)
{
    static char results[16][32];
    static int next;
    char *res = results[next++ % 16];
    pid_t pid;
    int waited = 0;
    int status = 0;
    res[0] = '\0';
    fflush(NULL);
    pid = fork();
    if (pid < 0) {
        snprintf(res, 32, "fork-failed");
        return res;
    }
    if (pid == 0) {
        int code;
        g_in_child = 1;
        alarm((unsigned)(timeout_ms / 1000 + 5));
        code = fn();
        _exit(code & 0xff);
    }
    for (;;) {
        pid_t w = waitpid(pid, &status, WNOHANG);
        if (w == pid) {
            break;
        }
        if (waited >= timeout_ms) {
            kill(pid, SIGKILL);
            waitpid(pid, &status, 0);
            snprintf(res, 32, "hang");
            break;
        }
        zkc_sleep_ms(10);
        waited += 10;
    }
    if (strcmp(res, "hang") != 0) {
        if (WIFEXITED(status)) {
            snprintf(res, 32, "exit:%d", WEXITSTATUS(status));
        } else if (WIFSIGNALED(status)) {
            snprintf(res, 32, "signal:%d", WTERMSIG(status));
        } else {
            snprintf(res, 32, "unknown");
        }
    }
    pthread_mutex_lock(&g_mu);
    fprintf(g_devf, "CHILD %s %s %s\n", label, g_core, res);
    fflush(g_devf);
    pthread_mutex_unlock(&g_mu);
    return res;
}

/* ------------------------------------------------------------------ */
/* TCP proxy                                                           */
/* ------------------------------------------------------------------ */

#define ZKC_PX_PAIRS 8

struct zkc_px_pair {
    int c;
    int s;
};

/*
 * TCP proxy state. `g_px_mu` synchronizes control flags and byte/connection
 * counters; socket descriptors in `p` are accessed exclusively by the proxy thread.
 */
static pthread_mutex_t g_px_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_px_cv = PTHREAD_COND_INITIALIZER;

static struct {
    pthread_t th;
    int running;
    int stop;
    int hold;
    int reset_req;
    int resets_done;
    long up;
    int accepted;
    int lfd;
    int wake[2];
    struct sockaddr_in target;
    struct zkc_px_pair p[ZKC_PX_PAIRS];
    int np;
    char host[64];
} g_px;

static void px_wake(void)
{
    char b = 1;
    if (write(g_px.wake[1], &b, 1) < 0) {
        /* A full pipe already wakes the proxy thread. */
    }
}

static int px_write_all(int fd, const char *buf, ssize_t n)
{
    while (n > 0) {
        ssize_t w = send(fd, buf, (size_t)n, ZKC_NOSIGNAL);
        if (w < 0 && errno == EINTR) {
            continue;
        }
        if (w <= 0) {
            return -1;
        }
        buf += w;
        n -= w;
    }
    return 0;
}

/* Closes `fd` with `SO_LINGER` timeout 0 to emit an immediate TCP `RST`. */
static void px_close_reset(int fd)
{
    struct linger lg;
    lg.l_onoff = 1;
    lg.l_linger = 0;
    setsockopt(fd, SOL_SOCKET, SO_LINGER, &lg, sizeof(lg));
    close(fd);
}

static void px_drop(int i, int reset)
{
    if (reset) {
        px_close_reset(g_px.p[i].c);
    } else {
        close(g_px.p[i].c);
    }
    close(g_px.p[i].s);
    g_px.p[i] = g_px.p[--g_px.np];
}

static void px_accept(void)
{
    int c = accept(g_px.lfd, NULL, NULL);
    int s;
    if (c < 0) {
        return;
    }
    s = socket(AF_INET, SOCK_STREAM, 0);
    if (s < 0 || g_px.np >= ZKC_PX_PAIRS ||
        connect(s, (struct sockaddr *)&g_px.target, sizeof(g_px.target)) !=
            0) {
        if (s >= 0) {
            close(s);
        }
        px_close_reset(c);
        return;
    }
    g_px.p[g_px.np].c = c;
    g_px.p[g_px.np].s = s;
    g_px.np++;
    pthread_mutex_lock(&g_px_mu);
    g_px.accepted++;
    pthread_mutex_unlock(&g_px_mu);
}

/*
 * Main TCP proxy event loop. When `g_px.hold` is enabled, omits `POLLIN` on
 * upstream server sockets so server responses remain queued in kernel socket
 * buffers until the hold is released.
 */
static void *px_main(void *arg)
{
    char buf[16384];
    (void)arg;
    for (;;) {
        struct pollfd pf[2 + 2 * ZKC_PX_PAIRS];
        int npf = 2;
        int hold;
        int i;
        pthread_mutex_lock(&g_px_mu);
        if (g_px.stop) {
            pthread_mutex_unlock(&g_px_mu);
            break;
        }
        if (g_px.reset_req) {
            while (g_px.np > 0) {
                px_drop(0, 1);
            }
            g_px.reset_req = 0;
            g_px.resets_done++;
            pthread_cond_broadcast(&g_px_cv);
        }
        hold = g_px.hold;
        pthread_mutex_unlock(&g_px_mu);
        pf[0].fd = g_px.wake[0];
        pf[0].events = POLLIN;
        pf[1].fd = g_px.lfd;
        pf[1].events = POLLIN;
        for (i = 0; i < g_px.np; i++) {
            pf[npf].fd = g_px.p[i].c;
            pf[npf].events = POLLIN;
            npf++;
            pf[npf].fd = g_px.p[i].s;
            pf[npf].events = hold ? 0 : POLLIN;
            npf++;
        }
        if (poll(pf, (nfds_t)npf, -1) < 0) {
            continue;
        }
        if (pf[0].revents & POLLIN) {
            ssize_t r = read(g_px.wake[0], buf, sizeof(buf));
            (void)r;
            continue;
        }
        if (pf[1].revents & POLLIN) {
            px_accept();
            continue;
        }
        for (i = g_px.np - 1; i >= 0; i--) {
            short cev = pf[2 + 2 * i].revents;
            short sev = pf[3 + 2 * i].revents;
            ssize_t n;
            if (cev & (POLLIN | POLLHUP | POLLERR)) {
                n = read(g_px.p[i].c, buf, sizeof(buf));
                if (n <= 0 || px_write_all(g_px.p[i].s, buf, n) != 0) {
                    px_drop(i, 0);
                    continue;
                }
                pthread_mutex_lock(&g_px_mu);
                g_px.up += (long)n;
                pthread_mutex_unlock(&g_px_mu);
            }
            if (!hold && (sev & (POLLIN | POLLHUP | POLLERR))) {
                n = read(g_px.p[i].s, buf, sizeof(buf));
                if (n <= 0 || px_write_all(g_px.p[i].c, buf, n) != 0) {
                    px_drop(i, 0);
                }
            }
        }
    }
    while (g_px.np > 0) {
        px_drop(0, 0);
    }
    return NULL;
}

const char *zkc_proxy_start(const char *target)
{
    struct sockaddr_in a;
    socklen_t alen = sizeof(a);
    char ip[64];
    const char *colon;
    int one = 1;
    if (g_px.running) {
        return g_px.host;
    }
    colon = target == NULL ? NULL : strrchr(target, ':');
    if (colon == NULL || (size_t)(colon - target) >= sizeof(ip)) {
        zkc_fail("bad proxy target %s", target == NULL ? "NULL" : target);
        return NULL;
    }
    memcpy(ip, target, (size_t)(colon - target));
    ip[colon - target] = '\0';
    memset(&g_px.target, 0, sizeof(g_px.target));
    g_px.target.sin_family = AF_INET;
    g_px.target.sin_port = htons((unsigned short)atoi(colon + 1));
    if (inet_pton(AF_INET, ip, &g_px.target.sin_addr) != 1) {
        zkc_fail("bad proxy target address %s", ip);
        return NULL;
    }
    memset(&a, 0, sizeof(a));
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    g_px.lfd = socket(AF_INET, SOCK_STREAM, 0);
    if (g_px.lfd < 0 || pipe(g_px.wake) != 0) {
        zkc_fail("proxy socket: %s", strerror(errno));
        return NULL;
    }
    setsockopt(g_px.lfd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    if (bind(g_px.lfd, (struct sockaddr *)&a, sizeof(a)) != 0 ||
        listen(g_px.lfd, 8) != 0 ||
        getsockname(g_px.lfd, (struct sockaddr *)&a, &alen) != 0) {
        zkc_fail("proxy listen: %s", strerror(errno));
        return NULL;
    }
    fcntl(g_px.wake[0], F_SETFL, O_NONBLOCK);
    fcntl(g_px.wake[1], F_SETFL, O_NONBLOCK);
    snprintf(g_px.host, sizeof(g_px.host), "127.0.0.1:%d",
             (int)ntohs(a.sin_port));
    pthread_mutex_lock(&g_mu);
    if (g_nports < ZKC_MAX_PORTS) {
        snprintf(g_port_num[g_nports], sizeof(g_port_num[0]), "%d",
                 (int)ntohs(a.sin_port));
        snprintf(g_port_name[g_nports], sizeof(g_port_name[0]), "PX");
        g_nports++;
    }
    pthread_mutex_unlock(&g_mu);
    g_px.stop = 0;
    g_px.hold = 0;
    g_px.np = 0;
    if (pthread_create(&g_px.th, NULL, px_main, NULL) != 0) {
        zkc_fail("proxy thread: %s", strerror(errno));
        return NULL;
    }
    g_px.running = 1;
    zkc_ev("proxy", "start -> %s", g_px.host);
    return g_px.host;
}

void zkc_proxy_hold(int on)
{
    pthread_mutex_lock(&g_px_mu);
    g_px.hold = on;
    pthread_mutex_unlock(&g_px_mu);
    px_wake();
    zkc_ev("proxy", "hold %s", on ? "on" : "off");
}

int zkc_proxy_reset(void)
{
    struct timespec ts;
    int want;
    int rc = 0;
    if (!g_px.running) {
        return -1;
    }
    deadline_after(&ts, 5000);
    pthread_mutex_lock(&g_px_mu);
    want = g_px.resets_done + 1;
    g_px.reset_req = 1;
    px_wake();
    while (g_px.resets_done < want) {
        if (pthread_cond_timedwait(&g_px_cv, &g_px_mu, &ts) == ETIMEDOUT) {
            rc = -1;
            break;
        }
    }
    pthread_mutex_unlock(&g_px_mu);
    zkc_ev("proxy", "reset -> %s", rc == 0 ? "ok" : "timeout");
    if (rc != 0) {
        zkc_fail("proxy reset timed out");
    }
    return rc;
}

long zkc_proxy_up_bytes(void)
{
    long n;
    pthread_mutex_lock(&g_px_mu);
    n = g_px.up;
    pthread_mutex_unlock(&g_px_mu);
    return n;
}

int zkc_proxy_accepted(void)
{
    int n;
    pthread_mutex_lock(&g_px_mu);
    n = g_px.accepted;
    pthread_mutex_unlock(&g_px_mu);
    return n;
}

void zkc_proxy_stop(void)
{
    if (!g_px.running) {
        return;
    }
    pthread_mutex_lock(&g_px_mu);
    g_px.stop = 1;
    pthread_mutex_unlock(&g_px_mu);
    px_wake();
    pthread_join(g_px.th, NULL);
    close(g_px.lfd);
    close(g_px.wake[0]);
    close(g_px.wake[1]);
    g_px.running = 0;
}
