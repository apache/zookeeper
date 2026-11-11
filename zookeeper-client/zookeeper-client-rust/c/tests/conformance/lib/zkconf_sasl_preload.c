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
 * Cyrus SASL and socket lifecycle interposition preload library for SASL
 * ordering conformance tests (`sasl_order`, `sasl_fail`). Test-only support
 * code; never installed or shipped.
 *
 * Compiled as a shared library linked against `libsasl2` and injected via
 * `DYLD_INSERT_LIBRARIES` (macOS) or `LD_PRELOAD` (Linux) for scenarios
 * declaring `zkconf-preload: sasl`. Interposes `sasl_client_new`,
 * `sasl_dispose`, `connect`, `close`, `getsockname`, and `getpeername` across
 * both client implementations (`zkconf_interpose.h`), recording events in a
 * single serialized timeline.
 *
 * Descriptors connecting to a port listed in `ZKCONF_HOSTS` are tagged as
 * server sockets. A `close` event (`'C'`) is recorded only for server sockets
 * that have undergone a local or peer address query (`getsockname` or
 * `getpeername`), filtering out transient reconnect attempts that fail prior to
 * SASL initialization.
 *
 * Also supports fault injection on socket address queries (`zkconf_sasl_fail`)
 * and an optional synchronization gate (`zkconf_sasl_gate`) that pauses the I/O
 * thread upon entering `sasl_client_new` (after the Rust I/O thread has
 * released its internal handle mutex following socket address queries).
 */

#include <errno.h>
#include <netinet/in.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#include <sasl/sasl.h>

#include "zkconf_interpose.h"
#include "zkconf_sasl_preload.h"

#define MAX_EVENTS 512
#define MAX_FDS 1024
#define MAX_PORTS 16

/* Per-descriptor flags: bit 0 = connected to a server port; bit 1 = address queried. */
static unsigned char g_fd[MAX_FDS];
static char g_ev[MAX_EVENTS];
static int g_nev;
static int g_fail;
static int g_gate;
static int g_gated;
static int g_ports[MAX_PORTS];
static int g_nports = -1;

static void add_event(char c)
{
    int i = __atomic_fetch_add(&g_nev, 1, __ATOMIC_SEQ_CST);
    if (i >= 0 && i < MAX_EVENTS) {
        __atomic_store_n(&g_ev[i], c, __ATOMIC_SEQ_CST);
    }
}

/* Lazily parses configured server ports from `ZKCONF_HOSTS` (`"H:P,H:P,..."`). */
static void load_ports(void)
{
    const char *s;
    int n = 0;
    if (__atomic_load_n(&g_nports, __ATOMIC_SEQ_CST) >= 0) {
        return;
    }
    s = getenv("ZKCONF_HOSTS");
    while (s != NULL && *s != '\0' && n < MAX_PORTS) {
        const char *colon = strchr(s, ':');
        const char *comma;
        if (colon == NULL) {
            break;
        }
        g_ports[n++] = atoi(colon + 1);
        comma = strchr(colon, ',');
        s = comma != NULL ? comma + 1 : NULL;
    }
    __atomic_store_n(&g_nports, n, __ATOMIC_SEQ_CST);
}

static int is_server_port(int port)
{
    int i;
    load_ports();
    for (i = 0; i < g_nports; i++) {
        if (g_ports[i] == port) {
            return 1;
        }
    }
    return 0;
}

static int addr_port(const struct sockaddr *a, socklen_t len)
{
    if (a == NULL) {
        return -1;
    }
    if (a->sa_family == AF_INET && len >= (socklen_t)sizeof(struct sockaddr_in)) {
        return ntohs(((const struct sockaddr_in *)(const void *)a)->sin_port);
    }
    if (a->sa_family == AF_INET6 && len >= (socklen_t)sizeof(struct sockaddr_in6)) {
        return ntohs(((const struct sockaddr_in6 *)(const void *)a)->sin6_port);
    }
    return -1;
}

static int fd_flags(int fd)
{
    if (fd < 0 || fd >= MAX_FDS) {
        return 0;
    }
    return __atomic_load_n(&g_fd[fd], __ATOMIC_SEQ_CST);
}

static void fd_set_flags(int fd, int flags)
{
    if (fd >= 0 && fd < MAX_FDS) {
        __atomic_store_n(&g_fd[fd], (unsigned char)flags, __ATOMIC_SEQ_CST);
    }
}

int zkconf_sasl_events(char *buf, int cap)
{
    int n = __atomic_load_n(&g_nev, __ATOMIC_SEQ_CST);
    int i;
    if (n > MAX_EVENTS) {
        n = MAX_EVENTS;
    }
    if (buf == NULL || cap <= 0) {
        return n;
    }
    for (i = 0; i < n && i < cap - 1; i++) {
        buf[i] = __atomic_load_n(&g_ev[i], __ATOMIC_SEQ_CST);
    }
    buf[i] = '\0';
    return n;
}

void zkconf_sasl_mark(char c)
{
    add_event(c);
}

void zkconf_sasl_fail(int n)
{
    __atomic_store_n(&g_fail, n, __ATOMIC_SEQ_CST);
}

int zkconf_sasl_open(void)
{
    int fd;
    int n = 0;
    for (fd = 0; fd < MAX_FDS; fd++) {
        if (fd_flags(fd) != 0) {
            n++;
        }
    }
    return n;
}

void zkconf_sasl_gate(int on)
{
    __atomic_store_n(&g_gate, on, __ATOMIC_SEQ_CST);
}

int zkconf_sasl_gated(void)
{
    return __atomic_load_n(&g_gated, __ATOMIC_SEQ_CST);
}

/* Blocks the caller for up to 10 seconds while `g_gate` is non-zero. */
static void wait_gate(void)
{
    struct timespec ts = {0, 10000000};
    int i;
    if (__atomic_load_n(&g_gate, __ATOMIC_SEQ_CST) == 0) {
        return;
    }
    __atomic_store_n(&g_gated, 1, __ATOMIC_SEQ_CST);
    for (i = 0; i < 1000 && __atomic_load_n(&g_gate, __ATOMIC_SEQ_CST) != 0; i++) {
        (void)nanosleep(&ts, NULL);
    }
    __atomic_store_n(&g_gated, 0, __ATOMIC_SEQ_CST);
}

/* Atomically consumes one armed failure token, returning 1 if the caller should fail. */
static int take_failure(void)
{
    int n = __atomic_load_n(&g_fail, __ATOMIC_SEQ_CST);
    while (n > 0) {
        if (__atomic_compare_exchange_n(&g_fail, &n, n - 1, 0, __ATOMIC_SEQ_CST,
                                        __ATOMIC_SEQ_CST)) {
            return 1;
        }
    }
    return 0;
}

static int zkconf_connect(int fd, const struct sockaddr *addr, socklen_t len)
{
    int rc = ZKCONF_REAL(connect)(fd, addr, len);
    int saved = errno;
    if (is_server_port(addr_port(addr, len))) {
        fd_set_flags(fd, 1);
    }
    errno = saved;
    return rc;
}

static int zkconf_close(int fd)
{
    int flags = fd_flags(fd);
    if (flags != 0) {
        fd_set_flags(fd, 0);
        if ((flags & 2) != 0) {
            add_event('C');
        }
    }
    return ZKCONF_REAL(close)(fd);
}

static int addr_read(int fd, char ev)
{
    int flags = fd_flags(fd);
    if ((flags & 1) == 0) {
        return 0;
    }
    fd_set_flags(fd, flags | 2);
    add_event(ev);
    if (take_failure()) {
        errno = ENOBUFS;
        return -1;
    }
    return 0;
}

static int zkconf_getsockname(int fd, struct sockaddr *addr, socklen_t *len)
{
    if (addr_read(fd, 'G') != 0) {
        return -1;
    }
    return ZKCONF_REAL(getsockname)(fd, addr, len);
}

static int zkconf_getpeername(int fd, struct sockaddr *addr, socklen_t *len)
{
    if (addr_read(fd, 'P') != 0) {
        return -1;
    }
    return ZKCONF_REAL(getpeername)(fd, addr, len);
}

static int zkconf_sasl_client_new(const char *service, const char *server_fqdn,
                                  const char *iplocalport, const char *ipremoteport,
                                  const sasl_callback_t *prompt_supp, unsigned flags,
                                  sasl_conn_t **pconn)
{
    wait_gate();
    add_event('N');
    return ZKCONF_REAL(sasl_client_new)(service, server_fqdn, iplocalport, ipremoteport,
                                        prompt_supp, flags, pconn);
}

static void zkconf_sasl_dispose(sasl_conn_t **pconn)
{
    if (pconn != NULL && *pconn != NULL) {
        add_event('D');
    }
    ZKCONF_REAL(sasl_dispose)(pconn);
}

ZKCONF_INTERPOSE(connect);
ZKCONF_INTERPOSE(close);
ZKCONF_INTERPOSE(getsockname);
ZKCONF_INTERPOSE(getpeername);
ZKCONF_INTERPOSE(sasl_client_new);
ZKCONF_INTERPOSE(sasl_dispose);
