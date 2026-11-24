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
 * Handling of pthread_create failure during zookeeper_init.
 * Mode: DEV.
 *
 * A forked child process exhausts thread creation capacity until pthread_create
 * fails with EAGAIN, then invokes zookeeper_init.
 *
 * On Linux, process thread limits are governed by host or cgroup pids limits
 * shared with other processes; to guarantee deterministic process-local
 * failure, the Linux path constrains RLIMIT_AS just above current virtual
 * memory usage so thread stack allocation fails with EAGAIN. On macOS, the
 * child spawns detached minimum-stack idle threads until the per-process thread
 * cap is reached.
 *
 * Invariants and deviations:
 * - Rust: zookeeper_init returns NULL with errno = EAGAIN and cleans
 *   up any partially spawned worker thread cleanly.
 * - Legacy C: start_threads aborts on assert() or hangs indefinitely
 *   at the startup barrier when compiled with NDEBUG.
 *
 * zkconf-skip-st: an st handle starts no threads
 */

#include "zkconf.h"

#include <errno.h>
#include <limits.h>
#include <pthread.h>
#include <string.h>
#include <unistd.h>

#ifdef __linux__
#include <stdio.h>
#include <sys/resource.h>
#endif

static int g_pipe[2];

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

/* Idle worker thread blocked indefinitely on an unread pipe descriptor. */
static void *idle_main(void *arg)
{
    char c;
    (void)arg;
    while (read(g_pipe[0], &c, 1) < 0 && errno == EINTR) {
    }
    return NULL;
}

#ifdef __linux__
/* Heap headroom above current VMA usage when setting RLIMIT_AS. */
#define HEADROOM (1024 * 1024)

/*
 * Caps RLIMIT_AS to current resident virtual size plus HEADROOM so subsequent
 * thread stack mmap allocations fail with EAGAIN.
 */
static int limit_address_space(void)
{
    struct rlimit rl;
    unsigned long pages = 0;
    FILE *f = fopen("/proc/self/statm", "r");
    int got;

    if (f == NULL) {
        return -1;
    }
    got = fscanf(f, "%lu", &pages);
    fclose(f);
    if (got != 1 || pages == 0 || getrlimit(RLIMIT_AS, &rl) != 0) {
        return -1;
    }
    rl.rlim_cur = (rlim_t)pages * (rlim_t)sysconf(_SC_PAGESIZE) + HEADROOM;
    return setrlimit(RLIMIT_AS, &rl);
}
#endif

/*
 * Child exit codes: 0 if zookeeper_init returned NULL with errno = EAGAIN,
 * 1 if a non-NULL handle was returned, 2 for other errno, 3 if thread capacity
 * could not be exhausted.
 */
static int child_spawn_fail(void)
{
    pthread_t t;
    zhandle_t *zh;
    int err = 0;
    int saved;

    if (pipe(g_pipe) != 0) {
        return 4;
    }
#ifdef __linux__
    if (limit_address_space() != 0) {
        return 4;
    }
    err = pthread_create(&t, NULL, idle_main, NULL);
    if (err == 0) {
        return 3;
    }
#else
    pthread_attr_t attr;
    int n;

    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, PTHREAD_STACK_MIN);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    for (n = 0; n < 100000; n++) {
        err = pthread_create(&t, &attr, idle_main, NULL);
        if (err != 0) {
            break;
        }
    }
    pthread_attr_destroy(&attr);
#endif
    if (err != EAGAIN) {
        return 3;
    }
    errno = 0;
    zh = zookeeper_init2(zkc_dead(), quiet_watcher, 10000, NULL, NULL, 0,
                         quiet_log);
    saved = errno;
    if (zh != NULL) {
        return 1;
    }
    return saved == EAGAIN ? 0 : 2;
}

int main(int argc, char **argv)
{
    const char *res;
    int rust;

    zkc_init(argc, argv, "ldg_a_thread_spawn", 0, "DEV", "ignore");
    rust = zkc_is_rust();
    res = zkc_child("INIT-12 thread spawn failure", child_spawn_fail, 15000);
    zkc_dev("INIT-12",
            rust ? strcmp(res, "exit:0") == 0
                 : strncmp(res, "signal:", 7) == 0 || strcmp(res, "hang") == 0,
            "zookeeper_init after pthread_create fails: %s", res);
    return zkc_finish();
}
