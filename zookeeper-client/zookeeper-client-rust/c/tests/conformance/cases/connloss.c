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
 * Connection loss and session recovery across a server restart.
 *
 * Invariants:
 * - Socket disconnection delivers a CONNECTING session event first, and
 *   in-flight requests awaiting responses fail with ZCONNECTIONLOSS in
 *   submission order.
 * - Requests submitted while disconnected fail with ZCONNECTIONLOSS upon the
 *   next failed connection attempt.
 * - When the server restarts within the session timeout, the client reconnects
 *   with the existing session ID, emits a CONNECTED session event, and
 *   re-registers active watches via SetWatches.
 * - The resumed session retains the negotiated session password in the
 *   storage returned by zoo_client_id.
 *
 * In st builds, zkcs helpers substitute for synchronous calls, and connection
 * loss detection / reconnect progression are driven by the test poll loop.
 *
 * zkconf-flavors: mt st
 */

#include "zkconf.h"
#include "zkconf_sync.h"

#include <string.h>

static void call(const char *what, int rc)
{
    zkc_ev("call", "%s -> %s", what, zkc_rc(rc));
}

int main(int argc, char **argv)
{
    zhandle_t *zh;
    struct Stat st;
    char buf[32];
    int len = sizeof(buf);
    int64_t sid;
    clientid_t cid;
    int state;

    zkc_init(argc, argv, "connloss", 1, "PER-ROLE", "uniq");
    zh = zkc_connect(zkc_host(0), 20000, 0);
    if (zh == NULL) {
        return zkc_finish();
    }
    sid = zoo_client_id(zh)->client_id;
    memcpy(&cid, zoo_client_id(zh), sizeof cid);
    zkc_ev("ret", "session %s", zkc_sid(sid));
    call("zoo_create /cl", zkcs_create(zh, "/cl", "a", 1, &ZOO_OPEN_ACL_UNSAFE,
                                      0, NULL, 0));
    call("zoo_wget /cl", zkcs_wget(zh, "/cl", zkc_watcher, "survivor", buf,
                                  &len, &st));
    call("zoo_wexists /cl/new", zkcs_wexists(zh, "/cl/new", zkc_watcher,
                                            "later", &st));

    /* Stop the server to induce connection loss. */
    zkc_server("stop", 0);
    zkc_await("session:CONNECTING@default", 1, 15000);
    state = zoo_state(zh);
    zkc_ev("ret", "zoo_state connected=%d", state == ZOO_CONNECTED_STATE);

    /* Requests queued while disconnected fail on the next failed reconnect attempt. */
    call("zoo_aget /cl", zoo_aget(zh, "/cl", 0, zkc_data_cb, "while-down"));
    zkc_await("cb", 1, 15000);
    len = sizeof(buf);
    call("zoo_get while down", zkcs_get(zh, "/cl", 0, buf, &len, &st));

    /* Restart the server and verify session resumption. */
    zkc_server("start", 0);
    zkc_await("session:CONNECTED@default", 1, 20000);
    zkc_ev("ret", "same session=%d", zoo_client_id(zh)->client_id == sid);
    /* Verify that session resumption preserves the stored session password. */
    zkc_ev("ret", "same passwd=%d",
           memcmp(zoo_client_id(zh)->passwd, cid.passwd, sizeof cid.passwd)
               == 0);
    zkc_ev("ret", "zoo_state -> %s", zkc_state(zoo_state(zh)));

    /* Watches registered prior to disconnect are restored via SetWatches. */
    call("zoo_aset /cl", zoo_aset(zh, "/cl", "b", 1, -1, zkc_stat_cb, "set"));
    zkc_await("watch", 1, 10000);
    zkc_await("cb", 1, 10000);
    call("zoo_acreate /cl/new", zoo_acreate(zh, "/cl/new", "n", 1,
                                            &ZOO_OPEN_ACL_UNSAFE, 0,
                                            zkc_string_cb, "mk"));
    zkc_await("watch", 1, 10000);
    zkc_await("cb", 1, 10000);
    len = sizeof(buf);
    memset(buf, 0, sizeof(buf));
    call("zoo_get /cl", zkcs_get(zh, "/cl", 0, buf, &len, &st));
    zkc_ev("ret", "data=%.*s", len, buf);

    /* Exercise a request in flight when the server stops. */
    call("zoo_async /cl", zoo_async(zh, "/cl", zkc_string_cb, "sync"));
    zkc_await("cb", 1, 10000);
    zkc_server("stop", 0);
    zkc_await("session:CONNECTING@default", 1, 15000);
    call("zoo_aexists /cl", zoo_aexists(zh, "/cl", 0, zkc_stat_cb, "down2"));
    zkc_await("cb", 1, 15000);
    zkc_server("start", 0);
    zkc_await("session:CONNECTED@default", 1, 20000);
    call("zoo_exists /cl", zkcs_exists(zh, "/cl", 0, &st));
    zkc_close(zh);
    return zkc_finish();
}
