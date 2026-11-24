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
 * Reply path verification for the public C API against a running server.
 *
 * This test checks the values that replies deliver to the application. It does not check only
 * the return codes. It covers these paths:
 *
 * 1. An asynchronous get delivers the data and the stat to the data completion.
 * 2. A data change fires the watch, and the watcher gets the type, state, path and context.
 * 3. A multi writes each result in place in the caller array. The created paths go into the
 *    caller path buffers, and the stat of a set operation goes into the caller stat. A failed
 *    multi gives the error of each operation and leaves the tree unchanged. The mt flavor uses
 *    the synchronous `zoo_multi`. The st flavor uses `zoo_amulti`.
 * 4. A data completion calls the API again. It submits another get and, in st, drives a nested
 *    `zookeeper_interest` and `zookeeper_process` loop until that get completes. The value of
 *    the outer completion must stay unchanged until the outer completion returns.
 *
 * The st flavor drives all I/O and all callbacks through `zookeeper_interest` and
 * `zookeeper_process` on the caller thread. The mt flavor gets its callbacks on the library
 * completion thread, so the shared fields use atomic flags.
 *
 * The test works against a real server and against the in-tree fake server. It creates its
 * nodes below a path that contains the process ID, and it deletes them at the end.
 *
 * Usage: reply_path HOST:PORT
 */

#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#include <zookeeper.h>

/* The small value fits in one read. The large value is above 64 KiB. */
#define SMALL_LEN 100
#define LARGE_LEN 70000
#define WAIT_MS 10000

#define LOAD(p) __atomic_load_n((p), __ATOMIC_ACQUIRE)
#define STORE(p, v) __atomic_store_n((p), (v), __ATOMIC_RELEASE)

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

static zhandle_t *g_zh;
static char g_base[64];
static char g_small_path[96];
static char g_large_path[96];
static char g_small[SMALL_LEN];
static char g_large[LARGE_LEN];

/* ---- helpers ---- */

static void sleep_ms(int ms)
{
    struct timespec ts;

    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (long)(ms % 1000) * 1000000L;
    nanosleep(&ts, NULL);
}

static long now_ms(void)
{
    struct timespec ts;

    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

static void fill(char *p, int len, int seed)
{
    int i;

    for (i = 0; i < len; i++) {
        p[i] = (char)('a' + (i * 7 + seed) % 26);
    }
}

#ifdef THREADED
/* Yields, so the library threads can deliver the replies. */
static void drive(zhandle_t *zh)
{
    (void)zh;
    sleep_ms(5);
}
#else
/* Runs one event-loop step of at most 20 ms on the caller thread. */
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

/*
 * Drives `zh` until `*flag` is set or `ms` milliseconds of wall time pass. A wall clock bound
 * is used, because a select call can return at once.
 */
static int await_flag(zhandle_t *zh, const int *flag, int ms)
{
    long end = now_ms() + ms;

    while (!LOAD(flag) && now_ms() < end) {
        drive(zh);
    }
    return LOAD(flag);
}

/* ---- completion records ---- */

/* One completion result. The completion sets `done` last. */
struct got {
    int done;
    int rc;
    int len;
    int has_stat;
    struct Stat stat;
    char *value;
};

static void got_reset(struct got *g)
{
    free(g->value);
    memset(g, 0, sizeof *g);
}

static void got_data(int rc, const char *value, int len, const struct Stat *stat,
                     const void *data)
{
    struct got *g = (struct got *)data;

    g->rc = rc;
    g->len = len;
    if (value != NULL && len > 0) {
        g->value = malloc((size_t)len);
        if (g->value != NULL) {
            memcpy(g->value, value, (size_t)len);
        }
    }
    if (stat != NULL) {
        g->stat = *stat;
        g->has_stat = 1;
    }
    STORE(&g->done, 1);
}

static void got_stat(int rc, const struct Stat *stat, const void *data)
{
    struct got *g = (struct got *)data;

    g->rc = rc;
    if (stat != NULL) {
        g->stat = *stat;
        g->has_stat = 1;
    }
    STORE(&g->done, 1);
}

static void got_string(int rc, const char *value, const void *data)
{
    struct got *g = (struct got *)data;

    g->rc = rc;
    if (value != NULL) {
        g->len = (int)strlen(value);
        g->value = strdup(value);
    }
    STORE(&g->done, 1);
}

static void got_void(int rc, const void *data)
{
    struct got *g = (struct got *)data;

    g->rc = rc;
    STORE(&g->done, 1);
}

/* Waits for `g` after a submission that returned `rc`. Returns the completion code. */
static int await_got(int rc, struct got *g)
{
    if (rc != ZOK) {
        return rc;
    }
    return await_flag(g_zh, &g->done, WAIT_MS) ? g->rc : ZOPERATIONTIMEOUT;
}

static int value_is(const struct got *g, const char *want, int len)
{
    return g->len == len && g->value != NULL && memcmp(g->value, want, (size_t)len) == 0;
}

/* One watcher record. The watcher sets `count` last. */
struct seen {
    int count;
    int type;
    int state;
    void *ctx;
    char path[96];
};

static void seen_watcher(zhandle_t *zh, int type, int state, const char *path, void *ctx)
{
    struct seen *s = (struct seen *)ctx;

    (void)zh;
    s->type = type;
    s->state = state;
    s->ctx = ctx;
    snprintf(s->path, sizeof s->path, "%s", path != NULL ? path : "(NULL)");
    STORE(&s->count, LOAD(&s->count) + 1);
}

static void session_watcher(zhandle_t *zh, int type, int state, const char *path, void *ctx)
{
    (void)zh;
    (void)type;
    (void)state;
    (void)path;
    (void)ctx;
}

/* ---- the cases ---- */

static int create_node(const char *path, const char *value, int len)
{
    struct got g;
    int rc;

    memset(&g, 0, sizeof g);
    rc = await_got(zoo_acreate(g_zh, path, value, len, &ZOO_OPEN_ACL_UNSAFE, 0, got_string, &g),
                   &g);
    CHECK(rc == ZOK, "create %s: %s", path, zerror(rc));
    CHECK(rc != ZOK || (g.value != NULL && strcmp(g.value, path) == 0),
          "create %s: the created path is %s", path, g.value != NULL ? g.value : "(NULL)");
    got_reset(&g);
    return rc;
}

static void delete_node(const char *path)
{
    struct got g;
    int rc;

    memset(&g, 0, sizeof g);
    rc = await_got(zoo_adelete(g_zh, path, -1, got_void, &g), &g);
    CHECK(rc == ZOK, "delete %s: %s", path, zerror(rc));
}

/* An async get with a watch delivers the data and the stat. A set then fires the watch. */
static void case_get_and_watch(void)
{
    static struct seen w;
    struct got g;
    int rc;

    memset(&g, 0, sizeof g);
    rc = await_got(zoo_awget(g_zh, g_base, seen_watcher, &w, got_data, &g), &g);
    printf("awget: %s len=%d version=%d dataLength=%d numChildren=%d\n", zerror(rc), g.len,
           g.stat.version, g.stat.dataLength, g.stat.numChildren);
    CHECK(rc == ZOK, "awget: %s", zerror(rc));
    CHECK(value_is(&g, "hello", 5), "awget: the value is not hello");
    CHECK(g.has_stat, "awget: no stat");
    CHECK(g.stat.version == 0 && g.stat.dataLength == 5 && g.stat.numChildren == 2,
          "awget: stat version=%d dataLength=%d numChildren=%d", g.stat.version,
          g.stat.dataLength, g.stat.numChildren);
    CHECK(g.stat.czxid > 0 && g.stat.mzxid == g.stat.czxid && g.stat.ephemeralOwner == 0,
          "awget: stat czxid=%lld mzxid=%lld ephemeralOwner=%lld", (long long)g.stat.czxid,
          (long long)g.stat.mzxid, (long long)g.stat.ephemeralOwner);
    CHECK(LOAD(&w.count) == 0, "awget: the watch fired before the change");
    got_reset(&g);

    rc = await_got(zoo_aset(g_zh, g_base, "world", 5, 0, got_stat, &g), &g);
    printf("aset: %s version=%d dataLength=%d\n", zerror(rc), g.stat.version,
           g.stat.dataLength);
    CHECK(rc == ZOK, "aset: %s", zerror(rc));
    CHECK(g.has_stat && g.stat.version == 1 && g.stat.dataLength == 5,
          "aset: stat version=%d dataLength=%d", g.stat.version, g.stat.dataLength);
    got_reset(&g);

    await_flag(g_zh, &w.count, WAIT_MS);
    printf("watch: count=%d type=%d state=%d path=%s\n", LOAD(&w.count), w.type, w.state,
           w.path);
    CHECK(LOAD(&w.count) == 1, "watch: count %d", LOAD(&w.count));
    CHECK(w.type == ZOO_CHANGED_EVENT && w.state == ZOO_CONNECTED_STATE,
          "watch: type %d state %d", w.type, w.state);
    CHECK(strcmp(w.path, g_base) == 0, "watch: path %s", w.path);
    CHECK(w.ctx == &w, "watch: the context is not the registered context");

#ifdef THREADED
    {
        struct Stat st;
        char buf[16];
        int len = (int)sizeof buf;

        memset(&st, 0, sizeof st);
        rc = zoo_get(g_zh, g_base, 0, buf, &len, &st);
        printf("get: %s len=%d version=%d\n", zerror(rc), len, st.version);
        CHECK(rc == ZOK && len == 5 && memcmp(buf, "world", 5) == 0,
              "get: %s len=%d", zerror(rc), len);
        CHECK(st.version == 1 && st.dataLength == 5, "get: stat version=%d dataLength=%d",
              st.version, st.dataLength);
    }
#endif
}

/* Submits the multi and waits for its results. */
static int run_multi(int count, const zoo_op_t *ops, zoo_op_result_t *results)
{
#ifdef THREADED
    return zoo_multi(g_zh, count, ops, results);
#else
    struct got g;

    memset(&g, 0, sizeof g);
    return await_got(zoo_amulti(g_zh, count, ops, results, got_void, &g), &g);
#endif
}

/*
 * A successful multi writes each result in place. A failed multi reports the error of each
 * operation, and the server applies none of the operations.
 */
static void case_multi(void)
{
    char p1[96];
    char p2[96];
    char p3[96];
    char p4[96];
    char b1[96];
    char b2[96];
    char b3[96];
    char b4[96];
    zoo_op_t ops[4];
    zoo_op_result_t res[4];
    struct Stat st;
    struct got g;
    int rc;
    int i;

    snprintf(p1, sizeof p1, "%s/m1", g_base);
    snprintf(p2, sizeof p2, "%s/m2", g_base);
    snprintf(p3, sizeof p3, "%s/m3", g_base);
    snprintf(p4, sizeof p4, "%s/m4", g_base);
    memset(b1, 'x', sizeof b1);
    memset(b2, 'x', sizeof b2);
    memset(&st, 0xff, sizeof st);
    zoo_create_op_init(&ops[0], p1, "m1", 2, &ZOO_OPEN_ACL_UNSAFE, 0, b1, (int)sizeof b1);
    zoo_create_op_init(&ops[1], p2, "m2", 2, &ZOO_OPEN_ACL_UNSAFE, 0, b2, (int)sizeof b2);
    zoo_set_op_init(&ops[2], g_small_path, "s1", 2, 0, &st);
    zoo_check_op_init(&ops[3], g_base, 1);
    memset(res, 0xff, sizeof res);
    rc = run_multi(4, ops, res);
    printf("multi: %s errs=%d,%d,%d,%d\n", zerror(rc), res[0].err, res[1].err, res[2].err,
           res[3].err);
    CHECK(rc == ZOK, "multi: %s", zerror(rc));
    for (i = 0; i < 4; i++) {
        CHECK(res[i].err == ZOK, "multi: result %d err %d", i, res[i].err);
    }
    CHECK(res[0].value == b1 && strcmp(b1, p1) == 0, "multi: the first path is not in place");
    CHECK(res[1].value == b2 && strcmp(b2, p2) == 0, "multi: the second path is not in place");
    CHECK(res[2].stat == &st, "multi: the set stat is not the caller stat");
    printf("multi set stat: version=%d dataLength=%d\n", st.version, st.dataLength);
    CHECK(st.version == 1 && st.dataLength == 2, "multi: set stat version=%d dataLength=%d",
          st.version, st.dataLength);

    memset(b3, 'x', sizeof b3);
    memset(b4, 'x', sizeof b4);
    memset(&st, 0, sizeof st);
    zoo_create_op_init(&ops[0], p3, "m3", 2, &ZOO_OPEN_ACL_UNSAFE, 0, b3, (int)sizeof b3);
    zoo_set_op_init(&ops[1], g_small_path, "s2", 2, 1, &st);
    zoo_check_op_init(&ops[2], g_base, 7);
    zoo_create_op_init(&ops[3], p4, "m4", 2, &ZOO_OPEN_ACL_UNSAFE, 0, b4, (int)sizeof b4);
    memset(res, 0xff, sizeof res);
    rc = run_multi(4, ops, res);
    printf("failed multi: %s errs=%d,%d,%d,%d\n", zerror(rc), res[0].err, res[1].err,
           res[2].err, res[3].err);
    CHECK(rc == ZBADVERSION, "failed multi: %s", zerror(rc));
    CHECK(res[0].err == ZOK && res[1].err == ZOK, "failed multi: the leading errs %d,%d",
          res[0].err, res[1].err);
    CHECK(res[2].err == ZBADVERSION, "failed multi: the check err %d", res[2].err);
    CHECK(res[3].err == ZRUNTIMEINCONSISTENCY, "failed multi: the trailing err %d", res[3].err);

    memset(&g, 0, sizeof g);
    rc = await_got(zoo_aexists(g_zh, p3, 0, got_stat, &g), &g);
    CHECK(rc == ZNONODE, "failed multi: exists %s: %s", p3, zerror(rc));
    got_reset(&g);
    rc = await_got(zoo_aget(g_zh, g_small_path, 0, got_data, &g), &g);
    CHECK(rc == ZOK && value_is(&g, "s1", 2) && g.stat.version == 1,
          "failed multi: the set is applied (%s version %d)", zerror(rc), g.stat.version);
    got_reset(&g);

    delete_node(p1);
    delete_node(p2);
}

/* State of the re-entrant completion. The outer completion sets `done` last. */
static struct {
    int done;
    int rc;
    int submit_rc;
    int stable_after_submit;
    int stable_after_nested;
    int inner_done_in_outer;
    struct got inner;
} g_re;

/*
 * Checks the value, calls the API again, and checks the value again. The value is the reply
 * buffer of the library. It must stay unchanged until this completion returns.
 */
static void reenter_data(int rc, const char *value, int len, const struct Stat *stat,
                         const void *data)
{
    (void)stat;
    (void)data;
    g_re.rc = rc;
    if (rc == ZOK && len == LARGE_LEN && value != NULL &&
        memcmp(value, g_large, LARGE_LEN) == 0) {
        g_re.submit_rc = zoo_aget(g_zh, g_small_path, 0, got_data, &g_re.inner);
        g_re.stable_after_submit = memcmp(value, g_large, LARGE_LEN) == 0;
#ifndef THREADED
        /* The nested loop delivers the inner reply while this completion runs. */
        if (g_re.submit_rc == ZOK) {
            g_re.inner_done_in_outer = await_flag(g_zh, &g_re.inner.done, WAIT_MS);
        }
#endif
        g_re.stable_after_nested = memcmp(value, g_large, LARGE_LEN) == 0;
    }
    STORE(&g_re.done, 1);
}

static void case_reenter(void)
{
    int rc;

    memset(&g_re, 0, sizeof g_re);
    rc = zoo_aget(g_zh, g_large_path, 0, reenter_data, NULL);
    CHECK(rc == ZOK, "reenter: submit %s", zerror(rc));
    if (rc != ZOK) {
        return;
    }
    CHECK(await_flag(g_zh, &g_re.done, WAIT_MS), "reenter: no outer completion");
    CHECK(await_flag(g_zh, &g_re.inner.done, WAIT_MS), "reenter: no inner completion");
    printf("reenter: outer=%s submit=%s inner=%s inner_len=%d in_outer=%d stable=%d,%d\n",
           zerror(g_re.rc), zerror(g_re.submit_rc), zerror(g_re.inner.rc), g_re.inner.len,
           g_re.inner_done_in_outer, g_re.stable_after_submit, g_re.stable_after_nested);
    CHECK(g_re.rc == ZOK, "reenter: outer %s", zerror(g_re.rc));
    CHECK(g_re.submit_rc == ZOK, "reenter: inner submit %s", zerror(g_re.submit_rc));
    CHECK(g_re.stable_after_submit && g_re.stable_after_nested,
          "reenter: the outer value changed in its completion");
    CHECK(g_re.inner.rc == ZOK, "reenter: inner %s", zerror(g_re.inner.rc));
    CHECK(value_is(&g_re.inner, "s1", 2), "reenter: the inner value is not s1");
    CHECK(g_re.inner.has_stat && g_re.inner.stat.version == 1 &&
              g_re.inner.stat.dataLength == 2,
          "reenter: inner stat version=%d dataLength=%d", g_re.inner.stat.version,
          g_re.inner.stat.dataLength);
#ifndef THREADED
    CHECK(g_re.inner_done_in_outer, "reenter: the nested loop did not deliver the inner reply");
#endif
    got_reset(&g_re.inner);
}

int main(int argc, char **argv)
{
    int rc;

    setvbuf(stdout, NULL, _IOLBF, 0);
    if (argc < 2) {
        fprintf(stderr, "usage: reply_path HOST:PORT\n");
        return 2;
    }
    signal(SIGPIPE, SIG_IGN);
    zoo_set_debug_level(ZOO_LOG_LEVEL_ERROR);
    fill(g_small, SMALL_LEN, 1);
    fill(g_large, LARGE_LEN, 2);
    snprintf(g_base, sizeof g_base, "/zk-abi-reply-%ld", (long)getpid());
    snprintf(g_small_path, sizeof g_small_path, "%s/small", g_base);
    snprintf(g_large_path, sizeof g_large_path, "%s/large", g_base);

    g_zh = zookeeper_init(argv[1], session_watcher, 10000, NULL, NULL, 0);
    if (g_zh == NULL) {
        printf("FAIL: zookeeper_init %s\n", argv[1]);
        return 1;
    }
    {
        long end = now_ms() + WAIT_MS;

        while (zoo_state(g_zh) != ZOO_CONNECTED_STATE && now_ms() < end) {
            drive(g_zh);
        }
    }
    if (zoo_state(g_zh) != ZOO_CONNECTED_STATE) {
        printf("FAIL: no session with %s\n", argv[1]);
        zookeeper_close(g_zh);
        return 1;
    }
    rc = create_node(g_base, "hello", 5);
    rc = rc == ZOK ? create_node(g_small_path, g_small, SMALL_LEN) : rc;
    rc = rc == ZOK ? create_node(g_large_path, g_large, LARGE_LEN) : rc;
    if (rc == ZOK) {
        case_get_and_watch();
        case_multi();
        case_reenter();
    }
    delete_node(g_large_path);
    delete_node(g_small_path);
    delete_node(g_base);
    rc = zookeeper_close(g_zh);
    CHECK(rc == ZOK, "close: %s", zerror(rc));
    printf("reply_path: %d failures\n", failures);
    return failures == 0 ? 0 : 1;
}
