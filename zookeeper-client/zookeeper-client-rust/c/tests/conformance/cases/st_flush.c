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
 * Immediate nonblocking socket flush on single threaded request submission.
 * Mode: EXACT.
 *
 * The handle connects through the test proxy, which tracks upstream bytes
 * received from the client without driving the handle between request
 * submission and byte-count inspection.
 *
 * Invariants:
 * - Submitting an asynchronous request on a connected st handle flushes the
 *   frame to the socket before the submission call returns, without requiring a
 *   subsequent zookeeper_process call.
 * - Submitting a request prior to the initial zookeeper_interest call enqueues
 *   the request without network I/O; the frame is transmitted once the
 *   connection handshake completes.
 * - Submissions rejected by synchronous argument validation transmit zero
 *   bytes.
 *
 * zkconf-flavors: st
 */

#include "zkconf.h"

#include <string.h>

static void call(const char *what, int rc)
{
    zkc_ev("call", "%s -> %s", what, zkc_rc(rc));
}

/* Polls proxy upstream byte count for up to 2 s without driving the handle. */
static int sent_since(long before)
{
    int i;
    for (i = 0; i < 200; i++) {
        if (zkc_proxy_up_bytes() > before) {
            return 1;
        }
        zkc_sleep_ms(10);
    }
    return 0;
}

int main(int argc, char **argv)
{
    const char *px;
    zhandle_t *zh;
    long up;

    zkc_init(argc, argv, "st_flush", 1, "EXACT", "ordered");
    px = zkc_proxy_start(zkc_host(0));
    if (px == NULL) {
        return zkc_finish();
    }
    zh = zookeeper_init2(px, zkc_watcher, 10000, NULL, "default", 0,
                         zkc_log_cb);
    zkc_ev("call", "zookeeper_init2 proxy -> %s", zh ? "zh" : "NULL");
    if (zh == NULL) {
        zkc_proxy_stop();
        return zkc_finish();
    }

    /* Before the first driving call. */
    call("zoo_aexists / early", zoo_aexists(zh, "/", 0, zkc_stat_cb, "early"));
    zkc_sleep_ms(200);
    zkc_ev("check", "before the first driving call: accepted=%d up=%ld",
           zkc_proxy_accepted(), zkc_proxy_up_bytes());
    zkc_poll_add(zh);
    zkc_await("session:CONNECTED@default", 1, 15000);
    zkc_await("cb", 1, 10000);

    /* A submit on a connected handle. */
    up = zkc_proxy_up_bytes();
    call("zoo_aexists / flush", zoo_aexists(zh, "/", 0, zkc_stat_cb, "flush"));
    zkc_ev("check", "sent before the next driving call: %d", sent_since(up));
    zkc_await("cb", 1, 10000);

    up = zkc_proxy_up_bytes();
    call("zoo_acreate bad path", zoo_acreate(zh, "bad", "v", 1,
                                             &ZOO_OPEN_ACL_UNSAFE, 0,
                                             zkc_string_cb, "bad"));
    zkc_sleep_ms(200);
    zkc_ev("check", "bytes sent by a rejected submit: %ld",
           zkc_proxy_up_bytes() - up);

    /* Two submits go out in order, before the next driving call. */
    up = zkc_proxy_up_bytes();
    call("zoo_aget / f1", zoo_aget(zh, "/", 0, zkc_data_cb, "f1"));
    call("zoo_aexists / f2", zoo_aexists(zh, "/", 0, zkc_stat_cb, "f2"));
    zkc_ev("check", "sent before the next driving call: %d", sent_since(up));
    zkc_await("cb", 2, 10000);
    zkc_close(zh);
    zkc_proxy_stop();
    return zkc_finish();
}
