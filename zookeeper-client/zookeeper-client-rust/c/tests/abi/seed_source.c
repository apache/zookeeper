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
 * Verification of the kernel entropy source used for PRNG reseeding across host resolutions
 * (Linux only).
 *
 * Usage: seed_source none|enosys|eagain dropin|legacy HOST
 * Requires an active ZooKeeper server at HOST. All waits are bounded.
 *
 * Interposes syscall(), open(), open64(), openat(), openat64(), srandom(), and srand48() via
 * --export-dynamic symbol preemption to count entropy syscalls and PRNG reseeds performed by the
 * client library before delegating to the underlying libc implementations.
 *
 * Connects with the default 0 ms resolution delay and executes 20 resolution triggers, via
 * zookeeper_interest in single threaded mode or synchronous zoo_exists in multithreaded mode.
 * Verifies that random() diverges from a deterministic srandom(42) baseline after each trigger.
 *
 * Expected syscall counts across R reseeds:
 * - none, dropin: 0 /dev/urandom opens and R SYS_getrandom calls.
 * - none, legacy: R /dev/urandom opens and 0 SYS_getrandom calls.
 * - enosys, dropin: SYS_getrandom fails with ENOSYS on the first attempt, latching fallback to
 *   /dev/urandom for all R reseeds with only 1 SYS_getrandom invocation in total.
 * - eagain, dropin: SYS_getrandom fails transiently with EAGAIN on every reseed, falling back to
 *   /dev/urandom R times while restoring the caller's prior errno across the failed syscall.
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <stdio.h>

#ifdef __linux__

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#include <zookeeper.h>

#define TRIGGERS 20

typedef long (*syscall_fn)(long, ...);
typedef int (*open_fn)(const char *, int, ...);
typedef int (*openat_fn)(int, const char *, int, ...);
typedef void (*srandom_fn)(unsigned);
typedef void (*srand48_fn)(long);

/* Synthetic errno value injected into SYS_getrandom calls, or 0 when fault injection is off. */
static int g_inject;
static int g_opens;
static int g_getrandom;
static int g_srandom;
static int g_srand48;
static int g_errno_bad;
/* Thread-local flag armed by an injected getrandom failure to verify errno restoration. */
static __thread int t_pending;
static __thread int t_errno_before;

static int failures;

#define CHECK(cond)                                                       \
    do {                                                                  \
        if (!(cond)) {                                                    \
            printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);        \
            failures++;                                                   \
        }                                                                 \
    } while (0)

static void inc(int *counter)
{
    __atomic_add_fetch(counter, 1, __ATOMIC_RELAXED);
}

static int load(int *counter)
{
    return __atomic_load_n(counter, __ATOMIC_RELAXED);
}

/* Resolves the underlying libc symbol via RTLD_NEXT without disturbing errno. */
static void *next_fn(const char *name)
{
    int saved = errno;
    void *fn = dlsym(RTLD_NEXT, name);

    errno = saved;
    if (fn == NULL) {
        fprintf(stderr, "seed_source: no libc %s\n", name);
        abort();
    }
    return fn;
}

/* Verifies that thread-local errno was restored to its earlier value after getrandom fails. */
static void check_errno_after_failure(void)
{
    if (t_pending && errno != t_errno_before) {
        inc(&g_errno_bad);
    }
}

static void note_open(const char *path)
{
    if (path != NULL && strcmp(path, "/dev/urandom") == 0) {
        inc(&g_opens);
        check_errno_after_failure();
    }
}

static int has_mode(int flags)
{
#ifdef O_TMPFILE
    if ((flags & O_TMPFILE) == O_TMPFILE) {
        return 1;
    }
#endif
    return (flags & O_CREAT) != 0;
}

long syscall(long number, ...)
{
    static syscall_fn real;
    va_list ap;
    long a[6];
    int i;

    va_start(ap, number);
    for (i = 0; i < 6; i++) {
        a[i] = va_arg(ap, long);
    }
    va_end(ap);
    /* Count only 4-byte PRNG seed requests, ignoring larger buffers requested by other callers. */
    if (number == SYS_getrandom && (unsigned long)a[1] <= sizeof(int)) {
        inc(&g_getrandom);
        if (g_inject != 0) {
            t_errno_before = errno;
            t_pending = 1;
            errno = g_inject;
            return -1;
        }
    }
    if (real == NULL) {
        real = (syscall_fn)next_fn("syscall");
    }
    return real(number, a[0], a[1], a[2], a[3], a[4], a[5]);
}

int open(const char *path, int flags, ...)
{
    static open_fn real;
    unsigned mode = 0;

    if (has_mode(flags)) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, unsigned);
        va_end(ap);
    }
    note_open(path);
    if (real == NULL) {
        real = (open_fn)next_fn("open");
    }
    return real(path, flags, mode);
}

int open64(const char *path, int flags, ...)
{
    static open_fn real;
    unsigned mode = 0;

    if (has_mode(flags)) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, unsigned);
        va_end(ap);
    }
    note_open(path);
    if (real == NULL) {
        real = (open_fn)next_fn("open64");
    }
    return real(path, flags, mode);
}

int openat(int dirfd, const char *path, int flags, ...)
{
    static openat_fn real;
    unsigned mode = 0;

    if (has_mode(flags)) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, unsigned);
        va_end(ap);
    }
    note_open(path);
    if (real == NULL) {
        real = (openat_fn)next_fn("openat");
    }
    return real(dirfd, path, flags, mode);
}

int openat64(int dirfd, const char *path, int flags, ...)
{
    static openat_fn real;
    unsigned mode = 0;

    if (has_mode(flags)) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, unsigned);
        va_end(ap);
    }
    note_open(path);
    if (real == NULL) {
        real = (openat_fn)next_fn("openat64");
    }
    return real(dirfd, path, flags, mode);
}

static srandom_fn real_srandom(void)
{
    static srandom_fn real;

    if (real == NULL) {
        real = (srandom_fn)next_fn("srandom");
    }
    return real;
}

void srandom(unsigned seed)
{
    inc(&g_srandom);
    check_errno_after_failure();
    t_pending = 0;
    real_srandom()(seed);
}

void srand48(long seed)
{
    static srand48_fn real;

    inc(&g_srand48);
    if (real == NULL) {
        real = (srand48_fn)next_fn("srand48");
    }
    real(seed);
}

static void sleep_ms(long ms)
{
    struct timespec ts;

    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (ms % 1000) * 1000000L;
    nanosleep(&ts, NULL);
}

static void watcher(zhandle_t *zh, int type, int state, const char *path, void *ctx)
{
    (void)zh;
    (void)type;
    (void)state;
    (void)path;
    (void)ctx;
}

#ifndef THREADED
/* Executes a single interest/select/process event loop iteration. */
static void drive(zhandle_t *zh)
{
    int fd = -1;
    int interest = 0;
    struct timeval tv;
    fd_set rfds;
    fd_set wfds;
    int events = 0;

    if (zookeeper_interest(zh, &fd, &interest, &tv) != ZOK || fd < 0) {
        sleep_ms(10);
        return;
    }
    FD_ZERO(&rfds);
    FD_ZERO(&wfds);
    if (interest & ZOOKEEPER_READ) {
        FD_SET(fd, &rfds);
    }
    if (interest & ZOOKEEPER_WRITE) {
        FD_SET(fd, &wfds);
    }
    if (tv.tv_sec > 0 || tv.tv_usec > 20000) {
        tv.tv_sec = 0;
        tv.tv_usec = 20000;
    }
    if (select(fd + 1, &rfds, &wfds, NULL, &tv) > 0) {
        if (FD_ISSET(fd, &rfds)) {
            events |= ZOOKEEPER_READ;
        }
        if (FD_ISSET(fd, &wfds)) {
            events |= ZOOKEEPER_WRITE;
        }
    }
    zookeeper_process(zh, events);
}
#endif

/* Triggers a host list resolution pass on an established connection. */
static void trigger(zhandle_t *zh, int check_errno)
{
#ifdef THREADED
    struct Stat st;

    (void)check_errno;
    CHECK(zoo_exists(zh, "/", 0, &st) == ZOK);
#else
    int fd = -1;
    int interest = 0;
    struct timeval tv;
    int rc;
    int err;

    errno = EDOM;
    rc = zookeeper_interest(zh, &fd, &interest, &tv);
    err = errno;
    CHECK(rc == ZOK);
    if (check_errno) {
        CHECK(err == EDOM);
    }
#endif
}

int main(int argc, char **argv)
{
    const char *mode;
    int dropin;
    zhandle_t *zh;
    long want;
    int moved = 0;
    int i;
    int r;
    int opens;
    int calls;

    if (argc != 4) {
        fprintf(stderr, "usage: seed_source none|enosys|eagain dropin|legacy HOST\n");
        return 2;
    }
    mode = argv[1];
    dropin = strcmp(argv[2], "dropin") == 0;
    if (strcmp(mode, "enosys") == 0) {
        g_inject = ENOSYS;
    } else if (strcmp(mode, "eagain") == 0) {
        g_inject = EAGAIN;
    } else if (strcmp(mode, "none") != 0) {
        fprintf(stderr, "seed_source: unknown mode %s\n", mode);
        return 2;
    }
    if (g_inject != 0 && !dropin) {
        fprintf(stderr, "seed_source: injection checks the drop-in only\n");
        return 2;
    }
    zoo_set_debug_level(ZOO_LOG_LEVEL_WARN);
    real_srandom()(42);
    want = random();
    zh = zookeeper_init(argv[3], watcher, 10000, 0, 0, 0);
    if (zh == NULL) {
        printf("FAIL zookeeper_init: errno %d\n", errno);
        return 1;
    }
    for (i = 0; i < 1000 && zoo_state(zh) != ZOO_CONNECTED_STATE; i++) {
#ifdef THREADED
        sleep_ms(10);
#else
        drive(zh);
#endif
    }
    if (zoo_state(zh) != ZOO_CONNECTED_STATE) {
        printf("FAIL no connection to %s\n", argv[3]);
        zookeeper_close(zh);
        return 1;
    }
    for (i = 0; i < TRIGGERS; i++) {
        real_srandom()(42);
        trigger(zh, g_inject == EAGAIN);
        if (random() != want) {
            moved++;
        }
    }
    zookeeper_close(zh);
    r = load(&g_srandom);
    opens = load(&g_opens);
    calls = load(&g_getrandom);
    printf("seed_source %s %s: reseeds=%d srand48=%d opens=%d getrandom=%d moved=%d/%d "
           "errno_bad=%d\n",
           mode, argv[2], r, load(&g_srand48), opens, calls, moved, TRIGGERS,
           load(&g_errno_bad));
    CHECK(moved == TRIGGERS);
    CHECK(r >= TRIGGERS);
    CHECK(load(&g_srand48) == r);
    if (!dropin) {
        CHECK(opens == r);
        CHECK(calls == 0);
    } else if (g_inject == 0) {
        CHECK(opens == 0);
        CHECK(calls == r);
    } else if (g_inject == ENOSYS) {
        CHECK(opens == r);
        CHECK(calls == 1);
        CHECK(load(&g_errno_bad) == 0);
    } else {
        CHECK(opens == r);
        CHECK(calls == r);
        CHECK(load(&g_errno_bad) == 0);
    }
    return failures == 0 ? 0 : 1;
}

#else

int main(void)
{
    printf("seed_source: Linux only\n");
    return 0;
}

#endif
