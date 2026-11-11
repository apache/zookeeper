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
 * Consumer test application for static-link verification. Links
 * directly against a static Rust client archive (`libzookeeper_{mt,st}.a` or
 * `libzookeeper.a`) under optional compile-time configurations:
 *
 * - `ZKLINK_RUST`: links and invokes an independent Rust `staticlib`
 *   to verify that two distinct `std` runtimes coexist in a single executable.
 * - `ZKLINK_OWN`: links `own_symbols.c`, which defines global symbols matching
 *   legacy C internal identifiers such as `hash` and `do_io`.
 * - `ZKLINK_RING`: links `zklinkring` containing independent `rustls` and
 *   `ring` instances, using TLS fixtures from `ZKLINK_TLS_FIX`.
 *
 * Usage: link_app [HOST]
 * When `HOST` is omitted, executes offline symbol, constant, and argument
 * validation checks. When `HOST` is provided, connects to the server and
 * exercises asynchronous create, get, delete, and close operations. In
 * single threaded (`st`) builds, `zookeeper_interest` and `zookeeper_process`
 * drive the handle with bounded waits.
 */

#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/select.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#include <zookeeper.h>
#include <zookeeper_version.h>

#ifdef ZKLINK_RUST
int zklinkrs_probe(const char *host);
int zklinkrs_format_len(int n);
#endif
#ifdef ZKLINK_OWN
int zklink_own_check(void);
#endif
#ifdef ZKLINK_RING
int zklinkring_probe(void);
int zklinkring_server_start(const char *dir);
int zklinkring_server_state(void);
/*
 * `zookeeper.h` guards `zookeeper_init_ssl` behind `HAVE_OPENSSL_H`, which in
 * turn includes OpenSSL headers. Declare the prototype directly so this test
 * compiles without requiring OpenSSL headers on the host.
 */
zhandle_t *zookeeper_init_ssl(const char *host, const char *cert, watcher_fn fn,
                              int recv_timeout, const clientid_t *clientid, void *context,
                              int flags);
#endif

static int failures;
static volatile int done;
static volatile int last_rc;
static char got[16];

#define CHECK(cond)                                                       \
    do {                                                                  \
        if (!(cond)) {                                                    \
            printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);        \
            failures++;                                                   \
        }                                                                 \
    } while (0)

static void sleep_ms(long ms)
{
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (ms % 1000) * 1000000L;
    nanosleep(&ts, NULL);
}

#ifdef THREADED
/* Yields for 20 ms while background I/O and completion threads advance. */
static void drive(zhandle_t *zh)
{
    (void)zh;
    sleep_ms(20);
}
#else
/* Executes one single threaded event-loop iteration bounded to 20 ms. */
static void drive(zhandle_t *zh)
{
    struct timeval tv = {0, 0};
    fd_set rd;
    fd_set wr;
    int fd = -1;
    int interest = 0;
    int events = 0;
    int rc = zookeeper_interest(zh, &fd, &interest, &tv);

    if (rc != ZOK || fd < 0 || fd >= FD_SETSIZE) {
        sleep_ms(rc == ZOK ? 1 : 20);
        return;
    }
    if (tv.tv_sec > 0 || tv.tv_usec > 20000) {
        tv.tv_sec = 0;
        tv.tv_usec = 20000;
    }
    FD_ZERO(&rd);
    FD_ZERO(&wr);
    if (interest & ZOOKEEPER_READ) {
        FD_SET(fd, &rd);
    }
    if (interest & ZOOKEEPER_WRITE) {
        FD_SET(fd, &wr);
    }
    if (select(fd + 1, &rd, &wr, NULL, &tv) > 0) {
        events |= FD_ISSET(fd, &rd) ? ZOOKEEPER_READ : 0;
        events |= FD_ISSET(fd, &wr) ? ZOOKEEPER_WRITE : 0;
    }
    zookeeper_process(zh, events);
}
#endif

/* Polls up to 10 seconds until the completion counter reaches `want`. */
static int wait_done(zhandle_t *zh, int want)
{
    int i;
    for (i = 0; i < 500; i++) {
        if (__atomic_load_n(&done, __ATOMIC_SEQ_CST) >= want) {
            return 1;
        }
        drive(zh);
    }
    return 0;
}

static void finish(int rc)
{
    last_rc = rc;
    __atomic_fetch_add(&done, 1, __ATOMIC_SEQ_CST);
}

static void watcher(zhandle_t *zh, int type, int state, const char *path, void *ctx)
{
    (void)zh;
    (void)type;
    (void)state;
    (void)path;
    (void)ctx;
}

static void string_done(int rc, const char *v, const void *d)
{
    (void)v;
    (void)d;
    finish(rc);
}

static void data_done(int rc, const char *v, int vl, const struct Stat *st, const void *d)
{
    (void)st;
    (void)d;
    if (rc == ZOK && v != NULL && vl > 0 && vl < (int)sizeof(got)) {
        memcpy(got, v, (size_t)vl);
        got[vl] = '\0';
    }
    finish(rc);
}

static void void_done(int rc, const void *d)
{
    (void)d;
    finish(rc);
}

/* Exercises server-independent entry points, exported constants, and error strings. */
static void offline_checks(void)
{
    CHECK(zoo_aget(NULL, "/a", 0, data_done, NULL) == ZBADARGUMENTS);
    CHECK(ZOO_CONNECTED_STATE == 3);
    CHECK(strcmp(zoo_version_str(), ZOO_VERSION) == 0);
    CHECK(strcmp(zerror(ZNONODE), "no node") == 0);
}

/* Establishes a session with `host` and creates, reads, and deletes an ephemeral znode. */
static void server_checks(const char *host)
{
    char path[64];
    int want = 0;
    int i;
    zhandle_t *zh = zookeeper_init(host, watcher, 10000, NULL, NULL, 0);

    CHECK(zh != NULL);
    if (zh == NULL) {
        return;
    }
    for (i = 0; i < 500 && zoo_state(zh) != ZOO_CONNECTED_STATE; i++) {
        drive(zh);
    }
    CHECK(zoo_state(zh) == ZOO_CONNECTED_STATE);
    snprintf(path, sizeof(path), "/zk-link-%ld", (long)getpid());

    CHECK(zoo_acreate(zh, path, "link", 4, &ZOO_OPEN_ACL_UNSAFE, ZOO_EPHEMERAL, string_done,
                      NULL) == ZOK);
    want++;
    CHECK(wait_done(zh, want) && last_rc == ZOK);
    CHECK(zoo_aget(zh, path, 0, data_done, NULL) == ZOK);
    want++;
    CHECK(wait_done(zh, want) && last_rc == ZOK);
    CHECK(strcmp(got, "link") == 0);
    CHECK(zoo_adelete(zh, path, -1, void_done, NULL) == ZOK);
    want++;
    CHECK(wait_done(zh, want) && last_rc == ZOK);
    CHECK(zookeeper_close(zh) == ZOK);
}

#ifdef ZKLINK_RING
/*
 * Cryptographic provider isolation test: `zklinkring` installs its own
 * default `rustls` provider and starts an in-process mTLS listener that accepts
 * a client-authenticated handshake from `zookeeper_init_ssl`. Each side
 * performs signing and verification using its own localized `ring` instance.
 */
static void ring_checks(void)
{
    char host[32];
    char cert[1024];
    int port;
    int i;
    zhandle_t *zh;

    CHECK(zklinkring_probe() == 0);
    port = zklinkring_server_start(ZKLINK_TLS_FIX);
    CHECK(port > 0);
    if (port <= 0) {
        return;
    }
    snprintf(host, sizeof(host), "127.0.0.1:%d", port);
    /* Pass an encrypted PKCS#8 client key with its passphrase (`zk-test-pass`). */
    snprintf(cert, sizeof(cert), "%s/ca.pem,%s/client.pem,%s/%s,zk-test-pass", ZKLINK_TLS_FIX,
             ZKLINK_TLS_FIX, ZKLINK_TLS_FIX, "keys/p8-sha256-aes256.pem");
    zh = zookeeper_init_ssl(host, cert, watcher, 10000, NULL, NULL, 0);
    CHECK(zh != NULL);
    if (zh == NULL) {
        return;
    }
    for (i = 0; i < 500 && zklinkring_server_state() == 0; i++) {
        drive(zh);
    }
    printf("link_app: TLS server state %d\n", zklinkring_server_state());
    CHECK(zklinkring_server_state() == 1);
    CHECK(zookeeper_close(zh) == ZOK);
}
#endif

int main(int argc, char **argv)
{
    const char *host = argc > 1 ? argv[1] : NULL;

#ifndef THREADED
    /* Ignore SIGPIPE when socket writes execute on the application thread. */
    signal(SIGPIPE, SIG_IGN);
#endif
    zoo_set_debug_level(0);
    offline_checks();
#ifdef ZKLINK_OWN
    CHECK(zklink_own_check() == 0);
#endif
#ifdef ZKLINK_RUST
    CHECK(zklinkrs_format_len(7) == 17);
    CHECK(zklinkrs_probe(host) == 0);
#endif
#ifdef ZKLINK_RING
    ring_checks();
#endif
    if (host != NULL) {
        server_checks(host);
    }
#ifdef ZKLINK_OWN
    /* Confirm that client operations did not invoke any application-defined symbols. */
    CHECK(zklink_own_check() == 0);
#endif
    printf("link_app: %d failures\n", failures);
    return failures == 0 ? 0 : 1;
}
