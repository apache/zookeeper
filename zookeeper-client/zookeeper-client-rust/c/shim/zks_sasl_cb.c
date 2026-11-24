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
 * SASL Authentication Callbacks and Secret Management.
 *
 * Implements the legacy Cyrus SASL callback structure and credential resolution
 * routines originally provided in zk_sasl.c. The callback block is allocated as a
 * contiguous memory region containing the callback dispatch table, credential context,
 * preallocated secret buffer, and trailing dynamic string storage, allowing caller
 * applications to deallocate the entire structure via a single call to free().
 *
 * Password retrieval supports interactive terminal prompts, plaintext credential files,
 * and user supplied transformation callbacks. To maintain strict thread safety and avoid
 * cross-session interference, ZooKeeper sessions process secrets via an unlocked sink
 * trampoline rather than modifying the shared callback descriptor directly.
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE /* POSIX names under -std=c99 on glibc */
#endif
#define ZKS_SASL_GLUE 1
#include "zks_internal.h"
#include "zks_sasl.h"
#include "zks_sasl_cb.h"

#include <fcntl.h>
#include <stdio.h>
#include <unistd.h>

struct zsasl_secret_ctx {
    const char *password_file;
    void *context;
    zoo_sasl_password_callback_t callback;
    sasl_secret_t *secret;
};

/* Contiguous memory layout holding callback pointers and associated string payloads. */
struct zsasl_callbacks_block {
    sasl_callback_t callbacks[5];
    struct zsasl_secret_ctx secret_ctx;
    union {
        sasl_secret_t secret;
        char space[sizeof(sasl_secret_t) + ZSASL_MAX_PASSWORD_LEN];
    } secret;
    char strings[1];
};

/*
 * Process-wide mutex serializing concurrent writes by _zsasl_getsecret into a
 * shared callback block's embedded secret buffer.
 */
static pthread_mutex_t zks_sasl_pass_mutex = PTHREAD_MUTEX_INITIALIZER;

static void zks_sasl_pass_lock(void)
{
    (void)pthread_mutex_lock(&zks_sasl_pass_mutex);
}

static void zks_sasl_pass_unlock(void)
{
    (void)pthread_mutex_unlock(&zks_sasl_pass_mutex);
}

/*
 * Securely sanitizes credential memory buffers upon function exit or stack
 * unwinding to prevent secret leakage in memory dumps.
 */
static void zks_zero_pw(char (*b)[ZSASL_MAX_PASSWORD_LEN + 1])
{
    zks_secure_zero(*b, sizeof *b);
}

/*
 * Reads a password file using direct unbuffered descriptors configured with
 * O_CLOEXEC, avoiding residual secret copies in stdio heap buffers and
 * preventing descriptor leakage across concurrent fork/exec calls. When
 * probe_more is non-zero and the buffer fills to capacity, *more indicates
 * whether a non-newline byte follows in the file.
 */
static int zks_read_pw_file(const char *path, char *buf, size_t cap, size_t *out_len,
                            int probe_more, int *more)
{
    size_t n = 0;
    ssize_t rc;
    char c = 0;
    int fd = open(path, O_RDONLY | O_CLOEXEC);

    *more = 0;
    if (fd < 0) {
        return -1;
    }
    while (n < cap) {
        rc = read(fd, buf + n, cap - n);
        if (rc > 0) {
            n += (size_t)rc;
        } else if (rc == 0) {
            break;
        } else if (errno != EINTR) {
            (void)close(fd);
            return -1;
        }
    }
    if (probe_more && n == cap) {
        do {
            rc = read(fd, &c, 1);
        } while (rc < 0 && errno == EINTR);
        /* Treat read errors on the trailing probe byte as EOF, matching fgetc semantics. */
        *more = rc == 1 && c != '\n';
        zks_secure_zero(&c, 1);
    }
    (void)close(fd);
    *out_len = n;
    return 0;
}

/*
 * Resolves the authentication secret from available credential sources (file, callback,
 * or interactive prompt). Application callbacks are invoked without holding internal locks.
 */
static int zks_getsecret_compute(struct zsasl_secret_ctx *c, char pw[ZSASL_MAX_PASSWORD_LEN + 1],
                                 size_t *out_len)
{
    char buf[ZSASL_MAX_PASSWORD_LEN + 1] ZKS_CLEANUP(zks_zero_pw);
    char np[ZSASL_MAX_PASSWORD_LEN + 1] ZKS_CLEANUP(zks_zero_pw);
    char *password = NULL;
    char *nl;
    size_t len = 0;
    int res = SASL_OK;
    int more = 0;

    *out_len = 0;
    if (c->password_file != NULL) {
        /* Read binary content from password file for subsequent callback processing. */
        if (zks_read_pw_file(c->password_file, buf, ZSASL_MAX_PASSWORD_LEN, &len,
                             c->callback == NULL, &more)
            != 0) {
            res = SASL_FAIL;
            goto out;
        }
        /*
         * Detect whether the credential file exceeds allowable length limits when no
         * custom transform callback is installed to handle pagination or truncation.
         */
        if (c->callback == NULL && len == ZSASL_MAX_PASSWORD_LEN &&
            memchr(buf, '\n', len) == NULL && more) {
            res = SASL_BUFOVER;
            goto out;
        }
        buf[len] = '\0';
        password = buf;
    }
    if (c->callback != NULL) {
        /* Invoke application provided secret transformation callback. */
        if (password == NULL) {
            res = SASL_BADPARAM;
            goto out;
        }
        res = c->callback(password, len, c->context, np, ZSASL_MAX_PASSWORD_LEN, &len);
        if (res != SASL_OK) {
            goto out;
        }
        if (len > ZSASL_MAX_PASSWORD_LEN) {
            res = SASL_BUFOVER;
            goto out;
        }
        np[len] = '\0';
        password = np;
    } else if (password != NULL) {
        /* In the absence of a custom callback, extract only the first newline terminated line. */
        nl = (char *)memchr(password, '\n', len);
        if (nl != NULL) {
            *nl = '\0';
        }
    } else {
        /*
         * Prompt for credentials on the controlling terminal if no file was specified.
         * The prompt blocks the thread that drives the handle and uses process-global
         * state, so only one handle can prompt at a time.
         */
#ifdef HAVE_GETPASSPHRASE
        password = getpassphrase("Password: ");
#else
        password = getpass("Password: ");
#endif
        if (password == NULL) {
            res = SASL_FAIL;
            goto out;
        }
    }
    /* Ensure password is NULL-terminated regardless of source. */
    len = strnlen(password, (size_t)ZSASL_MAX_PASSWORD_LEN + 1u);
    if (len > ZSASL_MAX_PASSWORD_LEN) {
        zks_secure_zero(password, strnlen(password, ZKS_SASL_LEN_CAP));
        res = SASL_BUFOVER;
        goto out;
    }
    memcpy(pw, password, len);
    pw[len] = '\0';
    if (password != buf && password != np) {
        zks_secure_zero(password, len); /* Clear interactive prompt static buffer. */
    }
    *out_len = len;
out:
    zks_zero_pw(&buf);
    zks_zero_pw(&np);
    return res;
}

/*
 * Default SASL_CB_PASS callback installed in zoo_sasl_make_password_callbacks
 * blocks for direct external callers. Populates the embedded secret buffer
 * inside the callback block under zks_sasl_pass_mutex. Because Cyrus SASL reads
 * the returned buffer after unlock, concurrent external callers sharing a single
 * callback block must supply identical credentials.
 */
static int _zsasl_getsecret(sasl_conn_t *conn, void *context, int id, sasl_secret_t **psecret)
{
    struct zsasl_secret_ctx *c = (struct zsasl_secret_ctx *)context;
    char pw[ZSASL_MAX_PASSWORD_LEN + 1] ZKS_CLEANUP(zks_zero_pw);
    size_t len = 0;
    int res;
    sasl_secret_t *x;

    if (conn == NULL || psecret == NULL || id != SASL_CB_PASS) {
        return SASL_BADPARAM;
    }
    res = zks_getsecret_compute(c, pw, &len);
    if (res != SASL_OK) {
        goto out;
    }
    x = c->secret;
    zks_sasl_pass_lock();
    memcpy((unsigned char *)x + offsetof(sasl_secret_t, data), pw, len + 1);
    memset((unsigned char *)x + offsetof(sasl_secret_t, data) + len + 1, 0,
           ZSASL_MAX_PASSWORD_LEN - len);
    x->len = (unsigned long)len;
    zks_sasl_pass_unlock();
    *psecret = x;
out:
    zks_zero_pw(&pw);
    return res;
}

/*
 * Session secret acquisition trampoline. Secret bytes are streamed directly into
 * the destination session sink without mutating the shared callback memory block.
 */
ZKS_HIDDEN int zks_getsecret_unlocked(sasl_conn_t *conn, void *context, zks_secret_sink sink,
                                      void *sink_arg, sasl_secret_t **psecret)
{
    char pw[ZSASL_MAX_PASSWORD_LEN + 1] ZKS_CLEANUP(zks_zero_pw);
    size_t len = 0;
    int res;

    if (conn == NULL || psecret == NULL) {
        return SASL_BADPARAM;
    }
    res = zks_getsecret_compute((struct zsasl_secret_ctx *)context, pw, &len);
    if (res == SASL_OK) {
        res = sink(sink_arg, (const unsigned char *)pw, len, psecret);
    }
    zks_zero_pw(&pw);
    return res;
}

ZKS_HIDDEN int zks_is_library_pass_cb(zks_sasl_proc proc)
{
    return proc == ZKS_SASL_PROC(&_zsasl_getsecret);
}

/* SASL_CB_GETREALM callback returning preconfigured authentication realm. */
static int _zsasl_getrealm(void *context, int id, const char **availrealms, const char **result)
{
    ZKS_UNUSED(id);
    ZKS_UNUSED(availrealms);
    *result = (const char *)context;
    return SASL_OK;
}

/* Handles SASL_CB_USER and SASL_CB_AUTHNAME callback requests for principal identities. */
static int _zsasl_simple(void *context, int id, const char **result, unsigned *len)
{
    ZKS_UNUSED(len);
    if (result == NULL) {
        return SASL_BADPARAM;
    }
    switch (id) {
    case SASL_CB_USER:
    case SASL_CB_AUTHNAME:
        *result = (const char *)context;
        return SASL_OK;
    default:
        return SASL_BADPARAM;
    }
}

/* Computes NULL-terminated string length with overflow protection. */
static int zks_opt_len(const char *s, size_t *out)
{
    size_t n;

    *out = 0;
    if (s == NULL) {
        return 0;
    }
    n = strnlen(s, ZKS_SASL_LEN_CAP);
    if (n >= ZKS_SASL_LEN_CAP) {
        return -1;
    }
    return ZKS_ADD_OVF(n, (size_t)1, out) ? -1 : 0;
}

/*
 * Constructs a self-contained Cyrus SASL callback array matching legacy memory layout.
 * Returns NULL with EINVAL if parameters are invalid, or ENOMEM on allocation failure.
 */
sasl_callback_t *zoo_sasl_make_password_callbacks(const char *user, const char *realm,
                                                  zoo_sasl_password_t *password)
{
    size_t user_len;
    size_t realm_len;
    size_t file_len;
    size_t total = offsetof(struct zsasl_callbacks_block, strings);
    struct zsasl_callbacks_block *b;
    const char *user_ctx = NULL;
    const char *realm_ctx = NULL;
    char *p;

    if (password == NULL) {
        errno = EINVAL;
        return NULL;
    }
    if (zks_opt_len(user, &user_len) != 0 || zks_opt_len(realm, &realm_len) != 0
        || zks_opt_len(password->password_file, &file_len) != 0
        || ZKS_ADD_OVF(total, user_len, &total) || ZKS_ADD_OVF(total, realm_len, &total)
        || ZKS_ADD_OVF(total, file_len, &total)) {
        errno = ENOMEM;
        return NULL;
    }
    b = (struct zsasl_callbacks_block *)calloc(1, total);
    if (b == NULL) {
        return NULL;
    }
    p = b->strings;
    if (user != NULL) {
        memcpy(p, user, user_len);
        user_ctx = p;
        p += user_len;
    }
    if (realm != NULL) {
        memcpy(p, realm, realm_len);
        realm_ctx = p;
        p += realm_len;
    }
    if (password->password_file != NULL) {
        memcpy(p, password->password_file, file_len);
        b->secret_ctx.password_file = p;
    }
    b->secret_ctx.context = password->context;
    b->secret_ctx.callback = password->callback;
    b->secret_ctx.secret = &b->secret.secret;
    b->callbacks[0].id = SASL_CB_GETREALM;
    b->callbacks[0].proc = ZKS_SASL_PROC(&_zsasl_getrealm);
    b->callbacks[0].context = (void *)(uintptr_t)realm_ctx;
    b->callbacks[1].id = SASL_CB_USER;
    b->callbacks[1].proc = ZKS_SASL_PROC(&_zsasl_simple);
    b->callbacks[1].context = (void *)(uintptr_t)user_ctx;
    b->callbacks[2].id = SASL_CB_AUTHNAME;
    b->callbacks[2].proc = ZKS_SASL_PROC(&_zsasl_simple);
    b->callbacks[2].context = (void *)(uintptr_t)user_ctx;
    b->callbacks[3].id = SASL_CB_PASS;
    b->callbacks[3].proc = ZKS_SASL_PROC(&_zsasl_getsecret);
    b->callbacks[3].context = &b->secret_ctx;
    b->callbacks[4].id = SASL_CB_LIST_END;
    return b->callbacks;
}

sasl_callback_t *zoo_sasl_make_basic_callbacks(const char *user, const char *realm,
                                               const char *password_file)
{
    zoo_sasl_password_t password = {password_file, NULL, NULL};

    return zoo_sasl_make_password_callbacks(user, realm, &password);
}
