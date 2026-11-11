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
 * Debug handle registry verification.
 *
 * Each scenario executes in an isolated child process so the parent can capture
 * stderr, inspect the termination signal, and verify the diagnostic output.
 *
 * Usage: use_after_close HOST
 * HOST should point to an unbound address so handles remain in the connecting
 * state without requiring a live server.
 *
 */

#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

#include <zookeeper.h>

static const char *host;
static int failures;

static void noop_watcher(zhandle_t *zh, int type, int state, const char *path, void *ctx)
{
    (void)zh;
    (void)type;
    (void)state;
    (void)path;
    (void)ctx;
}

static zhandle_t *open_handle(void)
{
    zhandle_t *zh = zookeeper_init(host, noop_watcher, 10000, NULL, NULL, 0);
    if (zh == NULL) {
        _exit(3);
    }
    return zh;
}

/* Baseline: valid operations and a single close on a registered handle succeed. */
static void live_handle(void)
{
    zhandle_t *zh = open_handle();
    if (zoo_state(zh) == ZOO_EXPIRED_SESSION_STATE) {
        _exit(4);
    }
    _exit(zookeeper_close(zh) == ZOK ? 0 : 5);
}

/* Invoking an accessor after `zookeeper_close` triggers a use-after-close abort. */
static void state_after_close(void)
{
    zhandle_t *zh = open_handle();
    zookeeper_close(zh);
    (void)zoo_state(zh);
    _exit(0);
}

/* Invoking `zookeeper_close` twice on the same handle triggers a double-close abort. */
static void double_close(void)
{
    zhandle_t *zh = open_handle();
    zookeeper_close(zh);
    (void)zookeeper_close(zh);
    _exit(0);
}

/* Submitting an asynchronous operation after `zookeeper_close` triggers a use-after-close abort. */
static void aget_after_close(void)
{
    zhandle_t *zh = open_handle();
    zookeeper_close(zh);
    (void)zoo_aget(zh, "/", 0, NULL, NULL);
    _exit(0);
}

/*
 * Executes `fn` in a forked child process and verifies that it either aborts
 * with the expected registry diagnostic (`want_abort != 0`) or exits cleanly
 * with status 0 (`want_abort == 0`).
 */
static void run_case(const char *name, void (*fn)(void), int want_abort)
{
    char err[4096];
    size_t used = 0;
    ssize_t got;
    int fds[2];
    int status = 0;
    int aborted;
    int has_line;
    pid_t pid;

    if (pipe(fds) != 0) {
        printf("FAIL %s: pipe\n", name);
        failures++;
        return;
    }
    fflush(stdout);
    pid = fork();
    if (pid == 0) {
        close(fds[0]);
        dup2(fds[1], STDERR_FILENO);
        fn();
        _exit(6);
    }
    close(fds[1]);
    while (used < sizeof(err) - 1 &&
           (got = read(fds[0], err + used, sizeof(err) - 1 - used)) > 0) {
        used += (size_t)got;
    }
    err[used] = '\0';
    close(fds[0]);
    waitpid(pid, &status, 0);

    aborted = WIFSIGNALED(status) && WTERMSIG(status) == SIGABRT;
    has_line = strstr(err, "is closed or invalid (use after close or double close)") != NULL;
    if (want_abort ? (aborted && has_line)
                   : (WIFEXITED(status) && WEXITSTATUS(status) == 0 && !has_line)) {
        printf("ok   %s\n", name);
    } else {
        printf("FAIL %s: status 0x%x, stderr: %s\n", name, (unsigned)status, err);
        failures++;
    }
}

int main(int argc, char **argv)
{
    if (argc != 2) {
        fprintf(stderr, "usage: use_after_close HOST\n");
        return 2;
    }
    host = argv[1];
    zoo_set_debug_level(0);
    run_case("live handle", live_handle, 0);
    run_case("zoo_state after close", state_after_close, 1);
    run_case("double close", double_close, 1);
    run_case("zoo_aget after close", aget_after_close, 1);
    printf("use_after_close: %d failures\n", failures);
    return failures == 0 ? 0 : 1;
}
