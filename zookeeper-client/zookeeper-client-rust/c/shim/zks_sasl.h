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
 * Cyrus SASL Integration Boundary.
 *
 * Defines the public C shim interface for managing SASL authentication sessions,
 * decoupling Cyrus implementation details from event loop and handle lifecycle logic.
 * This header does not expose Cyrus SASL types directly, allowing it to be safely
 * included by handle management and outbox drain loops when SASL support is
 * compiled into the library.
 *
 * Rust side state machines track SASL sessions through monotonic generation counters.
 * All operations cross check this generation value to ensure stale authentication
 * handshakes aborted by network reconnections are safely ignored without corrupting
 * new connection negotiations.
 */

#ifndef ZKS_SASL_H_
#define ZKS_SASL_H_

#include "zks_internal.h"

typedef zkrs_bytes zks_bytes;       /* Byte slice reference marshaled across the FFI boundary. */
typedef zkrs_sasl_out zks_sasl_out; /* Structured SASL negotiation outcome (status, mechanism, token). */

/* Upper bound on SASL buffer lengths to prevent integer overflow when interfacing with Cyrus. */
#define ZKS_SASL_LEN_CAP 0x7fffffffu
#define ZKS_SASL_OUT_FAIL {ZKRS_SASL_FAIL, {NULL, 0, 1}, {NULL, 0, 1}}

/* Session owned configuration strings and borrowed callback pointers. */
struct zks_sasl_params {
    char *service, *host, *mechlist; /* Dynamically allocated, NULL-terminated parameter strings. */
    const void *callbacks;           /* Borrowed reference to application-supplied sasl_callback_t array. */
};

struct zks_sasl_sess; /* Opaque session context defined in zks_sasl_cyrus.c. */
typedef struct zks_sasl_sess zks_sasl_sess;
typedef struct zks_sasl_params zks_sasl_params;
struct zoo_sasl_params; /* Forward declaration of public parameter structure. */

/*
 * Lifecycle management for SASL session contexts. Allocates or tears down Cyrus
 * connection handles and associated parameter copies. Returns NULL on memory exhaustion.
 */
ZKS_HIDDEN struct zks_sasl_sess *zks_sasl_sess_new(zhandle_t *zh, const struct zoo_sasl_params *p);
ZKS_HIDDEN void zks_sasl_sess_free(struct zks_sasl_sess *s);
ZKS_HIDDEN void zks_sasl_sess_clear(struct zks_sasl_sess *s);

/* Updates or resets the active connection generation counter. */
ZKS_HIDDEN void zks_sasl_set_gen(struct zks_sasl_sess *s, uint32_t gen);

/*
 * Dispatches SASL handshake progression requests. Operations validate the caller's
 * generation identifier against the active session before invoking underlying Cyrus APIs,
 * returning ZKRS_SASL_STALE if the handshake belongs to an obsolete connection.
 */
ZKS_HIDDEN void zks_sasl_start(zks_sasl_sess *s, uint32_t gen, zks_bytes local, zks_bytes peer,
                               zkrs_sasl_out *out);
ZKS_HIDDEN void zks_sasl_step(zks_sasl_sess *s, uint32_t gen, zks_bytes challenge,
                              zkrs_sasl_out *out);
ZKS_HIDDEN void zks_sasl_dispose(zks_sasl_sess *s, uint32_t gen);

#endif
