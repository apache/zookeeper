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
 * Runtime ABI verification suite for the public C API.
 *
 * Links against `-lzookeeper_mt` or `-lzookeeper_st` identically to an external
 * consumer. single threaded (`st`) builds omit `THREADED` and synchronous APIs,
 * driving completion waits through an explicit `zookeeper_interest` and
 * `zookeeper_process` event loop.
 *
 * Each execution mode emits a deterministic transcript to stdout and returns a
 * non-zero exit status on invariant violation. The test harness compares the
 * `common`, `logging`, and `server` transcripts produced by the Rust drop-in
 * against the legacy C library, while `dev` mode validates intentional
 * behavioral deviations specific to the Rust implementation.
 *
 * Usage: runtime_abi common
 *        runtime_abi dev UNUSED_HOST:PORT
 *        runtime_abi server HOST:PORT
 *        runtime_abi logging UNUSED_HOST:PORT
 */

#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <sys/time.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include <zookeeper.h>
#include <zookeeper_log.h>
#include <zookeeper_version.h>

/*
 * `ZOO_SSL_CONNECTING_STATE` is part of the exported symbol ABI even though no
 * installed public header declares it (`abi/symbols.tsv`).
 */
extern ZOOAPI const int ZOO_SSL_CONNECTING_STATE;

/*
 * `zookeeper.h` guards `zookeeper_interest` and `zookeeper_process` behind
 * `!THREADED`, but `libzookeeper_mt` exports both symbols (`abi/symbols.tsv`).
 */
#ifdef THREADED
ZOOAPI int zookeeper_interest(zhandle_t *zh, int *fd, int *interest, struct timeval *tv);
ZOOAPI int zookeeper_process(zhandle_t *zh, int events);
#endif

static int failures;

#define CHECK(cond, ...)                                                    \
    do {                                                                    \
        if (!(cond)) {                                                      \
            printf("FAIL %s:%d: ", __func__, __LINE__);                     \
            printf(__VA_ARGS__);                                            \
            printf("\n");                                                   \
            failures++;                                                     \
        }                                                                   \
    } while (0)

/* ---- helpers ---- */

/* Suspends execution for `ms` milliseconds. */
static void sleep_ms(int ms)
{
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (long)(ms % 1000) * 1000000L;
    nanosleep(&ts, NULL);
}

/*
 * Executes `fn(arg)` in an isolated child process and classifies its
 * termination: `"ok"` for exit status 0, `"fault"` for `SIGSEGV` or `SIGBUS`,
 * `"abort"` for `SIGABRT`, and `"other"` for any other outcome.
 */
static const char *in_child(void (*fn)(void *), void *arg)
{
    int status = 0;
    pid_t pid;

    fflush(stdout);
    fflush(stderr);
    pid = fork();
    if (pid < 0) {
        return "fork-failed";
    }
    if (pid == 0) {
        fn(arg);
        _exit(0);
    }
    if (waitpid(pid, &status, 0) != pid) {
        return "wait-failed";
    }
    if (WIFEXITED(status) && WEXITSTATUS(status) == 0) {
        return "ok";
    }
    if (WIFSIGNALED(status) && (WTERMSIG(status) == SIGSEGV || WTERMSIG(status) == SIGBUS)) {
        return "fault";
    }
    if (WIFSIGNALED(status) && WTERMSIG(status) == SIGABRT) {
        return "abort";
    }
    return "other";
}

/* Attempts a store through a pointer to `const` data using a volatile cast. */
static void write_const_int(void *arg)
{
    volatile int *p = (volatile int *)arg;
    *p = 12345;
}

static void write_first_char(void *arg)
{
    volatile char *p = (volatile char *)arg;
    *p = 'X';
}

static void noop_watcher(zhandle_t *zh, int type, int state, const char *path, void *ctx)
{
    (void)zh;
    (void)type;
    (void)state;
    (void)path;
    (void)ctx;
}

static void noop_stat(int rc, const struct Stat *stat, const void *data)
{
    (void)rc;
    (void)stat;
    (void)data;
}

static void noop_data(int rc, const char *value, int len, const struct Stat *stat, const void *data)
{
    (void)rc;
    (void)value;
    (void)len;
    (void)stat;
    (void)data;
}

static void print_acl_vector(const char *name, const struct ACL_vector *v)
{
    int i;
    printf("%s: sizeof=%lu count=%d\n", name, (unsigned long)sizeof(*v), v->count);
    for (i = 0; i < v->count; i++) {
        printf("%s[%d]: perms=%d scheme=%s id=%s\n", name, i, v->data[i].perms,
               v->data[i].id.scheme, v->data[i].id.id);
    }
}

#ifdef THREADED
/* Yields for 20 ms to allow background library threads to make progress. */
static void drive(zhandle_t *zh)
{
    (void)zh;
    sleep_ms(20);
}
#else
/*
 * Executes one single threaded event-loop iteration bounded to 20 ms, driving
 * socket I/O and completion dispatch on the caller thread.
 */
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

/* Polls `zh` up to `ms` milliseconds until it transitions to `state`. */
static int wait_state(zhandle_t *zh, int state, int ms)
{
    int waited = 0;
    while (zoo_state(zh) != state && waited < ms) {
        drive(zh);
        waited += 20;
    }
    return zoo_state(zh) == state;
}

/* ---- common mode: differential checks across both implementations ---- */

/*
 * Verifies that all 30 exported integer constants have the expected ABI sizes
 * and values.
 */
static void common_constants(void)
{
#define ZK_INT(n) printf("int %s=%d sizeof=%lu\n", #n, n, (unsigned long)sizeof(n))
    ZK_INT(ZOOKEEPER_READ);
    ZK_INT(ZOOKEEPER_WRITE);
    ZK_INT(ZOO_ASSOCIATING_STATE);
    ZK_INT(ZOO_AUTH_FAILED_STATE);
    ZK_INT(ZOO_CHANGED_EVENT);
    ZK_INT(ZOO_CHILD_EVENT);
    ZK_INT(ZOO_CONNECTED_STATE);
    ZK_INT(ZOO_CONNECTING_STATE);
    ZK_INT(ZOO_CONTAINER);
    ZK_INT(ZOO_CREATED_EVENT);
    ZK_INT(ZOO_DELETED_EVENT);
    ZK_INT(ZOO_EPHEMERAL);
    ZK_INT(ZOO_EPHEMERAL_SEQUENTIAL);
    ZK_INT(ZOO_EXPIRED_SESSION_STATE);
    ZK_INT(ZOO_NOTCONNECTED_STATE);
    ZK_INT(ZOO_NOTWATCHING_EVENT);
    ZK_INT(ZOO_PERM_ADMIN);
    ZK_INT(ZOO_PERM_ALL);
    ZK_INT(ZOO_PERM_CREATE);
    ZK_INT(ZOO_PERM_DELETE);
    ZK_INT(ZOO_PERM_READ);
    ZK_INT(ZOO_PERM_WRITE);
    ZK_INT(ZOO_PERSISTENT);
    ZK_INT(ZOO_PERSISTENT_SEQUENTIAL);
    ZK_INT(ZOO_PERSISTENT_SEQUENTIAL_WITH_TTL);
    ZK_INT(ZOO_PERSISTENT_WITH_TTL);
    ZK_INT(ZOO_READONLY_STATE);
    ZK_INT(ZOO_SEQUENCE);
    ZK_INT(ZOO_SESSION_EVENT);
    ZK_INT(ZOO_SSL_CONNECTING_STATE);
#undef ZK_INT
}

/*
 * Verifies the five exported ACL and ID structs. The top-level structs and ACL
 * element arrays reside in writable data segments, whereas embedded string
 * pointers reference read-only string literals in `.rodata`.
 */
static void common_records(void)
{
    int saved;

    print_acl_vector("ZOO_OPEN_ACL_UNSAFE", &ZOO_OPEN_ACL_UNSAFE);
    print_acl_vector("ZOO_READ_ACL_UNSAFE", &ZOO_READ_ACL_UNSAFE);
    print_acl_vector("ZOO_CREATOR_ALL_ACL", &ZOO_CREATOR_ALL_ACL);
    printf("ZOO_ANYONE_ID_UNSAFE: sizeof=%lu scheme=%s id=%s\n",
           (unsigned long)sizeof(ZOO_ANYONE_ID_UNSAFE), ZOO_ANYONE_ID_UNSAFE.scheme,
           ZOO_ANYONE_ID_UNSAFE.id);
    printf("ZOO_AUTH_IDS: sizeof=%lu scheme=%s id=%s\n", (unsigned long)sizeof(ZOO_AUTH_IDS),
           ZOO_AUTH_IDS.scheme, ZOO_AUTH_IDS.id);

    /* Confirm that exported ACL vector elements reside in writable memory. */
    saved = ZOO_OPEN_ACL_UNSAFE.data[0].perms;
    ZOO_OPEN_ACL_UNSAFE.data[0].perms = ZOO_PERM_READ;
    CHECK(ZOO_OPEN_ACL_UNSAFE.data[0].perms == ZOO_PERM_READ, "ACL entry is not writable");
    ZOO_OPEN_ACL_UNSAFE.data[0].perms = saved;

    printf("write ZOO_PERM_READ: %s\n", in_child(write_const_int, (void *)&ZOO_PERM_READ));
    printf("write ZOO_CONNECTED_STATE: %s\n",
           in_child(write_const_int, (void *)&ZOO_CONNECTED_STATE));
    printf("write ZOO_ANYONE_ID_UNSAFE.scheme[0]: %s\n",
           in_child(write_first_char, ZOO_ANYONE_ID_UNSAFE.scheme));
    printf("write ZOO_OPEN_ACL_UNSAFE.data[0].id.id[0]: %s\n",
           in_child(write_first_char, ZOO_OPEN_ACL_UNSAFE.data[0].id.id));
}

/*
 * Verifies `logLevel`: a single writable 4-byte global shared between the
 * application and library, initialized to `ZOO_LOG_LEVEL_INFO` and mutated in
 * place by `zoo_set_debug_level`.
 */
static void common_loglevel(void)
{
    printf("logLevel: sizeof=%lu default=%d\n", (unsigned long)sizeof(logLevel), (int)logLevel);
    logLevel = ZOO_LOG_LEVEL_DEBUG;
    CHECK(logLevel == ZOO_LOG_LEVEL_DEBUG, "logLevel is not writable");
    zoo_set_debug_level((ZooLogLevel)0);
    printf("after zoo_set_debug_level(0): %d\n", (int)logLevel);
    zoo_set_debug_level((ZooLogLevel)9);
    printf("after zoo_set_debug_level(9): %d\n", (int)logLevel);
    zoo_set_debug_level(ZOO_LOG_LEVEL_WARN);
    printf("after zoo_set_debug_level(WARN): %d\n", (int)logLevel);
    logLevel = ZOO_LOG_LEVEL_INFO;
}

/*
 * Verifies `zerror` string mappings across standard negative error codes and
 * positive `errno` values (`ZSSLCONNECTIONERROR` is excluded here and checked
 * in `dev` mode).
 */
static void common_zerror(void)
{
    int c;
    for (c = -200; c <= 200; c++) {
        if (c == ZSSLCONNECTIONERROR) {
            continue;
        }
        printf("zerror(%d)=%s\n", c, zerror(c));
    }
}

/* Verifies that `ZOO_VERSION` and `zoo_version_str()` report identical strings. */
static void common_version(void)
{
    printf("ZOO_VERSION=%s zoo_version_str=%s\n", ZOO_VERSION, zoo_version_str());
    CHECK(strcmp(ZOO_VERSION, zoo_version_str()) == 0, "version strings differ");
}

/*
 * Verifies NULL-handle behavior for entry points that are already NULL-safe in
 * the legacy C library (including `w*` watch-taking variants). Synchronous
 * entry points are omitted in single threaded builds.
 */
static void common_null_safe(void)
{
    struct Stat st;
    int interest = 0;
    int fd = -1;
    struct timeval tv;
#ifdef THREADED
    char buf[8];
    int len = (int)sizeof(buf);
    struct String_vector sv;
#endif

    printf("zoo_state(NULL)=%d\n", zoo_state(NULL));
    zoo_set_context(NULL, &st);
    printf("zoo_get_log_callback(NULL)=%s\n", zoo_get_log_callback(NULL) == NULL ? "NULL" : "set");
    zoo_set_log_callback(NULL, NULL);
    printf("zookeeper_close(NULL)=%d\n", zookeeper_close(NULL));
    printf("zookeeper_interest(NULL)=%d\n", zookeeper_interest(NULL, &fd, &interest, &tv));
    printf("zookeeper_process(NULL)=%d\n", zookeeper_process(NULL, 0));
#ifdef THREADED
    printf("zoo_create(NULL)=%d\n",
           zoo_create(NULL, "/a", NULL, -1, &ZOO_OPEN_ACL_UNSAFE, 0, NULL, 0));
#endif
    printf("zoo_awget(NULL)=%d\n", zoo_awget(NULL, "/a", NULL, NULL, noop_data, NULL));
    printf("zoo_awexists(NULL)=%d\n", zoo_awexists(NULL, "/a", NULL, NULL, noop_stat, NULL));
#ifdef THREADED
    printf("zoo_wget(NULL)=%d\n", zoo_wget(NULL, "/a", NULL, NULL, buf, &len, &st));
    printf("zoo_wexists(NULL)=%d\n", zoo_wexists(NULL, "/a", NULL, NULL, &st));
    printf("zoo_wget_children(NULL)=%d\n", zoo_wget_children(NULL, "/a", NULL, NULL, &sv));
    printf("zoo_delete(NULL)=%d\n", zoo_delete(NULL, "/a", -1));
    printf("zoo_set(NULL)=%d\n", zoo_set(NULL, "/a", "x", 1, -1));
#endif
}

static int run_common(void)
{
    common_constants();
    common_records();
    common_loglevel();
    common_zerror();
    common_version();
    common_null_safe();
    return 0;
}

/* ---- logging mode: differential checks across both implementations ---- */

/* Returns the number of lines in `f` containing `needle`. */
static int count_lines(FILE *f, const char *needle)
{
    char line[4096];
    int n = 0;
    rewind(f);
    while (fgets(line, sizeof(line), f) != NULL) {
        if (strstr(line, needle) != NULL) {
            n++;
        }
    }
    return n;
}

#ifdef THREADED
/*
 * `start_threads` logs its DEBUG message synchronously on the calling thread
 * before `zookeeper_init` returns.
 */
#define LIB_DEBUG_LINE "ZOO_DEBUG@start_threads@"
#else
/*
 * In single threaded builds, the initial `zookeeper_interest` call initiates
 * the connection and emits `[zk] connect()` on the caller thread.
 */
#define LIB_DEBUG_LINE "[zk] connect()"
#endif

/* Initializes and closes a handle at `level`, summarizing emitted log lines. */
static void log_session(const char *host, ZooLogLevel level, const char *label)
{
    FILE *f = tmpfile();
    zhandle_t *zh;
    int debug_app;
#ifndef THREADED
    struct timeval tv;
    int fd = -1;
    int interest = 0;
#endif

    if (f == NULL) {
        CHECK(0, "tmpfile: %s", strerror(errno));
        return;
    }
    zoo_set_log_stream(f);
    /* Mutate `logLevel` directly as permitted by the public C ABI. */
    logLevel = level;
    zh = zookeeper_init(host, noop_watcher, 10000, NULL, NULL, 0);
    CHECK(zh != NULL, "zookeeper_init failed");
    if (zh != NULL) {
#ifndef THREADED
        CHECK(zookeeper_interest(zh, &fd, &interest, &tv) == ZOK, "zookeeper_interest failed");
#endif
        zookeeper_close(zh);
    }
    /* Application logging macros read the same shared `logLevel` global. */
    LOG_DEBUG(NULL, "application debug line");
    fflush(f);
    debug_app = count_lines(f, "application debug line");
    printf("%s: lines=%s info=%s lib_debug=%s any_debug=%s debug_app=%d\n", label,
           count_lines(f, "") > 0 ? "some" : "none",
           count_lines(f, "Initiating client connection") > 0 ? "yes" : "no",
           count_lines(f, LIB_DEBUG_LINE) > 0 ? "yes" : "no",
           count_lines(f, "ZOO_DEBUG@") > 0 ? "yes" : "no", debug_app);
    zoo_set_log_stream(NULL);
    fclose(f);
}

/*
 * Verifies that every internal log site observes direct mutations to the shared
 * `logLevel` variable without caching.
 */
static int run_logging(const char *host)
{
    log_session(host, (ZooLogLevel)0, "level 0");
    log_session(host, ZOO_LOG_LEVEL_ERROR, "level ERROR");
    log_session(host, ZOO_LOG_LEVEL_INFO, "level INFO");
    log_session(host, ZOO_LOG_LEVEL_DEBUG, "level DEBUG");
    logLevel = ZOO_LOG_LEVEL_INFO;
    return 0;
}

/* ---- server mode: live server tests across both implementations ---- */

/*
 * Verifies that in-place mutations to `ZOO_OPEN_ACL_UNSAFE` are reflected in
 * transmitted requests, and validates payload length semantics (-1 encodes a
 * null payload, 0 encodes an empty payload).
 */
#ifdef THREADED
static int run_server(const char *host)
{
    struct ACL_vector acl;
    struct Stat st;
    char buf[16];
    char path[64];
    int len;
    int rc;
    int saved;
    zhandle_t *zh;

    logLevel = ZOO_LOG_LEVEL_ERROR;
    zh = zookeeper_init(host, noop_watcher, 10000, NULL, NULL, 0);
    if (zh == NULL || !wait_state(zh, ZOO_CONNECTED_STATE, 10000)) {
        printf("FAIL: no session with %s\n", host);
        return 1;
    }
    snprintf(path, sizeof(path), "/zk-abi-%ld", (long)getpid());

    saved = ZOO_OPEN_ACL_UNSAFE.data[0].perms;
    ZOO_OPEN_ACL_UNSAFE.data[0].perms = ZOO_PERM_READ | ZOO_PERM_WRITE | ZOO_PERM_DELETE;
    rc = zoo_create(zh, path, "v", 1, &ZOO_OPEN_ACL_UNSAFE, 0, NULL, 0);
    ZOO_OPEN_ACL_UNSAFE.data[0].perms = saved;
    printf("create with changed ACL: %s\n", zerror(rc));
    memset(&acl, 0, sizeof(acl));
    rc = zoo_get_acl(zh, path, &acl, &st);
    printf("get_acl: %s count=%d perms=%d\n", zerror(rc), acl.count,
           acl.count > 0 ? acl.data[0].perms : -1);
    CHECK(rc == ZOK && acl.count == 1
              && acl.data[0].perms == (ZOO_PERM_READ | ZOO_PERM_WRITE | ZOO_PERM_DELETE),
          "the server did not get the changed ACL");
    deallocate_ACL_vector(&acl);

    rc = zoo_set(zh, path, "ignored", -1, -1);
    len = (int)sizeof(buf);
    rc = rc == ZOK ? zoo_get(zh, path, 0, buf, &len, &st) : rc;
    printf("set len -1: %s buffer_len=%d dataLength=%d\n", zerror(rc), len, st.dataLength);
    rc = zoo_set(zh, path, NULL, 0, -1);
    len = (int)sizeof(buf);
    rc = rc == ZOK ? zoo_get(zh, path, 0, buf, &len, &st) : rc;
    printf("set len 0: %s buffer_len=%d dataLength=%d\n", zerror(rc), len, st.dataLength);
    rc = zoo_set(zh, path, "abc", 3, -1);
    len = 2;
    rc = rc == ZOK ? zoo_get(zh, path, 0, buf, &len, &st) : rc;
    printf("get into 2 bytes: %s buffer_len=%d bytes=%.2s dataLength=%d\n", zerror(rc), len, buf,
           st.dataLength);

    rc = zoo_delete(zh, path, -1);
    printf("delete: %s\n", zerror(rc));
    printf("is_unrecoverable(connected)=%d\n", is_unrecoverable(zh));
    rc = zookeeper_close(zh);
    printf("close: %s\n", zerror(rc));
    return 0;
}
#endif

#ifndef THREADED
/* Captures asynchronous completion results in single threaded server mode. */
struct result {
    int done;
    int rc;
    int len;
    int data_length;
    int count;
    int perms;
    char bytes[16];
};

static void result_string(int rc, const char *value, const void *data)
{
    struct result *r = (struct result *)data;
    (void)value;
    r->rc = rc;
    r->done = 1;
}

static void result_stat(int rc, const struct Stat *stat, const void *data)
{
    struct result *r = (struct result *)data;
    (void)stat;
    r->rc = rc;
    r->done = 1;
}

static void result_void(int rc, const void *data)
{
    struct result *r = (struct result *)data;
    r->rc = rc;
    r->done = 1;
}

static void result_data(int rc, const char *value, int len, const struct Stat *stat,
                        const void *data)
{
    struct result *r = (struct result *)data;
    r->rc = rc;
    r->len = len;
    r->data_length = stat != NULL ? stat->dataLength : -1;
    memset(r->bytes, 0, sizeof(r->bytes));
    if (value != NULL && len > 0) {
        size_t n = (size_t)len < sizeof(r->bytes) - 1 ? (size_t)len : sizeof(r->bytes) - 1;
        memcpy(r->bytes, value, n);
    }
    r->done = 1;
}

static void result_acl(int rc, struct ACL_vector *acl, struct Stat *stat, const void *data)
{
    struct result *r = (struct result *)data;
    (void)stat;
    r->rc = rc;
    r->count = acl != NULL ? acl->count : -1;
    r->perms = acl != NULL && acl->count > 0 ? acl->data[0].perms : -1;
    r->done = 1;
}

/*
 * Drives the single threaded event loop until `r->done` is set or 10 seconds
 * elapse. Propagates immediate submission failures (`rc != ZOK`).
 */
static int await_result(zhandle_t *zh, int rc, struct result *r)
{
    int waited = 0;
    if (rc != ZOK) {
        return rc;
    }
    while (!r->done && waited < 10000) {
        drive(zh);
        waited += 20;
    }
    return r->done ? r->rc : ZOPERATIONTIMEOUT;
}

/*
 * single threaded (`st`) counterpart to `run_server`, exercising the same ACL
 * mutation and payload-length invariants through asynchronous operations and
 * the caller-driven event loop.
 */
static int run_server(const char *host)
{
    struct result r;
    char path[64];
    int rc;
    int saved;
    zhandle_t *zh;

    signal(SIGPIPE, SIG_IGN);
    logLevel = ZOO_LOG_LEVEL_ERROR;
    zh = zookeeper_init(host, noop_watcher, 10000, NULL, NULL, 0);
    if (zh == NULL || !wait_state(zh, ZOO_CONNECTED_STATE, 10000)) {
        printf("FAIL: no session with %s\n", host);
        return 1;
    }
    snprintf(path, sizeof(path), "/zk-abi-%ld", (long)getpid());

    /* ACLs are serialized at submission time; restore the global immediately after. */
    saved = ZOO_OPEN_ACL_UNSAFE.data[0].perms;
    ZOO_OPEN_ACL_UNSAFE.data[0].perms = ZOO_PERM_READ | ZOO_PERM_WRITE | ZOO_PERM_DELETE;
    memset(&r, 0, sizeof(r));
    rc = zoo_acreate(zh, path, "v", 1, &ZOO_OPEN_ACL_UNSAFE, 0, result_string, &r);
    ZOO_OPEN_ACL_UNSAFE.data[0].perms = saved;
    rc = await_result(zh, rc, &r);
    printf("create with changed ACL: %s\n", zerror(rc));
    memset(&r, 0, sizeof(r));
    rc = await_result(zh, zoo_aget_acl(zh, path, result_acl, &r), &r);
    printf("get_acl: %s count=%d perms=%d\n", zerror(rc), r.count, r.perms);
    CHECK(rc == ZOK && r.count == 1
              && r.perms == (ZOO_PERM_READ | ZOO_PERM_WRITE | ZOO_PERM_DELETE),
          "the server did not get the changed ACL");

    memset(&r, 0, sizeof(r));
    rc = await_result(zh, zoo_aset(zh, path, "ignored", -1, -1, result_stat, &r), &r);
    memset(&r, 0, sizeof(r));
    rc = rc == ZOK ? await_result(zh, zoo_aget(zh, path, 0, result_data, &r), &r) : rc;
    printf("set len -1: %s value_len=%d dataLength=%d\n", zerror(rc), r.len, r.data_length);
    memset(&r, 0, sizeof(r));
    rc = await_result(zh, zoo_aset(zh, path, NULL, 0, -1, result_stat, &r), &r);
    memset(&r, 0, sizeof(r));
    rc = rc == ZOK ? await_result(zh, zoo_aget(zh, path, 0, result_data, &r), &r) : rc;
    printf("set len 0: %s value_len=%d dataLength=%d\n", zerror(rc), r.len, r.data_length);
    memset(&r, 0, sizeof(r));
    rc = await_result(zh, zoo_aset(zh, path, "abc", 3, -1, result_stat, &r), &r);
    memset(&r, 0, sizeof(r));
    rc = rc == ZOK ? await_result(zh, zoo_aget(zh, path, 0, result_data, &r), &r) : rc;
    printf("set abc: %s value_len=%d bytes=%s dataLength=%d\n", zerror(rc), r.len, r.bytes,
           r.data_length);

    memset(&r, 0, sizeof(r));
    rc = await_result(zh, zoo_adelete(zh, path, -1, result_void, &r), &r);
    printf("delete: %s\n", zerror(rc));
    printf("is_unrecoverable(connected)=%d\n", is_unrecoverable(zh));
    rc = zookeeper_close(zh);
    printf("close: %s\n", zerror(rc));
    return 0;
}
#endif

/* ---- dev mode: Rust implementation deviations ---- */

static int completions;

static void count_stat(int rc, const struct Stat *stat, const void *data)
{
    (void)rc;
    (void)stat;
    (void)data;
    completions++;
}

/*
 * Verifies documented Rust deviations: NULL-handle guards on entry points that
 * dereference NULL in legacy C, the `"ssl connection error"` string for
 * `ZSSLCONNECTIONERROR` (-15), and `ZMARSHALLINGERROR` validation for invalid
 * payload buffer/length pairs.
 */
static int run_dev(const char *host)
{
#ifdef THREADED
    struct Stat st;
    struct String_vector sv;
    char buf[8];
    int len = (int)sizeof(buf);
#endif
    const char *srv;
    zhandle_t *zh;
    int rc;

    /* Integer-watch convenience wrappers (synchronous forms are `mt`-only). */
    CHECK(zoo_aget(NULL, "/a", 0, noop_data, NULL) == ZBADARGUMENTS, "zoo_aget(NULL)");
    CHECK(zoo_aexists(NULL, "/a", 0, noop_stat, NULL) == ZBADARGUMENTS, "zoo_aexists(NULL)");
#ifdef THREADED
    CHECK(zoo_get(NULL, "/a", 0, buf, &len, &st) == ZBADARGUMENTS, "zoo_get(NULL)");
    CHECK(zoo_exists(NULL, "/a", 0, &st) == ZBADARGUMENTS, "zoo_exists(NULL)");
    CHECK(zoo_get_children(NULL, "/a", 0, &sv) == ZBADARGUMENTS, "zoo_get_children(NULL)");
    CHECK(zoo_getconfig(NULL, 0, buf, &len, &st) == ZBADARGUMENTS, "zoo_getconfig(NULL)");
#endif
    /* NULL handle on `zoo_get_current_server` returns `"null"`. */
    srv = zoo_get_current_server(NULL);
    printf("zoo_get_current_server(NULL)=%s\n", srv == NULL ? "(NULL)" : srv);
    CHECK(srv != NULL && strcmp(srv, "null") == 0, "zoo_get_current_server(NULL)");
    CHECK(zoo_set_servers(NULL, "127.0.0.1:1") == ZBADARGUMENTS, "zoo_set_servers(NULL)");
    CHECK(zoo_set_servers_resolution_delay(NULL, 0) == ZBADARGUMENTS,
          "zoo_set_servers_resolution_delay(NULL)");
    zoo_cycle_next_server(NULL);
    /* NULL-safe handle accessors. */
    CHECK(zoo_get_context(NULL) == NULL, "zoo_get_context(NULL)");
    CHECK(zoo_recv_timeout(NULL) == 0, "zoo_recv_timeout(NULL)");
    CHECK(zoo_set_watcher(NULL, noop_watcher) == NULL, "zoo_set_watcher(NULL)");
    CHECK(is_unrecoverable(NULL) == ZINVALIDSTATE, "is_unrecoverable(NULL)");
    CHECK(zookeeper_get_connected_host(NULL, NULL, NULL) == NULL,
          "zookeeper_get_connected_host(NULL)");
    CHECK(zoo_client_id(NULL) == NULL, "zoo_client_id(NULL)");
    /* `zerror(ZSSLCONNECTIONERROR)` returns a descriptive string. */
    printf("zerror(-15)=%s\n", zerror(ZSSLCONNECTIONERROR));
    CHECK(strcmp(zerror(ZSSLCONNECTIONERROR), "ssl connection error") == 0, "zerror(-15)");

    /* Validate payload buffer and length checks on a connecting handle. */
    logLevel = (ZooLogLevel)0;
    zh = zookeeper_init(host, noop_watcher, 10000, NULL, NULL, 0);
    CHECK(zh != NULL, "zookeeper_init(%s)", host);
    if (zh != NULL) {
        CHECK(is_unrecoverable(zh) == ZOK, "is_unrecoverable(connecting)");
        rc = zoo_aset(zh, "/a", "x", -1, -1, count_stat, NULL);
        CHECK(rc == ZOK, "zoo_aset len -1 gives %d", rc);
        rc = zoo_aset(zh, "/a", NULL, 0, -1, count_stat, NULL);
        CHECK(rc == ZOK, "zoo_aset len 0 gives %d", rc);
        rc = zoo_aset(zh, "/a", "x", -2, -1, count_stat, NULL);
        CHECK(rc == ZMARSHALLINGERROR, "zoo_aset len -2 gives %d", rc);
        rc = zoo_aset(zh, "/a", NULL, 4, -1, count_stat, NULL);
        CHECK(rc == ZMARSHALLINGERROR, "zoo_aset NULL with len 4 gives %d", rc);
        rc = zoo_acreate(zh, "/a", "x", -7, &ZOO_OPEN_ACL_UNSAFE, 0, NULL, NULL);
        CHECK(rc == ZMARSHALLINGERROR, "zoo_acreate len -7 gives %d", rc);
        rc = zookeeper_close(zh);
        CHECK(rc == ZOK, "zookeeper_close gives %d", rc);
        /*
         * Closing drains the two accepted requests with `ZCLOSING` while
         * synchronously rejected requests never invoke completion callbacks.
         */
        CHECK(completions == 2, "completions=%d", completions);
    }
    logLevel = ZOO_LOG_LEVEL_INFO;
    printf("dev: %d failures\n", failures);
    return 0;
}

int main(int argc, char **argv)
{
    int rc;

    setvbuf(stdout, NULL, _IOLBF, 0);
    if (argc >= 2 && strcmp(argv[1], "common") == 0) {
        rc = run_common();
    } else if (argc >= 3 && strcmp(argv[1], "logging") == 0) {
        rc = run_logging(argv[2]);
    } else if (argc >= 3 && strcmp(argv[1], "server") == 0) {
        rc = run_server(argv[2]);
    } else if (argc >= 3 && strcmp(argv[1], "dev") == 0) {
        rc = run_dev(argv[2]);
    } else {
        fprintf(stderr, "usage: runtime_abi common|logging HOST|server HOST|dev HOST\n");
        return 2;
    }
    return rc != 0 || failures != 0 ? 1 : 0;
}
