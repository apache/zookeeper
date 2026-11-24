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
 * SIGPIPE suppression when writing to a half-closed socket. Mode: DEV.
 *
 * A forked child restores SIGPIPE to SIG_DFL, establishes a single threaded
 * session, calls shutdown(fd, SHUT_WR) on the underlying socket descriptor, and
 * submits a request that flushes into the closed write half.
 *
 * Deviations:
 * - Rust configures SO_NOSIGPIPE via setsockopt on macOS/BSD (and passes
 *   MSG_NOSIGNAL to send() on Linux), so the write fails cleanly with EPIPE
 *   without raising SIGPIPE.
 * - Legacy C passes SO_NOSIGPIPE as a send() flags argument on macOS rather
 *   than a socket option, where send() ignores it and the kernel terminates the
 *   process with SIGPIPE.
 *
 * zkconf-flavors: st
 */

#include "zkconf.h"

#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/select.h>
#include <sys/socket.h>

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

static void quiet_stat(int rc, const struct Stat *stat, const void *data)
{
    (void)rc;
    (void)stat;
    (void)data;
}

/* Executes one st event-loop iteration (interest -> select <= 50 ms -> process). */
static int pass(zhandle_t *zh, int *fd)
{
    int interest = 0;
    struct timeval tv = {0, 0};
    fd_set rd;
    fd_set wr;
    int events = 0;
    int rc = zookeeper_interest(zh, fd, &interest, &tv);
    if (rc != ZOK || *fd < 0) {
        zkc_sleep_ms(10);
        return rc;
    }
    if (tv.tv_sec > 0 || tv.tv_usec > 50000) {
        tv.tv_sec = 0;
        tv.tv_usec = 50000;
    }
    FD_ZERO(&rd);
    FD_ZERO(&wr);
    if (interest & ZOOKEEPER_READ) {
        FD_SET(*fd, &rd);
    }
    if (interest & ZOOKEEPER_WRITE) {
        FD_SET(*fd, &wr);
    }
    if (select(*fd + 1, &rd, &wr, NULL, &tv) > 0) {
        if (FD_ISSET(*fd, &rd)) {
            events |= ZOOKEEPER_READ;
        }
        if (FD_ISSET(*fd, &wr)) {
            events |= ZOOKEEPER_WRITE;
        }
    }
    return zookeeper_process(zh, events);
}

static int child_send(void)
{
    zhandle_t *zh;
    int fd = -1;
    int waited;
    signal(SIGPIPE, SIG_DFL);
    zh = zookeeper_init2(zkc_host(0), quiet_watcher, 10000, NULL, NULL, 0,
                         quiet_log);
    if (zh == NULL) {
        return 7;
    }
    for (waited = 0; waited < 10000; waited += 50) {
        if (zoo_state(zh) == ZOO_CONNECTED_STATE) {
            break;
        }
        pass(zh, &fd);
    }
    if (zoo_state(zh) != ZOO_CONNECTED_STATE) {
        return 8;
    }
    pass(zh, &fd);
    if (fd < 0 || shutdown(fd, SHUT_WR) != 0) {
        return 9;
    }
    zoo_aexists(zh, "/", 0, quiet_stat, NULL);
    for (waited = 0; waited < 500; waited += 50) {
        pass(zh, &fd);
    }
    return 0;
}

int main(int argc, char **argv)
{
    const char *res;
    zkc_init(argc, argv, "ldg_d_sigpipe", 1, "DEV", "ignore");
    res = zkc_child("GLB-01 send after shutdown", child_send, 15000);
    if (zkc_is_rust()) {
        zkc_dev("GLB-01", strcmp(res, "exit:0") == 0,
                "the child ends with %s", res);
    } else {
#ifdef __APPLE__
        char want[32];
        snprintf(want, sizeof(want), "signal:%d", SIGPIPE);
        zkc_dev("GLB-01", strcmp(res, want) == 0,
                "legacy: the child ends with %s", res);
#else
        zkc_dev("GLB-01", 1, "legacy: the child ends with %s", res);
#endif
    }
    return zkc_finish();
}
