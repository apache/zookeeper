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
 * SASL password-file truncation detection and shared callback block isolation.
 * Mode: DEV.
 *
 * Boundary checks invoke the SASL_CB_PASS entry of a library-allocated callback
 * array directly, while concurrency checks connect multiple live handles
 * sharing a single callback array.
 *
 * Invariants and deviations:
 * - Transform boundary: the password transformation callback receives at most
 *   1023 bytes of file content and a 1023-byte output buffer; returning a
 *   1023-byte secret succeeds (SASL_OK), whereas a 1024-byte output length
 *   returns SASL_BUFOVER.
 * - File line boundary: without a transformation callback, a first line of
 *   1023 bytes succeeds on both libraries; a 1024-byte first line is rejected
 *   with SASL_BUFOVER on the Rust drop-in, whereas legacy C silently truncates
 *   at 1023 bytes via fgets() and returns SASL_OK.
 * - Shared callback block isolation: handles sharing a single callback block on
 *   the Rust drop-in store secrets per connection without mutating the shared
 *   block's embedded secret buffer in place, allowing four concurrent handles
 *   sharing one block to authenticate simultaneously while a fifth handle with
 *   an invalid password transitions to ZOO_AUTH_FAILED_STATE.
 * - Password file descriptor: the Rust drop-in opens the password file with
 *   O_CLOEXEC so concurrent fork and exec calls do not leak the descriptor to
 *   child processes, whereas legacy C opens it via fopen without close-on-exec.
 *   A named FIFO suspends the reader long enough to inspect the open descriptor
 *   flags before supplying the password payload and EOF.
 *
 * zkconf-flavors: mt
 */

#include "zkconf_sasl.h"

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stddef.h>
#include <sys/stat.h>
#include <unistd.h>

#ifdef HAVE_CYRUS_SASL_H

/* Legacy callback context layout (zk_sasl.c:449-482), preserved by the shim. */
struct harden_secret_ctx {
    const char *password_file;
    void *context;
    zoo_sasl_password_callback_t callback;
    sasl_secret_t *secret;
};

typedef int (*pass_fn)(sasl_conn_t *, void *, int, sasl_secret_t **);

static size_t g_content_len;
static size_t g_buf_len;
static size_t g_result_len;

/* Transformation hook writing g_result_len bytes of 'p'. */
static int transform(const char *content, size_t content_len, void *context,
                     char *buf, size_t buf_len, size_t *passwd_len)
{
    size_t n = g_result_len < buf_len ? g_result_len : buf_len;
    (void)content;
    (void)context;
    g_content_len = content_len;
    g_buf_len = buf_len;
    memset(buf, 'p', n);
    *passwd_len = g_result_len;
    return SASL_OK;
}

/* Invokes the SASL_CB_PASS entry of cb directly as Cyrus SASL does. */
static int call_pass(sasl_callback_t *cb, unsigned long *len)
{
    static long dummy_conn;
    sasl_secret_t *secret = NULL;
    pass_fn fn;
    int rc;
    memcpy(&fn, &cb[3].proc, sizeof fn);
    rc = fn((sasl_conn_t *)(void *)&dummy_conn, cb[3].context, SASL_CB_PASS,
            &secret);
    *len = secret != NULL ? secret->len : 0;
    return rc;
}

static unsigned long block_secret_len(sasl_callback_t *cb)
{
    return ((struct harden_secret_ctx *)cb[3].context)->secret->len;
}

/* Writes n bytes of character c followed by tail to path. */
static int write_fill(const char *path, char c, size_t n, const char *tail)
{
    char buf[2100];
    size_t t = strlen(tail);
    if (n + t > sizeof buf) {
        return -1;
    }
    memset(buf, c, n);
    memcpy(buf + n, tail, t);
    return zkcs_write_file(path, buf, n + t);
}

static void bound_transform(const char *path)
{
    zoo_sasl_password_t pw;
    sasl_callback_t *cb;
    unsigned long len = 0;
    int rc;

    if (write_fill(path, 'x', 2000, "") != 0) {
        zkc_fail("cannot write the password file");
        return;
    }
    pw.password_file = path;
    pw.context = NULL;
    pw.callback = transform;
    cb = zkcs_sasl()->password(ZKCS_SASL_USER, NULL, &pw);
    if (cb == NULL) {
        zkc_fail("zoo_sasl_make_password_callbacks -> NULL");
        return;
    }
    g_result_len = 1023;
    rc = call_pass(cb, &len);
    zkc_dev("DAT-13",
            rc == SASL_OK && len == 1023 && g_content_len == 1023 &&
                g_buf_len == 1023,
            "SASL-BOUND-1023: rc=%d len=%lu content_len=%zu buf_len=%zu", rc,
            len, g_content_len, g_buf_len);
    g_result_len = 1024;
    rc = call_pass(cb, &len);
    zkc_dev("DAT-13", rc == SASL_BUFOVER, "SASL-BOUND-1024: rc=%d", rc);
    free(cb);
}

static void bound_file(const char *path)
{
    sasl_callback_t *cb = zkcs_sasl()->basic(ZKCS_SASL_USER, NULL, path);
    unsigned long len = 0;
    int rc;

    if (cb == NULL) {
        zkc_fail("zoo_sasl_make_basic_callbacks -> NULL");
        return;
    }
    if (write_fill(path, 'y', 1023, "\n") != 0) {
        zkc_fail("cannot write the password file");
        free(cb);
        return;
    }
    rc = call_pass(cb, &len);
    zkc_dev("DAT-13", rc == SASL_OK && len == 1023,
            "SASL-FILE-1023-NL: rc=%d len=%lu", rc, len);
    if (write_fill(path, 'y', 1023, "") == 0) {
        rc = call_pass(cb, &len);
        zkc_dev("DAT-13", rc == SASL_OK && len == 1023,
                "SASL-FILE-1023: rc=%d len=%lu", rc, len);
    }
    if (write_fill(path, 'y', 1024, "\n") == 0) {
        rc = call_pass(cb, &len);
        if (zkc_is_rust()) {
            zkc_dev("DAT-13", rc == SASL_BUFOVER, "SASL-FILE-1024: rust rc=%d",
                    rc);
        } else {
            zkc_dev("DAT-13", rc == SASL_OK && len == 1023,
                    "SASL-FILE-1024: legacy truncates: rc=%d len=%lu", rc, len);
        }
    }
    free(cb);
}

struct pass_call {
    sasl_callback_t *cb;
    unsigned long len;
    int rc;
};

static void *pass_thread(void *arg)
{
    struct pass_call *pc = arg;
    pc->rc = call_pass(pc->cb, &pc->len);
    return NULL;
}

/* Locates the open read descriptor matching the device and inode of fifo. */
static int find_reader(const struct stat *fifo)
{
    struct stat st;
    int max = getdtablesize();
    int fd;
    if (max > 4096) {
        max = 4096;
    }
    for (fd = 0; fd < max; fd++) {
        if (fstat(fd, &st) == 0 && st.st_dev == fifo->st_dev &&
            st.st_ino == fifo->st_ino &&
            (fcntl(fd, F_GETFL) & O_ACCMODE) == O_RDONLY) {
            return fd;
        }
    }
    return -1;
}

static void pw_file_cloexec(const char *fifo_path)
{
    struct pass_call pc;
    struct stat fst;
    pthread_t t;
    int found = 0;
    int cloexec = 0;
    int wfd = -1;
    int fd;
    int i;

    (void)remove(fifo_path);
    if (mkfifo(fifo_path, 0600) != 0 || stat(fifo_path, &fst) != 0) {
        zkc_fail("cannot make the password FIFO");
        return;
    }
    pc.cb = zkcs_sasl()->basic(ZKCS_SASL_USER, NULL, fifo_path);
    pc.len = 0;
    pc.rc = -1;
    if (pc.cb == NULL) {
        zkc_fail("zoo_sasl_make_basic_callbacks -> NULL");
        return;
    }
    if (pthread_create(&t, NULL, pass_thread, &pc) != 0) {
        zkc_fail("pthread_create failed");
        free(pc.cb);
        return;
    }
    /* Poll nonblocking write-side open (returns ENXIO until the reader opens the FIFO). */
    for (i = 0; i < 500 && wfd < 0; i++) {
        wfd = open(fifo_path, O_WRONLY | O_NONBLOCK);
        if (wfd < 0 && errno == ENXIO) {
            zkc_sleep_ms(10);
        } else if (wfd < 0) {
            break;
        }
    }
    for (i = 0; wfd >= 0 && i < 500 && !found; i++) {
        fd = find_reader(&fst);
        if (fd >= 0) {
            found = 1;
            cloexec = (fcntl(fd, F_GETFD) & FD_CLOEXEC) != 0;
        } else {
            zkc_sleep_ms(10);
        }
    }
    if (wfd >= 0) {
        if (write(wfd, ZKCS_SASL_PASSWORD "\n", sizeof ZKCS_SASL_PASSWORD) < 0) {
            zkc_fail("cannot write the password FIFO");
        }
        close(wfd);
        pthread_join(t, NULL);
    } else {
        /* Unblock a reader still waiting in open() by opening the write side briefly. */
        wfd = open(fifo_path, O_WRONLY);
        if (wfd >= 0) {
            close(wfd);
        }
        pthread_join(t, NULL);
    }
    zkc_dev("DAT-13", pc.rc == SASL_OK && pc.len == sizeof ZKCS_SASL_PASSWORD - 1,
            "SASL-FILE-FIFO: rc=%d len=%lu", pc.rc, pc.len);
    if (zkc_is_rust()) {
        zkc_dev("DV-FIX-12", found && cloexec,
                "rust password fd: found=%d cloexec=%d", found, cloexec);
    } else {
        zkc_dev("DV-FIX-12", found && !cloexec,
                "legacy password fd is inheritable: found=%d cloexec=%d", found,
                cloexec);
    }
    free(pc.cb);
    (void)remove(fifo_path);
}

#define SHARED 4

static void shared_block(const char *path, const char *bad_path)
{
    static const char *ctx[SHARED] = {"s0", "s1", "s2", "s3"};
    zhandle_t *zh[SHARED];
    zhandle_t *bad;
    sasl_callback_t *cb;
    sasl_callback_t *bad_cb;
    unsigned long in_block;
    char key[64];
    int connected = 0;
    int i;

    if (zkcs_write_file(path, ZKCS_SASL_PASSWORD "\n",
                        sizeof ZKCS_SASL_PASSWORD) != 0 ||
        zkcs_write_file(bad_path, "wrong\n", 6) != 0) {
        zkc_fail("cannot write the password files");
        return;
    }
    cb = zkcs_sasl()->basic(ZKCS_SASL_USER, NULL, path);
    bad_cb = zkcs_sasl()->basic(ZKCS_SASL_USER, NULL, bad_path);
    if (cb == NULL || bad_cb == NULL) {
        zkc_fail("zoo_sasl_make_basic_callbacks -> NULL");
        free(cb);
        free(bad_cb);
        return;
    }
    /* Launch all five handles concurrently so their SASL negotiations overlap. */
    for (i = 0; i < SHARED; i++) {
        zh[i] = zkcs_sasl_init(zkc_host(0), ctx[i], cb, zkc_watcher, NULL,
                               10000);
    }
    bad = zkcs_sasl_init(zkc_host(0), "bad", bad_cb, zkc_watcher, NULL, 10000);
    for (i = 0; i < SHARED; i++) {
        snprintf(key, sizeof key, "session:CONNECTED@%s", ctx[i]);
        if (zh[i] != NULL && zkc_await(key, 1, 20000) == 0) {
            connected++;
        }
    }
    /* Poll zoo_state(bad) directly because legacy C omits the AUTH_FAILED event. */
    for (i = 0;
         bad != NULL && i < 2000 && zoo_state(bad) != ZOO_AUTH_FAILED_STATE;
         i++) {
        zkc_sleep_ms(10);
    }
    zkc_dev("DAT-13",
            connected == SHARED && bad != NULL &&
                zoo_state(bad) == ZOO_AUTH_FAILED_STATE,
            "SASL-SHARED: %d of %d handles on one block connected, "
            "wrong password state=%s",
            connected, SHARED,
            bad != NULL ? zkc_state(zoo_state(bad)) : "NULL");
    in_block = block_secret_len(cb);
    if (zkc_is_rust()) {
        zkc_dev("DAT-13", in_block == 0,
                "SASL-ISOLATION: rust in-block secret len=%lu", in_block);
    } else {
        zkc_dev("DAT-13", in_block == sizeof ZKCS_SASL_PASSWORD - 1,
                "SASL-ISOLATION: legacy writes the block: len=%lu", in_block);
    }
    for (i = 0; i < SHARED; i++) {
        if (zh[i] != NULL) {
            zkc_close(zh[i]);
        }
    }
    if (bad != NULL) {
        zkc_close(bad);
    }
    free(cb);
    free(bad_cb);
}

static void run(void)
{
    char path[1024];
    char bad_path[1024];
    char fifo_path[1024];
    const char *prefix = getenv("ZKCONF_OUT");
    const char *base = prefix != NULL ? prefix : "/tmp/zkconf";

    snprintf(path, sizeof path, "%s.pw", base);
    snprintf(bad_path, sizeof bad_path, "%s.bad.pw", base);
    snprintf(fifo_path, sizeof fifo_path, "%s.pw.fifo", base);
    bound_transform(path);
    bound_file(path);
    pw_file_cloexec(fifo_path);
    shared_block(path, bad_path);
    (void)remove(path);
    (void)remove(bad_path);
}
#endif

int main(int argc, char **argv)
{
    zkc_init(argc, argv, "sasl_harden", 1, "DEV", "ignore");
#ifdef HAVE_CYRUS_SASL_H
    if (zkcs_sasl_resolve() == 0) {
        run();
        return zkc_finish();
    }
#endif
    zkc_ev("check", "skip: no SASL library or no SASL server");
    return zkc_finish();
}
