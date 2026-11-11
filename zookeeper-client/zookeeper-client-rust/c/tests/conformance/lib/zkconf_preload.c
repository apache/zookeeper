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
 * `connect(2)` interposition preload library for TLS conformance scenarios.
 *
 * Compiled as a shared object by `run.sh` and injected via
 * `DYLD_INSERT_LIBRARIES` (macOS) or `LD_PRELOAD` (Linux) for scenarios
 * declaring `zkconf-preload: connect`. Interposes `connect(2)` across both C
 * and Rust client cores (`zkconf_interpose.h`) so tests can deterministically
 * select the completion path of nonblocking loopback connects:
 *
 * - `ZKCONF_CONNECT_EINPROGRESS`: forces an immediately completed nonblocking
 *   `connect(2)` to return `-1` with `errno = EINPROGRESS` even though the
 *   underlying socket is already connected.
 * - `ZKCONF_CONNECT_IMMEDIATE`: intercepts `-1` / `EINPROGRESS`, polls the
 *   socket for up to 5 seconds until completion, and returns the final result
 *   synchronously.
 * - `ZKCONF_CONNECT_PASS`: passes through the native `connect(2)` outcome.
 *
 * In addition, records the monotonic timestamp of every `connect(2)` call for
 * failover timing assertions.
 */

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdint.h>
#include <sys/socket.h>
#include <time.h>

#include "zkconf_interpose.h"
#include "zkconf_preload.h"

#define MAX_CALLS 256

static int g_mode = ZKCONF_CONNECT_PASS;
/*
 * Each invocation claims a slot index from `g_next` and increments `g_count`
 * only after storing its timestamp in `g_times`, ensuring readers never observe
 * an uninitialized slot below `g_count`.
 */
static int g_next;
static int g_count;
static long g_times[MAX_CALLS];

static long now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

void zkconf_connect_mode(int mode)
{
    __atomic_store_n(&g_mode, mode, __ATOMIC_SEQ_CST);
}

int zkconf_connect_count(void)
{
    return __atomic_load_n(&g_count, __ATOMIC_SEQ_CST);
}

long zkconf_connect_time(int index)
{
    if (index < 0 || index >= MAX_CALLS) {
        return -1;
    }
    return __atomic_load_n(&g_times[index], __ATOMIC_SEQ_CST);
}

/* Awaits completion of a nonblocking connect on `fd` and returns its synchronous outcome. */
static int finish_connect(int fd)
{
    struct pollfd p;
    int err = 0;
    socklen_t len = sizeof(err);
    int rc;
    p.fd = fd;
    p.events = POLLOUT;
    p.revents = 0;
    do {
        rc = poll(&p, 1, 5000);
    } while (rc < 0 && errno == EINTR);
    if (rc <= 0) {
        errno = EINPROGRESS;
        return -1;
    }
    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &len) != 0) {
        return -1;
    }
    if (err != 0) {
        errno = err;
        return -1;
    }
    return 0;
}

static int zkconf_connect(int fd, const struct sockaddr *addr, socklen_t len)
{
    int mode = __atomic_load_n(&g_mode, __ATOMIC_SEQ_CST);
    int nonblock = (fcntl(fd, F_GETFL) & O_NONBLOCK) != 0;
    int i = __atomic_fetch_add(&g_next, 1, __ATOMIC_SEQ_CST);
    int rc = ZKCONF_REAL(connect)(fd, addr, len);
    int saved = errno;
    if (i >= 0 && i < MAX_CALLS) {
        __atomic_store_n(&g_times[i], now_ms(), __ATOMIC_SEQ_CST);
    }
    __atomic_fetch_add(&g_count, 1, __ATOMIC_SEQ_CST);
    errno = saved;
    if (!nonblock) {
        return rc;
    }
    if (mode == ZKCONF_CONNECT_EINPROGRESS && rc == 0) {
        errno = EINPROGRESS;
        return -1;
    }
    if (mode == ZKCONF_CONNECT_IMMEDIATE && rc == -1 && errno == EINPROGRESS) {
        return finish_connect(fd);
    }
    return rc;
}

ZKCONF_INTERPOSE(connect);
