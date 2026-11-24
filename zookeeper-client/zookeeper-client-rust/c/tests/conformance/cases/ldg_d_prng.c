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
 * Mutation of process-global libc PRNG state during host address resolution.
 * Mode: EXACT.
 *
 * Unless deterministic connection order is enabled, address resolution reseeds
 * process-global srandom() and srand48() state from kernel entropy, and draws
 * from drand48() for server selection weight calculation. Both libraries
 * perform identical libc PRNG operations:
 * - When address shuffling is enabled, both random() and drand48() diverge from
 *   a pre-seeded deterministic sequence after connecting.
 * - When zoo_deterministic_conn_order(1) is enabled, srandom() is not reseeded,
 *   while drand48() advances by one draw during update_addrs.
 *
 * zkconf-flavors: mt st
 */

#include "zkconf.h"

#include <stdlib.h>

/* Seeds srandom/srand48 with a fixed value, connects, and checks PRNG advancement. */
static void run(const char *label)
{
    long want_r;
    double want_d;
    long got_r;
    double got_d;
    zhandle_t *zh;
    srandom(42);
    want_r = random();
    srand48(42);
    want_d = drand48();
    srandom(42);
    srand48(42);
    zh = zkc_connect(zkc_host(0), 10000, 0);
    if (zh == NULL) {
        return;
    }
    got_r = random();
    got_d = drand48();
    zkc_ev("check", "%s: random reseeded=%d drand48 moved=%d", label,
           got_r != want_r, got_d != want_d);
    zkc_close(zh);
}

int main(int argc, char **argv)
{
    zkc_init(argc, argv, "ldg_d_prng", 1, "EXACT", "ignore");
    run("shuffle");
    zoo_deterministic_conn_order(1);
    run("deterministic");
    return zkc_finish();
}
