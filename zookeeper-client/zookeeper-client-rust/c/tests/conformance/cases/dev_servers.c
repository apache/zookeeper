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
 * Server-list update deviations. Mode: DEV.
 *
 * Deviations verified:
 * - Port-only change in zoo_set_servers. Legacy C
 *   compares only IP addresses (ignoring port differences) and remains on the
 *   existing server. The Rust core compares full socket addresses (IP and
 *   port), disconnects with a CONNECTING event, and connects to the new port
 *   (which rejects the unknown standalone session ID with EXPIRED).
 * - Invalid host string in zoo_set_servers.
 *   zoo_set_servers returns ZBADARGUMENTS on both cores. Legacy C overwrites
 *   zh->hostname before validation fails, preventing reconnection after a
 *   server restart (and in legacy st builds, causing subsequent
 *   zookeeper_interest calls to re-run update_addrs and fail with
 *   ZBADARGUMENTS). The Rust core validates the string atomically and retains
 *   the existing server list on failure.
 * - Calling zoo_set_servers(zh, "") with an empty string.
 *   Legacy C installs an empty address vector, returns ZOK, transitions to
 *   ZOO_NOTCONNECTED_STATE (999), and fails subsequent operations with
 *   ZSYSTEMERROR. The Rust core rejects the empty string with
 *   ZBADARGUMENTS while keeping the existing connection intact.
 *
 * zkconf-flavors: mt st
 */

#include "zkconf.h"
#include "zkconf_sync.h"

#include <string.h>

/* Polls the handle up to ms milliseconds until zoo_state reaches CONNECTED. */
static int wait_connected(zhandle_t *zh, int ms)
{
    int waited;
    for (waited = 0; waited < ms; waited += 50) {
        if (zoo_state(zh) == ZOO_CONNECTED_STATE) {
            return 1;
        }
        zkc_poll(50);
    }
    return 0;
}

static void port_only_change(void)
{
    zhandle_t *zh = zkc_connect(zkc_host(0), 20000, 0);
    char cur[64];
    int rc;
    int moved;
    if (zh == NULL) {
        return;
    }
    rc = zoo_set_servers(zh, zkc_host(1));
    zkc_ev("call", "zoo_set_servers port-only -> %s", zkc_rc(rc));
    if (zkc_is_rust()) {
        /* Standalone instances do not share session state, so P2 expires the session. */
        zkc_await("session:CONNECTING@default", 1, 10000);
        moved = zkc_await("session:EXPIRED@default", 1, 15000) == 0;
        snprintf(cur, sizeof(cur), "%s", zoo_get_current_server(zh));
        zkc_norm_text(cur, sizeof(cur), cur);
        zkc_dev("DV-FIX-2", rc == ZOK && moved && strstr(cur, ":P2") != NULL,
                "rc=%s moved=%d current=%s", zkc_rc(rc), moved, cur);
    } else {
        moved = zkc_await_none("watch", 2000);
        snprintf(cur, sizeof(cur), "%s", zoo_get_current_server(zh));
        zkc_norm_text(cur, sizeof(cur), cur);
        zkc_dev("DV-FIX-2", rc == ZOK && moved == 0 &&
                                strstr(cur, ":P1") != NULL,
                "rc=%s events=%d current=%s", zkc_rc(rc), moved, cur);
    }
    zkc_close(zh);
}

static void bad_string_not_committed(void)
{
    zhandle_t *zh = zkc_connect(zkc_host(2), 30000, 0);
    struct Stat st;
    int rc;
    int back;
    if (zh == NULL) {
        return;
    }
    rc = zoo_set_servers(zh, "127.0.0.1:12x");
    zkc_ev("call", "zoo_set_servers bad -> %s", zkc_rc(rc));
    zkc_server("stop", 2);
#ifndef THREADED
    if (!zkc_is_rust()) {
        int fd = -1;
        int interest = 0;
        struct timeval tv;
        int ncon = zkc_await_none("session:CONNECTING@default", 3000);
        int irc = zkc_interest(zh, &fd, &interest, &tv);
        zkc_server("start", 2);
        zkc_dev("D9", ncon == 0 && irc == ZBADARGUMENTS,
                "st: CONNECTING=%d interest=%s (legacy keeps the bad list)",
                ncon, zkc_rc(irc));
        zkc_close(zh);
        return;
    }
#endif
    zkc_await("session:CONNECTING@default", 1, 10000);
    zkc_server("start", 2);
    if (zkc_is_rust()) {
        back = zkc_await("session:CONNECTED@default", 1, 20000) == 0;
        rc = back ? zkcs_exists(zh, "/", 0, &st) : ZCONNECTIONLOSS;
        zkc_dev("D9", back && rc == ZOK,
                "reconnected=%d exists=%s", back, zkc_rc(rc));
    } else {
        back = wait_connected(zh, 6000);
        zkc_dev("D9", back == 0, "reconnected=%d (legacy keeps the bad list)",
                back);
    }
    zkc_close(zh);
}

static void empty_list(void)
{
    zhandle_t *zh = zkc_connect(zkc_host(0), 10000, 0);
    int rc;
    int arc;
    int st0;
    int ncb;
    int nev;
    if (zh == NULL) {
        return;
    }
    rc = zoo_set_servers(zh, "");
    st0 = zoo_state(zh);
    arc = zoo_aexists(zh, "/", 0, zkc_stat_cb, "after-empty");
    /*
     * Both cores deliver one completion; zkc_await_none terminates immediately
     * upon the first callback arrival.
     */
    ncb = zkc_await_none("cb", 10000);
    nev = zkc_await_none("session:CONNECTING@default", 1);
    if (zkc_is_rust()) {
        zkc_dev("RC-09", rc == ZBADARGUMENTS &&
                                   st0 == ZOO_CONNECTED_STATE && arc == ZOK &&
                                   ncb == 1 && nev == 0,
                "RC-09-empty: rc=%s state=%s aexists=%s completions=%d "
                "CONNECTING=%d",
                zkc_rc(rc), zkc_state(st0), zkc_rc(arc), ncb, nev);
    } else {
        zkc_dev("RC-09", rc == ZOK && st0 == ZOO_NOTCONNECTED_STATE &&
                                   arc == ZOK && ncb == 1 && nev == 0,
                "RC-09-empty: rc=%s state=%s aexists=%s completions=%d "
                "CONNECTING=%d",
                zkc_rc(rc), zkc_state(st0), zkc_rc(arc), ncb, nev);
    }
    zkc_close(zh);
}

int main(int argc, char **argv)
{
    zkc_init(argc, argv, "dev_servers", 3, "DEV", "ignore");
    port_only_change();
    bad_string_not_committed();
    empty_list();
    return zkc_finish();
}
