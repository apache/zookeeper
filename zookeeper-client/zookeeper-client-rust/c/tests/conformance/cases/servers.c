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
 * Server-list reconfiguration and endpoint accessors. Mode: PER-ROLE.
 *
 * Invariants:
 * - zoo_set_servers with hosts == NULL returns ZBADARGUMENTS and
 *   logs "New server list cannot be empty"; invalid
 *   port specifications also return ZBADARGUMENTS, and zoo_set_servers never
 *   returns ZINVALIDSTATE.
 * - Resolution delay values below -1 return ZBADARGUMENTS.
 * - zoo_get_current_server formats "IP:port".
 * - Updating zoo_set_servers with an identical server list retains the
 *   active connection without emitting session events.
 * - Calling zoo_cycle_next_server on a single-server list preserves
 *   the active session.
 *
 * In st builds, zkcs helpers substitute for synchronous calls.
 *
 * zkconf-flavors: mt st
 */

#include "zkconf.h"
#include "zkconf_sync.h"

#include <string.h>
#include <sys/socket.h>

static void current(zhandle_t *zh, const char *when)
{
    zkc_ev("call", "zoo_get_current_server %s -> %s", when,
           zoo_get_current_server(zh));
}

int main(int argc, char **argv)
{
    zhandle_t *zh;
    zhandle_t *bad;
    char one[64];
    struct Stat st;
    int rc;

    zkc_init(argc, argv, "servers", 2, "PER-ROLE", "ordered");

    zh = zkc_connect(zkc_host(0), 10000, 0);
    if (zh == NULL) {
        return zkc_finish();
    }
    current(zh, "connected");
    {
        struct sockaddr_storage ss;
        socklen_t sl = sizeof(ss);
        struct sockaddr *a;
        memset(&ss, 0, sizeof(ss));
        a = zookeeper_get_connected_host(zh, (struct sockaddr *)&ss, &sl);
        zkc_ev("call", "zookeeper_get_connected_host -> %s family=%d len=%d",
               a == NULL ? "NULL" : "addr", (int)ss.ss_family, (int)sl);
    }

    zkc_ev("call", "zoo_set_servers(zh, NULL) -> %s",
           zkc_rc(zoo_set_servers(zh, NULL)));
    zkc_ev("call", "zoo_set_servers_resolution_delay(-2) -> %s",
           zkc_rc(zoo_set_servers_resolution_delay(zh, -2)));
    zkc_ev("call", "zoo_set_servers_resolution_delay(-1) -> %s",
           zkc_rc(zoo_set_servers_resolution_delay(zh, -1)));
    zkc_ev("call", "zoo_set_servers_resolution_delay(0) -> %s",
           zkc_rc(zoo_set_servers_resolution_delay(zh, 0)));

    /* Re-applying the identical server list causes no disconnect or session event. */
    snprintf(one, sizeof(one), "%s", zkc_host(0));
    rc = zoo_set_servers(zh, one);
    zkc_ev("call", "zoo_set_servers same list -> %s", zkc_rc(rc));
    current(zh, "after same list");
    zkc_ev("check", "session events after same list: %d",
           zkc_await_none("watch", 500));
    rc = zkcs_exists(zh, "/", 0, &st);
    zkc_ev("call", "zoo_exists after same list -> %s", zkc_rc(rc));

    /* Cycling the server cursor on a single-server list. */
    zoo_cycle_next_server(zh);
    current(zh, "after cycle");
    rc = zkcs_exists(zh, "/", 0, &st);
    zkc_ev("call", "zoo_exists after cycle -> %s", zkc_rc(rc));
    zkc_ev("call", "zoo_state -> %s", zkc_state(zoo_state(zh)));
    zkc_close(zh);

    /* Test malformed ports on a separate handle to avoid mutating zh. */
    bad = zkc_connect(zkc_host(1), 10000, 0);
    if (bad != NULL) {
        current(bad, "second handle");
        zkc_ev("call", "zoo_set_servers bad port -> %s",
               zkc_rc(zoo_set_servers(bad, "127.0.0.1:12x")));
        zkc_ev("call", "zoo_set_servers port 0 -> %s",
               zkc_rc(zoo_set_servers(bad, "127.0.0.1:0")));
        zkc_close(bad);
    }
    return zkc_finish();
}
