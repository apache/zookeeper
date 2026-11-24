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
 * Null-pointer hardening, crash-path remediation, and minor behavioral
 * deviations. Mode: DEV.
 *
 * Each scenario that crashes or blocks in the legacy library executes in an
 * isolated forked child process under a timeout prior to parent handle
 * creation. The child exits with status 0 when it observes the hardened Rust
 * outcome; on the legacy core, the parent records the legacy exit/signal/hang
 * outcome in the .dev file.
 *
 * Deviations verified:
 * - Passing a NULL zhandle_t returns ZBADARGUMENTS, NULL, 0,
 *   ZINVALIDSTATE, or "null" rather than dereferencing NULL.
 * - Passing a NULL completion callback pointer to async entry points
 *   is accepted without crashing on response delivery.
 * - In mt builds, zoo_aremove_watches with local=1 returns ZOK without
 *   invoking the completion callback or corrupting the caller's data pointer.
 * - zoo_amulti / zoo_multi with NULL ops or results when count > 0, or
 *   with a negative count, returns ZBADARGUMENTS (legacy C crashes on NULL
 *   arrays and transmits an empty multi request on negative counts).
 * - Passing a NULL value buffer with len > 0, a negative length
 *   less than -1, or a NULL ACL vector in zoo_aset_acl returns
 *   ZMARSHALLINGERROR.
 * - Logging at an out-of-range level (7) formats "ZOO_INVALID", and
 *   an oversized 5000-byte function name is safely truncated to the log buffer.
 * - In mt builds, calling zookeeper_interest on an mt handle sets *fd = -1 and
 *   returns ZOK, and calling zookeeper_process returns ZINVALIDSTATE without
 *   disrupting the session.
 * - zerror(ZSSLCONNECTIONERROR) returns "ssl connection error".
 * - Calling zookeeper_close in a forked child process does not
 *   terminate the parent's active session.
 * - Calling an empty zoo_amulti or zoo_multi on an expired
 *   handle fails immediately with ZMARSHALLINGERROR without queuing the
 *   request.
 * - A thread with a pending deferred pthread cancellation can submit
 *   200 zoo_acreate calls while cancellation is masked inside the library,
 *   leaving the handle intact.
 * - A thread that unwinds out of an initialization log callback via longjmp and
 *   subsequently enables deferred cancellation can still submit 200 zoo_acreate
 *   calls; subsequent library entries mask cancellation across FFI transitions
 *   and honor the pending cancellation only when reaching pthread_testcancel.
 *
 * zkconf-skip-st: its children use mt handles
 */

#include "zkconf.h"

#include <errno.h>
#include <netinet/in.h>
#include <pthread.h>
#include <setjmp.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
#include <zookeeper_log.h>

/* Declared in zookeeper.h only when THREADED is undefined, but exported by mt. */
int zookeeper_interest(zhandle_t *zh, int *fd, int *interest,
                       struct timeval *tv);
int zookeeper_process(zhandle_t *zh, int events);

static int g_sel;
static volatile int g_called;
static char g_stream_path[1024];

static void quiet_watcher(zhandle_t *zh, int type, int state,
                          const char *path, void *ctx)
{
    (void)zh;
    (void)type;
    (void)state;
    (void)path;
    (void)ctx;
}

static void quiet_log(const char *msg)
{
    (void)msg;
}

static void mark_void(int rc, const void *d)
{
    (void)rc;
    (void)d;
    g_called = 1;
}

static void mark_stat(int rc, const struct Stat *s, const void *d)
{
    (void)rc;
    (void)s;
    (void)d;
    g_called = 1;
}

/* Completion tracking for submissions from a thread with pending cancellation. */
static pthread_mutex_t g_can_mu = PTHREAD_MUTEX_INITIALIZER;
static int g_can_done;
static zhandle_t *g_can_zh;

static void can_string(int rc, const char *v, const void *d)
{
    (void)rc;
    (void)v;
    (void)d;
    pthread_mutex_lock(&g_can_mu);
    g_can_done++;
    pthread_mutex_unlock(&g_can_mu);
}

static volatile int g_can_submitted;
static jmp_buf g_can_jmp;
static volatile int g_can_jumped;

/* Submits 200 creates with a pending deferred cancel, then honors the cancel. */
static void can_submit(void)
{
    int i;
    pthread_setcancelstate(PTHREAD_CANCEL_ENABLE, NULL);
    pthread_setcanceltype(PTHREAD_CANCEL_DEFERRED, NULL);
    pthread_cancel(pthread_self());
    for (i = 0; i < 200; i++) {
        zoo_acreate(g_can_zh, "/can", "x", 1, &ZOO_OPEN_ACL_UNSAFE,
                    ZOO_EPHEMERAL | ZOO_SEQUENCE, can_string, NULL);
        g_can_submitted++;
    }
    pthread_testcancel();
}

static void *can_thread(void *arg)
{
    (void)arg;
    can_submit();
    return NULL;
}

/* Unwinds out of zookeeper_init2 via longjmp on the initial log callback. */
static void can_jump_log(const char *msg)
{
    (void)msg;
    g_can_jumped++;
    longjmp(g_can_jmp, 1);
}

static void *can_jump_thread(void *arg)
{
    (void)arg;
    if (setjmp(g_can_jmp) == 0) {
        /* Intentional non-local jump abandoning the partially initialized handle. */
        zookeeper_init2(zkc_host(0), quiet_watcher, 10000, NULL, NULL, 0,
                        can_jump_log);
        return (void *)"no jump";
    }
    can_submit();
    return NULL;
}

static int cancel_case(zhandle_t *zh, int jump)
{
    pthread_t t;
    void *res = NULL;
    struct Stat st;
    int done;
    int waited;

    g_can_zh = zh;
    if (pthread_create(&t, NULL, jump ? can_jump_thread : can_thread, NULL)
        != 0) {
        return 9;
    }
    pthread_join(t, &res);
    if (jump && g_can_jumped != 1) {
        return 5;
    }
    if (res != PTHREAD_CANCELED) {
        return 1;
    }
    if (g_can_submitted != 200) {
        return 6;
    }
    for (waited = 0; waited < 10000; waited += 10) {
        pthread_mutex_lock(&g_can_mu);
        done = g_can_done;
        pthread_mutex_unlock(&g_can_mu);
        if (done == 200) {
            break;
        }
        zkc_sleep_ms(10);
    }
    if (done != 200) {
        return 2;
    }
    if (zoo_exists(zh, "/", 0, &st) != ZOK) {
        return 3;
    }
    return zookeeper_close(zh) == ZOK ? 0 : 4;
}

/* Connects with an unknown session ID so the server expires the session. */
static int expired_multi_case(void)
{
    clientid_t cid;
    zhandle_t *zh;
    int waited;
    int rc;

    memset(&cid, 0, sizeof(cid));
    cid.client_id = 0x1234567812345678LL;
    memcpy(cid.passwd, "0123456789abcdef", sizeof(cid.passwd));
    zh = zookeeper_init2(zkc_host(0), quiet_watcher, 10000, &cid, NULL, 0,
                         quiet_log);
    for (waited = 0; zh != NULL && waited < 10000; waited += 10) {
        if (zoo_state(zh) == ZOO_EXPIRED_SESSION_STATE) {
            break;
        }
        zkc_sleep_ms(10);
    }
    if (zh == NULL || zoo_state(zh) != ZOO_EXPIRED_SESSION_STATE) {
        return 8;
    }
    rc = zoo_amulti(zh, 0, NULL, NULL, mark_void, NULL);
    if (rc != ZMARSHALLINGERROR) {
        return 1;
    }
    rc = zoo_multi(zh, 0, NULL, NULL);
    if (rc != ZMARSHALLINGERROR) {
        return 2;
    }
    if (zookeeper_close(zh) != ZOK) {
        return 3;
    }
    return !g_called ? 0 : 4;
}

static zhandle_t *connected(void)
{
    zhandle_t *zh = zookeeper_init2(zkc_host(0), quiet_watcher, 10000, NULL,
                                    NULL, 0, quiet_log);
    int waited;
    for (waited = 0; zh != NULL && waited < 10000; waited += 10) {
        if (zoo_state(zh) == ZOO_CONNECTED_STATE) {
            return zh;
        }
        zkc_sleep_ms(10);
    }
    return NULL;
}

/* Synchronous round-trip barrier ensuring all prior replies have been processed. */
static void barrier(zhandle_t *zh)
{
    struct Stat st;
    zoo_exists(zh, "/", 0, &st);
}

static int is(int rc, int want)
{
    return rc == want ? 0 : 1;
}

/* Reads the first line of the log stream output file into buf. */
static int stream_line(char *buf, size_t len)
{
    FILE *in = fopen(g_stream_path, "r");
    int ok = in != NULL && fgets(buf, (int)len, in) != NULL;
    if (in != NULL) {
        fclose(in);
    }
    return ok;
}

static int log_case(int big_func)
{
    char line[8192];
    FILE *f = fopen(g_stream_path, "w");
    if (f == NULL) {
        return 9;
    }
    zoo_set_log_stream(f);
    if (big_func) {
        char *func = malloc(5001);
        if (func == NULL) {
            return 9;
        }
        memset(func, 'f', 5000);
        func[5000] = '\0';
        log_message(NULL, ZOO_LOG_LEVEL_INFO, 1, func, "after a long func");
    } else {
        log_message(NULL, (ZooLogLevel)7, 1, "lv7", "level seven");
    }
    fflush(f);
    if (!stream_line(line, sizeof(line))) {
        return 2;
    }
    if (big_func) {
        return strlen(line) <= 4096 ? 0 : 3;
    }
    return strstr(line, ":ZOO_INVALID@lv7@1: level seven") != NULL ? 0 : 4;
}

static int child_main(void)
{
    char buf[16];
    int len = sizeof(buf);
    struct Stat st;
    struct String_vector sv;
    zhandle_t *zh = NULL;
    int rc;

    if (g_sel >= 40) {
        zh = connected();
        if (zh == NULL) {
            return 8;
        }
    }
    switch (g_sel) {
    /* NULL handle on integer-watch convenience wrappers. */
    case 1: return is(zoo_aget(NULL, "/", 0, zkc_data_cb, NULL), ZBADARGUMENTS);
    case 2: return is(zoo_aexists(NULL, "/", 0, zkc_stat_cb, NULL),
                      ZBADARGUMENTS);
    case 3: return is(zoo_aget_children(NULL, "/", 0, zkc_strings_cb, NULL),
                      ZBADARGUMENTS);
    case 4: return is(zoo_aget_children2(NULL, "/", 0, zkc_strings_stat_cb,
                                         NULL),
                      ZBADARGUMENTS);
    case 5: return is(zoo_agetconfig(NULL, 0, zkc_data_cb, NULL),
                      ZBADARGUMENTS);
    case 6: return is(zoo_exists(NULL, "/", 0, &st), ZBADARGUMENTS);
    case 7: return is(zoo_get(NULL, "/", 0, buf, &len, &st), ZBADARGUMENTS);
    case 8: return is(zoo_getconfig(NULL, 0, buf, &len, &st), ZBADARGUMENTS);
    case 9: return is(zoo_get_children(NULL, "/", 0, &sv), ZBADARGUMENTS);
    case 10: return is(zoo_get_children2(NULL, "/", 0, &sv, &st),
                       ZBADARGUMENTS);
    /* NULL handle on server-list management entry points. */
    case 11: return is(zoo_set_servers(NULL, "127.0.0.1:1"), ZBADARGUMENTS);
    case 12: zoo_cycle_next_server(NULL); return 0;
    case 13: return strcmp(zoo_get_current_server(NULL), "null") == 0 ? 0 : 1;
    case 14: return is(zoo_set_servers_resolution_delay(NULL, 0),
                       ZBADARGUMENTS);
    /* NULL handle on state and context accessors. */
    case 15: return zoo_get_context(NULL) == NULL ? 0 : 1;
    case 16: return zoo_recv_timeout(NULL) == 0 ? 0 : 1;
    case 17: return zoo_set_watcher(NULL, quiet_watcher) == NULL ? 0 : 1;
    case 18: return is(is_unrecoverable(NULL), ZINVALIDSTATE);
    case 19: {
        struct sockaddr_storage ss;
        socklen_t sl = sizeof(ss);
        return zookeeper_get_connected_host(NULL, (struct sockaddr *)&ss,
                                            &sl) == NULL ? 0 : 1;
    }
    /* NULL handle on zoo_client_id. */
    case 20: return zoo_client_id(NULL) == NULL ? 0 : 1;
    /* Out-of-range log level and oversized function name. */
    case 21: return log_case(0);
    case 22: return log_case(1);

    /* NULL completion callbacks followed by a round trip and close. */
    case 40:
        rc = zoo_aget(zh, "/", 0, NULL, NULL);
        rc = rc != ZOK ? rc : zoo_aexists(zh, "/", 0, NULL, NULL);
        rc = rc != ZOK ? rc : zoo_acreate(zh, "/n5", "", 0,
                                          &ZOO_OPEN_ACL_UNSAFE,
                                          ZOO_EPHEMERAL, NULL, NULL);
        barrier(zh);
        rc = rc != ZOK ? rc : zoo_adelete(zh, "/n5", -1, NULL, NULL);
        barrier(zh);
        zookeeper_close(zh);
        return is(rc, ZOK);
    /* Local watch removal with canary data and with NULL data. */
    case 41:
    case 42: {
        unsigned char canary[256];
        unsigned char ref[256];
        size_t i;
        for (i = 0; i < sizeof(canary); i++) {
            canary[i] = (unsigned char)(i * 7 + 1);
        }
        memcpy(ref, canary, sizeof(ref));
        zoo_wexists(zh, "/", quiet_watcher, NULL, &st);
        rc = zoo_aremove_watches(zh, "/", ZWATCHTYPE_ANY, quiet_watcher, NULL,
                                 1, (void_completion_t *)mark_void,
                                 g_sel == 41 ? (void *)canary : NULL);
        zkc_sleep_ms(300);
        barrier(zh);
        if (rc != ZOK) {
            return 1;
        }
        if (g_called) {
            return 2;
        }
        return memcmp(canary, ref, sizeof(ref)) == 0 ? 0 : 3;
    }
    /* zoo_amulti with NULL operation or result array. */
    case 43:
    case 44: {
        zoo_op_t op;
        zoo_op_result_t res;
        res.err = 12345;
        zoo_create_op_init(&op, "/m8", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, NULL,
                           0);
        rc = g_sel == 43 ? zoo_amulti(zh, 1, NULL, &res, mark_void, NULL)
                         : zoo_amulti(zh, 1, &op, NULL, mark_void, NULL);
        barrier(zh);
        if (rc != ZBADARGUMENTS) {
            return 1;
        }
        return res.err == 12345 && !g_called ? 0 : 2;
    }
    /* Payload buffer/length and ACL pointer validation. */
    case 45: rc = zoo_aset(zh, "/", NULL, 5, -1, mark_stat, NULL); break;
    case 46: rc = zoo_aset(zh, "/", "abc", -2, -1, mark_stat, NULL); break;
    case 47:
        rc = zoo_acreate(zh, "/d9", NULL, 5, &ZOO_OPEN_ACL_UNSAFE, 0,
                         (string_completion_t)NULL, NULL);
        break;
    case 48: rc = zoo_aset_acl(zh, "/", -1, NULL, mark_void, NULL); break;
    case 49: {
        zoo_op_t op;
        zoo_op_result_t res;
        zoo_set_op_init(&op, "/", NULL, 5, -1, NULL);
        rc = zoo_amulti(zh, 1, &op, &res, mark_void, NULL);
        break;
    }
    /* single threaded driver entry points invoked on a multithreaded handle. */
    case 50: {
        int fd = 99;
        int interest = 99;
        struct timeval tv;
        int prc;
        memset(&tv, 0, sizeof(tv));
        rc = zookeeper_interest(zh, &fd, &interest, &tv);
        prc = zookeeper_process(zh, ZOOKEEPER_READ);
        if (rc != ZOK || fd != -1 || interest != 0) {
            return 1;
        }
        if (prc != ZINVALIDSTATE) {
            return 2;
        }
        rc = zoo_exists(zh, "/", 0, &st);
        return rc == ZOK && zoo_state(zh) == ZOO_CONNECTED_STATE ? 0 : 3;
    }
    /* Negative operation count in zoo_multi and zoo_amulti. */
    case 51: {
        zoo_op_result_t res;
        res.err = 12345;
        rc = zoo_multi(zh, -1, NULL, &res);
        if (rc != ZBADARGUMENTS) {
            return 1;
        }
        return res.err == 12345 ? 0 : 2;
    }
    case 52:
        rc = zoo_amulti(zh, -1, NULL, NULL, mark_void, NULL);
        barrier(zh);
        if (rc != ZBADARGUMENTS) {
            return 1;
        }
        return !g_called ? 0 : 2;
    /* Empty multi request on an expired session. */
    case 53: return expired_multi_case();
    /* Submissions from a thread with pending deferred cancellation. */
    case 54: return cancel_case(zh, 0);
    /* The same submissions after a longjmp out of an init log callback. */
    case 55: return cancel_case(zh, 1);
    default:
        return 7;
    }
    /*
     * Payload and ACL validation cases: verify ZMARSHALLINGERROR, no
     * completion, and session health.
     */
    barrier(zh);
    if (rc != ZMARSHALLINGERROR) {
        return 1;
    }
    if (g_called) {
        return 2;
    }
    return zoo_exists(zh, "/", 0, &st) == ZOK ? 0 : 3;
}

struct dev_child {
    int sel;
    const char *id;
    const char *what;
};

static const struct dev_child g_children[] = {
    {1, "NUL-01", "zoo_aget(NULL)"},
    {2, "NUL-01", "zoo_aexists(NULL)"},
    {3, "NUL-01", "zoo_aget_children(NULL)"},
    {4, "NUL-01", "zoo_aget_children2(NULL)"},
    {5, "NUL-01", "zoo_agetconfig(NULL)"},
    {6, "NUL-01", "zoo_exists(NULL)"},
    {7, "NUL-01", "zoo_get(NULL)"},
    {8, "NUL-01", "zoo_getconfig(NULL)"},
    {9, "NUL-01", "zoo_get_children(NULL)"},
    {10, "NUL-01", "zoo_get_children2(NULL)"},
    {11, "NUL-02", "zoo_set_servers(NULL, hosts)"},
    {12, "NUL-02", "zoo_cycle_next_server(NULL)"},
    {13, "NUL-02", "zoo_get_current_server(NULL)"},
    {14, "NUL-02", "zoo_set_servers_resolution_delay(NULL, 0)"},
    {15, "NUL-03", "zoo_get_context(NULL)"},
    {16, "NUL-03", "zoo_recv_timeout(NULL)"},
    {17, "NUL-03", "zoo_set_watcher(NULL)"},
    {18, "NUL-03", "is_unrecoverable(NULL)"},
    {19, "NUL-03", "zookeeper_get_connected_host(NULL)"},
    {20, "NUL-04", "zoo_client_id(NULL)"},
    {21, "LOG-09", "log_message at level 7"},
    {22, "LOG-10", "log_message with a 5000-byte func"},
    {40, "NUL-05", "NULL completions"},
    {41, "WAT-13", "local aremove with canary data"},
    {42, "WAT-13", "local aremove with NULL data"},
    {43, "MUL-08", "zoo_amulti NULL ops"},
    {44, "MUL-08", "zoo_amulti NULL results"},
    {45, "DAT-09", "zoo_aset NULL value len 5"},
    {46, "DAT-09", "zoo_aset len -2"},
    {47, "DAT-09", "zoo_acreate NULL value len 5"},
    {48, "DAT-10", "zoo_aset_acl NULL acl"},
    {49, "DAT-09", "multi set op NULL value len 5"},
    {50, "DSP-18", "mt zookeeper_interest and zookeeper_process"},
    {51, "MUL-08", "zoo_multi count -1"},
    {52, "MUL-08", "zoo_amulti count -1"},
    {53, "RC-06", "empty zoo_amulti and zoo_multi on an expired session"},
    {54, "CAN-01", "200 zoo_acreate calls with a pending cancel"},
    {55, "CAN-02", "pending cancel after a longjmp out of an init log"},
};

/*
 * Verifies that the legacy outcome diverges from the hardened Rust outcome
 * (except for log buffer overflow UB and cancellation-point races, which are
 * recorded without assertion on legacy C).
 */
static int legacy_ok(const struct dev_child *c, const char *res)
{
    if (c->sel == 22 || c->sel == 54 || c->sel == 55) {
        return 1;
    }
    return strcmp(res, "exit:0") != 0;
}

static zhandle_t *g_parent;

static int child_close(void)
{
    zookeeper_close(g_parent);
    return 0;
}

int main(int argc, char **argv)
{
    const char *out;
    size_t i;
    int rust;
    struct Stat st;
    int rc;

    zkc_init(argc, argv, "dev_misc", 1, "DEV", "ignore");
    rust = zkc_is_rust();
    out = getenv("ZKCONF_OUT");
    snprintf(g_stream_path, sizeof(g_stream_path), "%s.stream",
             out ? out : "/tmp/zkconf");

    for (i = 0; i < sizeof(g_children) / sizeof(g_children[0]); i++) {
        const struct dev_child *c = &g_children[i];
        const char *res;
        g_sel = c->sel;
        res = zkc_child(c->what, child_main, 15000);
        zkc_dev(c->id, rust ? strcmp(res, "exit:0") == 0 : legacy_ok(c, res),
                "%s: %s", c->what, res);
    }

    /* Descriptive error string for ZSSLCONNECTIONERROR (-15). */
    zkc_dev("DV-FIX-6",
            strcmp(zerror(ZSSLCONNECTIONERROR),
                   rust ? "ssl connection error" : "unknown error") == 0,
            "zerror(-15) -> \"%s\"", zerror(ZSSLCONNECTIONERROR));

    /* Forked-child close executes last because it forks while holding an active parent handle. */
    g_parent = zkc_connect(zkc_host(0), 10000, 0);
    if (g_parent != NULL) {
        const char *res;
        int lost;
        zoo_create(g_parent, "/cls11", "", 0, &ZOO_OPEN_ACL_UNSAFE,
                   ZOO_EPHEMERAL, NULL, 0);
        res = zkc_child("CLS-11 close in a forked child", child_close, 10000);
        lost = zkc_await_none("watch", 3000);
        rc = zoo_exists(g_parent, "/cls11", 0, &st);
        /*
         * Record the child outcome and verify on Rust that the parent session
         * and ephemeral node remain intact.
         */
        zkc_dev("CLS-11", rust ? (lost == 0 && rc == ZOK) : 1,
                "child %s; parent session events=%d exists=%s", res, lost,
                zkc_rc(rc));
        zkc_close(g_parent);
    }
    return zkc_finish();
}
