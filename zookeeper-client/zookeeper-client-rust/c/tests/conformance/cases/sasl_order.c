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
 * Relative ordering of Cyrus SASL lifecycle calls and socket closure across
 * reconnect and shutdown. Mode: PER-ROLE.
 *
 * The SASL preload records sasl_client_new ('N'), sasl_dispose ('D'), and
 * close() of any server socket that initiated SASL negotiation ('C'),
 * interleaved with test milestone markers ('a', 'b', 'c', 'z').
 *
 * Invariants:
 * - Deferred disposal on disconnect: connection teardown closes the socket
 *   ('C') without immediately calling sasl_dispose; the stale sasl_conn_t is
 *   disposed at the beginning of the next SASL handshake immediately prior to
 *   sasl_client_new ("C b D N"), matching legacy zoo_sasl_connect.
 * - Shutdown ordering: zookeeper_close closes the active socket prior to
 *   invoking sasl_dispose ("z C D"), matching legacy destroy.
 *
 * zkconf-flavors: mt st
 * zkconf-preload: sasl
 */

#include "zkconf_sasl.h"
#include "zkconf_sync.h"

static void show_order(const char *when)
{
#ifdef HAVE_CYRUS_SASL_H
    char buf[256];
    zkc_ev("order", "%s: %s", when, zkcs_order(buf, sizeof buf));
#else
    (void)when;
#endif
}

#ifdef HAVE_CYRUS_SASL_H
static void run(void)
{
    sasl_callback_t *cb;
    zhandle_t *zh;
    char pwfile[1024];
    const char *prefix = getenv("ZKCONF_OUT");

    snprintf(pwfile, sizeof pwfile, "%s.pw",
             prefix != NULL ? prefix : "/tmp/zkconf");
    if (zkcs_write_file(pwfile, "mypassword\n", 11) != 0) {
        zkc_fail("cannot write the password file");
        return;
    }
    cb = zkcs_sasl()->basic(ZKCS_SASL_USER, NULL, pwfile);
    zh = cb != NULL
             ? zkcs_sasl_init(zkc_host(0), "order", cb, zkc_watcher, NULL,
                              10000)
             : NULL;
    zkc_ev("call", "zookeeper_init_sasl -> %s", zh != NULL ? "zh" : "NULL");
    if (zh == NULL) {
        free(cb);
        return;
    }
    zkc_poll_add(zh);
    zkc_await("session:CONNECTED@order", 1, 15000);
    zkcs_mark('a');
    show_order("connected");

    /* Connection teardown closes the socket while retaining the sasl_conn_t. */
    zkc_server("stop", 0);
    zkc_await("session:CONNECTING@order", 1, 15000);
    zkcs_mark('b');
    show_order("connecting");

    /* The subsequent handshake disposes the old sasl_conn_t before creating a new one. */
    zkc_server("start", 0);
    zkc_await("session:CONNECTED@order", 1, 30000);
    zkcs_mark('c');
    show_order("reconnected");

    /* Handle shutdown closes the socket before invoking sasl_dispose. */
    zkcs_mark('z');
    zkc_close(zh);
    show_order("closed");
    free(cb);
    (void)remove(pwfile);
}
#endif

int main(int argc, char **argv)
{
    zkc_init(argc, argv, "sasl_order", 1, "PER-ROLE", "ignore");
#ifdef HAVE_CYRUS_SASL_H
    if (zkcs_sasl_resolve() == 0 && zkcs_has_preload()) {
        run();
        return zkc_finish();
    }
#endif
    show_order("none");
    zkc_ev("check", "skip: no SASL library, no SASL server or no SASL preload");
    return zkc_finish();
}
