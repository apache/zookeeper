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
 * Verifies the close-on-exec (`FD_CLOEXEC`) flag of the session socket.
 *
 * A library built with the `SOCK_CLOEXEC` option (Autotools
 * `--with-sock-cloexec`, CMake `WANT_SOCK_CLOEXEC`) creates the session socket
 * with `FD_CLOEXEC`, as legacy `zookeeper_connect` does; without the option,
 * the socket has no `FD_CLOEXEC`.
 *
 * The test listens on a loopback port and accepts the handle's connection
 * without answering, avoiding the need for a ZooKeeper server. It locates the
 * client socket in its own process by matching the local port to the peer port
 * of the accepted connection, and reads its descriptor flags with `fcntl`
 * `F_GETFD`.
 *
 * Usage: sock_cloexec yes|no
 *   yes  expects `FD_CLOEXEC` to be set.
 *   no   expects `FD_CLOEXEC` to be clear.
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#ifndef _DARWIN_C_SOURCE
#define _DARWIN_C_SOURCE
#endif

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#include <zookeeper.h>

static void watcher(zhandle_t *zh, int type, int state, const char *path, void *ctx)
{
    (void)zh;
    (void)type;
    (void)state;
    (void)path;
    (void)ctx;
}

/*
 * Returns the first descriptor below 1024, other than `skip`, that is an IPv4
 * socket with local port `port`, or -1 when none matches.
 */
static int find_local_port(int port, int skip)
{
    struct sockaddr_in sa;
    socklen_t len;
    int fd;

    for (fd = 0; fd < 1024; fd++) {
        if (fd == skip) {
            continue;
        }
        len = sizeof sa;
        memset(&sa, 0, sizeof sa);
        if (getsockname(fd, (struct sockaddr *)&sa, &len) == 0 && sa.sin_family == AF_INET &&
            ntohs(sa.sin_port) == port) {
            return fd;
        }
    }
    return -1;
}

int main(int argc, char **argv)
{
    struct sockaddr_in sa;
    struct sockaddr_in peer;
    socklen_t len = sizeof sa;
    struct pollfd pfd;
    char host[64];
    zhandle_t *zh;
    int want;
    int lfd;
    int afd = -1;
    int cfd;
    int flags;
    int i;

    setvbuf(stdout, NULL, _IOLBF, 0);
    if (argc < 2 || (strcmp(argv[1], "yes") != 0 && strcmp(argv[1], "no") != 0)) {
        fprintf(stderr, "usage: sock_cloexec yes|no\n");
        return 2;
    }
    want = strcmp(argv[1], "yes") == 0;
    zoo_set_debug_level((ZooLogLevel)0);
    memset(&sa, 0, sizeof sa);
    sa.sin_family = AF_INET;
    sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    lfd = socket(AF_INET, SOCK_STREAM, 0);
    if (lfd < 0 || bind(lfd, (struct sockaddr *)&sa, sizeof sa) != 0 || listen(lfd, 1) != 0 ||
        getsockname(lfd, (struct sockaddr *)&sa, &len) != 0) {
        printf("FAIL listen: %s\n", strerror(errno));
        return 1;
    }
    snprintf(host, sizeof host, "127.0.0.1:%d", ntohs(sa.sin_port));
    zh = zookeeper_init(host, watcher, 10000, NULL, NULL, 0);
    if (zh == NULL) {
        printf("FAIL zookeeper_init: %s\n", strerror(errno));
        return 1;
    }
    pfd.fd = lfd;
    pfd.events = POLLIN;
    for (i = 0; i < 250 && afd < 0; i++) {
#ifndef THREADED
        struct timeval tv;
        int fd;
        int interest;

        /*
         * The single threaded (`st`) handle opens its socket in
         * `zookeeper_interest`, so the `st` build calls it in the loop.
         */
        zookeeper_interest(zh, &fd, &interest, &tv);
#endif
        if (poll(&pfd, 1, 20) == 1) {
            len = sizeof peer;
            afd = accept(lfd, (struct sockaddr *)&peer, &len);
        }
    }
    if (afd < 0) {
        printf("FAIL the handle did not connect\n");
        zookeeper_close(zh);
        return 1;
    }
    cfd = find_local_port(ntohs(peer.sin_port), afd);
    flags = cfd >= 0 ? fcntl(cfd, F_GETFD) : -1;
    printf("session socket fd=%d FD_CLOEXEC=%s\n", cfd,
           flags < 0 ? "?" : (flags & FD_CLOEXEC) ? "yes" : "no");
    zookeeper_close(zh);
    close(afd);
    close(lfd);
    if (flags < 0) {
        printf("FAIL no session socket found\n");
        return 1;
    }
    if (((flags & FD_CLOEXEC) != 0) != want) {
        printf("FAIL FD_CLOEXEC is %s, want %s\n", (flags & FD_CLOEXEC) ? "set" : "clear",
               want ? "set" : "clear");
        return 1;
    }
    return 0;
}
