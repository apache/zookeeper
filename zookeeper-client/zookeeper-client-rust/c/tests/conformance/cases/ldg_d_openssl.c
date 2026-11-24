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
 * Absence of process-global OpenSSL state and symbol pollution in TLS sessions.
 * Mode: DEV.
 *
 * Establishes a TLS session via zookeeper_init_ssl, executes a round-trip
 * zoo_exists call on the secure port, and then probes the process symbol
 * namespace for OpenSSL entry points (OPENSSL_init_ssl, SSL_CTX_new,
 * ERR_get_error).
 *
 * Invariants and deviations:
 * - Legacy C links against libssl and libcrypto and initializes process-wide
 *   OpenSSL state, exposing all three symbols.
 * - The Rust drop-in uses rustls and neither defines nor links OpenSSL symbols
 *   (on ELF platforms where libsasl2 transitively links libcrypto, libcrypto
 *   symbols originating from libcrypto are permitted, whereas libssl symbols
 *   or symbols defined by libzookeeper_* itself are forbidden).
 *
 * Requires `run.sh --tls` and TLS-enabled library builds.
 *
 * zkconf-flavors: mt st
 */

#include "zkconf.h"
#include "zkconf_sync.h"
#include "zkconf_tls.h"

#include <dlfcn.h>
#include <stdio.h>
#include <string.h>

static const char *const g_syms[] = {"OPENSSL_init_ssl", "SSL_CTX_new",
                                     "ERR_get_error"};
/* The first g_ssl_syms entries in g_syms belong to libssl. */
static const size_t g_ssl_syms = 2;

int main(int argc, char **argv)
{
    char cert[4096];
    const char *c;
    zhandle_t *zh;
    struct Stat st;
    int rc;
    int found = 0;
    int ssl = 0;
    int own = 0;
    size_t i;

    zkc_init(argc, argv, "ldg_d_openssl", 1, "DEV", "ignore");
    if (zkt_setup(1) != 0) {
        return zkc_finish();
    }
    c = zkt_certs();
    snprintf(cert, sizeof(cert),
             "%s/server.crt,%s/client.crt,%s/clientkey.pem,password", c, c, c);
    zh = zkt_init(zkt_host(0), cert, 30000, NULL, "tls", 0);
    if (zh == NULL) {
        zkc_fail("zookeeper_init_ssl failed");
        return zkc_finish();
    }
    rc = zkcs_exists(zh, "/", 0, &st);
    zkc_dev("GLB-08", rc == ZOK, "exists over TLS -> %s", zkc_rc(rc));

    for (i = 0; i < sizeof(g_syms) / sizeof(g_syms[0]); i++) {
        void *p = dlsym(RTLD_DEFAULT, g_syms[i]);
        Dl_info info;
        const char *from = "";
        if (p != NULL && dladdr(p, &info) != 0 && info.dli_fname != NULL) {
            from = info.dli_fname;
        }
        zkc_ev("check", "%s %s", g_syms[i], p != NULL ? "present" : "absent");
        found += p != NULL;
        ssl += p != NULL && i < g_ssl_syms;
        own += p != NULL && strstr(from, "libzookeeper_") != NULL;
    }
    if (zkc_is_rust()) {
        /* Only a libsasl2 build linked against libcrypto may introduce libcrypto symbols. */
        zkc_dev("GLB-08",
                ssl == 0 && own == 0 &&
                    (found == 0 || zkc_sasl_needs_crypto()),
                "%d of 3 OpenSSL symbols are in the process: %d of libssl, "
                "%d in the library, libsasl2 needs libcrypto %d",
                found, ssl, own, zkc_sasl_needs_crypto());
    } else {
        zkc_dev("GLB-08", found == 3,
                "legacy: %d of 3 OpenSSL symbols are in the process", found);
    }
    zkc_close(zh);
    return zkc_finish();
}
