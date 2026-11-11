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
 * Concurrent zoo_set_servers updates during continuous failover cycling.
 * Mode: DEV.
 *
 * A forked child initializes a handle against unreachable endpoints so the I/O
 * thread continuously fails connections and advances the address cursor while
 * the application thread repeatedly swaps the server list via zoo_set_servers
 * for two seconds, then assigns a live server endpoint, awaits CONNECTED, and
 * verifies a round-trip zoo_exists call.
 *
 * In legacy C, handle_error reads and advances the address cursor without
 * acquiring reconfig_lock while zoo_set_servers frees and replaces the address
 * vector (a use-after-free race). The Rust core serializes address list
 * mutation and cursor advancement under its core state lock.
 *
 * zkconf-flavors: mt
 */

#include "zkconf.h"

#include <stdio.h>
#include <string.h>

static char g_list_a[256];
static char g_list_b[256];

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

static int child_race(void)
{
    zhandle_t *zh;
    struct Stat st;
    int waited;
    int i;
    zoo_set_debug_level(ZOO_LOG_LEVEL_ERROR);
    zh = zookeeper_init2(g_list_a, quiet_watcher, 1000, NULL, NULL, 0,
                         quiet_log);
    if (zh == NULL) {
        return 7;
    }
    for (i = 0; i < 2000; i++) {
        if (zoo_set_servers(zh, (i % 2) != 0 ? g_list_a : g_list_b) != ZOK) {
            return 8;
        }
        zkc_sleep_ms(1);
    }
    if (zoo_set_servers(zh, zkc_host(0)) != ZOK) {
        return 9;
    }
    for (waited = 0; waited < 10000; waited += 10) {
        if (zoo_state(zh) == ZOO_CONNECTED_STATE) {
            break;
        }
        zkc_sleep_ms(10);
    }
    if (zoo_state(zh) != ZOO_CONNECTED_STATE) {
        return 10;
    }
    if (zoo_exists(zh, "/", 0, &st) != ZOK) {
        return 11;
    }
    return zookeeper_close(zh) == ZOK ? 0 : 12;
}

int main(int argc, char **argv)
{
    const char *res;
    zkc_init(argc, argv, "ldg_d_cursor_race", 1, "DEV", "ignore");
    snprintf(g_list_a, sizeof(g_list_a), "%s,%s", zkc_host(1), zkc_dead());
    snprintf(g_list_b, sizeof(g_list_b), "%s,%s,%s", zkc_host(2), zkc_dead(),
             zkc_host(1));
    res = zkc_child("SRV-10 set_servers during failover", child_race, 30000);
    zkc_dev("SRV-10", zkc_is_rust() ? strcmp(res, "exit:0") == 0 : 1,
            "%sthe child ends with %s", zkc_is_rust() ? "" : "legacy: ", res);
    return zkc_finish();
}
