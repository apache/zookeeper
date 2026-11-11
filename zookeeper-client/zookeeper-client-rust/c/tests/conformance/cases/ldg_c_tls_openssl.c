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
 * Independence of the Rust TLS stack from system OpenSSL linkage, protocol
 * floors, and OPENSSL_CONF policy overrides. Mode: DEV.
 *
 * Invariants and deviations:
 * - The Rust core negotiates only TLS 1.2 and TLS 1.3 and
 *   rejects a TLS 1.1-only server regardless of OPENSSL_CONF, whereas legacy C
 *   connects when OPENSSL_CONF lowers MinProtocol to TLSv1 and SECLEVEL=0.
 * - The Rust core rejects server-initiated TLS 1.2 renegotiation,
 *   causing the server to terminate the session, whereas legacy C honors
 *   renegotiation under SSL_MODE_AUTO_RETRY.
 * - OPENSSL_CONF policy directives (such as MinProtocol =
 *   TLSv1.3 or SECLEVEL=5) cause legacy C to reject a TLS 1.2 server, whereas
 *   the Rust core ignores OPENSSL_CONF and connects normally.
 * - The Rust drop-in does not link against libssl or libcrypto
 *   (although on ELF platforms libsasl2 may pull in libcrypto as a transitive
 *   dependency).
 * - The Rust core offers only AEAD ECDHE cipher suites and rejects a
 *   TLS 1.2 server restricted to a legacy CBC suite, whereas legacy C connects.
 * - Performing a TLS handshake in the Rust drop-in neither loads
 *   OpenSSL shared objects nor initializes process-global OpenSSL state.
 *
 * Test peers are local `openssl s_server` child processes offering TLS 1.1
 * (SECLEVEL=0), TLS 1.2 (SECLEVEL=0), and TLS 1.2 CBC-only respectively. Each
 * client connection attempt executes in a forked child so OPENSSL_CONF is
 * evaluated fresh per attempt (exit 0 = reached ASSOCIATING, 1 = logged "error
 * in ssl connect", 2 = timeout, 3 = server logged renegotiation refusal or
 * connection closure).
 *
 * zkconf-flavors: mt st
 */

/* Expose dl_iterate_phdr on glibc platforms. */
#if !defined(__APPLE__) && !defined(_GNU_SOURCE)
#define _GNU_SOURCE
#endif

#include "zkconf.h"
#include "zkconf_tls.h"

#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#if defined(__APPLE__)
#include <mach-o/dyld.h>
#else
#include <link.h>
#endif

#define T_MS 30000
#define WAIT_MS 8000
/* Default openssl binary path: Homebrew openssl@3 on macOS, /usr/bin/openssl elsewhere. */
#ifdef __APPLE__
#define OPENSSL_DEFAULT "/opt/homebrew/opt/openssl@3/bin/openssl"
#else
#define OPENSSL_DEFAULT "/usr/bin/openssl"
#endif

/* State for a managed `openssl s_server` subprocess. */
struct peer {
    pid_t pid;
    int in;
    char addr[64];
    char out[1100];
};

static struct peer g_s11;
static struct peer g_s12;
static struct peer g_cbc;
static char g_cert[4096];
static const char *g_target;
static const char *g_conf;
static int g_reneg;
static int g_reneg_fd;
static const char *g_reneg_out;

static void quiet_watcher(zhandle_t *zh, int type, int state,
                          const char *path, void *ctx)
{
    (void)zh;
    (void)type;
    (void)state;
    (void)path;
    (void)ctx;
}

static int write_file(const char *path, const char *text)
{
    FILE *f = fopen(path, "w");
    if (f == NULL) {
        return -1;
    }
    fputs(text, f);
    return fclose(f);
}

/* Returns the number of lines in path containing needle. */
static int count_in(const char *path, const char *needle)
{
    char line[4096];
    int n = 0;
    FILE *f = fopen(path, "r");
    if (f == NULL) {
        return 0;
    }
    while (fgets(line, sizeof(line), f) != NULL) {
        if (strstr(line, needle) != NULL) {
            n++;
        }
    }
    fclose(f);
    return n;
}

/* Returns 1 if any loaded shared object path contains name. */
#if defined(__APPLE__)
static int image_loaded(const char *name)
{
    uint32_t i;
    for (i = 0; i < _dyld_image_count(); i++) {
        const char *p = _dyld_get_image_name(i);
        if (p != NULL && strstr(p, name) != NULL) {
            return 1;
        }
    }
    return 0;
}
#else
static int image_cb(struct dl_phdr_info *info, size_t size, void *arg)
{
    (void)size;
    return info->dlpi_name != NULL &&
           strstr(info->dlpi_name, (const char *)arg) != NULL;
}

static int image_loaded(const char *name)
{
    return dl_iterate_phdr(image_cb, (void *)name);
}
#endif

static int openssl_loaded(void)
{
    return image_loaded("libssl") || image_loaded("libcrypto");
}

/*
 * Returns 1 when neither libssl nor libcrypto is loaded directly by the client
 * library (allowing libcrypto if pulled in transitively by libsasl2).
 */
static int no_own_openssl(void)
{
    return !image_loaded("libssl") &&
           (!image_loaded("libcrypto") || zkc_sasl_needs_crypto());
}

/*
 * Spawns `openssl s_server` on port with protocol flag proto and cipher list
 * cipher, keeping its stdin pipe open so the server persists until peer_stop.
 */
static int peer_start(struct peer *p, const char *tag, int port,
                      const char *proto, const char *cipher)
{
    char accept[64];
    char cert[1100];
    char key[1100];
    const char *ossl = getenv("OPENSSL");
    int fds[2];
    long end;
    if (ossl == NULL || *ossl == '\0') {
        ossl = OPENSSL_DEFAULT;
    }
    snprintf(p->addr, sizeof(p->addr), "127.0.0.1:%d", port);
    snprintf(accept, sizeof(accept), "127.0.0.1:%d", port);
    snprintf(p->out, sizeof(p->out), "%s.%s.out", zkt_out(), tag);
    snprintf(cert, sizeof(cert), "%s/server.pem", zkt_fix());
    snprintf(key, sizeof(key), "%s/server.key", zkt_fix());
    if (access(ossl, X_OK) != 0 || pipe(fds) != 0) {
        return -1;
    }
    fflush(NULL);
    p->pid = fork();
    if (p->pid < 0) {
        return -1;
    }
    if (p->pid == 0) {
        FILE *o = fopen(p->out, "w");
        if (o == NULL) {
            _exit(127);
        }
        dup2(fds[0], 0);
        dup2(fileno(o), 1);
        dup2(fileno(o), 2);
        close(fds[1]);
        execl(ossl, ossl, "s_server", "-accept", accept, proto, "-cipher",
              cipher, "-cert", cert, "-key", key, (char *)NULL);
        _exit(127);
    }
    close(fds[0]);
    p->in = fds[1];
    end = zkt_now_ms() + 10000;
    while (count_in(p->out, "ACCEPT") == 0) {
        if (zkt_now_ms() >= end) {
            return -1;
        }
        zkc_sleep_ms(20);
    }
    return 0;
}

static void peer_stop(struct peer *p)
{
    if (p->pid > 0) {
        close(p->in);
        kill(p->pid, SIGTERM);
        waitpid(p->pid, NULL, 0);
        p->pid = 0;
    }
}

/* Advances the handle by one tick (10 ms sleep in mt; interest + process in st). */
static void pass(zhandle_t *zh)
{
#ifdef THREADED
    (void)zh;
    zkc_sleep_ms(10);
#else
    int interest;
    int err;
    int mid;
    const char *where;
    (void)zkt_st_pass(zh, 10, &interest, &err, &where, &mid);
#endif
}

/*
 * Child body for a single TLS connection attempt: configures OPENSSL_CONF
 * prior to initializing the library and connects to g_target.
 */
static int attempt_child(void)
{
    zhandle_t *zh;
    long end;
    int done = 0;
    if (g_conf != NULL) {
        setenv("OPENSSL_CONF", g_conf, 1);
    } else {
        unsetenv("OPENSSL_CONF");
    }
    zkt_log_reset();
    zh = zkt_init_raw(g_target, g_cert, quiet_watcher, T_MS, NULL, NULL, 0);
    if (zh == NULL) {
        return 4;
    }
    end = zkt_now_ms() + WAIT_MS;
    while (zkt_now_ms() < end) {
        if (zoo_state(zh) == ZOO_ASSOCIATING_STATE) {
            done = 1;
            break;
        }
        if (zkt_log_count("error in ssl connect") > 0) {
            return 1;
        }
        pass(zh);
    }
    if (!done) {
        return 2;
    }
    if (g_reneg) {
        /*
         * Writing "r\n" to s_server stdin triggers a server-initiated
         * renegotiation on the active session; s_server logs "no renegotiation"
         * or "CONNECTION CLOSED" when the client rejects it.
         */
        int closed = count_in(g_reneg_out, "CONNECTION CLOSED");
        if (write(g_reneg_fd, "r\n", 2) != 2) {
            return 5;
        }
        end = zkt_now_ms() + 3000;
        while (zkt_now_ms() < end) {
            if (zoo_state(zh) != ZOO_ASSOCIATING_STATE ||
                count_in(g_reneg_out, "no renegotiation") > 0 ||
                count_in(g_reneg_out, "CONNECTION CLOSED") > closed) {
                return 3;
            }
            pass(zh);
        }
    }
    return 0;
}

static const char *attempt(const char *label, struct peer *p, const char *conf,
                           int reneg)
{
    g_target = p->addr;
    g_conf = conf;
    g_reneg = reneg;
    g_reneg_fd = p->in;
    g_reneg_out = p->out;
    return zkc_child(label, attempt_child, WAIT_MS + 8000);
}

static int is(const char *res, const char *want)
{
    return strcmp(res, want) == 0;
}

int main(int argc, char **argv)
{
    char neutral[1100];
    char open[1100];
    char min13[1100];
    char sec5[1100];
    const char *dead;
    const char *colon;
    const char *r;
    int port;
    int rust;
    zhandle_t *zh;
    long end;
    int assoc = 0;
    int crypto0;

    zkc_init(argc, argv, "ldg_c_tls_openssl", 0, "DEV", "ignore");
    if (zkt_setup(0) != 0) {
        return zkc_finish();
    }
    rust = zkc_is_rust();
    snprintf(g_cert, sizeof(g_cert), "%s/ca.pem,,,", zkt_fix());

    /* Verify loaded OpenSSL shared library state at startup. */
    crypto0 = image_loaded("libcrypto");
    zkc_dev("TLS-15", rust ? no_own_openssl() : openssl_loaded(),
            "loaded at start: libssl %d, libcrypto %d, libsasl2 %d "
            "(needs libcrypto %d)",
            image_loaded("libssl"), crypto0, image_loaded("libsasl2"),
            zkc_sasl_needs_crypto());

    snprintf(neutral, sizeof(neutral), "%s.neutral.cnf", zkt_out());
    snprintf(open, sizeof(open), "%s.open.cnf", zkt_out());
    snprintf(min13, sizeof(min13), "%s.min13.cnf", zkt_out());
    snprintf(sec5, sizeof(sec5), "%s.sec5.cnf", zkt_out());
#define CNF_HEAD                                                          \
    "openssl_conf = openssl_init\n[openssl_init]\nssl_conf = ssl_sect\n"  \
    "[ssl_sect]\nsystem_default = sys\n[sys]\n"
    if (write_file(neutral, CNF_HEAD) != 0 ||
        write_file(open, CNF_HEAD "MinProtocol = TLSv1\n"
                                  "CipherString = DEFAULT@SECLEVEL=0\n") != 0 ||
        write_file(min13, CNF_HEAD "MinProtocol = TLSv1.3\n") != 0 ||
        write_file(sec5, CNF_HEAD "CipherString = DEFAULT@SECLEVEL=5\n") != 0) {
        zkc_fail("cannot write the openssl.cnf files");
        return zkc_finish();
    }

    dead = zkc_dead();
    colon = strrchr(dead, ':');
    port = colon != NULL ? atoi(colon + 1) : 0;
    if (port <= 0) {
        zkc_fail("no dead port in %s", dead);
        return zkc_finish();
    }
    if (peer_start(&g_s11, "s11", port + 4, "-tls1_1", "DEFAULT@SECLEVEL=0") !=
            0 ||
        peer_start(&g_s12, "s12", port + 6, "-tls1_2", "DEFAULT@SECLEVEL=0") !=
            0 ||
        peer_start(&g_cbc, "cbc", port + 2, "-tls1_2",
                   "ECDHE-ECDSA-AES128-SHA256") != 0) {
        zkc_fail("cannot start openssl s_server");
        peer_stop(&g_s11);
        peer_stop(&g_s12);
        peer_stop(&g_cbc);
        return zkc_finish();
    }

    /* Protocol version floor. */
    r = attempt("floor-neutral", &g_s11, neutral, 0);
    zkc_dev("DV-TLS-1", is(r, "exit:1"),
            "TLS 1.1 server, neutral openssl.cnf: %s (both refuse)", r);
    r = attempt("floor-open", &g_s11, open, 0);
    zkc_dev("TLS-12", rust ? is(r, "exit:1") : is(r, "exit:0"),
            "TLS 1.1 server, openssl.cnf allows TLS 1.1: %s (%s)", r,
            rust ? "the Rust core refuses" : "legacy connects");

    /* Server-initiated TLS 1.2 renegotiation. */
    r = attempt("reneg", &g_s12, neutral, 1);
    zkc_dev("DV-TLS-1", rust ? is(r, "exit:3") : is(r, "exit:0"),
            "server renegotiation: %s (%s)", r,
            rust ? "the Rust core refuses, the server ends the connection"
                 : "legacy renegotiates");

    /* OPENSSL_CONF policy independence. */
    r = attempt("policy-neutral", &g_s12, neutral, 0);
    zkc_dev("TLS-13", is(r, "exit:0"),
            "TLS 1.2 server, neutral openssl.cnf: %s (both connect)", r);
    r = attempt("policy-min13", &g_s12, min13, 0);
    zkc_dev("TLS-13", rust ? is(r, "exit:0") : is(r, "exit:1"),
            "TLS 1.2 server, MinProtocol TLSv1.3: %s (%s)", r,
            rust ? "openssl.cnf does not apply" : "legacy refuses");
    r = attempt("policy-seclevel5", &g_s12, sec5, 0);
    zkc_dev("DV-TLS-2", rust ? is(r, "exit:0") : is(r, "exit:1"),
            "TLS 1.2 server, SECLEVEL 5: %s (%s)", r,
            rust ? "the OpenSSL security level does not apply"
                 : "legacy refuses");

    /* Cipher-suite floor: reject non-AEAD CBC cipher suites. */
    r = attempt("cbc-only", &g_cbc, neutral, 0);
    zkc_dev("TLS-14", rust ? is(r, "exit:1") : is(r, "exit:0"),
            "TLS 1.2 server with only a CBC suite: %s (%s)", r,
            rust ? "the Rust core offers only AEAD ECDHE suites"
                 : "legacy offers CBC suites");

    /* Verify that in-process TLS handshakes do not dynamically load OpenSSL. */
    setenv("OPENSSL_CONF", neutral, 1);
    zh = zkt_init_raw(g_s12.addr, g_cert, quiet_watcher, T_MS, NULL, NULL, 0);
    if (zh == NULL) {
        zkc_fail("init failed: errno %d", errno);
    } else {
        end = zkt_now_ms() + WAIT_MS;
        while (zkt_now_ms() < end && !assoc) {
            assoc = zoo_state(zh) == ZOO_ASSOCIATING_STATE;
            pass(zh);
        }
        zkc_dev("TLS-02",
                assoc && (rust ? no_own_openssl() &&
                                     image_loaded("libcrypto") == crypto0
                               : openssl_loaded()),
                "after a TLS handshake: associating %d, libssl %d, "
                "libcrypto %d (at start %d)",
                assoc, image_loaded("libssl"), image_loaded("libcrypto"),
                crypto0);
        zookeeper_close(zh);
    }
    peer_stop(&g_s11);
    peer_stop(&g_s12);
    peer_stop(&g_cbc);
    return zkc_finish();
}
