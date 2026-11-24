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
 * Per-resolution reseeding of process global libc PRNG state on a connected handle (EXACT).
 *
 * Under the default 0 ms server resolution delay, every zookeeper_interest evaluation on a
 * connected handle resolves the ensemble host list and reseeds both srandom() and srand48().
 * After seeding both generators with a fixed value and triggering a resolution cycle (via
 * zookeeper_interest in single threaded mode or a synchronous zoo_exists in multithreaded mode),
 * subsequent random() and drand48() draws must diverge from the fixed seed baseline. When periodic
 * resolution is disabled (delay -1), both generators remain at their fixed seed sequence. Also
 * verifies that the lowest available file descriptor number is preserved across reseeds so no
 * entropy descriptor is leaked.
 *
 * zkconf-flavors: mt st
 */

#include "zkconf.h"

#include <stdlib.h>
#include <unistd.h>

/* Probes and returns the lowest unallocated file descriptor number in the process. */
static int lowest_free_fd(void)
{
    int fd = dup(0);

    if (fd >= 0) {
        close(fd);
    }
    return fd;
}

/* Drives one operation that triggers an interest cycle, and host resolution when due. */
static void trigger(zhandle_t *zh)
{
#ifdef THREADED
    struct Stat st;
    int rc = zoo_exists(zh, "/", 0, &st);

    if (rc != ZOK) {
        zkc_fail("zoo_exists: %s", zkc_rc(rc));
    }
#else
    int fd = -1;
    int interest = 0;
    struct timeval tv;
    int rc = zkc_interest(zh, &fd, &interest, &tv);

    if (rc != ZOK) {
        zkc_fail("zookeeper_interest: %s", zkc_rc(rc));
    }
#endif
}

/* Seeds both PRNGs to a known baseline, runs one trigger, and records whether each generator was
 * reseeded. */
static void step(zhandle_t *zh, const char *label, long want_r, double want_d)
{
    long got_r;
    double got_d;

    srandom(42);
    srand48(42);
    trigger(zh);
    got_r = random();
    got_d = drand48();
    zkc_ev("check", "%s: random reseeded=%d drand48 reseeded=%d", label, got_r != want_r,
           got_d != want_d);
}

int main(int argc, char **argv)
{
    long want_r;
    double want_d;
    int fd_before;
    int fd_after;
    int i;
    zhandle_t *zh;

    zkc_init(argc, argv, "ldg_d_prng_each", 1, "EXACT", "ignore");
    srandom(42);
    want_r = random();
    srand48(42);
    want_d = drand48();
    zh = zkc_connect(zkc_host(0), 10000, 0);
    if (zh == NULL) {
        return zkc_finish();
    }
    /*
     * Disable background resolution (delay -1) and drain one round trip so any in-flight
     * resolution on the multithreaded I/O thread settles before sampling the baseline descriptor
     * watermark and PRNG state.
     */
    zoo_set_servers_resolution_delay(zh, -1);
    trigger(zh);
    fd_before = lowest_free_fd();
    zoo_set_servers_resolution_delay(zh, 0);
    for (i = 0; i < 5; i++) {
        step(zh, "delay 0", want_r, want_d);
    }
    zoo_set_servers_resolution_delay(zh, -1);
    trigger(zh);
    step(zh, "delay -1", want_r, want_d);
    fd_after = lowest_free_fd();
    zkc_ev("check", "fd_same=%d", fd_before == fd_after);
    zkc_close(zh);
    return zkc_finish();
}
