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
 * Verifies failure handling for the per-session SASL callback list copy
 * (drop-in only).
 *
 * The C shim copies the application callback list at the start of each SASL
 * handshake. If the copy fails, the SASL attempt fails in the same way as a
 * failed `sasl_client_new` call in legacy `zoo_sasl_connect`: the shim logs
 * `"allocating SASL connection state: %s"` and transitions the handle to
 * `ZOO_AUTH_FAILED_STATE` without aborting the process.
 *
 * This translation unit compiles in two modes:
 *
 * 1. Interposer library (default): interposes `malloc`. After
 *    `zk_cb_fail_arm(n)`, the next `malloc` of exactly `n` bytes returns
 *    `NULL`, and `zk_cb_fail_hits()` returns the count of such failures. On
 *    Mach-O it uses a dyld interpose tuple; on ELF it is loaded via
 *    `LD_PRELOAD` and calls `__libc_malloc`.
 *
 * 2. Test application (when `ZK_SASL_CB_APP` is defined): forks a child process
 *    to serve a single connect reply, avoiding the need for a ZooKeeper server,
 *    and starts a SASL client handle.
 *
 * Usage: sasl_cb_fail oom|long
 *   oom   supplies a list of 1000 callbacks whose copy allocation fails in
 *         `malloc`; run with the interposer library preloaded.
 *   long  supplies a list of 4096 callbacks, matching the shim limit so the
 *         shim logs the `SASL_BADPARAM` error text.
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#ifndef _DARWIN_C_SOURCE
#define _DARWIN_C_SOURCE
#endif

#include <stddef.h>

#ifndef ZK_SASL_CB_APP

/* ---- interposer library ---- */

#include <stdlib.h>

static volatile size_t zkcf_size;
static volatile int zkcf_hits;

void zk_cb_fail_arm(size_t size);
int zk_cb_fail_hits(void);

void zk_cb_fail_arm(size_t size)
{
    zkcf_size = size;
}

int zk_cb_fail_hits(void)
{
    return zkcf_hits;
}

/* Returns 1, records a hit, and disarms if `size` equals the armed size; returns 0 otherwise. */
static int zkcf_fail(size_t size)
{
    if (zkcf_size != 0 && size == zkcf_size) {
        zkcf_size = 0;
        zkcf_hits++;
        return 1;
    }
    return 0;
}

#ifdef __APPLE__
static void *zkcf_malloc(size_t size)
{
    return zkcf_fail(size) ? NULL : malloc(size);
}

__attribute__((used)) static const struct {
    const void *replacement;
    const void *replacee;
} zkcf_interpose[] __attribute__((section("__DATA,__interpose"))) = {
    {(const void *)zkcf_malloc, (const void *)malloc}};
#else
void *__libc_malloc(size_t size);
void *malloc(size_t size);

void *malloc(size_t size)
{
    return zkcf_fail(size) ? NULL : __libc_malloc(size);
}
#endif

#else

/* ---- test application ---- */

#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <netinet/in.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include <zookeeper.h>

#if defined(__APPLE__) && defined(__clang__)
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
#endif

/*
 * Expected log lines built from the Cyrus SASL 2.1 `sasl_errstring` texts for
 * `SASL_NOMEM` and `SASL_BADPARAM`.
 */
#define ZKCF_NOMEM "allocating SASL connection state: no memory available"
#define ZKCF_BADPARAM "allocating SASL connection state: invalid parameter supplied"

static const char *zkcf_want;
static int zkcf_seen;

static void zkcf_log(const char *line)
{
    if (strstr(line, "SASL") != NULL) {
        printf("log: %s\n", line);
    }
    if (strstr(line, zkcf_want) != NULL) {
        zkcf_seen++;
    }
}

static void zkcf_watcher(zhandle_t *zh, int type, int state, const char *path, void *ctx)
{
    (void)zh;
    (void)type;
    (void)state;
    (void)path;
    (void)ctx;
}

static void zkcf_sleep_ms(int ms)
{
    struct timespec ts;

    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (long)(ms % 1000) * 1000000L;
    nanosleep(&ts, NULL);
}

static int zkcf_read_full(int fd, unsigned char *p, size_t n)
{
    while (n > 0) {
        ssize_t r = read(fd, p, n);
        if (r <= 0) {
            return -1;
        }
        p += r;
        n -= (size_t)r;
    }
    return 0;
}

/*
 * Accepts a single connection, reads the `ConnectRequest`, writes a
 * `ConnectResponse` with a 10 s session timeout, session ID `0x1234`, and a
 * zero password, and then reads until EOF.
 */
static void zkcf_serve(int lfd)
{
    static const unsigned char reply[] = {
        0, 0, 0, 37,               /* frame length */
        0, 0, 0, 0,                /* protocolVersion */
        0, 0, 0x27, 0x10,          /* timeOut 10000 */
        0, 0, 0, 0, 0, 0, 0x12, 0x34, /* sessionId */
        0, 0, 0, 16,               /* passwd length */
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0,                         /* readOnly */
    };
    unsigned char hdr[4];
    unsigned char body[256];
    size_t len;
    int fd;

    alarm(30);
    fd = accept(lfd, NULL, NULL);
    if (fd < 0 || zkcf_read_full(fd, hdr, sizeof hdr) != 0) {
        _exit(3);
    }
    len = ((size_t)hdr[0] << 24) | ((size_t)hdr[1] << 16) | ((size_t)hdr[2] << 8) | hdr[3];
    if (len > sizeof body || zkcf_read_full(fd, body, len) != 0) {
        _exit(4);
    }
    if (write(fd, reply, sizeof reply) != (ssize_t)sizeof reply) {
        _exit(5);
    }
    while (read(fd, body, sizeof body) > 0) {
    }
    _exit(0);
}

#ifdef THREADED
static void zkcf_drive(zhandle_t *zh)
{
    (void)zh;
    zkcf_sleep_ms(20);
}
#else
static void zkcf_drive(zhandle_t *zh)
{
    struct timeval tv = {0, 0};
    fd_set rd;
    fd_set wr;
    int fd = -1;
    int interest = 0;
    int events = 0;

    if (zookeeper_interest(zh, &fd, &interest, &tv) != ZOK || fd < 0 || fd >= FD_SETSIZE) {
        zkcf_sleep_ms(20);
        return;
    }
    tv.tv_sec = 0;
    tv.tv_usec = 20000;
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

int main(int argc, char **argv)
{
    struct sockaddr_in sa;
    socklen_t salen = sizeof sa;
    zoo_sasl_params_t params;
    sasl_callback_t *cbs;
    void (*arm)(size_t) = NULL;
    int (*hits)(void) = NULL;
    char host[64];
    zhandle_t *zh;
    size_t n;
    size_t i;
    pid_t pid;
    int lfd;
    int waited;
    int state;
    int ok = 1;
    int oom;

    setvbuf(stdout, NULL, _IOLBF, 0);
    if (argc < 2 || (strcmp(argv[1], "oom") != 0 && strcmp(argv[1], "long") != 0)) {
        fprintf(stderr, "usage: sasl_cb_fail oom|long\n");
        return 2;
    }
    oom = strcmp(argv[1], "oom") == 0;
    n = oom ? 1000 : 4096;
    zkcf_want = oom ? ZKCF_NOMEM : ZKCF_BADPARAM;
    if (oom) {
        *(void **)&arm = dlsym(RTLD_DEFAULT, "zk_cb_fail_arm");
        *(void **)&hits = dlsym(RTLD_DEFAULT, "zk_cb_fail_hits");
        if (arm == NULL || hits == NULL) {
            printf("FAIL the malloc interposer is not loaded\n");
            return 1;
        }
    }
    cbs = calloc(n + 1, sizeof *cbs);
    if (cbs == NULL) {
        return 1;
    }
    for (i = 0; i < n; i++) {
        cbs[i].id = SASL_CB_USER;
    }
    cbs[n].id = SASL_CB_LIST_END;

    memset(&sa, 0, sizeof sa);
    sa.sin_family = AF_INET;
    sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    lfd = socket(AF_INET, SOCK_STREAM, 0);
    if (lfd < 0 || bind(lfd, (struct sockaddr *)&sa, sizeof sa) != 0 || listen(lfd, 1) != 0 ||
        getsockname(lfd, (struct sockaddr *)&sa, &salen) != 0) {
        printf("FAIL listen: %s\n", strerror(errno));
        return 1;
    }
    snprintf(host, sizeof host, "127.0.0.1:%d", ntohs(sa.sin_port));
    /*
     * Fork the server child before `zookeeper_init_sasl`, so the child has no
     * copies of the library threads of an mt handle.
     */
    pid = fork();
    if (pid < 0) {
        return 1;
    }
    if (pid == 0) {
        zkcf_serve(lfd);
    }
    close(lfd);

    params.service = "zookeeper";
    params.host = "zk-sasl-md5";
    params.mechlist = "DIGEST-MD5";
    params.callbacks = cbs;
    if (oom) {
        /* The armed size is the copy size, holding `n` entries plus `SASL_CB_LIST_END`. */
        arm((n + 1) * sizeof *cbs);
    }
    zh = zookeeper_init_sasl(host, zkcf_watcher, 10000, NULL, NULL, 0, zkcf_log, &params);
    if (zh == NULL) {
        printf("FAIL zookeeper_init_sasl: %s\n", strerror(errno));
        ok = 0;
    } else {
        for (waited = 0; waited < 10000 && zoo_state(zh) != ZOO_AUTH_FAILED_STATE; waited += 20) {
            zkcf_drive(zh);
        }
        state = zoo_state(zh);
        printf("state=%d\n", state);
        if (state != ZOO_AUTH_FAILED_STATE) {
            printf("FAIL the handle is not in ZOO_AUTH_FAILED_STATE\n");
            ok = 0;
        }
        zookeeper_close(zh);
    }
    printf("log \"%s\": %d\n", zkcf_want, zkcf_seen);
    if (zkcf_seen != 1) {
        printf("FAIL the log line is not seen once\n");
        ok = 0;
    }
    if (oom) {
        printf("failed mallocs: %d\n", hits());
        if (hits() != 1) {
            printf("FAIL the copy of the callback list did not fail\n");
            ok = 0;
        }
    }
    kill(pid, SIGTERM);
    waitpid(pid, NULL, 0);
    free(cbs);
    return ok ? 0 : 1;
}

#endif
