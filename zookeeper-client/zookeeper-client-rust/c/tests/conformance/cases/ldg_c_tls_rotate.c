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
 * Dynamic reloading of the CA trust bundle across TLS connection attempts
 * against a live server. Mode: EXACT.
 *
 * Invariants:
 * - Each socket connection attempt rebuilds its TLS configuration from
 *   the configured filesystem paths (constructing a fresh SSL_CTX in legacy C
 *   and a fresh rustls ClientConfig in the Rust drop-in) without caching trust
 *   anchors from prior attempts.
 * - Initial connection attempts with a mismatched CA bundle (the zk-driver CA)
 *   fail TLS verification with "error in ssl connect"; overwriting the CA path
 *   with the server's issuing CA (testSSL server.crt) allows subsequent retry
 *   attempts on the same handle to connect and create an ephemeral node.
 * - Replacing the CA file with the mismatched CA again and restarting the
 *   server causes reconnect attempts to fail until the valid CA bundle is
 *   restored, at which point the handle resumes its existing session.
 *
 * zkconf-flavors: mt st
 */

#include "zkconf.h"
#include "zkconf_sync.h"
#include "zkconf_tls.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define SITE "@init_ssl_for_socket@"
#define LABEL "rotate"

/* Copies file contents from source path `from` to destination path `to`. */
static int copy_file(const char *from, const char *to)
{
    char buf[4096];
    size_t n;
    int rc = 0;
    FILE *in = fopen(from, "rb");
    FILE *out;
    if (in == NULL) {
        return -1;
    }
    out = fopen(to, "wb");
    if (out == NULL) {
        fclose(in);
        return -1;
    }
    while ((n = fread(buf, 1, sizeof(buf), in)) > 0) {
        if (fwrite(buf, 1, n, out) != n) {
            rc = -1;
        }
    }
    fclose(in);
    if (fclose(out) != 0) {
        rc = -1;
    }
    return rc;
}

/* Advances the handle for ms milliseconds. */
static void drive(int ms)
{
#ifdef THREADED
    zkc_sleep_ms(ms);
#else
    zkc_poll(ms);
#endif
}

/* Waits until at least n log lines matching SITE have been captured. */
static int refusals(int n, int timeout_ms)
{
    long end = zkt_now_ms() + timeout_ms;
    while (zkt_log_count(SITE) < n) {
        if (zkt_now_ms() >= end) {
            return -1;
        }
        drive(20);
    }
    return 0;
}

int main(int argc, char **argv)
{
    char ca[1100];
    char good[1100];
    char bad[1100];
    char cs[4096];
    zhandle_t *zh;
    int64_t sid;
    struct Stat st;

    zkc_init(argc, argv, "ldg_c_tls_rotate", 1, "EXACT", "ignore");
    if (zkt_setup(1) != 0) {
        return zkc_finish();
    }
    snprintf(ca, sizeof(ca), "%s.ca.pem", zkt_out());
    snprintf(good, sizeof(good), "%s/server.crt", zkt_certs());
    snprintf(bad, sizeof(bad), "%s/ca.pem", zkt_fix());
    snprintf(cs, sizeof(cs), "%s,,,", ca);
    if (copy_file(bad, ca) != 0) {
        zkc_fail("cannot write the CA file");
        return zkc_finish();
    }

    /* Initial attempts read the mismatched zk-driver CA and fail verification. */
    zkc_ev("case", "%s CA file holds the zk-driver CA", LABEL);
    zkt_log_reset();
    zh = zkt_init(zkt_host(0), cs, 30000, NULL, LABEL, 0);
    if (zh == NULL) {
        return zkc_finish();
    }
    if (refusals(1, 10000) != 0) {
        zkc_ev("wait", "%s no refusal in 10 s", LABEL);
    }
    zkt_log_record(LABEL, SITE, 1);

    /* Overwrite the CA path with the testSSL CA so the next retry connects. */
    zkc_ev("case", "%s CA file holds the testSSL CA", LABEL);
    if (copy_file(good, ca) != 0) {
        zkc_fail("cannot write the CA file");
    }
    if (zkc_await("session:CONNECTED@" LABEL, 1, 20000) != 0) {
        zkc_ev("wait", "%s no CONNECTED in 20 s", LABEL);
        zkc_close(zh);
        return zkc_finish();
    }
    sid = zoo_client_id(zh)->client_id;
    zkc_ev("call", "zoo_acreate /ldg_c_tls_rotate -> %s",
           zkc_rc(zoo_acreate(zh, "/ldg_c_tls_rotate", "x", 1,
                              &ZOO_OPEN_ACL_UNSAFE, ZOO_EPHEMERAL,
                              zkc_string_cb, "create")));
    zkc_await("cb", 1, 10000);

    /* Restore the mismatched CA and restart the server; reconnect attempts fail. */
    zkc_ev("case", "%s CA file holds the zk-driver CA, server restarts",
           LABEL);
    if (copy_file(bad, ca) != 0) {
        zkc_fail("cannot write the CA file");
    }
    zkt_log_reset();
    zkc_server("stop", 0);
    zkc_await("session:CONNECTING@" LABEL, 1, 15000);
    zkc_server("start", 0);
    if (refusals(1, 20000) != 0) {
        zkc_ev("wait", "%s no refusal in 20 s", LABEL);
    }
    zkt_log_record(LABEL, SITE, 1);

    /* Restore the valid testSSL CA; the handle reconnects under the same session ID. */
    zkc_ev("case", "%s CA file holds the testSSL CA again", LABEL);
    if (copy_file(good, ca) != 0) {
        zkc_fail("cannot write the CA file");
    }
    if (zkc_await("session:CONNECTED@" LABEL, 1, 20000) != 0) {
        zkc_ev("wait", "%s no CONNECTED in 20 s", LABEL);
    } else {
        zkc_ev("ret", "same session=%d", zoo_client_id(zh)->client_id == sid);
        zkc_ev("call", "zoo_exists /ldg_c_tls_rotate -> %s",
               zkc_rc(zkcs_exists(zh, "/ldg_c_tls_rotate", 0, &st)));
    }
    zkc_close(zh);
    return zkc_finish();
}
