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
 * TLS session initialization entry point for SSL-enabled builds.
 *
 * Implements zookeeper_init_ssl, parsing the comma separated TLS configuration string
 * ("ca,cert,key,passwd") using thread safe tokenization while adhering to legacy strtok
 * semantics. Sensitive memory containing private key passphrases is zeroed immediately
 * after passing validated paths and credentials to the Rust core during handle construction.
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE /* POSIX names under -std=c99 on glibc */
#endif
#include "zks_internal.h"

#ifdef ZKS_WITH_TLS

zhandle_t *zookeeper_init_ssl(const char *host, const char *cert, watcher_fn fn,
                              int recv_timeout, const clientid_t *clientid, void *context,
                              int flags);

/*
 * Initializes an SSL-enabled ZooKeeper handle. Validates certificate arguments, parses
 * credential components into secure temporary buffers, and delegates handle allocation to
 * zks_init_common before securely scrubbing plaintext passphrases.
 */
zhandle_t *zookeeper_init_ssl(const char *host, const char *cert, watcher_fn fn,
                              int recv_timeout, const clientid_t *clientid, void *context,
                              int flags)
{
    struct zks_tls_args t = {1, NULL, NULL, NULL, NULL};
    char *copy;
    char *save = NULL;
    size_t n;
    zhandle_t *zh;
    int err;

    /* Ensures the linked Rust library was compiled with TLS feature support. */
    if (cert == NULL || ZKS_CALL_LEAF(zkrs_feature_tls()) != 1u) {
        errno = EINVAL;
        return NULL;
    }
    n = strlen(cert);
    copy = strdup(cert);
    if (copy == NULL) {
        errno = ENOMEM;
        return NULL;
    }
    t.ca = strtok_r(copy, ",", &save);
    t.cert = strtok_r(NULL, ",", &save);
    t.key = strtok_r(NULL, ",", &save);
    t.passwd = strtok_r(NULL, ",", &save);
    zh = zks_init_common(host, fn, recv_timeout, clientid, context, flags, NULL, &t, NULL);
    err = errno;
    zks_secure_zero(copy, n);
    free(copy);
    errno = err;
    return zh;
}

#else

/*
 * Placeholder declaration for non-TLS builds to satisfy ISO C requirements
 * against empty translation units.
 */
typedef int zks_tls_unit_is_empty;

#endif
