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
 * Pseudorandom number generation hooks and server connection ordering controls.
 *
 * Preserves legacy side effects on process libc PRNG state (reseeding from /dev/urandom,
 * random() and drand48() distributions) and exposes zoo_deterministic_conn_order to disable
 * randomized server address permutation during connection establishment.
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE /* POSIX names under -std=c99 on glibc */
#endif
#include "zks_internal.h"

#include <fcntl.h>
#include <unistd.h>

/* Disables random permutation of the server address list when non-zero. */
static int zks_conn_permute_disabled = 0;

ZKS_HIDDEN int zks_vt_conn_permute_disabled(void)
{
    int saved = zks_in_vtable;
    int r;

    zks_in_vtable = 1;
    r = ZKS_LOAD(&zks_conn_permute_disabled);
    zks_in_vtable = saved;
    return r;
}

ZKS_HIDDEN long zks_vt_rng_random(void)
{
    int saved = zks_in_vtable;
    long r;

    zks_in_vtable = 1;
    r = random();
    zks_in_vtable = saved;
    return r;
}

ZKS_HIDDEN double zks_vt_rng_drand48(void)
{
    int saved = zks_in_vtable;
    double d;

    zks_in_vtable = 1;
    d = drand48();
    zks_in_vtable = saved;
    return d;
}

/*
 * Reads an initial PRNG seed from /dev/urandom using O_CLOEXEC to prevent
 * descriptor inheritance across concurrent fork/exec calls, retrying on short
 * reads or EINTR and falling back to getpid() if the entropy device is
 * unavailable.
 */
static int zks_urandom_seed(void)
{
    int seed = 0;
    size_t seed_len = 0;
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);

    if (fd == -1) {
        return (int)getpid();
    }
    while (seed_len < sizeof(seed)) {
        ssize_t rc = read(fd, (char *)&seed + seed_len, sizeof(seed) - seed_len);
        if (rc > 0) {
            seed_len += (size_t)rc;
        } else if (rc == 0 || errno != EINTR) {
            seed = (int)getpid();
            break;
        }
    }
    close(fd);
    return seed;
}

/* Seeds both srandom and srand48 generators to match legacy initialization behavior. */
ZKS_HIDDEN void zks_vt_rng_reseed(void)
{
    int saved = zks_in_vtable;
    int seed;

    zks_in_vtable = 1;
    seed = zks_urandom_seed();
    srandom((unsigned)seed);
    srand48((long)seed);
    zks_in_vtable = saved;
}

void zoo_deterministic_conn_order(int yesOrNo)
{
    ZKS_STORE(&zks_conn_permute_disabled, yesOrNo);
}
