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
 * single threaded zoo_state trajectory across a TLS handshake on both TCP
 * connect paths. Mode: EXACT with DEV checks.
 *
 * Invariants and deviations:
 * - zoo_state transitions to 7 (SSL_CONNECTING_STATE_DEF) while the TLS handshake
 *   is in progress before advancing to ZOO_ASSOCIATING_STATE and
 *   ZOO_CONNECTED_STATE.
 * - On the immediate connect path, zoo_state remains 0 / ZOO_NOTCONNECTED_STATE
 *   (999) until the TLS handshake completes, then transitions directly to
 *   ZOO_ASSOCIATING_STATE. Legacy C executes the entire TLS handshake
 *   synchronously inside the initial zookeeper_interest call, whereas the Rust
 *   core drives the handshake nonblockingly across poll passes.
 *
 * zkconf-flavors: st
 * zkconf-preload: connect
 */

#include "zkconf.h"
#include "zkconf_preload.h"
#include "zkconf_tls.h"

#include <stdio.h>

static void run_path(int mode, const char *label, const char *cert)
{
    zhandle_t *zh;
    long end;
    int last;
    int changes = 0;
    int passes = 0;
    zkt_connect_mode(mode);
    zh = zkt_init(zkt_host(0), cert, 30000, NULL, label, 0);
    if (zh == NULL) {
        return;
    }
    /* Detach from the shared poll registry so the local loop drives zh. */
    zkc_poll_remove(zh);
    last = zoo_state(zh);
    zkc_ev("state", "%s start %s", label, zkc_state(last));
    end = zkt_now_ms() + 15000;
    while (zkt_now_ms() < end && last != ZOO_CONNECTED_STATE &&
           changes < 16) {
        int interest;
        int err;
        const char *where;
        int mid;
        int rc = zkt_st_pass(zh, 50, &interest, &err, &where, &mid);
        int st = zoo_state(zh);
        if (passes == 0 && mode == ZKCONF_CONNECT_IMMEDIATE) {
            /*
             * Legacy C blocks and completes the entire TLS handshake inside
             * the first zookeeper_interest call, whereas the Rust core returns
             * immediately while the nonblocking handshake is pending.
             */
            int done = mid != ZOO_NOTCONNECTED_STATE && mid != 0;
            zkc_dev("SEC-1", zkc_is_rust() ? !done : done,
                    "%s: state after the first interest call: %s", label,
                    zkc_state(mid));
        }
        passes++;
        if (mid != last) {
            zkc_ev("state", "%s -> %s", label, zkc_state(mid));
            last = mid;
            changes++;
        }
        if (rc != ZOK) {
            zkc_ev("st", "%s rc=%s in=%s errno=%s", label, zkc_rc(rc), where,
                   zkt_errno(err));
            break;
        }
        if (st != last) {
            zkc_ev("state", "%s -> %s", label, zkc_state(st));
            last = st;
            changes++;
        }
    }
    zkc_close(zh);
}

int main(int argc, char **argv)
{
    char cert[4096];
    const char *c;
    zkc_init(argc, argv, "tls_state", 1, "EXACT", "ignore");
    if (zkt_setup(1) != 0) {
        return zkc_finish();
    }
    if (!zkt_have_preload()) {
        zkc_fail("the connect preload is not loaded");
        return zkc_finish();
    }
    c = zkt_certs();
    snprintf(cert, sizeof(cert),
             "%s/server.crt,%s/client.crt,%s/clientkey.pem,password", c, c, c);
    run_path(ZKCONF_CONNECT_EINPROGRESS, "einprogress", cert);
    run_path(ZKCONF_CONNECT_IMMEDIATE, "immediate", cert);
    zkt_connect_mode(ZKCONF_CONNECT_PASS);
    return zkc_finish();
}
