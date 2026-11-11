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
 * zookeeper_init_ssl certificate specification parsing and per-attempt TLS
 * setup diagnostics. Mode: EXACT.
 *
 * Invariants:
 * - The certificate string is tokenized with strtok(..., ",") semantics:
 *   Adjacent commas collapse, at most four tokens are
 *   consumed, and leading/trailing whitespace within tokens is preserved.
 * - Setup steps: each failing TLS configuration step logs its exact legacy
 *   diagnostic line and returns ZSSLCONNECTIONERROR with errno = EINVAL without
 *   tearing down the handle; an omitted CA path formats "(null)".
 * - When client cert or key tokens are omitted, client identity loading is
 *   skipped and the attempt proceeds to the TLS handshake (failing against the
 *   plaintext server with "error in ssl connect"). When only the password token
 *   is omitted, legacy C skips identity loading while the Rust core loads the
 *   unencrypted identity. Using a valid unencrypted cert/key pair
 *   allows both cores to reach the handshake step here (fine-grained key-step
 *   divergences are covered by the key corpus in tls/keys).
 * - Supplying a private key in the certificate slot fails at the key step;
 *   supplying a mismatched key algorithm (Ed25519) fails at CheckPrivateKey.
 * - An empty host string fails synchronously with NULL and errno = EINVAL.
 *
 * Distinct @init_ssl_for_socket@ log lines are deduplicated in the trace
 * because legacy C retries failed configuration steps in a tight spin in state
 * 7. A DEV check asserts that the Rust mt core logs exactly one line
 * per attempt.
 *
 * DEV checks:
 * - Passing cert == NULL returns NULL with errno = EINVAL on Rust
 *   (skipped on legacy C, which faults in strdup(NULL)).
 * - An active caller strtok() sequence is preserved across
 *   zookeeper_init_ssl on Rust (clobbered by legacy C).
 *
 * zkconf-flavors: mt st
 */

#include "zkconf.h"

#include <dlfcn.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <sys/time.h>
#include <unistd.h>
#include <zookeeper_log.h>

typedef zhandle_t *(*init_ssl_fn)(const char *host, const char *cert,
                                  watcher_fn fn, int recv_timeout,
                                  const clientid_t *clientid, void *context,
                                  int flags);

/* Log tag for TLS setup and handshake diagnostics. */
#define SITE_TAG "@init_ssl_for_socket@"
#define MISSING "/nonexistent-zkconf"
#define MAX_LINES 16
#define LINE_LEN 1024

static init_ssl_fn g_init_ssl;
static char g_fix[1024];
static char g_out[1024];
static char g_stream_path[1100];
static char g_edkey[1100];
static FILE *g_stream;

/* Distinct @init_ssl_for_socket@ lines captured during an attempt, in first-seen order. */
static char g_lines[MAX_LINES][LINE_LEN];
static int g_counts[MAX_LINES];
static int g_nlines;

static const char *errno_name(int e)
{
    switch (e) {
    case 0: return "0";
    case EINVAL: return "EINVAL";
    case ENOMEM: return "ENOMEM";
    case EAGAIN: return "EAGAIN";
    case ECONNREFUSED: return "ECONNREFUSED";
    case ETIMEDOUT: return "ETIMEDOUT";
    default: return "other";
    }
}

static void quiet_watcher(zhandle_t *zh, int type, int state,
                          const char *path, void *ctx)
{
    (void)zh;
    (void)type;
    (void)state;
    (void)path;
    (void)ctx;
}

/* Copies `in` to `out`, substituting `tok` for every occurrence of `what`. */
static void replace_all(char *out, size_t outlen, const char *in,
                        const char *what, const char *tok)
{
    size_t wl = strlen(what);
    size_t o = 0;
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

/* Normalizes "errno=N(text)" in handshake log messages to "errno=E:". */
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

/* Normalizes and records an @init_ssl_for_socket@ log line starting from "ZOO_". */
static void note_line(const char *line)
{
    char a[LINE_LEN];
    char b[LINE_LEN];
    const char *p = strstr(line, "ZOO_");
    int i;
    if (p == NULL || strstr(p, SITE_TAG) == NULL) {
        return;
    }
    replace_all(a, sizeof(a), p, g_fix, "FIX");
    replace_all(b, sizeof(b), a, g_out, "OUT");
    hide_errno(b);
    for (i = 0; i < g_nlines; i++) {
        if (strcmp(g_lines[i], b) == 0) {
            g_counts[i]++;
            return;
        }
    }
    if (g_nlines < MAX_LINES) {
        snprintf(g_lines[g_nlines], LINE_LEN, "%s", b);
        g_counts[g_nlines] = 1;
        g_nlines++;
    }
}

/* Scans the log stream file and returns the total number of @init_ssl_for_socket@ lines. */
static int scan_stream(int keep)
{
    char *line = malloc(16384);
    FILE *in;
    int n = 0;
    fflush(g_stream);
    in = fopen(g_stream_path, "r");
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
        if (strstr(line, SITE_TAG) != NULL) {
            n++;
            if (keep) {
                note_line(line);
            }
        }
    }
    fclose(in);
    free(line);
    return n;
}

static void reset_stream(void)
{
    fflush(g_stream);
    if (ftruncate(fileno(g_stream), 0) != 0) {
        zkc_fail("ftruncate failed");
    }
    rewind(g_stream);
    g_nlines = 0;
}

#ifdef THREADED
/* mt: waits until the IO thread logs the first line of the attempt. */
static void wait_mt(void)
{
    int waited = 0;
    while (scan_stream(0) == 0 && waited < 10000) {
        zkc_sleep_ms(20);
        waited += 20;
    }
    if (waited >= 10000) {
        zkc_ev("wait", "no init_ssl_for_socket line in 10 s");
        return;
    }
    /* Time for a retry loop to show. */
    zkc_sleep_ms(300);
}
#else
/* st: drives the handle up to the first rc that is not ZOK. */
static void drive_st(zhandle_t *zh, int config_step)
{
    int waited = 0;
    while (waited < 10000) {
        int fd = -1;
        int interest = 0;
        int rc;
        int e;
        struct timeval tv;
        fd_set rd;
        fd_set wr;
        long ms;
        tv.tv_sec = 0;
        tv.tv_usec = 0;
        errno = 0;
        rc = zkc_interest(zh, &fd, &interest, &tv);
        e = errno;
        if (rc != ZOK) {
            zkc_ev("st", "first failure rc=%s errno=%s", zkc_rc(rc),
                   config_step ? errno_name(e) : "-");
            return;
        }
        ms = (long)tv.tv_sec * 1000 + tv.tv_usec / 1000;
        if (ms > 50 || ms < 0) {
            ms = 50;
        }
        tv.tv_sec = 0;
        tv.tv_usec = (int)(ms * 1000);
        FD_ZERO(&rd);
        FD_ZERO(&wr);
        if (fd >= 0 && (interest & ZOOKEEPER_READ)) {
            FD_SET(fd, &rd);
        }
        if (fd >= 0 && (interest & ZOOKEEPER_WRITE)) {
            FD_SET(fd, &wr);
        }
        if (select(fd >= 0 ? fd + 1 : 0, &rd, &wr, NULL, &tv) < 0) {
            zkc_fail("select failed");
            return;
        }
        waited += (int)(ms > 0 ? ms : 1);
        if (fd >= 0) {
            int ev = (FD_ISSET(fd, &rd) ? ZOOKEEPER_READ : 0) |
                     (FD_ISSET(fd, &wr) ? ZOOKEEPER_WRITE : 0);
            errno = 0;
            rc = zkc_process(zh, ev);
            e = errno;
            if (rc != ZOK) {
                zkc_ev("st", "first failure rc=%s errno=%s", zkc_rc(rc),
                       config_step ? errno_name(e) : "-");
                return;
            }
        }
    }
    zkc_ev("st", "no failure in 10 s");
}
#endif

/*
 * Executes one connection attempt with `cert`. config_step is non-zero when the
 * attempt is expected to fail during local TLS configuration rather than during
 * the network handshake.
 */
static void attempt(const char *label, const char *cert, int config_step)
{
    char tmp[2048];
    char shown[2048];
    zhandle_t *zh;
    int e;
    int total;
    int i;
    reset_stream();
    replace_all(tmp, sizeof(tmp), cert, g_fix, "FIX");
    replace_all(shown, sizeof(shown), tmp, g_out, "OUT");
    zkc_ev("case", "%s cert=\"%s\"", label, shown);
    errno = 0;
    zh = g_init_ssl(zkc_host(0), cert, quiet_watcher, 20000, NULL, NULL, 0);
    e = errno;
    if (zh == NULL) {
        zkc_ev("init", "NULL errno=%s", errno_name(e));
        return;
    }
#ifdef THREADED
    wait_mt();
#else
    drive_st(zh, config_step);
#endif
    zookeeper_close(zh);
    total = scan_stream(1);
    for (i = 0; i < g_nlines; i++) {
        zkc_ev("log", "%s", g_lines[i]);
    }
    if (g_nlines == 0) {
        zkc_ev("log", "none");
    }
    if (zkc_is_rust() && !zkc_is_st() && config_step) {
        /* Emit exactly one diagnostic log line per attempt without a retry loop. */
        zkc_dev("SEC-3", total == 1, "%s: %d init_ssl_for_socket lines",
                label, total);
    }
}

/* Writes an Ed25519 PKCS#8 key from a fixed seed (RFC 8410). */
static int write_ed25519_key(const char *path)
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

/* ZKCONF_TLS_FIX is injected by run.sh pointing to the zk-driver TLS fixture cache. */
#ifndef ZKCONF_TLS_FIX
#error "give -DZKCONF_TLS_FIX=<zk-driver TLS fixture directory> (run.sh does)"
#endif

/* Populates g_fix and verifies that ca.pem is readable. */
static int find_fixtures(void)
{
    char ca[1100];
    snprintf(g_fix, sizeof(g_fix), "%s", ZKCONF_TLS_FIX);
    snprintf(ca, sizeof(ca), "%s/ca.pem", g_fix);
    return access(ca, R_OK);
}

static void strtok_scan(void)
{
    char scan[] = "x y z";
    const char *t1;
    const char *t2;
    zhandle_t *zh;
    t1 = strtok(scan, " ");
    zh = g_init_ssl(zkc_host(0), MISSING "/ca.pem,,,", quiet_watcher, 20000,
                    NULL, NULL, 0);
    t2 = strtok(NULL, " ");
    if (zh != NULL) {
        zookeeper_close(zh);
    }
    zkc_dev("INIT-14", zkc_is_rust() ? t2 != NULL && strcmp(t2, "y") == 0 : 1,
            "the scan gave \"%s\" then \"%s\"%s", t1 ? t1 : "(null)",
            t2 ? t2 : "(null)",
            zkc_is_rust() ? "" : " (legacy clobbers the scan)");
}

int main(int argc, char **argv)
{
    char cs[4096];
    void *sym;
    const char *out;

    zkc_init(argc, argv, "tls_init", 1, "EXACT", "ignore");
    sym = dlsym(RTLD_DEFAULT, "zookeeper_init_ssl");
    if (sym == NULL) {
        zkc_ev("skip", "the library does not export zookeeper_init_ssl");
        return zkc_finish();
    }
    memcpy(&g_init_ssl, &sym, sizeof(g_init_ssl));
    if (find_fixtures() != 0) {
        zkc_fail("no TLS fixtures under %s", g_fix);
        return zkc_finish();
    }
    out = getenv("ZKCONF_OUT");
    snprintf(g_out, sizeof(g_out), "%s", out ? out : "/tmp/zkconf-tls");
    snprintf(g_stream_path, sizeof(g_stream_path), "%s.stream", g_out);
    snprintf(g_edkey, sizeof(g_edkey), "%s.ed25519.key", g_out);
    if (write_ed25519_key(g_edkey) != 0) {
        zkc_fail("cannot write %s", g_edkey);
        return zkc_finish();
    }
    g_stream = fopen(g_stream_path, "w+");
    if (g_stream == NULL) {
        zkc_fail("cannot open %s", g_stream_path);
        return zkc_finish();
    }
    zoo_set_debug_level(ZOO_LOG_LEVEL_ERROR);
    zoo_set_log_stream(g_stream);

    /* NULL cert string validation. */
    if (zkc_is_rust()) {
        zhandle_t *zh;
        errno = 0;
        zh = g_init_ssl(zkc_host(0), NULL, quiet_watcher, 20000, NULL, NULL, 0);
        zkc_dev("INIT-13", zh == NULL && errno == EINVAL,
                "NULL cert: handle=%s errno=%s", zh ? "set" : "NULL",
                errno_name(errno));
        if (zh != NULL) {
            zookeeper_close(zh);
        }
    } else {
        zkc_dev("INIT-13", 1, "not run: legacy crashes in strdup(NULL)");
    }

    /* An empty host fails before any attempt. */
    reset_stream();
    errno = 0;
    {
        zhandle_t *zh = g_init_ssl("", "", quiet_watcher, 20000, NULL, NULL, 0);
        zkc_ev("init", "empty host: handle=%s errno=%s", zh ? "set" : "NULL",
               errno_name(errno));
        if (zh != NULL) {
            zookeeper_close(zh);
        }
    }

    /* CaFile: NULL, missing, not PEM, and a leading space. */
    attempt("empty", "", 1);
    attempt("commas", ",,,", 1);
    attempt("missing-ca", MISSING "/ca.pem,,,", 1);
    snprintf(cs, sizeof(cs), "%s/gen.sh", g_fix);
    attempt("ca-not-pem", cs, 1);
    snprintf(cs, sizeof(cs), " %s/ca.pem,,,", g_fix);
    attempt("ca-space", cs, 1);

    /* ClientCertChain, with and without an empty field. */
    snprintf(cs, sizeof(cs), "%s/ca.pem," MISSING "/c.pem,%s/client.key,pw",
             g_fix, g_fix);
    attempt("missing-cert", cs, 1);
    snprintf(cs, sizeof(cs), "%s/ca.pem,," MISSING "/c.pem,%s/client.key,pw",
             g_fix, g_fix);
    attempt("collapse", cs, 1);

    /* ClientKey: a missing key, and a key of the slot of the leaf. */
    snprintf(cs, sizeof(cs), "%s/ca.pem,%s/client.pem," MISSING "/k.pem,pw",
             g_fix, g_fix);
    attempt("missing-key", cs, 1);
    snprintf(cs, sizeof(cs), "%s/ca.pem,%s/client.pem,%s/server.key,pw",
             g_fix, g_fix, g_fix);
    attempt("key-mismatch", cs, 1);

    /* CheckPrivateKey: a key of another slot. */
    snprintf(cs, sizeof(cs), "%s/ca.pem,%s/client.pem,%s,pw", g_fix, g_fix,
             g_edkey);
    attempt("key-other-slot", cs, 1);

    /* Omitted password field: a valid unencrypted key pair reaches the handshake. */
    snprintf(cs, sizeof(cs), "%s/ca.pem,%s/client.pem,%s/client.key",
             g_fix, g_fix, g_fix);
    attempt("no-passwd", cs, 0);
    /* Trailing fields beyond the fourth comma-delimited token are ignored. */
    snprintf(cs, sizeof(cs), "%s/ca.pem,%s/client.pem,%s/client.key,pw,"
             MISSING "/x", g_fix, g_fix, g_fix);
    attempt("five-fields", cs, 0);

    /* Verify that cert parsing does not clobber the caller's strtok() state. */
    reset_stream();
    strtok_scan();

    zoo_set_log_stream(NULL);
    fclose(g_stream);
    return zkc_finish();
}
