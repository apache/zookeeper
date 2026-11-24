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
 * Blocking operation wrappers for multithreaded and single threaded
 * conformance scenarios (see `zkconf_sync.h`). single threaded output copy
 * semantics match legacy `process_sync_completion`.
 */

#include "zkconf_sync.h"
#include "zkconf.h"

#include <stdlib.h>
#include <string.h>
#include <sys/time.h>

#ifndef THREADED

#define ZKCS_TIMEOUT_MS 20000

/*
 * Heap-allocated completion context for an in-flight `st` blocking wrapper. If
 * a wait times out, the caller marks the record `abandoned` so any late
 * completion simply frees the allocation without touching caller stack buffers.
 */
struct zkcs_req {
    int done;
    int abandoned;
    int rc;
    char *buf;
    int *buf_len;
    int str_len;
    struct Stat *stat;
    struct String_vector *strings;
    struct ACL_vector *acl;
};

static long zkcs_now_ms(void)
{
    struct timeval now;
    gettimeofday(&now, NULL);
    return (long)now.tv_sec * 1000L + (long)(now.tv_usec / 1000);
}

static struct zkcs_req *zkcs_new(void)
{
    struct zkcs_req *r = (struct zkcs_req *)calloc(1, sizeof(*r));
    if (r == NULL) {
        zkc_fail("zkcs: out of memory");
    }
    return r;
}

/*
 * Marks `r` complete and returns 1 if output parameters should be populated,
 * or frees `r` if abandoned.
 */
static int zkcs_finish(struct zkcs_req *r, int rc)
{
    if (r->abandoned) {
        free(r);
        return 0;
    }
    r->rc = rc;
    r->done = 1;
    return 1;
}

static char *zkcs_strdup(const char *s)
{
    size_t n = strlen(s) + 1;
    char *d = (char *)malloc(n);
    if (d != NULL) {
        memcpy(d, s, n);
    }
    return d;
}

/*
 * Drives the single threaded poll registry until `r` completes. On immediate
 * submission failure (`submit_rc != ZOK`), marks `r` abandoned rather than
 * freeing it immediately in case an implementation invokes the callback later.
 */
static int zkcs_wait(zhandle_t *zh, struct zkcs_req *r, int submit_rc,
                     const char *what)
{
    long deadline = zkcs_now_ms() + ZKCS_TIMEOUT_MS;
    int rc;
    if (submit_rc != ZOK) {
        r->abandoned = 1;
        return submit_rc;
    }
    while (!r->done) {
        long left = deadline - zkcs_now_ms();
        if (left <= 0) {
            r->abandoned = 1;
            zkc_fail("zkcs: timeout waiting for %s", what);
            return ZOPERATIONTIMEOUT;
        }
        if (!zkc_polled(zh)) {
            r->abandoned = 1;
            zkc_fail("zkcs: %s on a handle outside the poll registry", what);
            return ZINVALIDSTATE;
        }
        zkc_poll_once(left < 1000 ? (int)left : 1000);
    }
    rc = r->rc;
    free(r);
    return rc;
}

static void zkcs_void_done(int rc, const void *d)
{
    zkcs_finish((struct zkcs_req *)d, rc);
}

static void zkcs_stat_done(int rc, const struct Stat *s, const void *d)
{
    struct zkcs_req *r = (struct zkcs_req *)d;
    if (zkcs_finish(r, rc) && rc == ZOK && r->stat != NULL && s != NULL) {
        *r->stat = *s;
    }
}

/* Copies `min(len, *buffer_len)` payload bytes; sets `*buffer_len = -1` for null data. */
static void zkcs_data_done(int rc, const char *v, int len,
                           const struct Stat *s, const void *d)
{
    struct zkcs_req *r = (struct zkcs_req *)d;
    int n;
    if (!zkcs_finish(r, rc) || rc != ZOK) {
        return;
    }
    n = len <= *r->buf_len ? len : *r->buf_len;
    if (n > 0 && v != NULL) {
        memcpy(r->buf, v, (size_t)n);
    }
    *r->buf_len = n;
    if (r->stat != NULL && s != NULL) {
        *r->stat = *s;
    }
}

/* Copies up to `path_buffer_len - 1` characters plus a terminating NUL byte. */
static void zkcs_string_done(int rc, const char *v, const void *d)
{
    struct zkcs_req *r = (struct zkcs_req *)d;
    int n;
    if (!zkcs_finish(r, rc) || rc != ZOK || v == NULL) {
        return;
    }
    n = (int)strlen(v) + 1;
    if (n > r->str_len) {
        n = r->str_len;
    }
    if (n > 0) {
        memcpy(r->buf, v, (size_t)(n - 1));
        r->buf[n - 1] = '\0';
    }
}

/* Deep-copies `sv` into `r->strings` for deallocation via `deallocate_String_vector`. */
static void zkcs_strings_done(int rc, const struct String_vector *sv,
                              const void *d)
{
    struct zkcs_req *r = (struct zkcs_req *)d;
    int i;
    if (!zkcs_finish(r, rc) || rc != ZOK || r->strings == NULL) {
        return;
    }
    r->strings->count = 0;
    r->strings->data = NULL;
    if (sv == NULL || sv->count <= 0) {
        return;
    }
    r->strings->data = (char **)calloc((size_t)sv->count, sizeof(char *));
    if (r->strings->data == NULL) {
        return;
    }
    r->strings->count = sv->count;
    for (i = 0; i < sv->count; i++) {
        r->strings->data[i] = zkcs_strdup(sv->data[i]);
    }
}

/* Deep-copies `acl` into `r->acl` for deallocation via `deallocate_ACL_vector`. */
static void zkcs_acl_done(int rc, struct ACL_vector *acl, struct Stat *s,
                          const void *d)
{
    struct zkcs_req *r = (struct zkcs_req *)d;
    int i;
    if (!zkcs_finish(r, rc) || rc != ZOK) {
        return;
    }
    if (r->stat != NULL && s != NULL) {
        *r->stat = *s;
    }
    if (r->acl == NULL) {
        return;
    }
    r->acl->count = 0;
    r->acl->data = NULL;
    if (acl == NULL || acl->count <= 0) {
        return;
    }
    r->acl->data = (struct ACL *)calloc((size_t)acl->count, sizeof(struct ACL));
    if (r->acl->data == NULL) {
        return;
    }
    r->acl->count = acl->count;
    for (i = 0; i < acl->count; i++) {
        r->acl->data[i].perms = acl->data[i].perms;
        r->acl->data[i].id.scheme = zkcs_strdup(acl->data[i].id.scheme);
        r->acl->data[i].id.id = zkcs_strdup(acl->data[i].id.id);
    }
}

#endif /* !THREADED */

int zkcs_create(zhandle_t *zh, const char *path, const char *value,
                int valuelen, const struct ACL_vector *acl, int mode,
                char *path_buffer, int path_buffer_len)
{
#ifdef THREADED
    return zoo_create(zh, path, value, valuelen, acl, mode, path_buffer,
                      path_buffer_len);
#else
    struct zkcs_req *r = zkcs_new();
    if (r == NULL) {
        return ZSYSTEMERROR;
    }
    r->buf = path_buffer;
    r->str_len = path_buffer == NULL ? 0 : path_buffer_len;
    return zkcs_wait(zh, r,
                     zoo_acreate(zh, path, value, valuelen, acl, mode,
                                 zkcs_string_done, r),
                     "create");
#endif
}

int zkcs_delete(zhandle_t *zh, const char *path, int version)
{
#ifdef THREADED
    return zoo_delete(zh, path, version);
#else
    struct zkcs_req *r = zkcs_new();
    if (r == NULL) {
        return ZSYSTEMERROR;
    }
    return zkcs_wait(zh, r, zoo_adelete(zh, path, version, zkcs_void_done, r),
                     "delete");
#endif
}

int zkcs_exists(zhandle_t *zh, const char *path, int watch,
                struct Stat *stat)
{
#ifdef THREADED
    return zoo_exists(zh, path, watch, stat);
#else
    struct zkcs_req *r = zkcs_new();
    if (r == NULL) {
        return ZSYSTEMERROR;
    }
    r->stat = stat;
    return zkcs_wait(zh, r, zoo_aexists(zh, path, watch, zkcs_stat_done, r),
                     "exists");
#endif
}

int zkcs_wexists(zhandle_t *zh, const char *path, watcher_fn watcher,
                 void *ctx, struct Stat *stat)
{
#ifdef THREADED
    return zoo_wexists(zh, path, watcher, ctx, stat);
#else
    struct zkcs_req *r = zkcs_new();
    if (r == NULL) {
        return ZSYSTEMERROR;
    }
    r->stat = stat;
    return zkcs_wait(zh, r,
                     zoo_awexists(zh, path, watcher, ctx, zkcs_stat_done, r),
                     "wexists");
#endif
}

int zkcs_get(zhandle_t *zh, const char *path, int watch, char *buffer,
             int *buffer_len, struct Stat *stat)
{
#ifdef THREADED
    return zoo_get(zh, path, watch, buffer, buffer_len, stat);
#else
    struct zkcs_req *r = zkcs_new();
    if (r == NULL) {
        return ZSYSTEMERROR;
    }
    r->buf = buffer;
    r->buf_len = buffer_len;
    r->stat = stat;
    return zkcs_wait(zh, r, zoo_aget(zh, path, watch, zkcs_data_done, r),
                     "get");
#endif
}

int zkcs_wget(zhandle_t *zh, const char *path, watcher_fn watcher,
              void *ctx, char *buffer, int *buffer_len, struct Stat *stat)
{
#ifdef THREADED
    return zoo_wget(zh, path, watcher, ctx, buffer, buffer_len, stat);
#else
    struct zkcs_req *r = zkcs_new();
    if (r == NULL) {
        return ZSYSTEMERROR;
    }
    r->buf = buffer;
    r->buf_len = buffer_len;
    r->stat = stat;
    return zkcs_wait(zh, r,
                     zoo_awget(zh, path, watcher, ctx, zkcs_data_done, r),
                     "wget");
#endif
}

int zkcs_set(zhandle_t *zh, const char *path, const char *buffer, int buflen,
             int version)
{
#ifdef THREADED
    return zoo_set(zh, path, buffer, buflen, version);
#else
    struct zkcs_req *r = zkcs_new();
    if (r == NULL) {
        return ZSYSTEMERROR;
    }
    return zkcs_wait(zh, r,
                     zoo_aset(zh, path, buffer, buflen, version,
                              zkcs_stat_done, r),
                     "set");
#endif
}

int zkcs_get_children(zhandle_t *zh, const char *path, int watch,
                      struct String_vector *strings)
{
#ifdef THREADED
    return zoo_get_children(zh, path, watch, strings);
#else
    struct zkcs_req *r = zkcs_new();
    if (r == NULL) {
        return ZSYSTEMERROR;
    }
    r->strings = strings;
    return zkcs_wait(zh, r,
                     zoo_aget_children(zh, path, watch, zkcs_strings_done, r),
                     "get_children");
#endif
}

int zkcs_wget_children(zhandle_t *zh, const char *path, watcher_fn watcher,
                       void *ctx, struct String_vector *strings)
{
#ifdef THREADED
    return zoo_wget_children(zh, path, watcher, ctx, strings);
#else
    struct zkcs_req *r = zkcs_new();
    if (r == NULL) {
        return ZSYSTEMERROR;
    }
    r->strings = strings;
    return zkcs_wait(zh, r,
                     zoo_awget_children(zh, path, watcher, ctx,
                                        zkcs_strings_done, r),
                     "wget_children");
#endif
}

int zkcs_get_acl(zhandle_t *zh, const char *path, struct ACL_vector *acl,
                 struct Stat *stat)
{
#ifdef THREADED
    return zoo_get_acl(zh, path, acl, stat);
#else
    struct zkcs_req *r = zkcs_new();
    if (r == NULL) {
        return ZSYSTEMERROR;
    }
    r->acl = acl;
    r->stat = stat;
    return zkcs_wait(zh, r, zoo_aget_acl(zh, path, zkcs_acl_done, r),
                     "get_acl");
#endif
}

/* `zoo_amulti` populates `results` directly prior to invoking its completion callback. */
int zkcs_multi(zhandle_t *zh, int count, const zoo_op_t *ops,
               zoo_op_result_t *results)
{
#ifdef THREADED
    return zoo_multi(zh, count, ops, results);
#else
    struct zkcs_req *r = zkcs_new();
    if (r == NULL) {
        return ZSYSTEMERROR;
    }
    return zkcs_wait(zh, r,
                     zoo_amulti(zh, count, ops, results, zkcs_void_done, r),
                     "multi");
#endif
}

int zkcs_remove_watches(zhandle_t *zh, const char *path,
                        ZooWatcherType wtype, watcher_fn watcher,
                        void *ctx, int local)
{
#ifdef THREADED
    return zoo_remove_watches(zh, path, wtype, watcher, ctx, local);
#else
    struct zkcs_req *r;
    /* Local watch removal completes synchronously without invoking a completion callback. */
    if (local) {
        return zoo_aremove_watches(zh, path, wtype, watcher, ctx, 1, NULL,
                                   NULL);
    }
    r = zkcs_new();
    if (r == NULL) {
        return ZSYSTEMERROR;
    }
    return zkcs_wait(zh, r,
                     zoo_aremove_watches(zh, path, wtype, watcher, ctx, local,
                                         (void_completion_t *)zkcs_void_done,
                                         r),
                     "remove_watches");
#endif
}

int zkcs_remove_all_watches(zhandle_t *zh, const char *path,
                            ZooWatcherType wtype, int local)
{
#ifdef THREADED
    return zoo_remove_all_watches(zh, path, wtype, local);
#else
    struct zkcs_req *r;
    if (local) {
        return zoo_aremove_all_watches(zh, path, wtype, 1, NULL, NULL);
    }
    r = zkcs_new();
    if (r == NULL) {
        return ZSYSTEMERROR;
    }
    return zkcs_wait(zh, r,
                     zoo_aremove_all_watches(zh, path, wtype, local,
                                             (void_completion_t *)zkcs_void_done,
                                             r),
                     "remove_all_watches");
#endif
}
