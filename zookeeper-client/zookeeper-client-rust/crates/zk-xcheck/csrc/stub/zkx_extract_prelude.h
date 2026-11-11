/*
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
 * Translation unit prelude included prior to extracted zookeeper.c routines.
 * Provides deterministic no-op stubs for synchronization primitives, system
 * clocks, socket teardown, and PRNG initialization while redirecting DNS
 * resolution and drand48() to controllable test harness hooks.
 */
#ifndef ZKX_EXTRACT_PRELUDE_H_
#define ZKX_EXTRACT_PRELUDE_H_

#include <ctype.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <netdb.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <netinet/in.h>

#include "zookeeper.h"
#include "zookeeper_log.h"
#include "zk_adaptor.h"
#include "addrvec.h"

#define lock_reconfig(zh) ((void)(zh))
#define unlock_reconfig(zh) ((void)(zh))

extern unsigned long zkx_setup_random_calls;
extern unsigned long zkx_close_calls;

static int disable_conn_permute = 0;

static void setup_random(void)
{
    zkx_setup_random_calls++;
}

static int calculate_interval(const struct timeval *start,
        const struct timeval *end)
{
    (void)start;
    (void)end;
    return 0;
}

static void get_system_time(struct timeval *tv)
{
    tv->tv_sec = 0;
    tv->tv_usec = 0;
}

static void close_zsock(zsock_t *fd)
{
    zkx_close_calls++;
    fd->sock = -1;
}

double zkx_drand48(void);
int zkx_getaddrinfo(const char *node, const char *service,
        const struct addrinfo *hints, struct addrinfo **res);
void zkx_freeaddrinfo(struct addrinfo *res);

#define drand48 zkx_drand48
#define getaddrinfo zkx_getaddrinfo
#define freeaddrinfo zkx_freeaddrinfo

#endif
