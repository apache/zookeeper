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
 * Log formatting, level filtering, stream/callback routing, and post-fork PID
 * reporting. Mode: EXACT.
 *
 * Invariants:
 * - mt stream log lines follow the format
 *   "YYYY-MM-DD HH:MM:SS,mmm:PID(0xTID):ZOO_LEVEL@func@line: message", whereas
 *   st stream lines omit the "(0xTID)" component (zk_log.c:165-182).
 * - direct calls to log_message bypass the global logLevel filter.
 * - calling zoo_set_debug_level with {0, 1, 4, 5, -1} sets logLevel to
 *   {0, 1, 4, 4, 4}.
 * - setting logLevel = 5 directly permits ERROR, WARN, and INFO macro
 *   messages while suppressing DEBUG (because LOG_DEBUG tests `logLevel ==
 *   ZOO_LOG_LEVEL_DEBUG`).
 * - zoo_get_log_stream returns stderr before any zoo_set_log_stream call,
 *   returns the configured stream after zoo_set_log_stream(f), and reverts to
 *   stderr after zoo_set_log_stream(NULL).
 * - log callbacks receive messages without a trailing newline, whereas
 *   stream output appends '\n'.
 * - handle initialization logs the client environment header first
 *   unless ZOO_NO_LOG_CLIENTENV is passed.
 * - Oversized messages are truncated at the 4094-byte buffer limit.
 * - Legacy C caches getpid() in a static variable and emits the parent PID in a
 *   forked child, whereas the Rust drop-in queries the current PID on each log
 *   emission.
 *
 * zkconf-flavors: mt st
 */

#include "zkconf.h"

#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <zookeeper_log.h>

static char g_path[1024];
static FILE *g_stream;
static char g_first[256];
static int g_ncb;
static size_t g_cb_len;
static int g_cb_newline;
/* Non-zero while draining log output produced by the forked child. */
static int g_child_step;

static int is_d(char c)
{
    return c >= '0' && c <= '9';
}

/* Validates stream line syntax and records the normalized message suffix. */
static void record_line(const char *line)
{
    static const char shape[] = "dddd-dd-dd dd:dd:dd,ddd:";
    char tid[32];
    const char *p = line;
    const char *rest;
    char *end;
    size_t i;
    long pid;
    unsigned long t;
    int ok = 1;
    if (line[0] == '\0') {
        zkc_ev("log", "empty line");
        return;
    }
    for (i = 0; i + 1 < sizeof(shape) && ok; i++) {
        ok = shape[i] == 'd' ? is_d(p[i]) : p[i] == shape[i];
    }
    if (!ok) {
        zkc_ev("log", "bad shape: %.60s", line);
        return;
    }
    p += sizeof(shape) - 1;
    pid = strtol(p, &end, 10);
    if (end == p) {
        zkc_ev("log", "bad pid: %.60s", line);
        return;
    }
    if (zkc_is_st()) {
        /* single threaded log lines omit the thread ID field. */
        if (*end != ':') {
            zkc_ev("log", "bad st pid end: %.60s", line);
            return;
        }
        rest = end + 1;
        snprintf(tid, sizeof(tid), "none");
    } else {
        if (strncmp(end, "(0x", 3) != 0) {
            zkc_ev("log", "bad pid or tid: %.60s", line);
            return;
        }
        t = strtoul(end + 3, &end, 16);
        if (strncmp(end, "):", 2) != 0) {
            zkc_ev("log", "bad tid end: %.60s", line);
            return;
        }
        rest = end + 2;
        snprintf(tid, sizeof(tid), "%s",
                 t == (unsigned long)pthread_self() ? "self" : "other");
    }
    if (g_child_step) {
        zkc_dev("GLB-06",
                zkc_is_rust() ? pid != (long)getpid() : pid == (long)getpid(),
                "LG-12: the forked child printed the pid of the %s",
                pid == (long)getpid() ? "parent" : "child");
    }
    zkc_ev("log", "pid=%s tid=%s line=%d text=%.200s",
           g_child_step ? "child" : pid == (long)getpid() ? "self" : "other",
           tid, (int)strlen(line), rest);
}

/* Reads and records all lines currently buffered in the stream file, then truncates it. */
static void drain_stream(const char *what)
{
    char *line = malloc(16384);
    FILE *in;
    zkc_ev("step", "%s", what);
    fflush(g_stream);
    in = fopen(g_path, "r");
    if (in == NULL || line == NULL) {
        zkc_fail("cannot read %s", g_path);
        free(line);
        return;
    }
    while (fgets(line, 16384, in) != NULL) {
        size_t n = strlen(line);
        int nl = n > 0 && line[n - 1] == '\n';
        if (nl) {
            line[n - 1] = '\0';
        }
        if (strstr(line, "Client environment:") != NULL &&
            strstr(line, "zookeeper.version") == NULL) {
            continue;
        }
        record_line(line);
        if (!nl) {
            zkc_ev("log", "no newline at the end");
        }
    }
    fclose(in);
    free(line);
    if (ftruncate(fileno(g_stream), 0) != 0) {
        zkc_fail("ftruncate failed");
    }
    rewind(g_stream);
}

static void first_cb(const char *msg)
{
    const char *p = strstr(msg, "ZOO_");
    size_t n = strlen(msg);
    if (g_ncb == 0) {
        snprintf(g_first, sizeof(g_first), "%.60s", p ? p : msg);
    }
    g_ncb++;
    g_cb_len = n;
    g_cb_newline = n > 0 && msg[n - 1] == '\n';
}

static int child_log(void)
{
    LOG_ERROR(NULL, "from the forked child");
    return 0;
}

static void callback_first(int flags, const char *label)
{
    zhandle_t *zh;
    g_ncb = 0;
    g_first[0] = '\0';
    zh = zookeeper_init2(zkc_host(0), NULL, 10000, NULL, NULL, flags,
                         first_cb);
    if (zh != NULL) {
        zookeeper_close(zh);
    }
    zkc_ev("check", "%s first callback line: %s", label, g_first);
}

int main(int argc, char **argv)
{
    static const int levels[5] = {0, 1, 4, 5, -1};
    char big[5000];
    const char *out;
    zhandle_t *zh;
    int i;

    zkc_init(argc, argv, "log", 1, "EXACT", "ignore");

    /* Verify zoo_set_debug_level updates the global logLevel variable. */
    for (i = 0; i < 5; i++) {
        zoo_set_debug_level((ZooLogLevel)levels[i]);
        zkc_ev("ret", "zoo_set_debug_level(%d) -> logLevel=%d", levels[i],
               (int)logLevel);
    }
    zoo_set_debug_level(ZOO_LOG_LEVEL_INFO);

    out = getenv("ZKCONF_OUT");
    snprintf(g_path, sizeof(g_path), "%s.stream", out ? out : "/tmp/zkconf");
    g_stream = fopen(g_path, "w+");
    if (g_stream == NULL) {
        zkc_fail("cannot open %s", g_path);
        return zkc_finish();
    }
    /* Verify default and custom log stream accessor round-trips. */
    zkc_ev("ret", "zoo_get_log_stream() == stderr -> %d",
           zoo_get_log_stream() == stderr);
    zoo_set_log_stream(g_stream);
    zkc_ev("ret", "zoo_get_log_stream() == stream -> %d",
           zoo_get_log_stream() == g_stream);

    /* Log line format and macro threshold filtering at INFO. */
    LOG_ERROR(NULL, "error %d", 1);
    LOG_WARN(NULL, "warn %s", "two");
    LOG_INFO(NULL, "info");
    LOG_DEBUG(NULL, "debug, filtered");
    drain_stream("macros at INFO");

    /* Direct log_message calls bypass macro-level logLevel filtering. */
    zoo_set_debug_level(ZOO_LOG_LEVEL_ERROR);
    log_message(NULL, ZOO_LOG_LEVEL_DEBUG, 42, "lg03", "direct debug %d", 3);
    drain_stream("direct log_message at DEBUG with logLevel=1");

    /* Direct assignment of out-of-range value 5 to logLevel enables all levels. */
    logLevel = (ZooLogLevel)5;
    LOG_ERROR(NULL, "e5");
    LOG_WARN(NULL, "w5");
    LOG_INFO(NULL, "i5");
    LOG_DEBUG(NULL, "d5");
    drain_stream("macros with logLevel=5");
    zoo_set_debug_level(ZOO_LOG_LEVEL_INFO);

    /* Truncation of a 4999-byte formatted log message. */
    memset(big, 'x', sizeof(big) - 1);
    big[sizeof(big) - 1] = '\0';
    log_message(NULL, ZOO_LOG_LEVEL_INFO, 7, "lg10", "%s", big);
    drain_stream("a 4999-byte message");

    /* Per-call log callback delivery without trailing newline. */
    log_message(first_cb, ZOO_LOG_LEVEL_INFO, 8, "lg06", "to the callback");
    zkc_ev("check", "callback line: newline=%d len_ok=%d", g_cb_newline,
           g_cb_len > strlen("to the callback"));

    /* PID emission in a forked child process. */
    zkc_child("LG-12", child_log, 5000);
    g_child_step = 1;
    drain_stream("a line from a forked child");
    g_child_step = 0;

    /* Library lines on the stream: init, connect and close. */
    zh = zookeeper_init(zkc_host(0), zkc_watcher, 10000, NULL, "default", 0);
    zkc_poll_add(zh);
    zkc_await("session:CONNECTED", 1, 15000);
    zkc_poll_remove(zh);
    zookeeper_close(zh);
    drain_stream("library lines of one session");

    /* ZOO_NO_LOG_CLIENTENV flag suppresses the client environment preamble. */
    callback_first(0, "flags=0");
    callback_first(ZOO_NO_LOG_CLIENTENV, "ZOO_NO_LOG_CLIENTENV");
    drain_stream("stream after the callback handles");

    zoo_set_log_stream(NULL);
    zkc_ev("ret", "zoo_get_log_stream() == stderr -> %d",
           zoo_get_log_stream() == stderr);
    fclose(g_stream);
    return zkc_finish();
}
